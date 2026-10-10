# DD-F-08 Runbooks (seed part 8)

Seven invented runbooks in NOC language (DM-E-63, SL-DR-04). File convention (DD-R-09): each `## ` heading starts one runbook; its fields come from the table under the heading; "(any)" means the empty set; `text` is everything after the table up to the next `## ` heading, kept as Markdown and trimmed. Every runbook is version 1 and active.

## RB-API-01

| Field | Value |
| --- | --- |
| runbook_key | RB-API-01 |
| version | 1 |
| title | Balance-check and bundle-purchase API error rate high |
| applicable_alert_types | balance_api_errors, bundle_api_errors, api_latency_high |
| applicable_component_kinds | (any) |

**When to use:** BalanceApiErrors or BundleApiErrors firing on one or more API instances, or API latency above threshold.

**First checks (L1, 5 minutes):**

1. Count the instances affected. One instance only: likely an instance problem. All instances: look downstream first (subscriber DB, charging).
2. Check whether Subscriber DB alerts (disk, write errors, connections) are firing at the same time. If yes, the API errors are a symptom; follow the DB runbook and do not restart API instances.
3. Check the synthetic USSD probes. Failures in all cities confirm customer impact.

**Actions:**

- Single instance: IT Applications removes the instance from the pool and restarts it.
- All instances with a DB problem: no API action. Inform IT Applications and wait for DB recovery.

**Escalate:** to IT Applications after 15 minutes without improvement, or at once when all instances are affected and the DB is healthy.

## RB-DB-01

| Field | Value |
| --- | --- |
| runbook_key | RB-DB-01 |
| version | 1 |
| title | Subscriber DB server: /data filling up |
| applicable_alert_types | disk_full, disk_space_low, db_write_errors |
| applicable_component_kinds | server, database |

**When to use:** Disk space low or disk full on /data of the Subscriber DB server, usually with database write errors.

**Background:** /data holds the subscriber database files (/data/db), export files (/data/export) and the transaction log archive (/data/txlog_archive). Archived transaction logs are shipped to backup every night and the nightly purge deletes local archives older than 24 hours. When shipping or the purge fails, archives build up, or a catch-up run writes many archives back at once. The database refuses writes when less than 256 MiB is free on /data.

**First checks (read-only):**

1. Disk usage of /data.
2. Largest folders under /data. If /data/txlog_archive is the largest, the archive is the cause.
3. How much was written recently. A large amount in a few minutes points to a catch-up run, not to database growth.

**Fix (registered action `purge-archived-txlogs`, needs DB Ops approval):** delete *.arc files directly under /data/txlog_archive that are older than 24 hours, oldest first, at most 12 GiB. Precondition: /data at 90% or more. Never delete files under /data/db.

**Recovery:** database write errors clear within about a minute after space is freed; API errors, USSD timeouts and SMS queue growth follow. Confirm with the synthetic USSD probes before closing.

**If writes still fail after space is freed:** the database may have switched to read-only mode. Do not run the purge again. Escalate to the DB Ops lead; leaving read-only mode needs a change approval.

## RB-DB-02

| Field | Value |
| --- | --- |
| runbook_key | RB-DB-02 |
| version | 1 |
| title | Subscriber DB: write errors, lock waits or connection exhaustion |
| applicable_alert_types | db_write_errors, db_lock_waits, db_connections_exhausted, db_replication_lag |
| applicable_component_kinds | database |

**When to use:** database write errors without a disk alert, lock waits, connection pool exhaustion or replication lag.

**First checks:**

1. Check /data free space first (RB-DB-01). Write errors with a full disk belong to RB-DB-01.
2. Lock waits: find the blocking session and its owner before any action.
3. Connections exhausted: check which application holds most sessions.

**Actions:** DB Ops only. Killing a session needs the owner's agreement; changing session limits needs a change request.

**Escalate:** to the DB Ops lead when the database is in read-only mode, or when the problem lasts more than 20 minutes.

## RB-RPT-01

| Field | Value |
| --- | --- |
| runbook_key | RB-RPT-01 |
| version | 1 |
| title | Report export job failed |
| applicable_alert_types | report_job_failed |
| applicable_component_kinds | application |

**When to use:** the report export job on the Reporting server reports a failed run.

**Impact:** management reports are late. No customer service is affected.

**Actions:** IT Applications re-runs the job during working hours. If it fails twice, raise a ticket with the job log attached. No action is needed at night.

## RB-SMS-01

| Field | Value |
| --- | --- |
| runbook_key | RB-SMS-01 |
| version | 1 |
| title | SMS queue growing |
| applicable_alert_types | sms_queue_growth, smsc_bind_down |
| applicable_component_kinds | (any) |

**When to use:** queue depth growing on one or more SMS routes.

**First checks:**

1. Few routes: check the SMSC binds of those routes.
2. All routes: the SMS dispatcher cannot record deliveries. Check the Subscriber DB (RB-DB-01, RB-DB-02).

**Actions:** do not flush queues; queued messages are customer notifications. VAS Operations restarts a stuck bind. The queue drains by itself after a DB recovery.

**Escalate:** to VAS Operations when the oldest message is older than 30 minutes.

## RB-SRV-01

| Field | Value |
| --- | --- |
| runbook_key | RB-SRV-01 |
| version | 1 |
| title | Server CPU or memory pressure |
| applicable_alert_types | cpu_saturation, memory_pressure |
| applicable_component_kinds | server |

**When to use:** CPU saturation or memory pressure on any platform server.

**Actions:** identify the top processes. Known batch jobs: wait for them to finish. Unknown processes: escalate to IT Infrastructure.

## RB-USSD-01

| Field | Value |
| --- | --- |
| runbook_key | RB-USSD-01 |
| version | 1 |
| title | USSD gateway timeouts and synthetic session failures |
| applicable_alert_types | ussd_timeouts, ussd_synthetic_failed, ussd_node_down |
| applicable_component_kinds | (any) |

**When to use:** USSD gateway timeouts on one or both nodes, or synthetic USSD sessions failing.

**First checks:**

1. One node only: check the node itself.
2. Both nodes and several menus: the gateway is waiting on the balance-check or bundle-purchase APIs. Follow RB-API-01 and do not restart the gateway.
3. Synthetic sessions failing in all cities confirms the impact; failures in one city point to the access network, not the platform.

**Escalate:** to VAS Operations when one node is down or timeouts last more than 10 minutes with healthy APIs.
