# Vigil — Invented Jazz Demo Data Set

| Field | Value |
| --- | --- |
| Document | 31-demo-data (ID prefix `DD-`) |
| Version | 1.1 |
| Date | 9 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed). Round 3 closed. |

## 1. Purpose

This folder is the complete invented data set required by SL-DR-04. It contains the seed bundle content that brings a tenant to the starting state (`01-scope-lock` Section 3; DM-R-56), the lab alert catalogue and the fault values used by the demo-only components. It also contains the checks that prove the data produces the outcomes the storyboard needs. The storyboard (`30-jazz-storyboard.md`) explains how the data is used on screen.

## 2. Inputs read

`00-context` v1.6, `01-scope-lock` v1.6, `10-product-map` v1.1, `20-domain-model` v1.0. At the Round 3 close they became `00-context` v1.7, `01-scope-lock` v1.7, `10-product-map` v1.2 and `20-domain-model` v1.1 (`30-jazz-storyboard` Section 14.2).

## 3. Files (complete list)

| ID | File | Content | Seed parts (DM-R-56) |
| --- | --- | --- | --- |
| DD-F-00 | `README.md` | This file | — |
| DD-F-01 | `01-tenant-identity.yaml` | Tenant settings, 9 teams, users with display names, memberships, permission grants | 1, 2 |
| DD-F-02 | `02-service-map.yaml` | Service map generation 1: 6 services, 18 components, identity keys, dependencies | 5 |
| DD-F-03 | `03-monitoring.yaml` | Monitoring sources and health values, alert types, alert type mappings, lab alert catalogue (demo-only) | 6 |
| DD-F-04 | `04-operations-actions-recovery.yaml` | Runner operations and grants, diagnostic definitions, registered action, action policy, recovery check definitions | 4, 12, 13, 14 |
| DD-F-05 | `05-routing-escalation.yaml` | Routing rule set revision 1, the SL-A2-06 rule change, escalation policy revision 1 | 11 |
| DD-F-06 | `06-workflow.yaml` | Workflow version 1 and the SL-A2-06 draft changes | 16 |
| DD-F-07 | `07-settings.yaml` | Credential references, connector instances, AI providers, allowed providers, task assignments, data routing, knowledge, correlation and incident settings, delivery addresses, baselines | 3, 7, 8, 9, 10, 15, 17 |
| DD-F-08 | `08-runbooks.md` | 6 runbooks | 8 |
| DD-F-09 | `09-past-incidents.csv` | Exactly 50 past incidents (SL-DR-04), all labelled "Fictional record" | 8 |
| DD-F-10 | `10-lab-and-faults.yaml` | Lab hosts, lab DB server facts, fault mechanics SL-F-1 to SL-F-4, monitoring configuration, timing targets, control panel rules (demo-only, not seeded) | — |
| DD-F-11 | `tools/gen_past.py` | Deterministic generator of DD-F-09 (seeded random; re-running reproduces the file byte for byte) | — |
| DD-F-12 | `tools/check_data.py` | Runs checks DD-K-01 to DD-K-14 over this folder | — |

## 4. Data rules

| ID | Rule | Trace |
| --- | --- | --- |
| DD-R-01 | All names, hosts, addresses and figures are invented. The data contains no real Jazz system name, IP address, host name, employee name, customer data or internal figure. The only real names are the customer name "Jazz" (subject to CTX-O-05) and Pakistani city names used as synthetic probe locations. | SL-DR-01 |
| DD-R-02 | No public Jazz fact (such as a USSD short code or product name) is used, so the list SL-DR-02 asks for is empty (`30-jazz-storyboard` SB-Q-N2). Services are named by function, for example "Balance Check (USSD)". | SL-DR-02 |
| DD-R-03 | The YAML, CSV and Markdown files hold the content of record. Round 5 chooses the seed bundle serialisation and the service map file format (SL-O-11). Round 5 MAY convert these files to that format, and MUST NOT change their content. | SL-O-11; DM-R-54 |
| DD-R-04 | Past incident times are given as minutes before the seed import (`opened_minutes_before_import`), and the acknowledgement and recovery times as minutes after opening. The seed import MUST convert them to timestamps at import time, so the seeded history is always the last 30 days of the demo run (DD-O-01). | SL-A1-09; SL-O-10 |
| DD-R-05 | `<demo-domain>` stands for the demo domain (CTX-O-02). Values marked `R5` in DD-F-07 are fixed in Round 5. | CTX-O-02 |
| DD-R-06 | A change to any file MUST be followed by running `tools/check_data.py` (exit status 0 only when every check passes), and every check MUST pass before the change is committed. Command, from the repository root: `python3 docs/31-demo-data/tools/check_data.py docs/31-demo-data`. It needs Python 3 with PyYAML. | CTX-Q-2 |
| DD-R-07 | File conventions (complete list): (1) `handle` fields (credential references, connector instances) and `connector_instance`, `credential_ref` and `webhook_token_ref` values are bundle-internal names that link records within the seed bundle; the import replaces them with record identifiers. (2) A field name ending in `_s` is the domain-model field without the suffix, a Duration in seconds. (3) Every record is seeded with `active = true`. (4) `R5` marks a value fixed in Round 5. | DM-C-01, DM-C-06 |
| DD-R-08 | The alert type set has 18 types: the 7 storm types, the SL-F-3 type, and 10 types used only by past incidents (DD-F-03). DM-E-16 now points to this set (amended by SB-A-01; SB-D-11). | DM-E-16, SL-DR-04 |
| DD-R-09 | Runbook file convention (DD-F-08): each `## ` heading starts one runbook; its fields come from the table under the heading; "(any)" means the empty set; `text` is everything after the table up to the next `## ` heading, kept as Markdown and trimmed. | DM-E-63 |
| DD-R-10 | Placeholder grammar. In a command template, `{name}` is a placeholder only when `name` is a declared parameter of the operation; every other brace is literal (Example: the braces of the awk program in `purge_old_txlogs`). In a summary template (diagnostic `summary_template`, registered action `result_summary_template`), a placeholder is `{` + an output value name of the parser + `}`, where the name MAY contain `/` and `.` but not `}`. | DM-E-12, DM-E-53 |
| DD-R-11 | The seed bundle built from this folder has `bundle_name` "jazz-demo" and `bundle_version` equal to this README's version. | DM-E-82 |

## 5. Checks (complete list)

`tools/check_data.py` runs these checks. Results for this version: all pass.

| ID | Check | Result |
| --- | --- | --- |
| DD-K-01 | The lab storm produces between 100 and 200 alerts. It covers all seven SL-A1-01 alert types and includes at least one alert from each source. | 138 alerts: 30 from Zabbix, 108 from Alertmanager |
| DD-K-02 | Every lab alert matches its intended component (DM-R-05). | Pass |
| DD-K-02b | Every lab alert's trigger name or alertname is classified by the alert type mappings (DM-E17-R1) as its intended type. | Pass |
| DD-K-03 | The service map passes the import rules of DM-R-55 (unique keys, existing references including identity-key components, `runs_on` and `target_key` rules, valid kind, criticality and source-kind values, no duplicate dependency), is acyclic counting the `runs_on` dependencies (DM-E28-R1, R2), and has the counts shown in SB-D02 (6 services, 18 components, 12 identity keys, 12 dependencies). | Pass |
| DD-K-04 | All storm components form one group under the relation DM-R-07. `reporting-app` (SL-F-3) is related to none of them (DM-R-14). | Pass |
| DD-K-05 | The hypothesis is `db-core-01` (DM-R-18). Routing gives the storm incident P1, owner `db-ops`, informed `vas-service-owners`, and gives the SL-F-3 incident P4, owner `it-infra`. | Pass |
| DD-K-06 | The storm customer impact is Balance Check (USSD), Bundle Purchase and SMS Notifications, and the titles computed by DM-R-20 are exactly "Subscriber DB server: Disk full — 3 services affected" (storm) and "Reporting application: Report export job failed — 1 service affected" (SL-F-3). | Pass |
| DD-K-07 | Version 2 is built by applying the structured `draft_changes_for_version_2` of DD-F-06 to version 1 (each change checked against version 1; every step output has at most one connection). Workflow version 1 and version 2 pass the SL-WE-08 rules checked by the script: one Trigger, every step reachable, no unconnected step, every Condition branch connected, Action guarded before and after, no cycle (so `cycle_has_wait` holds), and the parameter bounds of DM Section 9 for Approval, Recovery check, Escalation, AI task and Ticket steps. | Pass |
| DD-K-08 | The runbooks given to SL-T-1 for the storm are RB-DB-001, RB-API-003 and RB-SMS-004 (DM-R-50). | Pass |
| DD-K-09 | The storm search text (DM-R-51) equals the search text of exactly one past incident, PI-0342. No past incident uses `reporting-app` or `batch_job_failed`. PI-0342 links the action `purge-archived-txlogs`, with success count 4. Exactly 4 past incidents use that action (PI-0303, PI-0306, PI-0327, PI-0342), and each carries success count 4. | Pass |
| DD-K-10 | Every cross-module key reference exists (DM-R-60); every bundle-internal handle resolves (DD-R-07); credential purposes match their use (webhook token, diagnostic and action grants with two different credentials, DM-E13-R1); every task assignment uses allowed provider kinds and SL-T-3 has exactly one provider (DM-E-71, DM-E72-R2); action policies and alert type mappings refer to existing actions, services and alert types; there are exactly 50 past incidents. | Pass |
| DD-K-11 | Dashboard values over the seeded history alone (DM-R-53) are exactly: time to acknowledge 4.3 min, time to recover 28.2 min, 684 alerts, 634 alerts suppressed, 46 incidents merged. | Pass |
| DD-K-12 | The readiness gaps in the starting state are exactly `bkp-srv-01` (server with no owner) and `svc-reporting` (service with no criticality). | Pass |
| DD-K-13 | The eligible approvers for the DB Ops Approval step are exactly `dbops-engineer` and `dbops-lead`. | Pass |
| DD-K-14 | Timing values are mutually consistent (complete list):<br>- health check interval < stale < disconnected;<br>- maximum storm gap (30 s, DD-F-10) < settle period < maximum forming time;<br>- observation window < the Recovery check maximum wait;<br>- escalation timer of version 1 is 600 s;<br>- stability check at most 5 s and closure check at most 60 s. | Pass |

Checks that need running software are listed in `30-jazz-storyboard` Section 11 (SB-T-01 to SB-T-09).

## 6. Assumptions

| ID | Assumption | Verified by |
| --- | --- | --- |
| DD-ASM-01 | The Zabbix connector can expose the host name, a tag value (`tag.component`) and the trigger name of each alert. The Alertmanager connector can expose the labels `component`, `instance` and `alertname`. The names used here are working names for those fields. Round 5 MUST keep the identity key order Zabbix [`tag.component`, `host`] and Alertmanager [`label.component`, `label.instance`] (SB-V-26). | Round 5 connector fact check (DM-O-04) |
| DD-ASM-02 | The lab hosts run Linux with GNU coreutils and findutils. `sar -F MOUNT` from sysstat prints per-mount-point filesystem usage, including `%ufsused`. sysstat can record filesystem statistics (`-S XDISK`) every 10 s on the lab DB server. | Round 5 fact check; lab build |
| DD-ASM-03 | The lab monitoring can meet the timing targets in DD-F-10 (arrival within 120 s with no gap above 30 s; no repeated firing entry; resolves within 60 s after the action and within 40 s after clearing faults; no re-firing within 300 s). The 45-second settle period depends on this. | Early lab spike (with SL-ASM-01, DM-ASM-05) |
| DD-ASM-04 | With the local embedding model chosen in Round 5:<br>- the storm incident's best match is PI-0342 with score 1.00, because its search text is identical;<br>- the SL-F-3 incident's best score is below 0.95;<br>- no other past incident (PI-0303, PI-0306, PI-0327 share part of the pattern) also rounds to 1.00, which would win the alphabetical tie-break (DM-E66-R1). | Build spike SB-T-03; if it fails, Round 5 changes `min_similarity` by a decision |
| DD-ASM-05 | Alertmanager's `group_by: ['...']` makes every alert its own notification group, so no firing alert is re-sent while other alerts join. Zabbix can send exactly one webhook per problem and one per recovery. | Round 5 fact check; SB-T-01 |
| DD-ASM-06 | An XFS filesystem on a 20 GiB device leaves enough usable capacity for the 11 GiB baseline to sit between 55% and 57% used. The exact percentages, file counts and sizes are measured by SB-T-01 and recorded in DD-F-10. | SB-T-01 |

## 7. Explicitly out of scope

The serialisation of the seed bundle (Round 5). The lab implementation (Round 6). Screen content (Round 4). Real connector addresses and secrets (Round 5 and deployment).

## 8. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| DD-O-01 | Seed bundle support for the relative times of DD-R-04 (the import interface of `vigil-knowledge` converts offsets to timestamps). | Claude | R5 |
| DD-O-02 | Connector addresses, settings, credential locators, model names and prices marked `R5` in DD-F-07 (with DM-O-12, DM-O-13, PM-O-11). | Claude | R5 |
| DD-O-03 | Measured lab values in DD-F-10 (baseline percentage, injected file count and size, diagnostic values). | Abdul | Lab spike SB-T-01, before the first rehearsal |

## 9. Quality-check results

Checks DD-K-01 to DD-K-14 pass (Section 5). The independent review and the readiness test of this folder are recorded with the storyboard (`30-jazz-storyboard` Section 16).

## 10. Change history

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 9 Oct 2026 | First version (Round 3). |
| 0.2 | 9 Oct 2026 | Resolved independent review findings: lab disk values measured by spike, purge output, growth window, Alertmanager grouping, timers, parsers, permissions, missing fields, approver channels, Customer Care team, wording. |
| 0.3 | 9 Oct 2026 | Resolved review pass 2 and readiness test findings: txlog ages refreshed by every storm, fixed 128 MiB free, only catalogue alerts sent, alert rule windows, label and annotation names, SL-F-4 by name override, UTC clock and storm window, control panel rules, conventions DD-R-09 to DD-R-11, stronger checks. |
| 0.4 | 9 Oct 2026 | Readiness test pass 2: generic USSD menu names without other services, SL-F-4 clear undoes every step, 15 s scrape interval, 15 s margin after clearing faults. Draft for owner review. |
| 1.0 | 9 Oct 2026 | Owner review recorded (CTX-D-017); final consistency audit fixes: stronger DD-K-03, DD-K-06, DD-K-07, DD-K-10 (verified by mutation tests), structured SL-A2-06 draft changes, 10 s sysstat interval, identity key order required of Round 5, notified-team channels; Round 3 closed. |
| 1.1 | 9 Oct 2026 | `result_summary_template` added to the registered action in DD-F-04; DD-R-10 covers it (`40-demo-cut` DC-A-02; CTX-D-020). Checks DD-K-01 to DD-K-14 pass. |
