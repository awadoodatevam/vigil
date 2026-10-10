# Vigil — Jazz Storyboard

| Field | Value |
| --- | --- |
| Document | 30-jazz-storyboard (ID prefix `SB-`) |
| Version | 1.1 |
| Date | 9 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed). Round 3 closed. Amended by Round 4 (`40-demo-cut` DC-A-07). |

## 1. Purpose

This document is the scene-by-scene script of the demo: the 15-minute primary demo (SL-A1) and the deep dives (SL-A2). Each scene has a time budget, a Real / Simplified / Staged tag, who does what, and the exact values the audience sees. The document also defines the faults, the demo order and the reset rules. It fixes every demo value assigned to Round 3. The invented Jazz data set itself is in `docs/31-demo-data/` (prefix `DD-`).

## 2. Inputs read

- `docs/00-context.md` v1.6.
- `docs/01-scope-lock.md` v1.6.
- `docs/10-product-map.md` v1.1.
- `docs/20-domain-model.md` v1.0.
- `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md` (demo story baseline).
- Owner answers given in the Round 3 conversation on 9 October 2026, which resolve CTX-O-03 (SB-D-08).

Public information about Jazz could not be checked in this round, because web access was unavailable. No public Jazz fact is used (SB-D-06).

## 3. Terms (complete list)

Terms defined in earlier documents keep their meaning. The terms below are added.

| Term | Meaning |
| --- | --- |
| Scene | One numbered part of the script, `SB-Snn` (primary demo) or `SB-Dnn` (deep dive). |
| Wall clock | Minutes and seconds since the start of the presentation, `mm:ss`. |
| T+ | Seconds since the storm fault (SL-F-1) was injected in the current run. |
| Narrator | The Techfinity senior manager, who tells the story (SB-D-08). |
| Operator | Abdul Wadood, who drives every screen (SB-D-08). |
| Phone window | A browser window 360 px wide and 780 px high, placed on the shared screen, used for the Acknowledge and Approval pages (SB-D-08). |
| Storm incident | The incident created from the SL-F-1 alerts. In the screen text it is identified by its title, because its display number depends on which incident leaves `forming` first (DM-E-32). |
| Varies | A value that is real output and is not fixed in advance (Example: AI wording). Each such value has acceptance criteria instead of an exact value (CTX-W-07). |

## 4. Demo setup (resolves CTX-O-03)

| ID | Item | Value | Trace |
| --- | --- | --- | --- |
| SB-SET-01 | Meeting tool | Microsoft Teams meeting; the operator shares one screen. | CTX-O-03; SB-D-08 |
| SB-SET-02 | Roles | The narrator talks. The operator drives every screen and says only the operator lines marked in the script. | CTX-O-03 |
| SB-SET-03 | Shared screen layout (complete list of windows) | (1) Vigil in a browser profile "Presenter", logged in as `presenter` (left, 2/3 of the width). (2) The phone window (right), in a separate browser profile "Phone", so its logins never affect window 1. (3) Teams desktop client showing the "Vigil Demo" team. (4) A browser tab with the Jira project board. (5) A web mail client showing the `noc-l1` mailbox. (6) The demo control panel (SL-A4-12) in its own browser window, shown only when a fault is injected. Windows 3 to 6 are brought to the front only when the script says so. | SL-A4-04, SL-A4-05, SL-A3-03 |
| SB-SET-04 | Phone window logins and links | Links in Teams messages and emails are copied (right-click, copy link) and pasted into the phone window; they are never opened by clicking, because a click opens the default browser. The operator logs in on the phone window as the user the scene names and logs out at the end of the scene. | SL-B-13 |
| SB-SET-05 | Attendees | Attendees do not join any demo Teams channel. Every message is shown on the shared screen (decides SL-X-16). | SL-X-16; CTX-O-03 |
| SB-SET-06 | Teams and email destinations | One demo Team, "Vigil Demo", with one channel per team that has a delivery address (complete list: NOC L1, Database Operations, VAS Service Owners, NOC Management, IT Operations Head, IT Infrastructure, Customer Care), plus one approvals channel per approver ("Approvals — Bilal", "Approvals — Sana"). One mailbox per notified team and per approver (DD-F-07). The operator shows the "NOC L1" channel, the approvals channels and the `noc-l1` mailbox. | SL-A1-05, SL-A1-06; PM-O-10 |
| SB-SET-07 | Before each presentation | Reset (SL-A3-03), then open all windows of SB-SET-03 and log in as `presenter`. Check that the incident list is empty and the dashboard shows the seeded values (SB-V-40). The storm window rule SB-R-07 applies to every storm of the presentation. | SL-A3-03, SL-A3-04 |
| SB-SET-08 | Backup | If the live session fails, the operator plays the backup video (SL-A3-08) from the scene where the failure happened. | SL-A3-08 |

## 5. Cast: users, teams and display names (resolves SL-O-18)

| User | Display name | Team | Used in |
| --- | --- | --- | --- |
| `presenter` | Demo Presenter | — | Main Vigil window, SB-S00 to SB-S10 |
| `noc-l1` | Ayesha Siddiqui (NOC L1) | NOC L1 | SB-S05 (acknowledges), SB-D03, SB-D07 |
| `dbops-engineer` | Bilal Qureshi (DB Ops) | Database Operations | SB-S06 (approves), SB-D03, SB-D07 |
| `dbops-lead` | Sana Rauf (DB Ops Lead) | Database Operations | SB-D03 (second approver) |
| `admin` | Vigil Administrator | — | Main Vigil window from SB-D02 on (all deep dives) |

The names are invented (SL-DR-01). The teams are in DD-F-01. Teams without members (complete list: VAS Operations, IT Applications, IT Infrastructure, VAS Service Owners, NOC Management, IT Operations Head, Customer Care) are configuration data: component and service owners, informed parties and escalation levels. Those with a delivery address in DD-F-07 can receive messages in their channels; in the demo, IT Infrastructure receives none (its SL-F-3 incident sends no message), and VAS Operations and IT Applications have no address. No user acknowledges as one of these teams.

## 6. Faults (resolves SL-O-02 and SL-O-16)

The mechanics and exact sizes are in DD-F-10. The demo control panel triggers each fault (SL-A3-03).

| ID | Fault | What the audience is told | Effect in the lab |
| --- | --- | --- | --- |
| SL-F-1 | Subscriber DB `/data` full | "The nightly purge of archived transaction logs stalled, and the subscriber database's data disk has just filled up." | Transaction log files older than 24 hours are added to `/data/txlog_archive` until `/data` is full (100%, DD-F-10). Database writes fail. The balance-check and bundle-purchase APIs return errors, USSD sessions time out, the SMS queues grow and synthetic USSD sessions fail. 138 alerts (DD-F-03). |
| SL-F-2 (SL-O-02) | Subscriber DB stays read-only | "This time the database also switched itself to read-only." | Database writes keep failing after disk space is freed, so every storm alert except `disk_full` keeps firing. |
| SL-F-3 | Unrelated report export failure | Not announced; it is pointed out in SB-S02. | One Zabbix alert, "Report export job failed", on the reporting server. Injected together with SL-F-1 by the same control-panel button. |
| SL-F-4 (SL-O-16) | Cloud AI unreachable | "We now cut this server off from the cloud AI providers." | Connections from the Vigil server to the Claude API and OpenAI API host names are refused (DD-F-10). Nothing else is blocked (PM-ASM-04). |

The control panel has these fault buttons (complete list):

- "Storm: SL-F-1 + SL-F-3";
- "Storm re-run: SL-F-1 only";
- "Storm with read-only DB: SL-F-1 + SL-F-2";
- "Cut cloud AI: SL-F-4";
- "Clear all faults";
- "Reset".

"Clear all faults" removes every active fault. It completes when every lab alert has been resolved at its source (DM-R-24 d).

## 7. Primary demo script, 15 minutes or less (resolves SL-O-03)

### 7.1 Scene plan (complete list)

| ID | Scope lock scene | Wall clock | Budget | Tag | Tag reason |
| --- | --- | --- | --- | --- | --- |
| SB-S00 | Opening | 00:00–01:00 | 1:00 | — | No product behaviour |
| SB-S01 | SL-A1-01 Alert storm | 01:00–02:45 | 1:45 | Real | Real faults in the lab, real Zabbix and Alertmanager, webhooks |
| SB-S02 | SL-A1-02 One incident, with reasons | 02:45–04:15 | 1:30 | Real | Grouping, hypothesis and impact computed by Vigil |
| SB-S03 | SL-A1-03 Diagnostics | 04:15–05:10 | 0:55 | Real | Read-only commands run on the lab DB server |
| SB-S04 | SL-A1-04 AI investigation and routing | 05:10–06:15 | 1:05 | Real | Live AI call; routing rules |
| SB-S05 | SL-A1-05 Ticket and notification | 06:15–08:15 | 2:00 | Real | Real Jira, Teams and email; real login |
| SB-S06 | SL-A1-06 Approved fix | 08:15–10:00 | 1:45 | Real | Real approval, rechecks and command on the lab DB server |
| SB-S07 | SL-A1-07 Recovery check | 10:00–12:15 | 2:15 | Real | Real resolve notifications and observation window |
| SB-S08 | SL-A1-08 Workflow glimpse | 12:15–12:45 | 0:30 | Real | Live highlight of the run (also visible from SB-S02 on) |
| SB-S09 | SL-A1-09 Dashboard | 12:45–13:45 | 1:00 | Simplified | Real computation; the history is the 50 seeded fictional incidents and the baselines are illustrative (DD-F-09, DD-F-07) |
| SB-S10 | Close | 13:45–14:15 | 0:30 | — | No product behaviour |

The total is 14:15, within the 15-minute limit; the 45 s left is margin. The storm is injected at wall clock 01:00 (T+0). SB-S01 to SB-S07 are Real (`01-scope-lock` Section 3).

### 7.2 Timeline the script depends on

| ID | Event | Expected time | Basis |
| --- | --- | --- | --- |
| SB-TL-01 | Last storm alert received | At most T+120 s (wall clock 03:00) | SL-A1-01; DD-F-10 timing targets |
| SB-TL-02 | Storm incident leaves `forming` | At most 50 s after the last storm alert (45 s settle period plus the 5 s stability check interval), so at most T+170 s (03:50); and at least 45 s after the incident was created, so not before T+45 s (01:45) | DD-F-07; DM-R-15 |
| SB-TL-03 | Diagnostics finished | Target: at most 15 s after SB-TL-02 (04:05); tested by SB-T-04 | 3 commands |
| SB-TL-04 | AI investigation and summary finished; ticket created; messages sent | Target: at most 60 s after SB-TL-03 (05:05), with Claude as the SL-T-1 and SL-T-2 provider; tested by SB-T-04 | SL-O-20 proposal |
| SB-TL-05 | The Escalation step starts waiting | Right after SB-TL-04: not before 01:45 (SB-TL-02) and at most 05:10. With the 600 s timer, no escalation can happen before 11:45 | DD-F-06 |
| SB-TL-06 | Acknowledgement | Between wall clock 07:30 and 08:15 (operator rule in SB-S05): at least 3 minutes 30 s before the timer could end, and at least 140 s after the latest possible wait start (used in SB-D03-4) | Script |
| SB-TL-07 | Approval request sent | At most 5 s after the acknowledgement (the similar-incident search is local) | DD-F-06 |
| SB-TL-08 | Action executed | Approve at the latest at 09:45; the action runs at most 10 s later (09:55) | DD-F-04 |
| SB-TL-09 | All storm alerts resolved at source | At most 60 s after the action (10:55) | DD-F-10 timing targets |
| SB-TL-10 | Incident resolved | Observation window of 60 s after SB-TL-09 plus at most 5 s (12:00); the 300 s maximum wait is never reached | DD-F-04, DD-F-06 |

### 7.3 Scenes

#### SB-S00 Opening (00:00–01:00)

| Part | Content |
| --- | --- |
| Screen | Vigil incident list, empty. The persistent banner reads "Demo environment — fictional data". The customer name is Jazz. |
| Narrator | "This is Vigil, connected to a lab built like an IT/service NOC environment: USSD, balance and bundle APIs, SMS, and a subscriber database, monitored by Zabbix and Prometheus, two tools common in IT NOCs. Everything you see is live. All data is fictional." |
| Operator | Nothing. |

#### SB-S01 Alert storm (01:00–02:45) — SL-A1-01, Real

| Part | Content |
| --- | --- |
| Operator | Brings the control panel to the front and presses "Storm: SL-F-1 + SL-F-3" (T+0). Returns to Vigil's incident list. |
| Screen | Within 30 s of T+0 (both monitoring tools evaluate every 15 s), a row appears for an incident that is still forming. Its alert count rises. Source badges show both Zabbix and Alertmanager. A second forming row appears for the reporting server. |
| Screen note | Several storm rows MAY appear while forming, because alerts on components that are not yet linked start separate forming incidents; they merge as linking alerts arrive (DM-R-10). How forming incidents are shown is decided in Round 4 (DM-E32-R3). |
| Narrator | "The data disk of the subscriber database just filled up. Watch what a NOC normally sees: over a hundred alerts from two tools in two minutes." |

#### SB-S02 One incident, with reasons (02:45–04:15) — SL-A1-02, Real

| Part | Content |
| --- | --- |
| Operator | Until the storm incident is `open` (at most T+170 s, 03:50, SB-TL-02) the narrator talks over the forming incident; the operator then opens it. Scrolls the grouped alerts and opens two grouping reasons. Then points to the second incident in the list. |
| Exact values | One numbered storm incident with **138** alerts (allowed range 100 to 200, SL-A3-04): 30 from Zabbix and 108 from Alertmanager, covering the 7 alert types of SL-A1-01 (DD-K-01). Title: **"Subscriber DB server: Disk full — 3 services affected"**. Hypothesis, labelled "Hypothesis": **Subscriber DB server** (`db-core-01`). Customer impact (complete list): **Balance Check (USSD)**, **Bundle Purchase**, **SMS Notifications**. Severity **P1**, owner team **Database Operations**, informed **VAS Service Owners**. |
| Grouping reasons, acceptance criteria (complete list) | Every one of the 138 members has a final reason (DM-R-58) with relation `same_component`, `depends_on` or `depended_on_by` (never `only_alert`), a relation path that exists in the service map (DD-F-02, including `runs_on`), and a time difference of at most 300 s. Example of how one reads: "Balance-check API errors on balance-api-2 — balance-api depends on Subscriber database (balance-api → subscriber-db), 6 s apart." |
| Second incident | Title **"Reporting application: Report export job failed — 1 service affected"**. Severity **P4**, owner **IT Infrastructure**. It is never grouped with the storm and gets no action (SL-A1-02). Its handling (resolves SL-O-05, complete list): an AI summary (SL-T-2) is made for the ticket; a Jira ticket is created; no Teams or email message is sent; no diagnostics, no AI investigation (SL-T-1) and no action. |
| Narrator | "One incident, not 138. Vigil used the service map to explain why each alert belongs here, and why the report job failure does not. Its best explanation is the database server, labelled as a hypothesis, not a fact." |

#### SB-S03 Diagnostics (04:15–05:10) — SL-A1-03, Real

| Part | Content |
| --- | --- |
| Operator | Opens the Evidence section of the storm incident. |
| Exact values (evidence summaries, complete list) | "**/data at 100% used (`<used>` of `<size>` GiB)**"; "**Largest folder: /data/txlog_archive (`<size>` GiB)**"; "**/data grew `<n>` percentage points in the last 5 minutes**". Each shows its source, exactly "lab-db-01 via runner, operation fs_usage", "… operation largest_dirs" and "… operation fs_growth" (format `{target_key} via runner, operation {operation_key}`, DM-E-38), and its collection time. The `<…>` values are measured by the lab spike and recorded in DD-F-10 before the first rehearsal (SB-O-03); they are the same on every run. |
| Narrator | "Vigil went to the server itself with read-only access. These are facts it collected, with source and time." |

#### SB-S04 AI investigation and routing (05:10–06:15) — SL-A1-04, Real

| Part | Content |
| --- | --- |
| Operator | Opens the AI investigation section, then the Assignment section. |
| Varies: AI text, acceptance criteria (complete list) | The result's validation is `accepted`; `verdict` is `confirms`; `likely_cause` contains "/data" and, ignoring case, "log"; at least one referenced evidence entry is of kind `diagnostic_result`; `runbook_refs` contains RB-DB-001; `uncertainty` is not empty. Checked by PM-M17-03 and SB-T-02. |
| Exact values | Runbooks given to the AI: RB-DB-001, RB-API-003, RB-SMS-004 (DD-K-08). Assignment: **P1**, **Database Operations**, informed **VAS Service Owners**, by routing rule **"Critical service, infrastructure or database cause"** (`p1-critical-infra`). |
| Narrator | "The AI explains the cause with evidence and admits what it does not know. But the AI does not decide severity or ownership; your rules do." |

#### SB-S05 Ticket and notification (06:15–08:15) — SL-A1-05, Real

| Part | Content |
| --- | --- |
| Operator | (1) Shows the Jira board: the new ticket. (2) Shows the Teams channel "NOC L1": the message. (3) Shows the `noc-l1` mailbox: the email. (4) Copies the Acknowledge link from the Teams message (right-click, copy link), opens it in the phone window, logs in as `noc-l1` and presses Acknowledge, not before wall clock 07:30 and not after 08:15 (SB-TL-06); then logs out of the phone window. (5) Returns to Vigil. |
| Exact values | Jira title: **"Demo environment — fictional data - Subscriber DB server: Disk full — 3 services affected"**. The Jira ticket holds the complete SL-A1-05 content. The Teams message and the email start with "Demo environment — fictional data - " and contain the incident summary and the Acknowledge link. Messages also go to the channels "Database Operations" and "VAS Service Owners". After acknowledging: "**Acknowledged by Ayesha Siddiqui (NOC L1)**" with the time. |
| Varies | Summary text (SL-T-2). Acceptance criteria (complete list): not empty; at most 2,000 characters; source `ai`. If SL-T-2 is unavailable, the template summary of DM-E69-R1 is used (source `template`). |
| Narrator | "Ticket, Teams and email, at once. Acknowledging needs a real login; a link alone gives no authority. Everything is recorded with name and time." |

#### SB-S06 Approved fix (08:15–10:00) — SL-A1-06, Real

| Part | Content |
| --- | --- |
| Operator | (1) Shows the similar-incident result in the incident. (2) Shows the "Approvals — Bilal" Teams channel: the approval request. (3) Copies its link into the phone window, logs in as `dbops-engineer`, reads the page, presses Approve, logs out. (4) Returns to Vigil and opens the action result. |
| Exact values: best match | **PI-0342 "Subscriber DB /data full — archived transaction logs not purged"**, similarity **1.00**, fix success count **4** (resolves SL-O-08; DD-K-09). |
| Exact values: Approval page (complete list, SL-A1-06) | Action **"Purge archived transaction logs"**. Parameters: path **/data/txlog_archive**, older than **1440 minutes (24 hours)**, at most **12884901888 bytes (12 GiB)**. Target **lab-db-01 (Subscriber DB server)**. Limits (DD-F-04): the four limit texts, plus "attempt 1 of 1". Expiry **15 minutes after the request**. Evidence: the three diagnostic summaries, the AI investigation and the similarity match PI-0342 (DM-V-12). Recovery checks **"Subscriber DB services recovered"**: all incident alerts resolved, and no firing alert on the Synthetic USSD session check, for 60 s. Reading (v1.1, `40-demo-cut` DC-A-07, no value changed): the page shows the parameters as stored (`path: /data/txlog_archive`, `older_than_minutes: 1440`, `max_bytes: 12884901888`), with "24 hours" and "12 GiB" in the limit texts; the expiry is shown as the absolute time with the remaining time, and as "expires after 900 s" in messages. |
| Exact values: after Approve | Rechecks (complete list, all passed): approval valid; action, parameters and target unchanged; incident open; precondition `/data` used ≥ 90% (shown as "Used percentage of one filesystem (mount=/data): value = 100 (required ≥ 90)", DC-A-07). Result: **succeeded**, with the number of files and GiB removed (measured values of DD-F-10; same on every run). |
| Narrator | "Vigil found that this exact pattern happened before and was fixed 4 times by a registered action. It proposes that action: exact, limited, and only after a named approver agrees. Just before running, it checks everything again." |

#### SB-S07 Recovery check (10:00–12:15) — SL-A1-07, Real

| Part | Content |
| --- | --- |
| Operator | Shows the incident while alerts resolve, then the incident state, then the Jira ticket. |
| Screen | The resolved-alert count rises to all 138 alerts. The synthetic check shows success. The Recovery check step shows its observation window counting 60 s. |
| Exact values | Incident state **Resolved**, with the recovery verification as evidence: all 138 alerts resolved, no firing synthetic USSD alert, held for 60 s (wording SB-O-01). Jira ticket status **Resolved**, with the recovery evidence as a comment. |
| Narrator | "The fix succeeding is not enough. Vigil closes the incident only when the monitoring itself shows customers are being served again." |

#### SB-S08 Workflow glimpse (12:15–12:45) — SL-A1-08, Real

| Part | Content |
| --- | --- |
| Operator | Opens the workflow graph tab of the storm incident. |
| Exact values | The read-only graph of workflow version 1 (DD-F-06). The path taken is t1 → c1 (P1) → d1 → a1 → a2 → k1 → n1 → e1 → s1 → p1 → x1 → r1 → k2, with each step's output shown. During SB-S03 to SB-S07 the running step was highlighted, and the highlight moved within 2 seconds of each step change. |
| Narrator | "Every step you saw is a workflow your team can change without code. We will change it live in a moment if you want." |

#### SB-S09 Dashboard (12:45–13:45) — SL-A1-09, Simplified

| Part | Content |
| --- | --- |
| Operator | Opens the dashboard. |
| Exact values | Four metrics, each next to its "Illustrative baseline". The history is the 50 seeded fictional incidents plus this run (SB-V-40). The two time metrics equal the DM-R-53 result computed from the seeded data and this run's own acknowledgement and resolution times, to one decimal place. |
| Narrator | "This is what thirty days of this looks like on illustrative numbers: acknowledgement in minutes instead of a quarter of an hour, and hundreds of alerts that nobody had to handle one by one." |

#### SB-S10 Close (13:45–14:15)

| Part | Content |
| --- | --- |
| Narrator | "Your monitoring, your rules, your approvals; Vigil does the legwork and keeps the record. We can go deeper into any part now." |

## 8. Deep dives (SL-A2), shown after the primary demo on request

Section 9 gives the order. Time budgets are guidance for the narrator. No deep dive is Staged.

### SB-D01 Audit trail — SL-A2-08, Real (2 minutes; shown after SB-D03)

| Part | Content |
| --- | --- |
| Operator | Opens the audit trail (SL-A4-11). First filters by the storm incident of the primary demo, then by event type `configuration_changed`. |
| Exact values (events shown, complete list for the storm incident of the primary demo, in order of occurrence) | `incident_created`; `alert_grouped` (one per alert whose grouping decision named this incident); `hypothesis_changed`; `assignment_changed`; `incident_state_changed` (forming → open); `ai_result_accepted`; `ticket_changed` (create); `notification_sent` (one per notification record); `incident_acknowledged` (Ayesha Siddiqui); `policy_check_result` (approval required); `approval_requested`; `notification_sent` (approval requests); `approval_decided` (approved, Bilal Qureshi); `action_executed` (attempt 1, succeeded); `recovery_verified` (recovered); `incident_state_changed` (open → resolved); `ticket_changed` (resolve); `incident_state_changed` (resolved → closed, 300 to 330 s after resolution: reopen window plus the 30 s closure check interval). Events of the same type MAY repeat when the hypothesis or assignment changed while the incident was forming. Configuration changes (complete list after SB-D02 and SB-D03): the seed import of the reset (module `vigil-setup`, always shown); service map generation 2 imported (admin, when SB-D02 was shown); routing rule set revision 2 (admin); workflow version 2 published (admin); workflow version 3 published by rollback (admin). Connection tests and draft saves produce no audit event. Reading of the incident filter: events are filtered by `incident_id`, so events of forming incidents absorbed by a merge are not listed; memberships moved by a merge write no `alert_grouped`, and an `alert_grouped` event written while an alert was in a forming incident that was later absorbed keeps that incident's `incident_id` (audit events never change). The filter therefore shows 138 `alert_grouped` events only when no merge occurred; tenant-wide there is always exactly one per storm alert (138); `ai_result_accepted` is written only for the SL-T-1 investigation result (DM-E-68). |
| Narrator | "Who did what and when, for every decision, approval and action, and for every configuration change." |

### SB-D02 Guided setup — SL-A2-01, Real (3 minutes)

| Part | Content |
| --- | --- |
| Operator | Logs in as `admin` in the main window. Opens Setup and sources (SL-A4-07). Runs "Test connection" on both sources. Imports the service map file of DD-F-02, unchanged, and shows the readiness gaps. |
| Exact values | Both sources: test **passed**, health **healthy**. The import creates service map generation 2 with 6 services, 18 components, 12 identity keys and 12 dependencies. Readiness gaps (complete list, DD-K-12): **"Server with no owner: Backup server (bkp-srv-01)"**; **"Service with no criticality: Internal Reporting"**. |
| Narrator | "Connecting Vigil means connecting what you already run and describing your services once. Vigil tells you what is still missing." |

### SB-D03 Change it live, and nobody acknowledges — SL-A2-06 with SL-A2-03, Real (11 minutes)

Precondition: more than 300 s have passed since the storm incident was resolved (`reopen_window`, DM-R-24 a). The operator pressed "Clear all faults" at the start of the deep dives, which clears SL-F-3 (DM-R-24 d).

| Step | Operator (as `admin` unless stated) | Exact values on screen |
| --- | --- | --- |
| SB-D03-1 | Opens the workflow editor. Drags an Approval step onto the canvas between "DB Ops approval" and "Run proposed fix"; the editor names it `p2` (SB-R-08). Types the label "DB Ops second approval", sets eligible team Database Operations and expiry 900 s, and connects it as in DD-F-06. | The draft is saved. |
| SB-D03-2 | Selects "Wait for acknowledgement" and changes the timer from 600 to 60 seconds. | Parameter accepted. |
| SB-D03-3 | Opens Routing rules (SL-A4-08). Adds Customer Care to the informed teams of "Critical service, infrastructure or database cause". Saves. | Routing rule set revision **2** (resolves SL-O-21). Storm severity and owner unchanged. |
| SB-D03-4 | Runs a test replay of the storm incident against the draft. | Result labelled **"Test"**. Assignment P1, Database Operations, informed **Customer Care, VAS Service Owners**. Path t1 → c1 → d1 → a1 → a2 → k1 → n1 → e1 → s1 → p1 → p2 → x1 → r1 → k2. Notes (exact): p1 "Simulated approval (eligible: Database Operations)"; p2 "Simulated approval (eligible: Database Operations); approver must differ from the approvers of the earlier Approval steps". The e1 note shows the simulated escalations (DM-R-49). On the simulated clock the wait starts at the storm incident's `stable_at` plus the stored durations of d1, a1 and a2 (DM-R-47), which is not later than the real wait start (at most 05:10). The original acknowledgement came at 07:30 or later (SB-TL-06), so more than 120 s after the simulated wait start, and the replay always shows **two simulated escalations: level 1 NOC Management, then level 2 IT Operations Head**. No message, ticket or command is sent. |
| SB-D03-5 | Publishes the draft. | Version **2** active. Change list: p2 added; p1 changed; e1 changed (SL-WE-09). |
| SB-D03-6 | In the control panel, presses "Storm re-run: SL-F-1 only". Waits, and does not acknowledge. | A new storm incident with the same title, P1, Database Operations, informed Customer Care and VAS Service Owners. It runs on version 2. |
| SB-D03-7 | Shows the "NOC Management" and "IT Operations Head" Teams channels and the Jira ticket. | **SL-A2-03:** 60 s after the messages, escalation to level 1 (**NOC Management**); 120 s after, escalation to level 2 (**IT Operations Head**); each with the evidence summary, recorded on the incident and as a Jira comment. After level 2 the step leaves, and the approval request to both approvers is sent. |
| SB-D03-8 | Phone window: logs in as `noc-l1` and acknowledges (still accepted: the incident is open and NOC L1 was notified). Then logs in as `dbops-engineer` and approves. Then shows the "Approvals — Sana" channel, opens the latest request there (the second request of the run; requests are listed in time order), logs in as `dbops-lead` and approves it. | Two approvals by two different named users. The second request appears only in "Approvals — Sana", not in "Approvals — Bilal" (DM-R-30). Then the action, recovery and resolution, as in SB-S06 and SB-S07. |
| SB-D03-9 | Opens version history and rolls back to version 1. | Version **3** active, with content equal to version 1 (SL-B-03). Version history lists who published each version and when. |

| Part | Content |
| --- | --- |
| Narrator | "Your own team changes the process: a second approver for P1, a shorter escalation timer, and Customer Care informed of P1 incidents. They test it against a real past incident, publish, and the next incident follows it. One click takes it back." |

### SB-D04 AI settings — SL-A2-02, Real (2 minutes)

| Part | Content |
| --- | --- |
| Operator | As `admin`, opens AI settings (SL-A4-09). |
| Exact values | Provider per task and fallback order: SL-T-1 and SL-T-2 **Claude → OpenAI → Local model**; SL-T-3 **Local embedding model** (SB-V-31; SL-O-20 confirmed in Round 5). Data routing: every category allowed to every provider (DD-F-07). Usage and cost per provider and per task for the runs so far. |
| Narrator | "You choose which AI does which job, in which fallback order, and which kinds of data may go where. Every call is metered." |

### SB-D05 Cloud AI unavailable — SL-A2-05, Real (3 minutes plus the local model's answer time)

| Step | Operator | Exact values on screen |
| --- | --- | --- |
| SB-D05-1 | Control panel: "Cut cloud AI: SL-F-4". | — |
| SB-D05-2 | Opens the storm incident of the primary demo (closed by then, 300 to 330 s after its resolution) and runs "Re-run AI investigation". | Usage records for this re-run: **Claude — unreachable**, **OpenAI — unreachable** (each failing within 5 s, SB-ASM-03), **Local model — succeeded**. The investigation shown is the local model's (acceptance criteria as SB-S04). Answer time per SB-T-04 (CTX-O-04). |
| SB-D05-3 | In AI settings, removes Local model from the SL-T-1 order, then re-runs again. | The incident shows **"AI investigation unavailable"** (reason: no provider reachable). Severity, owner and informed parties are unchanged; routing never depended on AI. |
| SB-D05-4 | Restores the SL-T-1 order. Control panel: "Clear all faults". | — |

### SB-D06 Keep data in-house — SL-A2-07, Real (2 minutes plus the local model's answer time)

| Step | Operator | Exact values on screen |
| --- | --- | --- |
| SB-D06-1 | AI settings: sets SL-T-1 and SL-T-2 to **Local model** only. SL-T-3 is already Local embedding model only. | No external provider in any fallback order (DM-R-45). |
| SB-D06-2 | Re-runs the AI investigation on the storm incident of the primary demo. | The usage log filtered by this re-run shows only **Local model** (location in-house). No request went to an external provider. |
| SB-D06-3 | Restores SL-T-1 and SL-T-2 to Claude → OpenAI → Local model. | SB-D07 then runs on the default providers. |

### SB-D07 The fix does not work — SL-A2-04, Real (10 minutes; always last)

Precondition: more than 300 s since the last storm incident was resolved, and all faults cleared.

| Step | Operator | Exact values on screen |
| --- | --- | --- |
| SB-D07-1 | Control panel: "Storm with read-only DB: SL-F-1 + SL-F-2". | A new storm incident with the same title, running on version 3 (content of version 1). |
| SB-D07-2 | Phone window: `noc-l1` acknowledges; `dbops-engineer` approves. | The action succeeds, with the same files removed as in SB-S06; `/data` back to its baseline percentage (DD-F-10). |
| SB-D07-3 | Shows the incident while waiting. | Database write, API, USSD, SMS queue and synthetic alerts keep firing. Only the 2 `disk_full` alerts resolve. |
| SB-D07-4 | Waits until 300 s after the Recovery check step started. | Recovery verification **not recovered**. Escalation to level 1 (**NOC Management**) with the evidence summary, recorded in Jira. The incident stays **open**. **No second action attempt** (attempt limit 1). |
| Narrator | — | "When the fix does not work, Vigil does not pretend. It does not try again beyond the agreed limit of one attempt; it hands over to people, with all the evidence." |

## 9. Demo order, reset and repeatability

| ID | Rule | Trace |
| --- | --- | --- |
| SB-R-01 | Every presentation starts from a reset (SB-SET-07). The primary demo (SB-S00 to SB-S10) always comes first. | SL-A3-03, CTX-D-007 |
| SB-R-02 | Deep dives MAY be skipped, but those shown MUST follow this order: SB-D02, SB-D03, SB-D01, SB-D04, SB-D05, SB-D06, SB-D07. The audit trail follows SB-D03 so it can show workflow and routing changes; AI settings come before the fault and the in-house switch (SB-D05 needs cloud providers selected); SB-D07 is last because its incident stays open. When SB-D02 or SB-D03 is skipped, its configuration changes are not listed. | SL-A2, SL-A2-08; DM-R-24 |
| SB-R-03 | At the start of the deep dives, the operator presses "Clear all faults". This clears SL-F-3 and lets its alert resolve; the SL-F-3 incident stays open with its P4 ticket. | DM-R-24 (d) |
| SB-R-04 | SB-D03 and SB-D07 each start a storm. Each MUST start more than 300 s (`reopen_window`) after the previous storm incident was resolved, and after "Clear all faults" has completed. | DM-R-24 (a), (b), (d) |
| SB-R-05 | After SB-D07, or after any deep dive that changed settings, the next presentation MUST start with a reset. | SL-A3-03 |
| SB-R-06 | Repeatability (SL-A3-04) applies to the primary demo from a reset. The expected result of each of the 5 runs is (complete list): one storm incident plus one SL-F-3 incident; hypothesis Subscriber DB server; P1 and Database Operations; best match PI-0342 at 1.00; the action executed once; the incident resolved; the Jira ticket resolved. The storm alert count MAY vary within 100 to 200 (expected 138). | SL-A3-04 |
| SB-R-07 | No storm is injected between 23:50 and 00:20 UTC (all hosts run in UTC; the growth diagnostic reads only the current day's sysstat samples); the control panel refuses storm buttons in that window (DD-F-10). Between two SB-T-02 runs at least 3 minutes pass after the previous run's action, so the 5-minute growth window holds a baseline sample. | DD-F-04 `fs_growth`; DD-F-10 |
| SB-R-08 | The workflow editor names a new step with its type letter (complete list: trigger t, condition c, diagnostic d, AI task a, approval p, action x, recovery check r, escalation e, notification n, ticket k, wait w) and 1 + the highest number that letter has in any version or draft of the workflow, so ids are never reused (DM-V-05). The ids of version 1 are seed data and do not follow this rule (Example: `s1`). The new step in SB-D03 is `p2` when the operator adds exactly one step and deletes none; otherwise the replay path, notes and change list show the id the editor gave. Round 4 carries this rule into the editor screen content. | DM-V-05; SL-WE-01 |

## 10. Demo values (complete list of values fixed in this round)

| ID | Open item | Value | Where |
| --- | --- | --- | --- |
| SB-V-01 | SL-O-01 Recovery observation window | 60 s | DD-F-04 `rc-subscriber-db`, `rc-default` |
| SB-V-02 | SL-O-02 Definition of SL-F-2 | Subscriber DB stays read-only after disk space is freed | Section 6; DD-F-10 |
| SB-V-03 | SL-O-03 Script and timing | Section 7 | — |
| SB-V-04 | SL-O-04 Grouping time window | 300 s | DD-F-07 `grouping_window_s` |
| SB-V-05 | SL-O-05 Handling of the SL-F-3 incident | P4, owner IT Infrastructure, AI summary (SL-T-2) for the ticket, Jira ticket, no message, no diagnostics, no AI investigation (SL-T-1), no action | SB-S02; DD-F-05, DD-F-06 |
| SB-V-06 | SL-O-06 Diagnostics | Data path `/data`; up to the 5 largest folders (`du` depth 1); growth over the last 5 minutes (31 samples, 10 s apart; last minus lowest `%ufsused`) | DD-F-04 |
| SB-V-07 | SL-O-07 Owner and informed parties of the storm incident | Owner Database Operations; informed VAS Service Owners; severity P1 | DD-F-05 |
| SB-V-08 | SL-O-08 Best match, score, success count, action, parameters | PI-0342; 1.00; 4; "Purge archived transaction logs"; path `/data/txlog_archive`, older than 1440 minutes, at most 12884901888 bytes | DD-F-04, DD-F-09 |
| SB-V-09 | SL-O-09 Approval expiry and limits | 900 s; the four limits and attempt limit 1 of DD-F-04 | DD-F-04, DD-F-06 |
| SB-V-10 | SL-O-10 Dashboard data source | All incidents of the tenant (the current demo run) plus the 50 seeded past incidents | DM-R-53; DD-F-09 |
| SB-V-11 | SL-O-12 Source health thresholds | Stale after 180 s, disconnected after 600 s without contact | DD-F-03 |
| SB-V-12 | SL-O-14 Escalation levels and timers | Level 1 NOC Management; level 2 IT Operations Head; timer 600 s in version 1, 60 s after SB-D03 | DD-F-05, DD-F-06 |
| SB-V-13 | SL-O-15 Action attempt limit | 1 per incident | DD-F-04 |
| SB-V-14 | SL-O-16 Scope of SL-F-4 | Claude API and OpenAI API hosts only | Section 6 |
| SB-V-15 | SL-O-18 Display names | Section 5 | DD-F-01 |
| SB-V-16 | SL-O-21 Routing rule changed in SL-A2-06 | Add Customer Care to the informed teams of `p1-critical-infra` | DD-F-05 |
| SB-V-17 | SL-O-22 Workflow version 1 | DD-F-06 | DD-F-06 |
| SB-V-17a | PM-O-03 (Round 3 part) | Workflow version 1 needs no extra waiting step: the engine holds every run until the group is stable (DM-R-16); the stability values are SB-V-23 | DD-F-06, DD-F-07 |
| SB-V-17b | PM-O-15 (Round 3 part) | The 50 past incidents with opening, acknowledgement and recovery times as offsets (DD-R-04) | DD-F-09 |
| SB-V-18 | PM-O-02 Identity key values | DD-F-02 `identity_keys` | DD-F-02 |
| SB-V-19 | PM-O-08 Recovery check maximum wait | 300 s | DD-F-06 step r1 |
| SB-V-20 | PM-O-12 Source health values | Health check every 60 s; thresholds as SB-V-11 | DD-F-03 |
| SB-V-21 | PM-O-14 Reopen values and demo order | Reopen window 300 s; order in Section 9 | DD-F-07; SB-R-02 to SB-R-04 |
| SB-V-22 | PM-O-16 Minimum similarity | 0.95 (checked by SB-T-03) | DD-F-07 |
| SB-V-23 | DM-O-01 Forming values | Settle period 45 s; maximum forming time 180 s; stability check every 5 s | DD-F-07 |
| SB-V-24 | DM-O-02 Reopen and closure values | Reopen window 300 s; closure check every 30 s | DD-F-07 |
| SB-V-25 | DM-O-03 Health check interval | 60 s per source | DD-F-03 |
| SB-V-26 | DM-O-04 (values) | Identity key values and alert type mapping values. Round 5 confirms the connector field names and MUST keep the identity key order Zabbix [`tag.component`, `host`] and Alertmanager [`label.component`, `label.instance`]; any other order changes the hypothesis, title and impact of SB-S02 and requires DD-K-02, DD-K-05 and DD-K-06 to be re-run and SB-S02 re-derived | DD-F-02, DD-F-03 |
| SB-V-27 | DM-O-07 Runner operations | Five operations with command templates, timeouts and output parsers | DD-F-04 |
| SB-V-28 | DM-O-09 Runbook limit | 3 | DD-F-07 |
| SB-V-29 | DM-O-10 Default data routing | Every category allowed to every provider | DD-F-07 |
| SB-V-30 | DM-O-14 Default recovery check | `rc-default`: all incident alerts resolved for 60 s | DD-F-04 |
| SB-V-31 | SL-O-20 (proposal only; Round 5 decides) | SL-T-1 and SL-T-2: Claude → OpenAI → Local model | DD-F-07 |
| SB-V-40 | Dashboard values | Seeded history alone (DD-K-11): time to acknowledge **4.3 min**; time to recover **28.2 min**; alerts suppressed **634**; incidents merged **46**. After one primary demo run with 138 storm alerts: alerts suppressed **771** (684 + 139 received − 52 incidents); incidents merged **47**. Time to acknowledge and time to recover move by less than 0.5 min, depending on the acknowledgement and recovery times of the storm incident. Baselines: 14.0 min, 95.0 min, 0, 0. | DD-F-07, DD-F-09 |

## 11. Tests that need running software (complete list)

These are carried into the build plan (Round 6). Each one proves an assumption or a value of this round.

| ID | Test | Pass condition | Proves |
| --- | --- | --- | --- |
| SB-T-01 | Lab storm timing and values | 10 storms in a row, each meeting every timing target of DD-F-10 and giving the same growth value (138 firing entries and no repeat, all within 120 s, no gap above 30 s, both `disk_full` alerts included, resolves within 60 s after the action, no re-firing within 300 s). The spike also records the measured values of DD-F-10. | DD-ASM-03, DD-ASM-05, DD-ASM-06, SB-TL-01, SB-TL-09 |
| SB-T-02 | Primary demo repeatability | 5 runs from a reset, driven through Vigil's public interface (logging in as `noc-l1` and `dbops-engineer`; Acknowledge and Approval links taken from Vigil's notification records). Each run MUST show the SB-R-06 result, the SB-S02 grouping-reason criteria, the SB-S04 and SB-S05 acceptance criteria, and every exact value of SB-S01 to SB-S09, with these readings (complete list): the storm count is exactly 138 (30 Zabbix, 108 Alertmanager), which SB-T-01 guarantees, while SB-R-06 tolerates 100 to 200 for the presentation; alerts suppressed is checked as 684 + alerts received − 52; measured values are compared with DD-F-10 once recorded; the Jira status and comment are read from Jira's API (read-only); Teams and email delivery is checked through Vigil's notification records (`sent`); the 2-second highlight (SB-S08) and the SB-TL times are recorded and do not fail this test (SB-T-08 checks them). | SL-A3-04 |
| SB-T-03 | Similarity | With the chosen local embedding model: storm best match PI-0342 with score 1.00; SL-F-3 best score below 0.95. | DD-ASM-04, SB-V-22 |
| SB-T-04 | Step times | Over 5 runs: diagnostics finish within 15 s of the incident leaving `forming`; Claude SL-T-1 plus SL-T-2, ticket creation and message sending finish within 60 s after that. The local model's SL-T-1 answer time is recorded for CTX-O-04. | SB-TL-03, SB-TL-04; CTX-O-04 |
| SB-T-05 | Reset and clear | "Clear all faults" resolves every lab alert within 40 s and completes within 55 s (DD-F-10 15 s margin); and the full reset, clearing included, completes within 60 s whatever mechanism Round 5 chooses for SL-O-17, 5 times in a row, including after SB-D07 (SL-F-1 and SL-F-2 active). | SL-A3-03; DM-ASM-08 |
| SB-T-06 | Teams posting | Messages with links arrive in each team channel and in the "Approvals — Bilal" and "Approvals — Sana" channels. | SL-ASM-02 |
| SB-T-07 | Jira | Create, resolve, comment, reopen and label search work on the demo project. | PM-ASM-03, DM-ASM-07, DM-ASM-09 |
| SB-T-08 | Rehearsal | Two full rehearsals of the primary demo, each at 15:00 or less, with every scene within its budget plus or minus 30 s. | SL-A1 |
| SB-T-09 | Cloud AI cut | On 3 different days, with SL-F-4 active for 10 minutes, every cloud provider call fails within 5 s and the re-run falls back to the local model; Jira, Teams and email still work. | SB-ASM-03, PM-ASM-04 |

## 12. Public Jazz facts used (SL-DR-02)

None. No USSD short code, product name, bundle name or other public Jazz fact appears in the demo or the data. Services are named by function. Adding a public fact later requires its public source and a change under SL-CR-02.

## 13. Explicitly out of scope for this document

Screen layout and content beyond the values named here (Round 4); stack, hosting, connector field names, model choice and the reset mechanism (Round 5); build packages and the lab build (Round 6); the Jazz name and logo permission (CTX-O-05).

## 14. Decisions and assumptions

### 14.1 Decisions made in this round

Status "Agreed by owner" means Abdul agreed the decision under CTX-Q-7 on 9 October 2026. "Decided by owner" means Abdul gave the answer himself. "Proposed" means Claude made the decision within the scope lock and earlier documents; Abdul MAY change any of them through the `00-context` decision log. All were adopted by CTX-D-017.

| ID | Decision | Reason | Status | Trace |
| --- | --- | --- | --- | --- |
| SB-D-01 | The storm is a subscriber DB data disk filled by unpurged archived transaction logs. It produces 138 alerts across the 7 types and affects 3 services. SL-F-3 is a report export failure on an unrelated reporting server, handled as P4 with a ticket only. | A common, believable NOC incident with a safe, bounded, registered fix; a clean dependency chain from the cause to the customer impact. | Agreed by owner | SL-A1-01, SL-A1-02, SL-O-05 |
| SB-D-02 | The best match PI-0342 has the same search text as the storm incident, so its score is 1.00 whatever embedding model Round 5 chooses. The minimum similarity is 0.95. | The match and score are then identical on every run and independent of the model (SL-A3-04). A recurring problem matching its own history is the story NOC automation sells. | Agreed by owner | SL-A1-06, SL-A3-04, SL-O-08, PM-O-16 |
| SB-D-03 | Timers: escalation wait 600 s (60 s after SB-D03); approval expiry 900 s; observation window 60 s; maximum recovery wait 300 s; settle period 45 s; reopen window 300 s. | Fits the 15-minute script with margin (Section 7.2): no escalation can happen before 11:45 in the primary demo; the deep-dive waits stay at 5 minutes or less. | Agreed by owner | SL-O-01, SL-O-09, SL-O-14, PM-O-08, DM-O-01, DM-O-02 |
| SB-D-04 | Deep-dive order of Section 9. SL-A2-03 is shown inside the SL-A2-06 storm re-run. SL-A2-04 comes last. | Avoids a 10-minute wait for the escalation timer (600 s), and avoids an open incident affecting later scenes. | Agreed by owner | SL-A2-03, SL-A2-04, SL-A2-06 |
| SB-D-05 | The seeded history is presented as the last 30 days handled by Vigil (fictional), against invented manual baselines of 14.0 min, 95.0 min, 0 and 0. | Without it, a single live incident cannot show a trend (SL-A1-09); the labels keep it honest. | Agreed by owner | SL-A1-09, SL-O-10 |
| SB-D-06 | No public Jazz fact is used; all names are invented and generic. | Public facts could not be verified in this round, and a wrong fact in front of Jazz would damage CTX-G-1. | Agreed by owner | SL-DR-01, SL-DR-02 |
| SB-D-07 | Routing rules of DD-F-05. The SL-A2-06 change adds Customer Care to the informed teams of the P1 rule. | The severity and owner of the storm stay the same (SL-O-21); the change is visible in the test replay and the re-run and does not overlap the escalation levels. | Agreed by owner | SL-O-07, SL-O-21 |
| SB-D-08 | Demo setup: Teams meeting; the narrator talks and the operator drives; a phone-size browser window for the mobile pages; attendees do not join Teams. | Answers given by Abdul (CTX-O-03). | Decided by owner | CTX-O-03, SL-X-16 |
| SB-D-09 | Workflow version 1 sends P1 notifications to the owner team, the informed parties and NOC L1. Non-P1 incidents get a summary and a ticket only. | Covers SL-A1-05 and the VAS-owner informing of the demo story; keeps P4 noise out of the channels. | Proposed | SL-A1-05, SL-O-22 |
| SB-D-10 | The action's attempt limit is 1. | Workflow version 1 has no retry; one attempt makes SL-A2-04 clear. | Proposed | SL-O-15 |
| SB-D-11 | The data set defines 18 alert types (DD-R-08), wider than the example demo set named in DM-E-16. `20-domain-model` v1.1 rewords DM-E-16 to point to `31-demo-data`. | Past incidents need varied, realistic types. | Proposed | DM-E-16, SL-DR-04 |
| SB-D-12 | The lab DB server's exact sizes and percentages are measured by the lab spike and recorded in DD-F-10 before the first rehearsal, instead of being fixed now. | Filesystem overhead makes exact figures impossible to state before the lab exists; once measured they are the same on every run. | Proposed | CTX-W-07, SL-A1-03 |

### 14.2 Changes to earlier documents (complete list)

| ID | Document and item | Change | Status |
| --- | --- | --- | --- |
| SB-A-01 | `20-domain-model` DM-E-16 | The meaning of `alert_type` says the demo set is defined in `31-demo-data` (DD-F-03), instead of "the seven types of SL-A1-01 plus the SL-F-3 type". Written as `20-domain-model` v1.1. | Done |
| SB-A-02 | `10-product-map` PM-DC-04 "Talks to" | Adds read-only queries to the Zabbix problem API and the Alertmanager alerts API, used only to know when "Clear all faults" is complete (DD-F-10). Demo-only; no Vigil module changes. Written as `10-product-map` v1.2. | Done |
| SB-A-03 | `10-product-map` PM-DC-01 | Clarification, no new lab service: the mock SMS dispatcher is part of the "SMS queue" mock, and the mock report export job on the reporting server exists only for SL-F-3 (SL-A3-02). Written as `10-product-map` v1.2. | Done |
| SB-A-04 | `00-context` CTX-O-03 | Closed by SB-D-08 (`00-context` v1.7). | Done |
| SB-A-05 | `20-domain-model` DM-E-38 | The `source_system` example becomes "lab-db-01 via runner, operation fs_usage" (an operation key), matching SB-S03. Written as `20-domain-model` v1.1. | Done |
| SB-A-06 | `01-scope-lock` SL-A3-01, SL-A3-07 | Clarifications, no item added: the SMS queue mock includes its dispatcher and a mock report export job exists only for SL-F-3; teams without members are configuration data (owners, informed parties, escalation levels). Written as `01-scope-lock` v1.7 (CTX-D-019). | Done |

### 14.3 Assumptions

The data assumptions are DD-ASM-01 to DD-ASM-06 (`31-demo-data` README). This document adds (complete list):

| ID | Assumption | Verified by |
| --- | --- | --- |
| SB-ASM-01 | The operator can show the Teams client, the Jira board and a mail client in one shared screen during a Teams meeting without exposing anything other than the demo accounts. | Rehearsal SB-T-08 |
| SB-ASM-02 | Claude as the SL-T-1 provider answers within the time of SB-T-04, so SB-TL-04 holds. | SB-T-04 |
| SB-ASM-03 | Overriding the two cloud AI host names to an address where nothing listens, together with rejecting and dropping connections to their previous addresses (DD-F-10), makes every cloud call fail within the gateway's 5 s connect timeout for as long as SL-F-4 is active, and affects no other traffic. | SB-T-09, run 3 times on different days |

## 15. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| SB-O-01 | Wording on screen of evidence one-line summaries for kinds `ai_investigation`, `similarity_match`, `approval`, `action_execution` and `recovery_verification`, and of the action result (the values they show are fixed here) | Claude | R4 |
| SB-O-02 | Confirmation of the SL-T-1 and SL-T-2 provider order (SB-V-31) | Claude, reviewed by Abdul | R5 (SL-O-20) |
| SB-O-03 | Measured lab values (DD-F-10, DD-O-03) used in SB-S03, SB-S06 and SB-D07 | Abdul | Lab spike SB-T-01, before the first rehearsal |

Open items resolved in this round are recorded in Section 10; as in earlier rounds, the open-item tables of earlier documents are not edited, except CTX-O-03 (closed in `00-context`). Carried open items still open after this round: CTX-O-01, CTX-O-02, CTX-O-04, CTX-O-05; SL-O-11 (format), SL-O-17, SL-O-19, SL-O-20; PM-O-01, PM-O-09 (connector capability), PM-O-10, PM-O-11, PM-O-17; DM-O-04 (field names), DM-O-05, DM-O-06, DM-O-08, DM-O-11, DM-O-12, DM-O-13; DD-O-01, DD-O-02, DD-O-03.

## 16. Quality-check results

| Check | Result |
| --- | --- |
| SB-Q-N1 Input versions | Repository and project mirror both held `00-context` v1.6, `01-scope-lock` v1.6, `10-product-map` v1.1 and `20-domain-model` v1.0 (written at the close of Round 2; confirmed identical by hash at the close of Round 3). At the close, `00-context` v1.7, `01-scope-lock` v1.7, `10-product-map` v1.2 and `20-domain-model` v1.1 were written to both. |
| SB-Q-N2 Public facts | Web access was unavailable in this round, so no public Jazz fact could be verified. None is used (SB-D-06). |
| CTX-Q-1 Coverage both ways | Every scene SL-A1-01 to SL-A1-09 and every deep dive SL-A2-01 to SL-A2-08 has a scene. Every Round 3 open item has a value (Section 10). Every data file traces to a seed part or a demo-only component (DD-F-00). |
| CTX-Q-2 Consistency | The data passes DD-K-01 to DD-K-14, which apply the rules of `20-domain-model` (grouping, hypothesis, routing, title, workflow validation, runbook selection, search text, cross-module keys, metrics). |
| CTX-Q-3 Scenario walkthrough | Section 7: each primary scene has its owner actions, data and exact outcomes; the timeline is in Section 7.2. |
| CTX-Q-4 Independent review | Done, 9 Oct 2026, on v0.1 of both outputs by a separate Claude reviewer: 37 findings (7 High, 11 Medium, 19 Low), all resolved in v0.2. High: (1) lab disk figures impossible because of filesystem overhead — XFS, fill-until-full injection, values measured by the spike (DD-F-10, SB-D-12); (2) the purge command could not report bytes — rewritten and tested (DD-F-04); (3) the 60-minute growth window was not repeatable — 5-minute window, last minus lowest (SB-V-06, SB-R-07); (4) Alertmanager would repeat firing alerts — `group_by ['...']` (DD-F-10, DD-ASM-05); (5) the 300 s escalation timer could fire during the primary demo — 600 s and bounded timeline (Section 7.2); (6) the test replay escalation count — two levels stated (SB-D03-4); (7) clearing faults versus the 60 s reset — 40 s clear target (SB-T-05). Main Medium fixes: parser outputs defined; diagnostic permissions; seed fields and records aligned with the domain model (DD-R-07); audit trail moved after SB-D03; level 2 escalation in the re-run; scene budgets re-derived from bounds; SL-F-4 rejection; success count 4 matches history; narration no longer claims Jazz's tooling; command quoting; browser profiles. Low fixes: per-approver channels; Customer Care as the new informed team; README sections; PM-O-03 and PM-O-15 values; DM-E-16 amendment (SB-D-11); checker extended; re-fire target; references, wording and generic menu names. |
| CTX-Q-4 pass 2 | Done, 9 Oct 2026, on v0.2 by the same reviewer (verification): 34 of 37 resolved, 3 partly resolved; 13 new findings (1 Medium, 12 Low), no High. All resolved in v0.3: service map import in the audit list; the 138 count moved to SB-S02; approvals channels in SB-T-06 and SB-D03-8; checker covers handles, credential purposes and allowed providers; earliest wait start and tested step targets (SB-TL, SB-T-04); acknowledgement window 07:30–08:15 and replay reasoning on the simulated clock; DM-E-16 amendment (SB-A-01); fact-check row; exact-mount filter in `fs_growth`; storm window for every storm; SL-F-4 by host name override; alert rule windows behind the resolve targets. |
| CTX-Q-5 Claude Code readiness test | Done, 9 Oct 2026, in two passes by a fresh Claude given only the `docs/` folder and asked to build the lab, the seed loader and SB-T-02. Pass 1 on v0.2: 31 questions, all answered in v0.3. Pass 2 on v0.3: 27 answered, 4 partly; 7 new questions; all answered in v0.4 (USSD menus that need no other service; remaining T+170 and 11:45 updates; exact evidence source text; SL-F-4 clear undoes every step; 15 s scrape interval; 15 s margin after clearing; SB-T-02 readings; step id rule; second request identified by time order). Remaining questions: 0. Main answers: baseline txlog ages refreshed by every storm and reset; the database never writes to `/data`; exactly 128 MiB left free; only catalogue alerts reach Vigil; Alertmanager label and annotation names (`api_instance`); control panel completion and storm guard (DD-F-10, SB-A-02); USSD story for all menus (RB-USSD-002); lab clarifications (SB-A-03); UTC storm window (SB-R-07); runbook and placeholder conventions and bundle name (DD-R-09 to DD-R-11); stronger DD-K-06 and DD-K-11; audit trail reading (SB-D01); exact replay notes and step id rule (SB-D03, SB-R-08); stability bound 50 s and re-derived timeline; acceptance criteria for grouping reasons, AI and summary text (SB-S02, SB-S04, SB-S05); evidence wording tracked (SB-O-01); SB-T-02 method; dashboard check rule; providers restored after SB-D06; reference fixes. |
| Final consistency audit | Done, 9 Oct 2026, after owner agreement, on the complete set (`00-context` v1.7, `01-scope-lock` v1.7, `10-product-map` v1.2, `20-domain-model` v1.1, this document and `31-demo-data`) by a fresh Claude, because work had been interrupted by a usage limit: no High; 7 Medium and 15 Low findings, all resolved before closing; a verification pass confirmed the fixes and found 5 further Low items, also resolved (DD-K-07 output and step checks, check script exit status, reset wording independent of SL-O-17, IT Infrastructure messages, CTX-D-019 marked for owner confirmation). Main fixes: leftover timer and assumption references; audit-trail count after merges (SB-D01); notified-team channel list; identity key order required of Round 5 (SB-V-26, DD-ASM-01); stronger checks DD-K-03, DD-K-06, DD-K-07, DD-K-10; scope-lock clarifications of SL-A3-01 and SL-A3-07 (CTX-D-019); DM-E-38 example (SB-A-05); 10 s sysstat interval so the growth value is repeatable; reset time budget; reopen boundary and closure wording; SL-F-3 AI summary; decision statuses defined. Hash comparison confirmed that the repository and the project held identical copies before closing. |
| CTX-Q-6 Fact check | Not possible in this round (no web access). Every claim about Zabbix, Alertmanager, Prometheus, sysstat, XFS, Jira, Teams and the AI providers is an assumption with a test: DD-ASM-01 to DD-ASM-06, SB-ASM-01 to SB-ASM-03; tests SB-T-01, SB-T-03 to SB-T-07 and SB-T-09. |
| CTX-Q-7 Owner review | Done, 9 Oct 2026. Seven decisions reviewed (SB-D-01 to SB-D-07): all agreed as proposed. Adoption recorded as CTX-D-017. |

## 17. Change history

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 9 Oct 2026 | First draft (Round 3). |
| 0.2 | 9 Oct 2026 | Resolved independent review findings (Section 16). |
| 0.3 | 9 Oct 2026 | Resolved review pass 2 and readiness test findings (Section 16). |
| 0.4 | 9 Oct 2026 | Answered readiness test pass 2 questions (Section 16). Draft for owner review. |
| 1.0 | 9 Oct 2026 | Owner review recorded; final consistency audit (Section 16); Round 3 closed. |
| 1.1 | 9 Oct 2026 | SB-S06 reading of the Approval page parameters, expiry and precondition text (`40-demo-cut` DC-A-07; CTX-D-020). |
