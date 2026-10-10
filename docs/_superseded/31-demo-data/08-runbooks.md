# DD-F-08 Runbooks (seed part 8, DM-E-63)

All runbooks are invented (SL-DR-01) and written in NOC language. Each section is one runbook. Its fields are given in the table at the top of the section, and the text below the table is the `text` field. Every runbook is version 1. With `runbook_limit` = 3, the storm incident receives RB-DB-001, RB-API-003 and RB-SMS-004, in that order (DM-R-50; check DD-K-08).

## RB-DB-001 — Subscriber DB data filesystem full

| Field | Value |
| --- | --- |
| runbook_key | RB-DB-001 |
| title | Subscriber DB data filesystem full |
| applicable_alert_types | disk_full, db_write_errors, balance_api_errors, bundle_api_errors |
| applicable_component_kinds | server, database |

**Symptoms.** /data on the subscriber DB server reaches 100%, and the database stops accepting writes. The balance-check and bundle-purchase APIs then return errors, and USSD sessions time out. SMS confirmations queue up because delivery records cannot be written.

**Most common cause.** The archived transaction logs under /data/txlog_archive are not purged because the nightly purge job has stalled. Each archived log is 256 MiB.

**Checks (read-only).**

1. Disk usage per filesystem. Confirm that /data is at or above 90%.
2. Largest folders under /data. A /data/txlog_archive folder above 5 GiB confirms the cause.
3. /data growth over the last 5 minutes. Growth of more than 10 percentage points means archiving is still running.

**Fix.** Run the registered action "Purge archived transaction logs":

- it deletes only *.arc files older than 24 hours;
- it deletes at most 12 GiB;
- it needs approval from Database Operations.

Do not delete logs newer than 24 hours, because they may still be needed for recovery.

**Recovery criteria.** All related alerts clear at source. The synthetic USSD session check passes from every probe city for 60 seconds.

**Escalate when.** /data stays above 90% after the purge, or database writes keep failing while disk space is free. The database may then be in read-only protection mode; escalate to NOC Management through the escalation policy.

## RB-USSD-002 — USSD gateway timeouts

| Field | Value |
| --- | --- |
| runbook_key | RB-USSD-002 |
| title | USSD gateway timeouts |
| applicable_alert_types | ussd_gateway_timeout |
| applicable_component_kinds | (any) |

**Symptoms.** USSD menus time out on one or both gateway nodes.

**Checks.**

1. Check whether every menu is affected, or only some.
2. The gateway writes a session record to the subscriber database for every menu. If every menu times out, check the subscriber database first (RB-DB-001); if only balance or bundle menus time out, check those APIs (RB-API-003).

**Fix.**

- Database or API fault: fix the back end. Do not restart the gateway.
- Gateway-only fault (one node, back ends healthy): VAS Operations restarts the affected node outside peak hours.

**Recovery criteria.** The timeout alerts clear, and synthetic USSD sessions succeed.

## RB-API-003 — Balance and bundle API errors

| Field | Value |
| --- | --- |
| runbook_key | RB-API-003 |
| title | Balance and bundle API errors |
| applicable_alert_types | balance_api_errors, bundle_api_errors, api_latency_high |
| applicable_component_kinds | (any) |

**Symptoms.** Error rates rise on the balance-check and bundle-purchase APIs on every instance.

**Checks.**

1. If every instance and every endpoint is failing, suspect the subscriber database.
2. If only one instance is failing, suspect that instance.
3. Check the API logs for "write failed" or "connection refused".

**Fix.**

- Database cause: follow RB-DB-001.
- Single-instance cause: IT Applications removes the instance from the pool and restarts it.

**Recovery criteria.** Error rates return below 1% for 60 seconds.

## RB-SMS-004 — SMS queue growth

| Field | Value |
| --- | --- |
| runbook_key | RB-SMS-004 |
| title | SMS queue growth |
| applicable_alert_types | sms_queue_growth, smsc_bind_lost |
| applicable_component_kinds | (any) |

**Symptoms.** Queue depth grows on many SMSC routes at once.

**Checks.**

1. If every route grows, check whether the SMS dispatcher can write delivery records to the subscriber database.
2. If only some routes grow, check the SMSC binds for those routes.

**Fix.**

- Database cause: follow RB-DB-001. The queue drains by itself once writes resume.
- Bind cause: VAS Operations re-binds the affected routes.

**Recovery criteria.** Queue depth falls on every route.

## RB-SYN-005 — Synthetic USSD session failures

| Field | Value |
| --- | --- |
| runbook_key | RB-SYN-005 |
| title | Synthetic USSD session failures |
| applicable_alert_types | synthetic_ussd_failed |
| applicable_component_kinds | (any) |

**Symptoms.** Synthetic USSD sessions fail from several probe cities.

**Checks.**

1. If every city fails, the cause is central: the gateway or a back end.
2. If one city fails, check the probe and the regional network path.

**Fix.** Fix the central cause. Synthetic sessions are the customer-experience signal used to confirm recovery.

**Recovery criteria.** Every probe city succeeds for 60 seconds.

## RB-RPT-006 — Report export job failed

| Field | Value |
| --- | --- |
| runbook_key | RB-RPT-006 |
| title | Report export job failed |
| applicable_alert_types | batch_job_failed |
| applicable_component_kinds | (any) |

**Symptoms.** The nightly or ad-hoc report export job on the reporting server ends with an error.

**Checks.** Check the job log on the reporting server and the read access to the billing database.

**Fix.** IT Infrastructure re-runs the job during business hours. It has no customer impact.

**Recovery criteria.** The next job run succeeds.
