#!/usr/bin/env python3
"""DD-F-11: deterministic generator of 09-past-incidents.csv (DD-F-09).

Usage (from the repository root):
    python3 docs/31-demo-data/tools/gen_past.py docs/31-demo-data/09-past-incidents.csv

The output is identical on every run (fixed seed, fixed pattern list). Every record is invented (SL-DR-01) and
labelled "Fictional record" (SL-DR-03). Times are relative to the seed import (DD-R-04).
"""
import csv
import random
import sys

SEED = 20261010
LABEL = "Fictional record"
PURGE = "purge-archived-txlogs"
PURGE_SUCCESS_COUNT = 4          # the purge was used 5 times and worked 4 times

BAL, BUN, SMS = "svc-balance-check", "svc-bundle-purchase", "svc-sms-notifications"
STORM_TYPES = ["balance_api_errors", "bundle_api_errors", "db_write_errors", "disk_full",
               "sms_queue_growth", "ussd_synthetic_failed", "ussd_timeouts"]

# (pattern id, copies, title, alert types, services, hypothesis component, alert count range,
#  summary, fix description, fix action key)
PATTERNS = [
    ("A", 1, "Subscriber DB /data full after archive catch-up run",
     STORM_TYPES, [BAL, BUN, SMS], "srv-db-01", (118, 124),
     "Archive shipping caught up after a link outage and wrote two days of transaction log archives back to /data. "
     "/data reached 100%, database writes failed, and balance, bundle and SMS services failed with it.",
     "Purged archived transaction logs older than 24 hours (oldest first, 8.8 GiB). Services recovered within 3 minutes.",
     PURGE),
    ("B", 2, "Subscriber DB /data nearly full, first write errors",
     ["db_write_errors", "disk_space_low"], [BAL, BUN], "srv-db-01", (3, 9),
     "Nightly purge had not run for two nights. /data passed 95% and a few database writes failed.",
     "Purged archived transaction logs older than 24 hours. Write errors stopped at once.",
     PURGE),
    ("C", 1, "Subscriber DB /data full, balance and bundle APIs failing",
     ["balance_api_errors", "bundle_api_errors", "db_write_errors", "disk_full"], [BAL, BUN], "srv-db-01", (36, 44),
     "Archive build-up filled /data during the evening peak; API errors followed the database write errors.",
     "Purged archived transaction logs older than 24 hours. APIs recovered after about 2 minutes.",
     PURGE),
    ("D", 1, "Subscriber DB /data full, database stayed read-only",
     ["balance_api_errors", "bundle_api_errors", "db_write_errors", "disk_full", "ussd_synthetic_failed", "ussd_timeouts"],
     [BAL, BUN], "srv-db-01", (98, 108),
     "/data filled with archived transaction logs. After the purge freed space, the database stayed in read-only mode "
     "and writes kept failing.",
     "Purged archived transaction logs older than 24 hours, which freed space but did not restore writes. The DB Ops lead "
     "cleared read-only mode under an emergency change. This purge is not counted as a success.",
     PURGE),
    ("E", 5, "Balance-check API errors on one instance",
     ["balance_api_errors"], [BAL], "api-balance", (3, 6),
     "One balance-check API instance lost its cache connection and returned errors.",
     "IT Applications removed the instance from the pool and restarted it.", ""),
    ("F", 4, "Bundle-purchase API errors on one instance",
     ["bundle_api_errors"], [BUN], "api-bundle", (3, 6),
     "One bundle-purchase API instance ran out of worker threads.",
     "IT Applications restarted the instance after draining it.", ""),
    ("G", 3, "Subscriber DB lock waits slowing balance and bundle APIs",
     ["api_latency_high", "db_lock_waits"], [BAL, BUN], "db-subscriber", (10, 22),
     "A long-running ad-hoc query held row locks during peak hours.",
     "DB Ops ended the blocking session after checking with its owner.", ""),
    ("H", 2, "Subscriber DB connection pool exhausted",
     ["balance_api_errors", "db_connections_exhausted"], [BAL], "db-subscriber", (12, 20),
     "A connection leak in a balance-check API release exhausted the database sessions.",
     "IT Applications recycled the API pool; the release was rolled back the next morning.", ""),
    ("I", 2, "Subscriber DB replication lag",
     ["db_replication_lag"], [BAL], "db-subscriber", (1, 2),
     "Replication to the standby fell behind during a bulk tariff update.",
     "DB Ops waited for the update to finish; lag cleared without action.", ""),
    ("J", 3, "USSD gateway node down",
     ["ussd_node_down", "ussd_synthetic_failed", "ussd_timeouts"], [BAL, BUN], "ussd-gw", (14, 30),
     "One USSD gateway node stopped responding after a memory leak; sessions failed until traffic moved to the other node.",
     "VAS Operations restarted the node and confirmed traffic on both nodes.", ""),
    ("K", 3, "USSD gateway timeouts on one node",
     ["ussd_timeouts"], [BAL, BUN], "ussd-gw", (2, 6),
     "One gateway node timed out on several menus after a configuration reload.",
     "VAS Operations reloaded the previous menu configuration.", ""),
    ("L", 4, "SMSC bind down, SMS queue growing",
     ["sms_queue_growth", "smsc_bind_down"], [SMS], "sms-queue", (4, 12),
     "One SMSC bind dropped and messages queued on its routes.",
     "VAS Operations re-established the bind; the queue drained in 10 minutes.", ""),
    ("M", 3, "SMS queue growing on a few routes",
     ["sms_queue_growth"], [SMS], "sms-queue", (2, 5),
     "Throughput on a few routes dropped during a promotional campaign.",
     "No action; the queue drained after the campaign peak.", ""),
    ("N", 3, "CPU saturation on the Charging API server",
     ["api_latency_high", "cpu_saturation"], [BAL, BUN], "srv-api-01", (3, 8),
     "A log compression job ran during peak hours and saturated the CPU.",
     "IT Infrastructure moved the job to the night schedule.", ""),
    ("O", 3, "Memory pressure on the VAS platform server",
     ["memory_pressure", "ussd_timeouts"], [BAL, BUN], "srv-vas-01", (3, 7),
     "Memory pressure on the VAS platform server slowed the USSD gateway.",
     "IT Infrastructure restarted a leaking monitoring agent.", ""),
    ("P", 2, "Bundle-purchase API certificate expiring",
     ["cert_expiring"], [BUN], "api-bundle", (1, 1),
     "The TLS certificate of the bundle-purchase API was due to expire within 7 days.",
     "IT Applications renewed and deployed the certificate.", ""),
    ("Q", 3, "Synthetic USSD sessions failing in one city",
     ["ussd_synthetic_failed"], [BAL], "ussd-probe", (2, 5),
     "Probes in one city failed while every other city passed; the cause was the probe site's access link.",
     "NOC L1 confirmed with the access network team; no platform action.", ""),
    ("R", 3, "Bundle-purchase API latency high",
     ["api_latency_high"], [BUN], "api-bundle", (2, 6),
     "Bundle offer lookups slowed after a cache flush.",
     "No action; latency returned to normal when the cache warmed up.", ""),
    ("S", 2, "VAS platform server disk space low",
     ["disk_space_low"], [SMS], "srv-vas-01", (1, 1),
     "Application logs on the VAS platform server grew after debug logging was left on.",
     "VAS Operations switched debug logging off and rotated the logs.", ""),
]

FIELDS = ["past_incident_key", "title", "record_label", "opened_minutes_before_import",
          "acknowledged_after_minutes", "recovered_after_minutes", "alert_count", "alert_types",
          "affected_service_keys", "hypothesis_component_key", "summary", "fix_description",
          "fix_action_key", "fix_success_count"]


def build():
    rng = random.Random(SEED)
    records = []
    for pid, copies, title, types, services, comp, (lo, hi), summary, fix, action in PATTERNS:
        for _ in range(copies):
            records.append({
                "pattern": pid, "title": title, "alert_types": ";".join(sorted(types)),
                "affected_service_keys": ";".join(sorted(services)), "hypothesis_component_key": comp,
                "alert_count": rng.randint(lo, hi), "summary": summary, "fix_description": fix,
                "fix_action_key": action, "copies": copies,
            })
    # spread over the last 30 days (43,200 minutes): a fixed shuffle, then one slot per record
    rng.shuffle(records)
    # the storm's best match (pattern A) gets the first key, so it also wins any tie on the rounded score (DM-E66-R1)
    records.sort(key=lambda r: 0 if r["pattern"] == "A" else 1)
    slot = 43200 // len(records)                        # 864 minutes
    rows = []
    for i, r in enumerate(records):
        opened = 43200 - (i * slot) - rng.randint(30, slot - 60)
        ack = rng.randint(2, 9)
        if r["pattern"] in ("A", "C", "D"):
            recovered = rng.randint(24, 41) if r["pattern"] != "D" else rng.randint(70, 95)
        else:
            recovered = rng.randint(12, 68)
        rows.append({
            "past_incident_key": "PI-%04d" % (1001 + i),
            "title": r["title"],
            "record_label": LABEL,
            "opened_minutes_before_import": opened,
            "acknowledged_after_minutes": ack,
            "recovered_after_minutes": recovered,
            "alert_count": r["alert_count"],
            "alert_types": r["alert_types"],
            "affected_service_keys": r["affected_service_keys"],
            "hypothesis_component_key": r["hypothesis_component_key"],
            "summary": r["summary"],
            "fix_description": r["fix_description"],
            "fix_action_key": r["fix_action_key"],
            # a fix's success count is per fix: the purge worked 4 of its 5 uses; a manual fix is counted by its pattern
            "fix_success_count": PURGE_SUCCESS_COUNT if r["fix_action_key"] == PURGE else r["copies"],
        })
    return rows


def main(path):
    rows = build()
    assert len(rows) == 50
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS, lineterminator="\n")
        w.writeheader()
        for row in rows:
            w.writerow(row)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "09-past-incidents.csv")
