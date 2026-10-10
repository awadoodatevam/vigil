# Vigil — Invented Jazz Demo Data Set

| Field | Value |
| --- | --- |
| Document | 31-demo-data (ID prefix `DD-`) |
| Version | 1.0 |
| Date | 10 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed). Round 3 closed. |

## 1. Purpose

This folder is the complete invented data set for the demo (SL-DR-04): the content of the seed bundle that brings a tenant to the starting state (`01-scope-lock` Section 3), and the demo-only lab, monitoring and fault definitions that produce the alert storm. The scene-by-scene script that uses it is `docs/30-jazz-storyboard.md` (prefix `SB-`).

## 2. Inputs read

`docs/00-context.md` v1.6; `docs/01-scope-lock.md` v1.6; `docs/10-product-map.md` v1.1; `docs/20-domain-model.md` v1.0; `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md` (story baseline). External product facts were checked on 10 October 2026 (`30-jazz-storyboard` Section 13).

## 3. Files (complete list)

| ID | File | Content | Seed parts (DM-R-56) |
| --- | --- | --- | --- |
| DD-F-00 | `README.md` | This file | — |
| DD-F-01 | `01-tenant-identity.yaml` | Tenant settings, 9 teams, 5 users with display names, memberships, permission grants | 1, 2 |
| DD-F-02 | `02-service-map.yaml` | Service map generation 1: 4 services, 12 components, 15 identity keys, 6 dependencies | 5 |
| DD-F-03 | `03-monitoring.yaml` | Monitoring sources with health thresholds, 18 alert types, 9 alert type mappings, and the lab alert catalogue (demo-only) | 6 |
| DD-F-04 | `04-operations-actions-recovery.yaml` | 5 runner operations and 2 target grants, 3 diagnostic definitions, 1 registered action and its policy, 2 recovery check definitions | 4, 12, 13, 14 |
| DD-F-05 | `05-routing-escalation.yaml` | Routing rule set revision 1 (3 rules), the SL-A2-06 rule change, escalation policy revision 1 (2 levels) | 11 |
| DD-F-06 | `06-workflow.yaml` | Workflow version 1 (19 steps, 31 connections) and the SL-A2-06 draft changes that make version 2 | 16 |
| DD-F-07 | `07-settings.yaml` | Credential references, connector instances, AI providers, allowed providers, task assignments, data routing, knowledge, incident and correlation settings, delivery addresses, baselines | 3, 7, 8, 9, 10, 15, 17 |
| DD-F-08 | `08-runbooks.md` | 7 runbooks | 8 |
| DD-F-09 | `09-past-incidents.csv` | Exactly 50 past incidents (SL-DR-04), each labelled "Fictional record" | 8 |
| DD-F-10 | `10-lab-and-faults.yaml` | Lab hosts and mock behaviour, the lab DB server, fault mechanics SL-F-1 to SL-F-4, monitoring configuration, timing targets, control panel rules (demo-only, not seeded) | — |
| DD-F-11 | `tools/gen_past.py` | Deterministic generator of DD-F-09 (fixed seed; re-running reproduces the file byte for byte) | — |
| DD-F-12 | `tools/check_data.py` | Runs checks DD-K-01 to DD-K-15 over this folder (the baseline folder sizes 6 GiB and 1 GiB of DD-F-10 are repeated in the script; a change to them MUST change both) | — |

## 4. Data rules

| ID | Rule | Trace |
| --- | --- | --- |
| DD-R-01 | All names, hosts, addresses and figures are invented. The data contains no real Jazz system name, IP address, host name, employee name, customer data or internal figure. The only real names are: the customer name "Jazz" (subject to CTX-O-05); Pakistani city names used as synthetic probe labels; and the public host names of the Claude and OpenAI APIs in the SL-F-4 definition. | SL-DR-01 |
| DD-R-02 | No public Jazz fact (such as a USSD short code or product name) is used; services are named by function (owner decision SB-D-06). The list SL-DR-02 asks for is therefore empty. | SL-DR-02 |
| DD-R-03 | The YAML, CSV and Markdown files hold the content of record. Round 5 chooses the seed bundle serialisation and the service map file format (SL-O-11). Round 5 MAY convert these files to that format and MUST NOT change their content. | SL-O-11; DM-R-54 |
| DD-R-04 | Past incident times are minutes before the seed import (`opened_minutes_before_import`, at most 43,200, so every record falls in the last 30 days), and acknowledgement and recovery times are minutes after opening. The seed import MUST convert them to timestamps at import time (DD-O-01). | SL-A1-09; SL-O-10 |
| DD-R-05 | `<demo-domain>` stands for the demo domain (CTX-O-02). Values marked `R5` are fixed in Round 5 (DD-O-02). | CTX-O-02 |
| DD-R-06 | A change to any file MUST be followed by running `tools/check_data.py`, and every check MUST pass before the change is committed. Command, from the repository root: `python3 docs/31-demo-data/tools/check_data.py docs/31-demo-data` (Python 3 with PyYAML). A change to DD-F-09 MUST be made in `tools/gen_past.py` and the file regenerated, never by hand. | CTX-Q-2 |
| DD-R-07 | File conventions (complete list): (1) `handle` values and the fields that hold them (`connector_instance`, `credential_ref`, `webhook_token_ref`) are bundle-internal names that link records within the seed bundle; the import replaces them with record identifiers. (2) A field or parameter name ending in `_s` is the domain-model field or parameter without the suffix, a Duration in seconds. (3) Every record is seeded with `active = true`. (4) `R5` marks a value fixed in Round 5. (5) In DD-F-09, list fields (`alert_types`, `affected_service_keys`) separate keys with `;`. | DM-C-01, DM-C-06 |
| DD-R-08 | The alert type set has 18 types: the 7 storm types of SL-A1-01, the SL-F-3 type, and 10 types used only by past incidents (DD-F-03). This extends the demo set named in DM-E-16 (amendment SB-A-01). | DM-E-16, SL-DR-04 |
| DD-R-09 | Runbook file convention (DD-F-08): each `## ` heading starts one runbook; its fields come from the table under the heading; "(any)" means the empty set; `text` is everything after the table up to the next `## ` heading, kept as Markdown and trimmed. | DM-E-63 |
| DD-R-10 | Placeholder grammar. In a command template, `{name}` is a placeholder only when `name` is a declared parameter of the operation; every other brace is literal (Example: the braces of the awk programs). In a diagnostic `summary_template`, a placeholder is `{` + an output value name of the operation's parser + `}`, where the name MAY contain `/` and `.` but not `}`. | DM-E-12, DM-E-53 |
| DD-R-11 | The seed bundle built from this folder has `bundle_name` "jazz-demo" and `bundle_version` equal to this README's version. | DM-E-82 |

## 5. Checks (complete list)

`tools/check_data.py` runs these checks. Results for this version: all 47 checks pass.

| ID | Check | Result |
| --- | --- | --- |
| DD-K-01 | Each lab catalogue row's count equals its number of dimension combinations. The SL-F-1 storm produces between 100 and 200 alerts, covers all seven SL-A1-01 alert types and has at least one alert from each source. SL-F-3 produces 1 alert. With SL-F-2, every storm alert except the disk_full alerts keeps firing. | 126 storm alerts: 31 from Zabbix, 95 from Alertmanager; 124 with SL-F-2 |
| DD-K-02 | Every lab alert matches its intended component (DM-R-05) and is classified by the alert type mappings (DM-E17-R1), never `unclassified`. | Pass |
| DD-K-03 | The service map passes the import rules of DM-R-55 (unique keys, existing references, `runs_on` and `target_key` rules, kind, criticality and source-kind values, DM-E27-R1), is acyclic counting `runs_on` (DM-E28-R1, R2), and has the counts 4 services, 12 components, 15 identity keys, 6 dependencies. | Pass |
| DD-K-04 | The storm components form one connected group under the relation DM-R-07; the grouping window (300 s) is at least the storm length (120 s); the SL-F-3 component `report-export` is related to no storm component (DM-R-14). | Pass |
| DD-K-05 | The storm hypothesis is `srv-db-01` (DM-R-18). Routing gives the storm incident P1, owner `db-ops`, informed `vas-service-owners`; the SL-F-3 incident P4, owner `it-apps`, no informed teams; after the SL-A2-06 change the storm result is P1, `db-ops`, informed `customer-care` and `vas-service-owners`. Escalation levels have no gaps. | Pass |
| DD-K-06 | Storm customer impact is Balance Check (USSD), Bundle Purchase and SMS Notifications. Titles (DM-R-20): "Subscriber DB server: Disk full — 3 services affected" (storm) and "Report export job: Report export job failed — 1 service affected" (SL-F-3). | Pass |
| DD-K-07 | Workflow version 1, and version 2 built by applying the draft changes, pass the SL-WE-08 rules (one Trigger, reachable, no unconnected step, Condition branches connected, Action guarded, no cycle) and the parameter bounds of DM Section 9. Version 1 meets PM-WV-02 to PM-WV-04, PM-WV-04 on both branches (PM-WV-01 is met by the engine, DM-R-16). Also checked: Condition comparisons use fields and operators of DM-R-25 and DM-R-26 with valid values, branch labels are unique and not `otherwise`, Notification recipients are valid kinds, step labels are at most 60 characters. A cycle is reported only when it has no Wait/timer step (the workflows have no cycle). Escalation timer 600 s in version 1 and 120 s in version 2; version 2 has 2 Approval steps. | Pass |
| DD-K-08 | The runbooks given to SL-T-1 for the storm (DM-R-50, limit 3) are RB-API-01, RB-DB-01 and RB-USSD-01. | Pass |
| DD-K-09 | The storm search text (DM-R-51) equals the search text of exactly one past incident, PI-1001, which links `purge-archived-txlogs` with success count 4; it has the alphabetically first key, so it also wins any tie on the rounded score (DM-E66-R1). Exactly 5 past incidents use that action (PI-1001, PI-1011, PI-1012, PI-1021, PI-1039), each with success count 4 (a success count belongs to the fix: the purge worked in 4 of its 5 uses; for a record without a registered action the count is the number of records of the same pattern, and the demo never shows it). No past incident uses `report_job_failed`, `svc-mgmt-reports`, `report-export` or `srv-rpt-01`. | Pass |
| DD-K-10 | Every cross-module key reference exists (DM-R-60); routing conditions use routing fields only and the default rule is the last and only rule without conditions (DM-E41-R1); credentials match their purpose (webhook tokens, target grants, connector credentials) and the two grants use different credentials (DM-E13-R1); allowed providers equal DM-E71-R1; task assignments use allowed kinds; SL-T-3 has exactly one provider, equal to the knowledge embedding provider (DM-E72-R2); every notifiable team and both approvers have delivery addresses; exactly 50 past incidents with unique keys and alert counts of at least 1. | Pass |
| DD-K-11 | Dashboard values over the seeded history alone (DM-R-53): time to acknowledge 5.7 min, time to recover 41.1 min, 575 alerts, 525 alerts suppressed, 46 incidents merged. Every past incident opened within the 30 days before the seed import (DM-R-53 applies no time filter: the dashboard counts all 50). | Pass |
| DD-K-12 | Readiness gaps in the starting state are exactly `srv-rpt-01` (server with no owner) and `svc-mgmt-reports` (service with no criticality). (`source_not_healthy` depends on the running system: none in the starting state.) | Pass |
| DD-K-13 | Eligible approvers for the DB Ops Approval step are exactly `dbops-engineer` and `dbops-lead`; `noc-l1` can acknowledge the storm incident. | Pass |
| DD-K-14 | Timing values are mutually consistent, read from DD-F-03, DD-F-04, DD-F-06, DD-F-07 and the `timing_targets.numbers` of DD-F-10 (complete list): health check interval < stale < disconnected; maximum storm gap < settle period < maximum forming time; storm arrival time + settle period ≤ maximum forming time; storm arrival time ≤ grouping window; resolve-after-action time + observation window < the P1 Recovery check maximum wait; resolve-after-clear time + clear settle time ≤ 60 s; no-refire time ≥ reopen window; stability check ≤ 5 s; closure check ≤ 60 s; observation windows < maximum waits; approval expiry ≥ 600 s; minimum similarity between 0 and 1. The injected archive (device size − baseline − 0.25 GiB allowance for XFS overhead and free space) is larger than the largest baseline folder + 1 GiB and at most the 12 GiB action limit. Every LA-02 schema is a database schema with a named writer in the mock behaviour. | Pass |
| DD-K-15 | The demo users are exactly those of SL-A3-07; there are 9 teams. | Pass |

Checks that need running software are listed in `30-jazz-storyboard` Section 11 (SB-T-01 to SB-T-10).

## 6. Assumptions

| ID | Assumption | Verified by |
| --- | --- | --- |
| DD-ASM-01 | The Zabbix connector can read the event tag `component`, the host name and the event name from the webhook message of DD-F-10; the Alertmanager connector can read the labels `component`, `instance` and `alertname`. The Zabbix message content is defined by our own media type script (DD-F-10), so this depends only on the connector reading it. Input to Round 5 (DM-O-04): the identity key names and precedence used here are Zabbix [`component`, `host`] and Alertmanager [`component`, `instance`]; if Round 5 chooses other names, DD-F-02 and DD-F-03 are updated with them. | Round 5 connector design; SB-T-01 |
| DD-ASM-02 | The lab DB server runs Linux with bash, GNU coreutils, GNU findutils and an awk that supports `-v` and `printf "%.0f"`: `df --output`, `du -x -B1 --max-depth`, `find -cmin` and `find -printf` behave as documented, and setting a file's modification time back leaves its change time at the time of the change. The command templates were run on test files on 10 October 2026 and behaved as specified. | Lab build; SB-T-01 |
| DD-ASM-03 | The lab monitoring settings of DD-F-10 meet the timing targets there (arrival within 120 s with no gap above 25 s and no repeated firing entry; resolves within 60 s after the action and within 40 s after clearing; no re-firing within 300 s). The 40-second settle period depends on this. | Lab spike SB-T-01 |
| DD-ASM-04 | With the local embedding model chosen in Round 5, the storm incident's best match is PI-1001 with score 1.00, because its search text is identical; a tie on the rounded score also goes to PI-1001 (first key, DM-E66-R1). | Build spike SB-T-03 |
| DD-ASM-05 | An XFS filesystem on a 16 GiB device holds the 7 GiB baseline at 43 to 45% used, and the SL-F-1 injection (about 8.8 GiB) is larger than `/data/db` (6 GiB) plus 1 GiB and smaller than the 12 GiB action limit. Exact percentages, file counts and sizes are measured by SB-T-01 and recorded in DD-F-10. | SB-T-01 |

## 7. Explicitly out of scope

The serialisation of the seed bundle and the service map file format (Round 5). The lab implementation (Round 6). Screen content (Round 4). Real connector addresses and secrets (Round 5 and deployment).

## 8. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| DD-O-01 | Seed bundle support for the relative times of DD-R-04 (the import interface of `vigil-knowledge` converts offsets to timestamps). | Claude | R5 |
| DD-O-02 | Connector addresses, settings, credential locators, model names and prices marked `R5` in DD-F-07 (with DM-O-12, DM-O-13, PM-O-11). | Claude | R5 |
| DD-O-03 | Measured lab values in DD-F-10 (baseline percentage, injected file count and size, diagnostic values, action result). | Abdul | Lab spike SB-T-01, before the first rehearsal |
| DD-O-04 | Form of the Teams delivery address (PM-O-10). The fact check found that a Teams channel is now reached through a Workflows webhook address per channel, and that this address works as a secret; Round 5 decides how a delivery address refers to it (Example: a credential reference per address) and records any domain model change. | Claude | R5 |

## 9. Quality-check results

See `30-jazz-storyboard` Section 17 (one review covers both outputs of Round 3).

## 10. Change history

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 10 Oct 2026 | First draft (Round 3, restarted). |
| 0.2 | 10 Oct 2026 | Resolved independent review and readiness test findings (`30-jazz-storyboard` Section 17): lab DB sizes, schema writers, SL-F-3 keeps failing, last injected file back-dated, SL-F-4 mechanism, reset and clear timing, command robustness, best-match key, PM-WV-04 on both branches, stronger checks. |
| 0.3 | 10 Oct 2026 | Resolved verification pass 2: clear settle 12 s, SL-F-3 wording, DD-ASM-03 40 s, parser and comment fixes. |
| 1.0 | 10 Oct 2026 | Owner review recorded (CTX-D-018); Round 3 closed. |
