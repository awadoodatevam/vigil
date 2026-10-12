#!/usr/bin/env python3
"""DD-F-12: runs checks DD-K-01 to DD-K-16 over the demo data folder (31-demo-data README Section 5).

Usage (from the repository root; needs Python 3 and PyYAML):
    python3 docs/31-demo-data/tools/check_data.py docs/31-demo-data

Exit status 0 only when every check passes. Every rule implemented here cites the document rule it follows.
"""
import csv
import itertools
import os
import re
import sys

import yaml

FAILS = []
RESULTS = []


def check(cid, cond, detail):
    RESULTS.append((cid, bool(cond), detail))
    if not cond:
        FAILS.append((cid, detail))


def load(folder):
    def y(name):
        with open(os.path.join(folder, name), encoding="utf-8") as f:
            return yaml.safe_load(f)
    d = {
        "ident": y("01-tenant-identity.yaml"), "smap": y("02-service-map.yaml"), "mon": y("03-monitoring.yaml"),
        "ops": y("04-operations-actions-recovery.yaml"), "rout": y("05-routing-escalation.yaml"),
        "wf": y("06-workflow.yaml"), "set": y("07-settings.yaml"), "lab": y("10-lab-and-faults.yaml"),
    }
    with open(os.path.join(folder, "09-past-incidents.csv"), encoding="utf-8") as f:
        d["past"] = list(csv.DictReader(f))
    d["runbooks"] = parse_runbooks(os.path.join(folder, "08-runbooks.md"))
    return d


def parse_runbooks(path):
    """DD-R-09 file convention."""
    text = open(path, encoding="utf-8").read()
    parts = re.split(r"^## ", text, flags=re.M)[1:]
    books = []
    for p in parts:
        fields = dict(re.findall(r"^\| (\w+) \| (.*?) \|$", p, flags=re.M))
        fields.pop("Field", None)
        def s(v):
            return set() if v.strip() == "(any)" else {x.strip() for x in v.split(",")}
        books.append({"runbook_key": fields["runbook_key"], "version": int(fields["version"]),
                      "title": fields["title"], "types": s(fields["applicable_alert_types"]),
                      "kinds": s(fields["applicable_component_kinds"])})
    return books


# ---------------------------------------------------------------- service map helpers (DM-E28-R2, DM-R-07)
class Map:
    def __init__(self, smap):
        self.comps = {c["component_key"]: c for c in smap["components"]}
        self.services = {s["service_key"]: s for s in smap["services"]}
        self.keys = smap["identity_keys"]
        self.deps = {k: set() for k in self.comps}
        for d in smap["dependencies"]:
            self.deps[d["from_component_key"]].add(d["to_component_key"])
        for k, c in self.comps.items():
            if c.get("runs_on_key"):
                self.deps[k].add(c["runs_on_key"])            # DM-E28-R2

    def ancestors(self, a):                                    # everything a depends on, directly or indirectly
        seen, stack = set(), [a]
        while stack:
            for n in self.deps.get(stack.pop(), ()):
                if n not in seen:
                    seen.add(n)
                    stack.append(n)
        return seen

    def related(self, a, b):                                   # DM-R-07
        return a == b or b in self.ancestors(a) or a in self.ancestors(b)

    def match(self, source_kind, pairs):                       # DM-R-05
        for name, value in pairs:
            for k in self.keys:
                if k["source_kind"] == source_kind and k["name"] == name and str(k["value"]) == str(value):
                    return k["component_key"]
        return None


CRIT = {"critical": 4, "high": 3, "medium": 2, "low": 1, None: 0}


def hypothesis(m, affected):                                   # DM-R-18
    def count(c):
        return sum(1 for o in affected if o != c and c in m.ancestors(o))
    best = sorted(affected, key=lambda c: (-count(c), -CRIT[m.comps[c].get("criticality")], c))
    return best[0], {c: count(c) for c in affected}


def impact(m, affected):                                       # DM-R-19
    out = []
    for sk in sorted(m.services):
        s = m.services[sk]
        via = sorted(set(s["component_keys"]) & set(affected))
        if via:
            out.append({"service_key": sk, "name": s["name"], "criticality": s.get("criticality"), "via": via})
    return out


def title(m, alert_types_display, hyp, members, services):    # DM-R-20 (with-hypothesis case)
    on_hyp = [a["type"] for a in members if a["component"] == hyp] or [a["type"] for a in members]
    counts = {}
    for t in on_hyp:
        counts[t] = counts.get(t, 0) + 1
    primary = sorted(counts, key=lambda t: (-counts[t], t))[0]
    n = len(services)
    tail = "" if n == 0 else (" — 1 service affected" if n == 1 else " — %d services affected" % n)
    return "%s: %s%s" % (m.comps[hyp]["name"], alert_types_display[primary], tail)


def routing(rules, m, inc):                                    # DM-E43-R1, DM-E41-R2, DM-R-25/26
    order = {"critical": 4, "high": 3, "medium": 2, "low": 1}
    fields = {"highest_service_criticality": max([order[s["criticality"]] for s in inc["services"] if s["criticality"]] or [None],
                                                key=lambda v: -1 if v is None else v),
              "affected_service_count": len(inc["services"]), "alert_count": inc["alert_count"],
              "hypothesis_component": inc["hyp"], "alert_types": set(inc["types"])}
    for r in rules:
        ok = True
        for c in r["conditions"]:
            v = fields.get(c["field"])
            if c["field"] == "highest_service_criticality":
                ok = ok and v is not None and c["operator"] == "at_least" and v >= order[c["value"]]
            else:
                raise ValueError("condition field not handled by this check: " + c["field"])
        if ok:
            if r["owner_mode"] == "hypothesis_component_owner":
                owner = m.comps[inc["hyp"]].get("owner_team_key") or r["owner_team_key"]
            else:
                owner = r["owner_team_key"]
            informed = set(r["informed_team_keys"])
            if r["inform_affected_service_owners"]:
                informed |= {m.services[s["service_key"]].get("owner_team_key") for s in inc["services"]} - {None}
            informed.discard(owner)
            return r["rule_key"], r["severity"], owner, sorted(informed)
    raise ValueError("no rule matched")


# ---------------------------------------------------------------- lab alerts from the catalogue
def lab_alerts(mon):
    out = []
    for row in mon["lab_alert_catalogue"]:
        dims = row.get("dimensions") or {}
        names = list(dims)
        combos = list(itertools.product(*[dims[n] for n in names])) or [()]
        for combo in combos:
            vals = dict(zip(names, combo))
            if row["source"] == "zabbix":
                text = row["trigger_name"].format(**vals)
                pairs = [("component", row["tag_component"]), ("host", row["host"])]
                fields = {"event_name": text}
                intended = row["tag_component"]
            else:
                labels = dict(row["labels"]); labels.update(vals)
                pairs = [("component", labels.get("component")), ("instance", labels.get("instance"))]
                fields = {"label.alertname": row["alertname"]}
                intended = labels["component"]
            out.append({"id": row["id"], "source": row["source"], "pairs": pairs, "fields": fields,
                        "intended": intended, "fires_in": row["fires_in"]})
        check("DD-K-01", len(combos) == row["count"], "%s count %d equals combinations %d" % (row["id"], row["count"], len(combos)))
    return out


def classify(mappings, source, fields):                       # DM-E17-R1
    for mp in sorted([x for x in mappings if x["source_kind"] == source], key=lambda x: x["order"]):
        v = fields.get(mp["field"])
        if v is None:
            continue
        if (mp["operator"] == "equals" and v == mp["value"]) or \
           (mp["operator"] == "starts_with" and v.startswith(mp["value"])) or \
           (mp["operator"] == "contains" and mp["value"] in v):
            return mp["alert_type"]
    return "unclassified"


# ---------------------------------------------------------------- workflow validation (SL-WE-08, DM-E48-R1, DM Section 9)
OUTPUTS = {"trigger": ["next"], "diagnostic": ["done", "failed"], "ai_task": ["done", "unavailable"],
           "approval": ["approved", "rejected", "expired"], "action": ["succeeded", "failed"],
           "recovery_check": ["recovered", "not_recovered"], "escalation": ["next"],
           "notification": ["sent", "failed"], "ticket": ["done", "failed"], "wait": ["event_received", "timed_out"]}


def outputs(step):
    if step["type"] == "condition":
        return [b["label"] for b in step["parameters"]["branches"]] + ["otherwise"]
    return OUTPUTS[step["type"]]


def validate(content, d, teams, diag_keys):
    v = []
    steps = {s["step_id"]: s for s in content["steps"]}
    conns = content["connections"]
    pairs = [(c["from_step_id"], c["from_output"]) for c in conns]
    if len(pairs) != len(set(pairs)):
        v.append("an output has more than one connection")
    nxt = {}
    for c in conns:
        if c["from_output"] not in outputs(steps[c["from_step_id"]]):
            v.append("unknown output %s.%s" % (c["from_step_id"], c["from_output"]))
        nxt[(c["from_step_id"], c["from_output"])] = c["to_step_id"]
    trig = [s for s in steps.values() if s["type"] == "trigger"]
    if len(trig) != 1:
        v.append("one_trigger")
        return v
    t = trig[0]["step_id"]
    reach, stack = {t}, [t]
    while stack:
        s = stack.pop()
        for o in outputs(steps[s]):
            n = nxt.get((s, o))
            if n and n not in reach:
                reach.add(n); stack.append(n)
    if reach != set(steps):
        v.append("reachable_from_trigger: %s" % sorted(set(steps) - reach))
    incoming = {c["to_step_id"] for c in conns}
    for s in steps:
        if s != t and s not in incoming:
            v.append("no_unconnected_step: " + s)
        if steps[s]["type"] == "condition":
            for o in outputs(steps[s]):
                if (s, o) not in nxt:
                    v.append("condition_branch_connected: %s.%s" % (s, o))
    # paths: enumerate every path from the trigger (graph is acyclic here; a cycle is reported)
    def paths(node, seen):
        outs = [(o, nxt.get((node, o))) for o in outputs(steps[node])]
        any_end = False
        for o, n in outs:
            if n is None:
                any_end = True
                yield [(node, o)]
            elif n in seen:
                if not any(steps[x]["type"] == "wait" for x in seen):
                    v.append("cycle_has_wait: cycle through " + n)
            else:
                for p in paths(n, seen | {n}):
                    yield [(node, o)] + p
    all_paths = list(paths(t, {t}))
    for p in all_paths:
        types = [steps[s]["type"] for s, _ in p]
        for i, ty in enumerate(types):
            if ty == "action":
                if "approval" not in types[:i]:
                    v.append("action_guarded: no approval before " + p[i][0])
                if "recovery_check" not in types[i + 1:]:
                    v.append("action_guarded: no recovery check after " + p[i][0])
    # parameter_valid (bounds of DM Section 9)
    cond_fields = {"severity": ("ordered", ["P1", "P2", "P3", "P4"]), "owner_team": ("key", None),
                   "informed_teams": ("set", None), "hypothesis_component": ("key", None),
                   "hypothesis_component_kind": ("enum", ["server", "application", "database", "queue", "check"]),
                   "hypothesis_criticality": ("ordered", ["critical", "high", "medium", "low"]),
                   "affected_services": ("set", None), "affected_service_count": ("int", None),
                   "highest_service_criticality": ("ordered", ["critical", "high", "medium", "low"]),
                   "alert_count": ("int", None), "alert_types": ("set", None), "alert_sources": ("set", None),
                   "acknowledged": ("bool", None), "escalation_level": ("int", None),
                   "ai_investigation_available": ("bool", None), "reopen_count": ("int", None)}
    ops_by_type = {"ordered": {"equals", "not_equals", "in", "not_in", "at_least", "at_most"},
                   "key": {"equals", "not_equals", "in", "not_in"}, "enum": {"equals", "not_equals", "in", "not_in"},
                   "set": {"contains", "not_contains"}, "bool": {"is"},
                   "int": {"equals", "not_equals", "greater_than", "greater_or_equal", "less_than", "less_or_equal"}}
    for s in steps.values():
        P = s["parameters"]
        ty = s["type"]
        if len(s["label"]) > 60:
            v.append("label longer than 60: " + s["step_id"])
        if ty == "condition":
            labels = [b["label"] for b in P["branches"]]
            if not labels or len(labels) != len(set(labels)) or "otherwise" in labels:
                v.append("parameter_valid: branch labels " + s["step_id"])
            for b in P["branches"]:
                for c in b["comparisons"]:
                    f = cond_fields.get(c["field"])
                    if f is None or c["operator"] not in ops_by_type[f[0]] or (f[1] and c["value"] not in f[1]):
                        v.append("parameter_valid: comparison %s in %s" % (c, s["step_id"]))
        if ty == "approval":
            if not (set(P["eligible_teams"]) <= teams and 60 <= P["expiry_s"] <= 86400):
                v.append("parameter_valid: " + s["step_id"])
        if ty == "recovery_check" and not (60 <= P["max_wait_s"] <= 86400):
            v.append("parameter_valid: " + s["step_id"])
        if ty == "escalation":
            if P["mode"] == "wait_for_acknowledgement" and not (30 <= P["timer_s"] <= 86400):
                v.append("parameter_valid: " + s["step_id"])
            if P["mode"] not in ("wait_for_acknowledgement", "escalate_now"):
                v.append("parameter_valid: " + s["step_id"])
        if ty == "ai_task" and P["task"] not in ("SL-T-1", "SL-T-2", "SL-T-3"):
            v.append("parameter_valid: " + s["step_id"])
        if ty == "ticket" and P["operation"] not in ("create", "resolve"):
            v.append("parameter_valid: " + s["step_id"])
        if ty == "diagnostic" and not (set(P["diagnostics"]) <= diag_keys and P["target"] == "hypothesis_component"):
            v.append("parameter_valid: " + s["step_id"])
        if ty == "notification":
            if not P["recipients"] or not set(P["recipients"]) <= {"owner_team", "informed_parties", "named_teams"}:
                v.append("parameter_valid: recipients " + s["step_id"])
            if "named_teams" in P["recipients"] and not (P.get("named_teams") and set(P["named_teams"]) <= teams):
                v.append("parameter_valid: " + s["step_id"])
        if ty == "trigger" and P["event"] != "incident_created_or_reopened":
            v.append("parameter_valid: " + s["step_id"])
    return v, all_paths, steps


def apply_changes(v1, changes):
    import copy
    c = copy.deepcopy(v1)
    for ch in changes:
        if ch["change"] == "add_step":
            assert ch["step"]["step_id"] not in {s["step_id"] for s in c["steps"]}
            c["steps"].append(ch["step"])
        elif ch["change"] == "remove_connection":
            assert ch["connection"] in c["connections"], ch
            c["connections"].remove(ch["connection"])
        elif ch["change"] == "add_connection":
            c["connections"].append(ch["connection"])
        elif ch["change"] == "set_parameter":
            st = [s for s in c["steps"] if s["step_id"] == ch["step_id"]][0]
            assert st["parameters"][ch["parameter"]] == ch["before"], ch
            st["parameters"][ch["parameter"]] = ch["after"]
    return c


# ---------------------------------------------------------------- dashboard (DM-R-53), seeded history only
def half_up(x, places=1):
    from decimal import Decimal, ROUND_HALF_UP
    return float(Decimal(str(x)).quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP))


def main(folder):
    d = load(folder)
    m = Map(d["smap"])
    teams = {t["team_key"] for t in d["ident"]["teams"]}
    users = {u["username"] for u in d["ident"]["users"]}
    alert_types = {t["alert_type"]: t["display_name"] for t in d["mon"]["alert_types"]}
    storm_types = {"disk_full", "db_write_errors", "ussd_timeouts", "balance_api_errors", "bundle_api_errors",
                   "sms_queue_growth", "ussd_synthetic_failed"}

    # DD-K-01 storm count, types and sources
    alerts = lab_alerts(d["mon"])
    for a in alerts:
        a["type"] = classify(d["mon"]["alert_type_mappings"], a["source"], a["fields"])
        a["component"] = m.match(a["source"], a["pairs"])
    storm = [a for a in alerts if "SL-F-1" in a["fires_in"]]
    f3 = [a for a in alerts if "SL-F-3" in a["fires_in"]]
    zb = sum(1 for a in storm if a["source"] == "zabbix")
    check("DD-K-01", 100 <= len(storm) <= 200 and {a["type"] for a in storm} == storm_types and zb >= 1 and len(storm) - zb >= 1,
          "storm: %d alerts (%d Zabbix, %d Alertmanager), types %s" % (len(storm), zb, len(storm) - zb, sorted({a['type'] for a in storm})))
    check("DD-K-01", len(f3) == 1, "SL-F-3: %d alert" % len(f3))
    ro = [a for a in alerts if "SL-F-2" in a["fires_in"]]
    check("DD-K-01", {a["type"] for a in storm} - {a["type"] for a in ro} == {"disk_full"} and len(ro) == len(storm) - 2,
          "with SL-F-2, %d storm alerts keep firing (all except disk_full)" % len(ro))

    # DD-K-02 component matching and classification
    bad = [a for a in alerts if a["component"] != a["intended"]]
    check("DD-K-02", not bad, "every lab alert matches its intended component (DM-R-05)%s" % ("" if not bad else ": %s" % bad[:3]))
    uncls = [a for a in alerts if a["type"] == "unclassified"]
    check("DD-K-02", not uncls, "every lab alert is classified by the alert type mappings (DM-E17-R1)")

    # DD-K-03 service map import rules (DM-R-55) and counts
    sm = d["smap"]
    def uniq(lst):
        return len(lst) == len(set(lst))
    ok = uniq([s["service_key"] for s in sm["services"]]) and uniq(list(m.comps))
    ok = ok and all(c in m.comps for s in sm["services"] for c in s["component_keys"]) and all(s["component_keys"] for s in sm["services"])
    for c in m.comps.values():
        if c["kind"] == "server":
            ok = ok and not c.get("runs_on_key")
        else:
            ok = ok and not c.get("target_key")
            if c.get("runs_on_key"):
                ok = ok and m.comps[c["runs_on_key"]]["kind"] == "server"
        ok = ok and c["kind"] in ("server", "application", "database", "queue", "check") and c.get("criticality") in CRIT
    ok = ok and all(s.get("criticality") in (None, "critical", "high", "medium", "low") for s in sm["services"])
    ok = ok and all(k["source_kind"] in ("zabbix", "alertmanager") for k in sm["identity_keys"])
    keyset = [(k["source_kind"], k["name"], str(k["value"])) for k in sm["identity_keys"]]
    ok = ok and uniq(keyset) and all(k["component_key"] in m.comps for k in sm["identity_keys"])
    deps = [(x["from_component_key"], x["to_component_key"]) for x in sm["dependencies"]]
    ok = ok and uniq(deps) and all(a != b and a in m.comps and b in m.comps for a, b in deps)
    acyclic = all(k not in m.ancestors(k) for k in m.comps)
    counts = (len(sm["services"]), len(m.comps), len(sm["identity_keys"]), len(sm["dependencies"]))
    check("DD-K-03", ok and acyclic and counts == (4, 12, 15, 6),
          "service map passes DM-R-55, acyclic with runs_on; counts services/components/identity keys/dependencies = %s" % (counts,))

    # DD-K-04 grouping: storm alerts form one group; SL-F-3 unrelated (DM-R-07, DM-R-08, DM-R-14)
    storm_comps = sorted({a["component"] for a in storm})
    window = d["set"]["correlation_settings"]["grouping_window_s"]
    # every storm alert fires within 120 s (DD-F-10), so the time relation (window >= 120 s) holds between any two
    g = {storm_comps[0]}
    changed = True
    while changed:
        changed = False
        for c in storm_comps:
            if c not in g and any(m.related(c, x) for x in g):
                g.add(c); changed = True
    check("DD-K-04", set(storm_comps) == g and window >= 120, "storm components %s form one connected group; window %d s >= storm length 120 s" % (storm_comps, window))
    check("DD-K-04", all(not m.related(f3[0]["component"], c) for c in storm_comps),
          "SL-F-3 component %s is related to no storm component" % f3[0]["component"])

    # DD-K-05 hypothesis and routing
    hyp, counts_ = hypothesis(m, storm_comps)
    storm_imp = impact(m, storm_comps)
    storm_inc = {"hyp": hyp, "services": storm_imp, "alert_count": len(storm), "types": {a["type"] for a in storm}}
    rules = d["rout"]["routing_rule_set_revision_1"]["rules"]
    r = routing(rules, m, storm_inc)
    check("DD-K-05", hyp == "srv-db-01", "storm hypothesis %s (dependent counts %s)" % (hyp, counts_))
    check("DD-K-05", r[1:] == ("P1", "db-ops", ["vas-service-owners"]), "storm routing %s" % (r,))
    f3_hyp, _ = hypothesis(m, [f3[0]["component"]])
    f3_imp = impact(m, [f3[0]["component"]])
    r3 = routing(rules, m, {"hyp": f3_hyp, "services": f3_imp, "alert_count": 1, "types": {f3[0]["type"]}})
    check("DD-K-05", r3[1:] == ("P4", "it-apps", []), "SL-F-3 routing %s" % (r3,))
    ch = d["rout"]["routing_change_for_sl_a2_06"]
    rules2 = [dict(x) for x in rules]
    for x in rules2:
        if x["rule_key"] == ch["rule_key"]:
            assert x[ch["field"]] == ch["before"]
            x[ch["field"]] = ch["after"]
    r2 = routing(rules2, m, storm_inc)
    exp = ch["expected_storm_result_after_change"]
    check("DD-K-05", r2[1:] == (exp["severity"], exp["owner_team_key"], sorted(exp["informed_team_keys"])) and r2[1:3] == r[1:3],
          "after the SL-A2-06 change: %s (severity and owner unchanged, SL-O-21)" % (r2,))
    lv = d["rout"]["escalation_policy_revision_1"]["levels"]
    check("DD-K-05", [x["level"] for x in lv] == list(range(1, len(lv) + 1)) and all(set(x["team_keys"]) <= teams and x["team_keys"] for x in lv),
          "escalation levels 1..%d without gaps, each with existing teams" % len(lv))

    # DD-K-06 impact and titles
    names = [s["name"] for s in storm_imp]
    t1 = title(m, alert_types, hyp, storm, storm_imp)
    t3 = title(m, alert_types, f3_hyp, f3, f3_imp)
    check("DD-K-06", names == ["Balance Check (USSD)", "Bundle Purchase", "SMS Notifications"], "storm customer impact %s" % names)
    check("DD-K-06", t1 == "Subscriber DB server: Disk full — 3 services affected", "storm title: " + t1)
    check("DD-K-06", t3 == "Report export job: Report export job failed — 1 service affected", "SL-F-3 title: " + t3)

    # DD-K-07 workflow version 1 and version 2
    diag_keys = {x["diagnostic_key"] for x in d["ops"]["diagnostic_definitions"]}
    v1 = d["wf"]["workflow"]["version_1"]
    viol1, paths1, steps1 = validate(v1, d, teams, diag_keys)
    check("DD-K-07", not viol1, "version 1 passes SL-WE-08 rules%s" % ("" if not viol1 else ": %s" % viol1))
    v2 = apply_changes(v1, d["wf"]["draft_changes_for_version_2"])
    viol2, paths2, steps2 = validate(v2, d, teams, diag_keys)
    check("DD-K-07", not viol2, "version 2 (draft changes applied) passes SL-WE-08 rules%s" % ("" if not viol2 else ": %s" % viol2))
    # PM-WV-02: the storm path (P1, every step taking its success output) contains the required steps in order
    def storm_path(steps, conns):
        nxt = {(c["from_step_id"], c["from_output"]): c["to_step_id"] for c in conns}
        pref = {"trigger": "next", "condition": "p1", "diagnostic": "done", "ai_task": "done", "ticket": "done",
                "notification": "sent", "escalation": "next", "approval": "approved", "action": "succeeded",
                "recovery_check": "recovered"}
        cur = [s for s in steps.values() if s["type"] == "trigger"][0]["step_id"]
        out = []
        while cur:
            st = steps[cur]
            tag = st["type"] + ("/" + st["parameters"].get("task", st["parameters"].get("operation", st["parameters"].get("mode", ""))) if st["type"] in ("ai_task", "ticket", "escalation") else "")
            out.append(tag)
            cur = nxt.get((cur, pref[st["type"]]))
        return out
    sp = storm_path(steps1, v1["connections"])
    need = ["diagnostic", "ai_task/SL-T-1", "ai_task/SL-T-2", "ticket/create", "notification",
            "escalation/wait_for_acknowledgement", "ai_task/SL-T-3", "approval", "action", "recovery_check", "ticket/resolve"]
    it = iter(sp)
    check("DD-K-07", all(n in it for n in need), "PM-WV-02: storm path %s" % sp)
    # PM-WV-03: approval and action only reachable on the P1 branch
    p1_only = all(("s-severity", "p1") in p for p in paths1 for (s, _) in p if steps1[s]["type"] in ("approval", "action"))
    check("DD-K-07", p1_only, "PM-WV-03: Approval and Action only on the P1 branch")
    # PM-WV-04: not_recovered reaches escalate_now; attempt limit 1 means no loop back to Action
    wv4 = all(any(steps1[s]["type"] == "escalation" and steps1[s]["parameters"]["mode"] == "escalate_now" for s, _ in p[i + 1:])
              for p in paths1 for i, (s, o) in enumerate(p) if steps1[s]["type"] == "recovery_check" and o == "not_recovered")
    check("DD-K-07", wv4, "PM-WV-04: on every branch, not recovered leads to an Escalation step in escalate_now mode")
    t1v = [s for s in v1["steps"] if s["step_id"] == "s-wait-ack"][0]["parameters"]["timer_s"]
    t2v = [s for s in v2["steps"] if s["step_id"] == "s-wait-ack"][0]["parameters"]["timer_s"]
    n_appr = sum(1 for s in v2["steps"] if s["type"] == "approval")
    check("DD-K-07", t1v == 600 and t2v == 120 and n_appr == 2, "timer v1 %d s, v2 %d s; approvals in v2: %d" % (t1v, t2v, n_appr))

    # DD-K-08 runbook selection for the storm (DM-R-50)
    kind = m.comps[hyp]["kind"]
    lim = d["set"]["knowledge_settings"]["runbook_limit"]
    app = [b for b in d["runbooks"] if b["types"] & storm_types and (not b["kinds"] or kind in b["kinds"])]
    app.sort(key=lambda b: (-len(b["types"] & storm_types), b["runbook_key"]))
    chosen = [b["runbook_key"] for b in app[:lim]]
    check("DD-K-08", chosen == ["RB-API-01", "RB-DB-01", "RB-USSD-01"], "runbooks given to SL-T-1 for the storm: %s" % chosen)
    rb_keys = [b["runbook_key"] for b in d["runbooks"]]
    check("DD-K-08", uniq(rb_keys) and all(b["types"] <= set(alert_types) and b["types"] for b in d["runbooks"]),
          "runbook keys unique; every applicable alert type exists")

    # DD-K-09 similar-incident search text (DM-R-51)
    def text(types, services, comp):
        return "alert types: %s; services: %s; component: %s" % (", ".join(sorted(types)), ", ".join(sorted(services)), comp)
    storm_text = text(storm_inc["types"], [s["service_key"] for s in storm_imp], hyp)
    def ptext(p):
        return text(p["alert_types"].split(";"), p["affected_service_keys"].split(";"), p["hypothesis_component_key"])
    same = [p for p in d["past"] if ptext(p) == storm_text]
    check("DD-K-09", len(same) == 1, "storm search text equals exactly one past incident: %s" % [p["past_incident_key"] for p in same])
    best = same[0] if same else {}
    purge = [p for p in d["past"] if p["fix_action_key"] == "purge-archived-txlogs"]
    check("DD-K-09", best.get("fix_action_key") == "purge-archived-txlogs" and best.get("fix_success_count") == "4",
          "best match %s links purge-archived-txlogs with success count %s" % (best.get("past_incident_key"), best.get("fix_success_count")))
    check("DD-K-09", len(purge) == 5 and all(p["fix_success_count"] == "4" for p in purge),
          "exactly 5 past incidents use the purge, each with success count 4: %s" % [p["past_incident_key"] for p in purge])
    clash = [p for p in d["past"] if "report_job_failed" in p["alert_types"] or "svc-mgmt-reports" in p["affected_service_keys"]
             or p["hypothesis_component_key"] in ("report-export", "srv-rpt-01")]
    check("DD-K-09", not clash, "no past incident uses report_job_failed, svc-mgmt-reports, report-export or srv-rpt-01")
    print("      storm search text: " + storm_text)

    # DD-K-10 references and counts (DM-R-60 and entity rules)
    errs = []
    if len(d["past"]) != 50:
        errs.append("past incidents %d" % len(d["past"]))
    if len({p["past_incident_key"] for p in d["past"]}) != 50:
        errs.append("past incident keys not unique")
    for p in d["past"]:
        if int(p["alert_count"]) < 1:
            errs.append("alert_count " + p["past_incident_key"])
        if p["record_label"] != "Fictional record":
            errs.append("label " + p["past_incident_key"])
        if not set(p["alert_types"].split(";")) <= set(alert_types):
            errs.append("alert type " + p["past_incident_key"])
        if not set(p["affected_service_keys"].split(";")) <= set(m.services):
            errs.append("service " + p["past_incident_key"])
        if p["hypothesis_component_key"] not in m.comps:
            errs.append("component " + p["past_incident_key"])
        if p["fix_action_key"] and p["fix_action_key"] not in {a["action_key"] for a in d["ops"]["registered_actions"]}:
            errs.append("action " + p["past_incident_key"])
    for s in m.services.values():
        if s.get("owner_team_key") and s["owner_team_key"] not in teams:
            errs.append("service owner " + s["service_key"])
    for c in m.comps.values():
        if c.get("owner_team_key") and c["owner_team_key"] not in teams:
            errs.append("component owner " + c["component_key"])
    for x in d["ident"]["memberships"]:
        if x["team_key"] not in teams or x["username"] not in users:
            errs.append("membership %s" % x)
    if set(d["ident"]["permission_grants"]) != users:
        errs.append("permission grants per user")
    routing_fields = {"hypothesis_component", "hypothesis_component_kind", "hypothesis_criticality", "affected_services",
                      "affected_service_count", "highest_service_criticality", "alert_count", "alert_types", "alert_sources"}
    if rules[-1]["conditions"] or any(not r_["conditions"] for r_ in rules[:-1]):
        errs.append("default rule must be last and only (DM-E41-R1)")
    for r_ in rules:
        if any(c["field"] not in routing_fields for c in r_["conditions"]):
            errs.append("routing field " + r_["rule_key"])
        if r_["owner_team_key"] not in teams or not set(r_["informed_team_keys"]) <= teams:
            errs.append("routing teams " + r_["rule_key"])
    if not set(ch["after"]) <= teams:
        errs.append("routing change teams")
    ops = {o["operation_key"]: o for o in d["ops"]["operations"]}
    for x in d["ops"]["diagnostic_definitions"]:
        if ops[x["operation_key"]]["permission_kind"] != "diagnostic":
            errs.append("diagnostic op " + x["diagnostic_key"])
        if set(x["parameter_values"]) != {p["name"] for p in ops[x["operation_key"]]["parameters"]}:
            errs.append("diagnostic params " + x["diagnostic_key"])
    rc_keys = {x["recovery_check_key"] for x in d["ops"]["recovery_check_definitions"]}
    for a in d["ops"]["registered_actions"]:
        if ops[a["operation_key"]]["permission_kind"] != "action":
            errs.append("action op")
        if set(a["parameter_values"]) != {p["name"] for p in ops[a["operation_key"]]["parameters"]}:
            errs.append("action params")
        for sc in (a["precondition"], a["reconciliation"]):
            if ops[sc["operation_key"]]["permission_kind"] != "diagnostic":
                errs.append("state check op")
        if a["recovery_check_key"] not in rc_keys:
            errs.append("action recovery check")
        if not a["limits"] or a["attempt_limit"] < 1:
            errs.append("limits")
        for p in ops[a["operation_key"]]["parameters"]:
            val = a["parameter_values"][p["name"]]
            if p["value_type"] == "integer" and not (p["min"] <= val <= p["max"]):
                errs.append("param bound " + p["name"])
            if p["value_type"] == "path" and not any(val.startswith(x) for x in p["allowed_prefixes"]):
                errs.append("param prefix " + p["name"])
    for x in d["ops"]["recovery_check_definitions"]:
        for c in x["conditions"]:
            if c["kind"] == "component_no_firing_alert" and (c["component_key"] not in m.comps or not set(c["alert_types"]) <= set(alert_types)):
                errs.append("recovery condition")
    if sum(1 for x in d["ops"]["recovery_check_definitions"] if x["is_default"]) != 1:
        errs.append("default recovery check")
    pol = d["ops"]["action_policies"]
    if [x for x in pol if x["service_key"] is None].__len__() != 1:
        errs.append("policy with empty service")
    grants = d["ops"]["target_access_grants"]
    targets = {c["target_key"] for c in m.comps.values() if c.get("target_key")}
    if {g_["target_key"] for g_ in grants} != targets:
        errs.append("target keys vs grants")
    if len({g_["credential_ref"] for g_ in grants}) != len(grants):
        errs.append("diagnostic and action grants share a credential (DM-E13-R1)")
    creds = {c["handle"]: c for c in d["set"]["credential_references"]}
    for g_ in grants:
        if creds[g_["credential_ref"]]["purpose"] != "target_" + g_["permission_kind"]:
            errs.append("grant credential purpose")
    for s in d["mon"]["monitoring_sources"]:
        if creds[s["webhook_token_ref"]]["purpose"] != "webhook_token":
            errs.append("webhook token purpose")
    if len({s["webhook_token_ref"] for s in d["mon"]["monitoring_sources"]}) != 2:
        errs.append("one webhook token per source")
    inst = {c["handle"] for c in d["set"]["connector_instances"]}
    for c in d["set"]["connector_instances"]:
        if c["credential_ref"] and c["credential_ref"] not in creds:
            errs.append("connector credential " + c["handle"])
        elif c["credential_ref"] and creds[c["credential_ref"]]["purpose"] not in ("external_api", "smtp"):
            errs.append("connector credential purpose " + c["handle"])
    prov = {p["provider_key"]: p for p in d["set"]["ai_provider_configurations"]}
    for p in prov.values():
        if p["connector_instance"] not in inst:
            errs.append("provider instance " + p["provider_key"])
    allowed = d["set"]["allowed_providers"]
    if allowed != {"SL-T-1": ["claude", "openai", "local_model"], "SL-T-2": ["claude", "openai", "local_model"], "SL-T-3": ["local_embedding", "openai"]}:
        errs.append("allowed providers differ from DM-E71-R1")
    for task, keys in d["set"]["task_assignments"].items():
        if any(prov[k]["provider_kind"] not in allowed[task] for k in keys) or len(keys) != len(set(keys)):
            errs.append("task assignment " + task)
    if len(d["set"]["task_assignments"]["SL-T-3"]) != 1:
        errs.append("SL-T-3 must have exactly one provider (DM-E72-R2)")
    if d["set"]["knowledge_settings"]["embedding_provider_key"] != d["set"]["task_assignments"]["SL-T-3"][0]:
        errs.append("embedding provider differs from the SL-T-3 assignment")
    cats = {"alert_data", "service_context", "diagnostic_output", "runbook_text", "incident_history", "incident_record"}
    if set(d["set"]["data_routing_rules"]) != cats or any(not set(v) <= set(prov) for v in d["set"]["data_routing_rules"].values()):
        errs.append("data routing rules")
    addr_subjects = {(a["subject_kind"], a["subject_key"]) for a in d["set"]["delivery_addresses"]}
    for a in d["set"]["delivery_addresses"]:
        if (a["subject_kind"] == "team" and a["subject_key"] not in teams) or (a["subject_kind"] == "user" and a["subject_key"] not in users):
            errs.append("delivery address " + a["subject_key"])
    # every team that the demo can notify has a Teams and an email address
    notifiable = {"noc-l1", "db-ops", "vas-service-owners", "it-apps", "customer-care"} | {t for x in lv for t in x["team_keys"]}
    for t in notifiable:
        if ("team", t) not in addr_subjects:
            errs.append("no address for notifiable team " + t)
    for u in ("dbops-engineer", "dbops-lead"):
        if ("user", u) not in addr_subjects:
            errs.append("no address for approver " + u)
    if {b["metric_key"] for b in d["set"]["baseline_values"]} != {"time_to_acknowledge", "time_to_recover", "alerts_suppressed", "incidents_merged"} or \
       any(b["label"] != "Illustrative baseline" for b in d["set"]["baseline_values"]):
        errs.append("baselines")
    check("DD-K-10", not errs, "cross-references and counts%s" % ("" if not errs else ": %s" % errs))

    # DD-K-11 dashboard over the seeded history (DM-R-53)
    tta = [int(p["acknowledged_after_minutes"]) for p in d["past"] if p["acknowledged_after_minutes"]]
    ttr = [int(p["recovered_after_minutes"]) for p in d["past"] if p["recovered_after_minutes"]]
    alerts_total = sum(int(p["alert_count"]) for p in d["past"])
    merged = sum(1 for p in d["past"] if int(p["alert_count"]) >= 2)
    dash = (half_up(sum(tta) / len(tta)), half_up(sum(ttr) / len(ttr)), alerts_total, alerts_total - len(d["past"]), merged)
    check("DD-K-11", dash == EXPECTED_DASHBOARD,
          "seeded history: time to acknowledge %.1f min, time to recover %.1f min, %d alerts, %d alerts suppressed, %d incidents merged" % dash)
    window_ok = all(0 < int(p["opened_minutes_before_import"]) <= 43200 for p in d["past"])
    check("DD-K-11", window_ok, "every past incident opened within the last 30 days before import")

    # DD-K-12 readiness gaps (DM-E-81)
    gaps = sorted([("server_without_owner", c["component_key"]) for c in m.comps.values()
                   if c["kind"] == "server" and (not c.get("owner_team_key") or c["owner_team_key"] not in teams)] +
                  [("service_without_criticality", s["service_key"]) for s in m.services.values() if not s.get("criticality")])
    check("DD-K-12", gaps == [("server_without_owner", "srv-rpt-01"), ("service_without_criticality", "svc-mgmt-reports")],
          "readiness gaps in the starting state: %s" % gaps)

    # DD-K-13 approvers and acknowledgers
    grants_ = d["ident"]["permission_grants"]
    mem = {}
    for x in d["ident"]["memberships"]:
        mem.setdefault(x["team_key"], set()).add(x["username"])
    elig = sorted(u for u in mem.get("db-ops", ()) if "approve_action" in grants_[u])
    check("DD-K-13", elig == ["dbops-engineer", "dbops-lead"], "eligible approvers for the DB Ops Approval step: %s" % elig)
    notified = {"noc-l1", "db-ops", "vas-service-owners"}            # storm Notification step (owner, informed, NOC L1)
    ack = sorted(u for t in notified for u in mem.get(t, ()) if "acknowledge" in grants_[u])
    check("DD-K-13", "noc-l1" in ack, "users who can acknowledge the storm incident: %s" % ack)

    # DD-K-14 timing consistency
    src = d["mon"]["monitoring_sources"]
    cs = d["set"]["correlation_settings"]
    inc = d["set"]["incident_settings"]
    rcs = {x["recovery_check_key"]: x for x in d["ops"]["recovery_check_definitions"]}
    steps_by_id = {s["step_id"]: s for s in v1["steps"]}
    num = d["lab"]["timing_targets"]["numbers"]
    t_ok = [
        all(s["health_check_interval_s"] < s["stale_after_s"] < s["disconnected_after_s"] for s in src),
        num["max_gap_s"] < cs["settle_period_s"] < cs["max_forming_s"],
        num["arrival_within_s"] + cs["settle_period_s"] <= cs["max_forming_s"],
        num["arrival_within_s"] <= cs["grouping_window_s"],
        num["resolve_after_action_s"] + rcs["rc-subscriber-db"]["observation_window_s"] < steps_by_id["s-recover"]["parameters"]["max_wait_s"],
        num["resolve_after_clear_s"] + num["clear_settle_s"] <= 60,
        num["no_refire_s"] >= d["set"]["incident_settings"]["reopen_window_s"],
        cs["stability_check_interval_s"] <= 5,
        inc["closure_check_interval_s"] <= 60,
        rcs["rc-subscriber-db"]["observation_window_s"] < steps_by_id["s-recover"]["parameters"]["max_wait_s"],
        rcs["rc-default"]["observation_window_s"] < steps_by_id["s-recover-2"]["parameters"]["max_wait_s"],
        60 + rcs["rc-subscriber-db"]["observation_window_s"] < steps_by_id["s-recover"]["parameters"]["max_wait_s"],
        steps_by_id["s-approve"]["parameters"]["expiry_s"] >= 600,
        0.0 <= d["set"]["knowledge_settings"]["min_similarity"] <= 1.0,
    ]
    check("DD-K-14", all(t_ok), "timing values consistent: %s" % t_ok)
    db = d["lab"]["lab_db_server"]
    folders = {"db": 6, "export": 1}
    inj = db["data_filesystem"]["device_size_gib"] - db["baseline_used_gib"] - 0.25   # XFS overhead and free space allowance
    check("DD-K-14", sum(folders.values()) == db["baseline_used_gib"] and max(folders.values()) + 1 <= inj <= 12,
          "injected archive about %.2f GiB: larger than the largest baseline folder + 1 GiB and at most the 12 GiB action limit" % inj)
    mb = d["lab"]["mock_behaviour"]
    lab_schemas = [row for row in d["mon"]["lab_alert_catalogue"] if row["id"] == "LA-02"][0]["dimensions"]["schema"]
    writers = mb["api_instances"] + mb["ussd_gateway"] + mb["sms_queue"]
    check("DD-K-14", all(x in mb["subscriber_database"] and x in writers for x in lab_schemas),
          "every LA-02 schema is a database schema with a named writer: %s" % lab_schemas)

    # DD-K-15 identity
    check("DD-K-15", users == {"presenter", "noc-l1", "dbops-engineer", "dbops-lead", "admin"} and len(teams) == 9,
          "demo users exactly SL-A3-07; 9 teams")

    # DD-K-16 Round 5 values (50-architecture AR-A-12, AR-A-13): no R5 marker left; only the AR-B-07 placeholders;
    # secret locators; source keys; Teams addresses with their own webhook credential; connector settings of Section 12.2
    errs16 = []
    def walk(v):
        if isinstance(v, dict):
            for x in v.values():
                yield from walk(x)
        elif isinstance(v, list):
            for x in v:
                yield from walk(x)
        else:
            yield v
    for part in ("ident", "smap", "mon", "ops", "rout", "wf", "set", "lab"):
        if any(v == "R5" for v in walk(d[part])):
            errs16.append("R5 marker left in " + part)
    allowed_ph = {"demo_domain", "jira_site", "gpu_host_ip", "lab_db_host_key_sha256"} | {"mailbox." + k for k in (
        "noc-l1", "db-ops", "vas-service-owners", "it-apps", "noc-shift-lead", "noc-management", "customer-care",
        "dbops-engineer", "dbops-lead")}
    for name in sorted(os.listdir(folder)):
        if name.endswith((".yaml", ".md", ".csv")) and name != "README.md":
            text = open(os.path.join(folder, name), encoding="utf-8").read()
            for ph in re.findall(r"\$\{([^}]*)\}", text):
                if ph not in allowed_ph:
                    errs16.append("placeholder ${%s} in %s" % (ph, name))
            if "<demo-domain>" in text:
                errs16.append("<demo-domain> in " + name)
    creds16 = {c["handle"]: c for c in d["set"]["credential_references"]}
    for c in creds16.values():
        if not re.fullmatch(r"secret:[a-z0-9-]{1,64}", str(c["locator"])):
            errs16.append("locator " + c["handle"])
    if len({c["locator"] for c in creds16.values()}) != len(creds16):
        errs16.append("two credential references share a locator")
    keys16 = [s_.get("source_key") for s_ in d["mon"]["monitoring_sources"]]
    if any(not isinstance(k, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", k) for k in keys16) or len(set(keys16)) != len(keys16):
        errs16.append("monitoring source keys %s" % keys16)
    used_webhook = []
    for a in d["set"]["delivery_addresses"]:
        ref = a.get("credential_ref")
        if a["channel"] == "teams":
            if not ref or creds16.get(ref, {}).get("purpose") != "webhook_url":
                errs16.append("Teams address without webhook_url credential: " + a["subject_key"])
            used_webhook.append(ref)
        elif ref:
            errs16.append("email address with a credential: " + a["subject_key"])
    if len(set(used_webhook)) != len(used_webhook) or set(used_webhook) != {h for h, c in creds16.items() if c["purpose"] == "webhook_url"}:
        errs16.append("each webhook_url credential belongs to exactly one Teams address")
    settings_keys = {"zabbix": {"api_path"}, "alertmanager": {"username"}, "linux_server": {"port", "host_key_sha256"},
                     "jira_cloud": {"project_key", "issue_type", "resolved_status", "reopened_status", "label_prefix"},
                     "ms_teams": set(), "email": {"from_address", "starttls"}, "claude": {"anthropic_version"},
                     "openai": set(), "local_model": {"keep_alive", "num_ctx"}, "local_embedding": {"keep_alive"}}
    for c in d["set"]["connector_instances"]:
        if not isinstance(c["settings"], dict) or set(c["settings"]) != settings_keys.get(c["kind"]):
            errs16.append("settings keys of " + c["handle"])
        if not isinstance(c["address"], str) or not c["address"]:
            errs16.append("address of " + c["handle"])
    for p_ in d["set"]["ai_provider_configurations"]:
        if not isinstance(p_["price_input_per_million"], (int, float)) or not isinstance(p_["price_output_per_million"], (int, float)):
            errs16.append("prices of " + p_["provider_key"])
    check("DD-K-16", not errs16, "Round 5 values: %d credential references, %d Teams webhooks, source keys %s%s" % (
        len(creds16), len(used_webhook), keys16, "" if not errs16 else ": %s" % errs16))

    for cid, ok_, detail in RESULTS:
        print("%s %s  %s" % ("PASS" if ok_ else "FAIL", cid, detail))
    print("\n%d checks, %d failed" % (len(RESULTS), len(FAILS)))
    return 1 if FAILS else 0


# Values recorded in README DD-K-11 after generation; a change to the past incidents must update both.
EXPECTED_DASHBOARD = (5.7, 41.1, 575, 525, 46)

if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "."))
