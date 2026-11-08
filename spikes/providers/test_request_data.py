"""Tests of the SL-T-1 and SL-T-2 requests, the search texts and valid_output (P-06 spec Sections 6.3 and 7)."""

import json

import pytest
import request_data as rd

REQUESTS = rd.Requests()


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
    assert members[0] == "1. disk_full | srv-db-01 | zabbix | Disk full: /data free space below 1 GiB on lab-db-01"
    assert members[1] == (
        "2. db_write_errors | db-subscriber | zabbix | Database write errors on schema subscriber_profile on lab-db-01"
    )
    assert "31. sms_queue_growth | sms-queue | zabbix | SMS queue growing on route route-24 on lab-vas-01" in members
    assert "32. disk_full | srv-db-01 | alertmanager | NodeFilesystemFull on lab-db-01:9100" in members
    assert (
        "33. balance_api_errors | api-balance | alertmanager | BalanceApiErrors on api-balance-1 (endpoint=/v1/balance)"
        in members
    )
    assert "65. ussd_timeouts | ussd-gw | alertmanager | UssdGatewayTimeouts (node=ussd-gw-a, menu=balance)" in members
    assert members[-1] == (
        "126. ussd_synthetic_failed | ussd-probe | alertmanager | "
        "SyntheticUssdSessionFailed (city=sialkot, journey=sms-confirmation)"
    )
    assert [line.split(". ", 1)[0] for line in members] == [str(i) for i in range(1, 127)]
    assert parts[1][2] == (
        "Subscriber DB server (srv-db-01), kind server, criticality critical; 6 other affected components depend on it"
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
    assert rendered.startswith("### Member alerts (Alert data)\n1. disk_full | srv-db-01 | zabbix | ")
    assert "\n\n### Hypothesis (Service context)\nSubscriber DB server (srv-db-01)" in rendered
    assert "\n\n### E1 (Diagnostic output)\nDisk usage of /data: " in rendered
    assert "\n\n### RB-DB-01 (Runbook text)\nSubscriber DB server: /data filling up\n\n" in rendered
    assert rendered.count("\n### ") == 8


def test_sl_t_2_parts():
    """SB-T-04 the SL-T-2 parts built from an accepted SL-T-1 answer."""
    parts = REQUESTS.parts_sl_t_2("Archive catch-up filled /data", "Whether the purge job failed")
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
    ]
    assert rd.render(parts).startswith(
        "### Title (Incident record)\nSubscriber DB server: Disk full — 3 services affected\n\n"
        "### Investigation (Incident record)\nLikely cause: "
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


VALID = {
    "likely_cause": "c",
    "hypothesis_verdict": "confirms",
    "evidence_labels": ["E1"],
    "uncertainty": "u",
    "runbooks_cited": ["RB-DB-01"],
}


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
