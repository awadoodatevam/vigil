"""Tests of the SL-T-1 and SL-T-2 requests, the search texts, valid_output (P-06 spec Sections 6.3 and 7) and the
rejection reasons of the product's SL-T-1 output checks (P-20 spec Sections 6.11, 6.14 and 7)."""

import json

import pytest
import request_data as rd
import spike_fakes

REQUESTS = rd.Requests()
VALID = {
    "likely_cause": "c",
    "hypothesis_verdict": "confirms",
    "evidence_labels": ["E1"],
    "uncertainty": "u",
    "runbooks_cited": ["RB-DB-01"],
}
RECORDINGS = spike_fakes.REPOSITORY / "dev" / "fakes" / "recordings"


def test_sl_t_1_parts():
    """SB-T-04 the SL-T-1 parts: 126 member alerts, hypothesis, impact, E1 to E3, three runbooks; the rendering."""
    parts = REQUESTS.parts_sl_t_1()
    assert [(label, category) for label, category, _ in parts] == [
        ("Member alerts", "alert_data"),
        ("Hypothesis", "service_context"),
        ("Customer impact", "service_context"),
        ("E1", "diagnostic_output"),
        ("E2", "diagnostic_output"),
        ("E3", "diagnostic_output"),
        ("RB-API-01", "runbook_text"),
        ("RB-DB-01", "runbook_text"),
        ("RB-USSD-01", "runbook_text"),
    ]
    members = parts[0][2].splitlines()
    assert len(members) == 126
    assert members[0] == (
        "- disk_full | srv-db-01 | zabbix | Disk full: /data free space below 1 GiB on lab-db-01 | "
        "fired 2026-11-10T09:00:00Z"
    )
    assert members[1] == (
        "- db_write_errors | db-subscriber | zabbix | Database write errors on schema subscriber_profile on lab-db-01"
        " | fired 2026-11-10T09:00:01Z"
    )
    assert members[30] == (
        "- sms_queue_growth | sms-queue | zabbix | SMS queue growing on route route-24 on lab-vas-01 | "
        "fired 2026-11-10T09:00:30Z"
    )
    assert members[31] == (
        "- disk_full | srv-db-01 | alertmanager | NodeFilesystemFull on lab-db-01:9100 | fired 2026-11-10T09:00:31Z"
    )
    assert members[32] == (
        "- balance_api_errors | api-balance | alertmanager | BalanceApiErrors on api-balance-1 (endpoint=/v1/balance)"
        " | fired 2026-11-10T09:00:32Z"
    )
    assert members[64] == (
        "- ussd_timeouts | ussd-gw | alertmanager | UssdGatewayTimeouts (node=ussd-gw-a, menu=balance) | "
        "fired 2026-11-10T09:01:04Z"
    )
    assert members[-1] == (
        "- ussd_synthetic_failed | ussd-probe | alertmanager | "
        "SyntheticUssdSessionFailed (city=sialkot, journey=sms-confirmation) | fired 2026-11-10T09:02:05Z"
    )
    assert parts[1][2] == (
        "Root-cause hypothesis: Subscriber DB server (srv-db-01), kind server, criticality critical; "
        "6 other affected components depend on it"
    )
    assert parts[2][2].splitlines() == [
        "Balance Check (USSD) (svc-balance-check), criticality critical, through api-balance, db-subscriber, ussd-gw",
        "Bundle Purchase (svc-bundle-purchase), criticality critical, through api-bundle, db-subscriber, ussd-gw",
        "SMS Notifications (svc-sms-notifications), criticality high, through sms-queue",
    ]
    assert [text for _, _, text in parts[3:6]] == [
        "Disk usage of /data: /data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
        "Largest folders under /data: Largest folder under /data: /data/txlog_archive (8.5 GiB)",
        "Data written under /data, last 15 minutes: 8.5 GiB written to 34 files under /data in the last 15 minutes",
    ]
    rb_db = parts[7][2]
    assert rb_db.startswith("Subscriber DB server: /data filling up\n\n**When to use:** Disk space low or disk full")
    assert rb_db.endswith("leaving read-only mode needs a change approval.")
    assert "|" not in rb_db.splitlines()[2]
    assert parts[8][2].endswith("timeouts last more than 10 minutes with healthy APIs.")
    assert parts[6][2].startswith("Balance-check and bundle-purchase API error rate high\n\n**When to use:**")

    rendered = rd.render(parts)
    assert rendered.startswith("### Member alerts (Alert data)\n- disk_full | srv-db-01 | zabbix | ")
    assert "\n\n### Hypothesis (Service context)\nRoot-cause hypothesis: Subscriber DB server (srv-db-01)" in rendered
    assert "\n\n### E1 (Diagnostic output)\nDisk usage of /data: " in rendered
    assert "\n\n### RB-DB-01 (Runbook text)\nSubscriber DB server: /data filling up\n\n" in rendered
    assert rendered.count("\n### ") == 8


def test_sl_t_2_parts():
    """SB-T-04 the SL-T-2 parts built from an accepted SL-T-1 answer, with E4; without one, no Investigation or E4."""
    e4 = "AI investigation by Claude: confirms the hypothesis Subscriber DB server; cites RB-DB-01, RB-API-01."
    parts = REQUESTS.parts_sl_t_2("Archive catch-up filled /data", "Whether the purge job failed", e4)
    assert parts == [
        ("Title", "incident_record", "Subscriber DB server: Disk full — 3 services affected"),
        (
            "Investigation",
            "incident_record",
            "Likely cause: Archive catch-up filled /data\nUncertainty: Whether the purge job failed",
        ),
        (
            "Alerts",
            "alert_data",
            "126 alerts of 7 types: balance_api_errors, bundle_api_errors, db_write_errors, disk_full, "
            "sms_queue_growth, ussd_synthetic_failed, ussd_timeouts",
        ),
        ("Customer impact", "service_context", REQUESTS.impact_text()),
        ("Hypothesis", "service_context", REQUESTS.hypothesis_text()),
        ("E1", "diagnostic_output", "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)"),
        ("E2", "diagnostic_output", "Largest folder under /data: /data/txlog_archive (8.5 GiB)"),
        ("E3", "diagnostic_output", "8.5 GiB written to 34 files under /data in the last 15 minutes"),
        ("E4", "incident_record", e4),
    ]
    assert REQUESTS.parts_sl_t_2("Archive catch-up filled /data", "Whether the purge job failed", None) == [
        part for part in parts if part[0] not in ("Investigation", "E4")
    ]
    assert rd.render(parts).startswith(
        "### Title (Incident record)\nSubscriber DB server: Disk full — 3 services affected\n\n"
        "### Investigation (Incident record)\nLikely cause: "
    )


def test_investigation_text():
    """AR-G-12 the SL-T-2 part E4: the DC-TX-03 summary of the answer's ai_investigation entry, cited keys in the
    answer's order without repeats; the verdict-less form for not_applicable."""
    answer = dict(VALID, hypothesis_verdict="disputes", runbooks_cited=["RB-USSD-01", "RB-DB-01", "RB-USSD-01"])
    assert REQUESTS.investigation_text("OpenAI", answer) == (
        "AI investigation by OpenAI: disputes the hypothesis Subscriber DB server; cites RB-USSD-01, RB-DB-01."
    )
    assert REQUESTS.investigation_text("Claude", dict(VALID, hypothesis_verdict="not_applicable")) == (
        "AI investigation by Claude; cites RB-DB-01."
    )


def test_search_texts():
    """SB-T-03 the storm's search text equals PI-1001's and only PI-1001's; 51 texts in file order."""
    storm = REQUESTS.storm_search_text()
    assert storm == (
        "alert types: balance_api_errors, bundle_api_errors, db_write_errors, disk_full, sms_queue_growth, "
        "ussd_synthetic_failed, ussd_timeouts; services: svc-balance-check, svc-bundle-purchase, "
        "svc-sms-notifications; component: srv-db-01"
    )
    past = REQUESTS.past_search_texts()
    assert len(past) == 50
    assert [key for key, _ in past] == [f"PI-{n}" for n in range(1001, 1051)]
    assert [key for key, text in past if text == storm] == ["PI-1001"]
    assert len([storm] + [text for _, text in past]) == 51


def test_valid_output_accepts():
    """AR-G-20 valid_output returns the object of an answer valid for the schema."""
    assert rd.valid_output(json.dumps(VALID), rd.SCHEMA_SL_T_1) == VALID
    assert rd.valid_output('{"summary": "s"}', rd.SCHEMA_SL_T_2) == {"summary": "s"}
    assert rd.cites_rb_db_01(VALID) is True
    assert rd.cites_rb_db_01("not JSON") is None


@pytest.mark.parametrize(
    "text, reason",
    [
        ("{not json", "not JSON"),
        ("[1, 2]", "not an object"),
        (json.dumps({k: v for k, v in VALID.items() if k != "uncertainty"}), "missing uncertainty"),
        (json.dumps(dict(VALID, extra="x")), "unexpected extra"),
        (json.dumps(dict(VALID, likely_cause=3)), "likely_cause has the wrong type"),
        (json.dumps(dict(VALID, evidence_labels=["E1", 2])), "evidence_labels has the wrong type"),
        (json.dumps(dict(VALID, runbooks_cited="RB-DB-01")), "runbooks_cited has the wrong type"),
        (json.dumps(dict(VALID, hypothesis_verdict="maybe")), "hypothesis_verdict is not one of the allowed values"),
        (json.dumps(dict(VALID, hypothesis_verdict=1)), "hypothesis_verdict has the wrong type"),
    ],
)
def test_valid_output_reasons(text, reason):
    """AR-G-20 valid_output names the reason an answer fails the schema."""
    assert rd.valid_output(text, rd.SCHEMA_SL_T_1) == reason


def test_system_texts_and_schemas():
    """AR-G-20 the system texts and schemas of Section 6.3, keys in the written order."""
    assert rd.SYSTEM_SL_T_2 == (
        "Write a summary of at most 600 characters for NOC staff in summary, in plain sentences, using only the "
        "supplied parts. Answer only with the JSON object."
    )
    assert rd.SYSTEM_SL_T_1.startswith("You are an IT and service NOC investigator. Use only the information")
    assert rd.SYSTEM_SL_T_1.endswith("Do not decide severity, owner or any action. Answer only with the JSON object.")
    assert list(rd.SCHEMA_SL_T_1) == ["type", "properties", "required", "additionalProperties"]
    assert list(rd.SCHEMA_SL_T_1["properties"]) == rd.SCHEMA_SL_T_1["required"]


SL_T_1_SYSTEM_TEXT = (
    "You are an IT and service NOC investigator. Use only the information in the supplied parts. Give the most likely "
    "cause of the incident in likely_cause. The part labelled Hypothesis, when it is supplied, names the root-cause "
    "hypothesis: in hypothesis_verdict, say whether the likely cause confirms or disputes that hypothesis, and answer "
    "not_applicable only when no part labelled Hypothesis is supplied. The evidence entries are the parts labelled E1, "
    "E2 and so on; member alerts and runbooks are not evidence entries. In evidence_labels, list the labels of the "
    "evidence entries the likely cause rests on, and no other label. In uncertainty, state what remains uncertain. In "
    "runbooks_cited, list the key of every supplied runbook whose steps address the likely cause, and no other key. "
    "Do not decide severity, owner or any action. Answer only with the JSON object."
)


def test_p20_member_line_hypothesis_and_system_text():
    """AR-G-12 the P-20 member line (no line numbers, fired times one second apart), the hypothesis text and the
    SL-T-1 system text of P-20 Section 6.10, exactly."""
    lines = REQUESTS.member_lines()
    assert all(line.startswith("- ") for line in lines)
    assert [line.rsplit(" | fired ", 1)[1] for line in lines[:3]] == [
        "2026-11-10T09:00:00Z",
        "2026-11-10T09:00:01Z",
        "2026-11-10T09:00:02Z",
    ]
    assert lines[59].endswith(" | fired 2026-11-10T09:00:59Z") and lines[60].endswith(" | fired 2026-11-10T09:01:00Z")
    assert [len(line.split(" | ")) for line in lines] == [5] * 126
    assert REQUESTS.hypothesis_text().startswith("Root-cause hypothesis: Subscriber DB server (srv-db-01), kind ")
    assert rd.SYSTEM_SL_T_1 == SL_T_1_SYSTEM_TEXT
    assert rd.EVIDENCE_LABELS == ["E1", "E2", "E3"]


def reasons(value, finish_complete=True, has_hypothesis=True):
    text = value if isinstance(value, str) or value is None else json.dumps(value)
    return rd.rejection_reasons(text, finish_complete, rd.EVIDENCE_LABELS, rd.RUNBOOK_KEYS, has_hypothesis)


@pytest.mark.parametrize(
    "changes, expected",
    [
        ({}, []),
        ({"likely_cause": " \n\t"}, ["no likely cause"]),
        ({"likely_cause": 7}, ["no likely cause"]),
        ({"likely_cause": None}, ["no likely cause"]),
        ({"likely_cause": "cause\u0000"}, ["no likely cause"]),
        ({"hypothesis_verdict": "not_applicable"}, ["does not say whether it confirms or disputes the hypothesis"]),
        ({"hypothesis_verdict": ["confirms"]}, ["does not say whether it confirms or disputes the hypothesis"]),
        ({"hypothesis_verdict": "disputes"}, []),
        ({"uncertainty": ""}, ["no uncertainty stated"]),
        ({"uncertainty": "u\u0000"}, ["no uncertainty stated"]),
        ({"evidence_labels": []}, ["no evidence reference"]),
        ({"evidence_labels": "E1"}, ["no evidence reference"]),
        ({"evidence_labels": ["E1", 2]}, ["no evidence reference"]),
        ({"evidence_labels": ["E1", "E2\u0000"]}, ["no evidence reference"]),
        ({"evidence_labels": ["E1", "E4"]}, ["refers to evidence that is not on the incident"]),
        ({"evidence_labels": ["RB-DB-01"]}, ["refers to evidence that is not on the incident"]),
        ({"evidence_labels": ["E1", "E1", "E3"]}, []),
        ({"runbooks_cited": []}, ["no runbook cited"]),
        ({"runbooks_cited": [None]}, ["no runbook cited"]),
        ({"runbooks_cited": ["RB-DB-01", "RB-X-99"]}, ["cites a runbook that was not supplied"]),
        ({"runbooks_cited": ["E1"]}, ["cites a runbook that was not supplied"]),
        (
            {
                "likely_cause": "",
                "hypothesis_verdict": "not_applicable",
                "uncertainty": " ",
                "evidence_labels": ["E9"],
                "runbooks_cited": [],
            },
            [
                "no likely cause",
                "does not say whether it confirms or disputes the hypothesis",
                "no uncertainty stated",
                "refers to evidence that is not on the incident",
                "no runbook cited",
            ],
        ),
        (
            {"evidence_labels": [], "runbooks_cited": ["RB-X-99"]},
            ["no evidence reference", "cites a runbook that was not supplied"],
        ),
    ],
)
def test_rejection_reasons(changes, expected):
    """AR-G-12 rejection_reasons gives each reason of the product's OutputChecks.slT1 with its text, in its order."""
    assert reasons(dict(VALID, **changes)) == expected


def test_rejection_reasons_missing_properties():
    """AR-G-12 a missing property counts as absent; every reason at once, in the order of P-20 Section 6.11."""
    assert reasons({}) == [
        "no likely cause",
        "does not say whether it confirms or disputes the hypothesis",
        "no uncertainty stated",
        "no evidence reference",
        "no runbook cited",
    ]
    assert reasons({key: value for key, value in VALID.items() if key != "hypothesis_verdict"}) == [
        "does not say whether it confirms or disputes the hypothesis"
    ]


def test_rejection_reasons_without_hypothesis():
    """AR-G-12 without a hypothesis the verdict is not checked, also for not_applicable or a missing verdict."""
    for verdict in ("not_applicable", "confirms", "maybe"):
        assert reasons(dict(VALID, hypothesis_verdict=verdict), has_hypothesis=False) == []
    without_verdict = {key: value for key, value in VALID.items() if key != "hypothesis_verdict"}
    assert reasons(without_verdict, has_hypothesis=False) == []


@pytest.mark.parametrize(
    "text, finish_complete",
    [
        (json.dumps(VALID), False),
        (None, True),
        ("{not json", True),
        ("", True),
        ("[1, 2]", True),
        ('"text"', True),
        (json.dumps(dict(VALID, extra="x")), True),
        ('{"likely_cause": NaN}', True),
        (json.dumps(VALID) + " {}", True),
    ],
)
def test_rejection_reasons_unreadable(text, finish_complete):
    """AR-G-12 truncated or refused, no text, not JSON, not an object, an extra property: only "no likely cause"."""
    assert reasons(text, finish_complete=finish_complete) == ["no likely cause"]


def test_finish_complete():
    """AR-G-12 finish_complete reads the finish as the product's connectors do (P-19 Section 6.5)."""
    assert rd.finish_complete("claude", {"stop_reason": "end_turn"}) is True
    assert rd.finish_complete("claude", {}) is True
    assert rd.finish_complete("claude", {"stop_reason": "max_tokens"}) is False
    assert rd.finish_complete("claude", {"stop_reason": "refusal"}) is False
    assert rd.finish_complete("openai", {"status": "completed", "output": []}) is True
    assert rd.finish_complete("openai", {"status": "incomplete"}) is False
    refusal = {"type": "message", "content": [{"type": "output_text", "text": "x"}, {"type": "refusal"}]}
    assert rd.finish_complete("openai", {"status": "completed", "output": [refusal]}) is False
    assert rd.finish_complete("openai", {"status": "completed", "output": [dict(refusal, type="reasoning")]}) is True
    assert rd.finish_complete("local_model", {"done_reason": "stop"}) is True
    assert rd.finish_complete("local_model", {"done_reason": "length"}) is False
    assert rd.finish_complete("local_model", None) is True


def recorded_answer(name):
    """The output text and finish of a recorded SL-T-1 answer, read as the product's connectors read them."""
    body = json.loads(json.loads((RECORDINGS / name).read_text())["response"]["body"])
    if name.startswith("claude/"):
        texts = [item.get("text") for item in body["content"] if item.get("type") == "text"]
        return (texts[0] if texts else None), rd.finish_complete("claude", body)
    if name.startswith("openai/"):
        texts = [
            item["text"]
            for output in body["output"]
            if output.get("type") == "message"
            for item in output.get("content") or []
            if item.get("type") == "output_text"
        ]
        return ("".join(texts) if texts else None), rd.finish_complete("openai", body)
    return (body["message"]["content"] or None), rd.finish_complete("local_model", body)


@pytest.mark.parametrize("name", ["claude/0001-sl-t-1.json", "openai/0001-sl-t-1.json"])
def test_recorded_answers_accepted(name):
    """AR-G-12 the recorded SL-T-1 answers of Claude and OpenAI pass the product's checks (as OutputChecksTest)."""
    text, finish = recorded_answer(name)
    assert text is not None and finish is True
    assert rd.rejection_reasons(text, finish, ["E1", "E2", "E3"], ["RB-API-01", "RB-DB-01", "RB-USSD-01"], True) == []


@pytest.mark.parametrize("k", range(1, 6))
def test_recorded_local_answers_rejected(k):
    """AR-G-12 each recorded local-llm answer of the P-06 prompt is rejected with exactly the verdict and the
    evidence reasons (as OutputChecksTest)."""
    text, finish = recorded_answer(f"local-llm/{k:04d}-chat-{k}.json")
    assert rd.rejection_reasons(text, finish, ["E1", "E2", "E3"], ["RB-API-01", "RB-DB-01", "RB-USSD-01"], True) == [
        "does not say whether it confirms or disputes the hypothesis",
        "refers to evidence that is not on the incident",
    ]
