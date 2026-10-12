# Vigil — Jazz Storyboard

| Field | Value |
| --- | --- |
| Document | 30-jazz-storyboard (ID prefix `SB-`) |
| Version | 1.2 |
| Date | 12 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed). Round 3 closed (restarted 10 October 2026). Amended by Round 4 (`40-demo-cut` DC-A-04) and Round 5 (`50-architecture` AR-A-14). |

## 1. Purpose

This document is the scene-by-scene script of the demo: the primary demo of 15 minutes or less (SL-A1) and the deep dives (SL-A2). For every scene it gives the time budget, the Real / Simplified / Staged tag (`01-scope-lock` Section 3), who does what, and the exact values the audience sees. It also defines the demo setup, the faults, the demo order and the reset rules, and it fixes every demo value assigned to Round 3. The invented Jazz data set itself is in `docs/31-demo-data/` (prefix `DD-`); this document refers to its records by key.

Round 3 was first run on 9 October 2026 without web access. On 10 October 2026 the owner restarted it from scratch: the earlier output was moved to `docs/_superseded/` and is not an input to this document.

## 2. Inputs read

- `docs/00-context.md` v1.6.
- `docs/01-scope-lock.md` v1.6.
- `docs/10-product-map.md` v1.1.
- `docs/20-domain-model.md` v1.0.
- `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md` (story baseline only, CTX §8).
- `docs/sources/Continuum — Business Solution Document.md` v0.2 (background).
- Owner answers given in the Claude project conversation on 10 October 2026: the demo setup (resolves CTX-O-03, SB-D-01); no public Jazz fact is used (SB-D-06).
- Official documentation of the external products, checked on 10 October 2026 (Section 13).

## 3. Terms (complete list)

Terms defined in earlier documents keep their meaning. The terms below are added.

| Term | Meaning |
| --- | --- |
| Scene | One numbered part of the script: `SB-Snn` (primary demo) or `SB-Dnn` (deep dive). |
| Wall clock | Minutes and seconds since the start of the presentation, `mm:ss`. |
| T+ | Seconds since the storm fault (SL-F-1) was injected in the current storm. |
| Narrator | The Techfinity senior manager, who tells the story (SB-D-01). |
| Operator | Abdul Wadood, who drives every screen (SB-D-01). |
| Phone window | A browser window 360 px wide and 780 px high on the shared screen, used for the Acknowledge and Approval pages. It uses its own browser profile, so its logins never affect the main Vigil window. |
| Storm incident | The incident created from the SL-F-1 alerts. Its title is "Subscriber DB server: Disk full — 3 services affected" (DD-K-06). |
| Report incident | The incident created from the SL-F-3 alert. Its title is "Report export job: Report export job failed — 1 service affected" (DD-K-06). |
| Varies | A value that is real output and is not fixed in advance (Example: AI wording). Each such value has acceptance criteria instead of an exact value (CTX-W-07). |
| Measured | A value that depends on the lab (Example: the exact size of the injected files). It is measured by lab spike SB-T-01 and recorded in DD-F-10 before the first rehearsal (SB-O-03). |

## 4. Demo setup (resolves CTX-O-03)

| ID | Item | Value | Trace |
| --- | --- | --- | --- |
| SB-SET-01 | Meeting tool | A Microsoft Teams meeting; the operator shares one screen. | CTX-O-03; SB-D-01 |
| SB-SET-02 | Roles | The narrator talks. The operator drives every screen and says only the operator lines in the script. | CTX-O-03; SB-D-01 |
| SB-SET-03 | Shared screen (complete list of windows) | (1) Vigil in the browser profile "Presenter", logged in as `presenter` (left, two thirds of the width). (2) The phone window (right), browser profile "Phone". (3) The Teams desktop client showing the demo Team "Techfinity Demo". (4) A browser tab for Jira, in which the operator opens the current incident's ticket from its link (DC-S02-04), so tickets of earlier runs are not shown (`50-architecture` AR-B-04). (5) A web mail client showing the mailbox of NOC L1 (`${mailbox.noc-l1}`). (6) The demo control panel (SL-A4-12) in its own browser window. Windows 3 to 6 are brought to the front only when the script says so. | SL-A4-04, SL-A4-05, SL-A3-03 |
| SB-SET-04 | Links into the phone window | Links in Teams messages and emails are copied (right-click, copy link) and pasted into the phone window, never opened by clicking, so they open in the phone window and not in the default browser. The operator logs in on the phone window as the user named by the scene and logs out at the end of the scene. | SL-B-13 |
| SB-SET-05 | Attendees | Attendees do not join any demo Teams channel; every message is shown on the shared screen (decides SL-X-16). | SL-X-16; CTX-O-03 |
| SB-SET-06 | Teams and email destinations | One demo Team "Techfinity Demo" with one channel per team that has a delivery address (complete list: NOC L1, Database Operations, VAS Service Owners, IT Applications, NOC Shift Lead, NOC Management, Customer Care) and one approvals channel per approver ("Approvals — Usman", "Approvals — Farah"); one mailbox per team and approver with an address (DD-F-07). The operator shows the channels the script names and the NOC L1 mailbox. | SL-A1-05, SL-A1-06 |
| SB-SET-07 | Before each presentation | Reset (SL-A3-03); open every window of SB-SET-03; log in as `presenter`; check that the incident list is empty, both sources are healthy and the dashboard shows the seeded values of SB-V-38; the GPU host is started and the local model answers (`50-architecture` AR-G-31). | SL-A3-03, SL-A3-04 |
| SB-SET-08 | Backup | If the live session fails, the operator plays the backup video (SL-A3-08) from the scene where the failure happened. | SL-A3-08 |

## 5. Cast: users, teams and display names (resolves SL-O-18)

| User | Display name | Team | Used in |
| --- | --- | --- | --- |
| `presenter` | Demo Presenter | — | Main Vigil window, SB-S00 to SB-S10 |
| `noc-l1` | Hira Baig (NOC L1) | NOC L1 | SB-S05 (acknowledges), SB-D07 |
| `dbops-engineer` | Usman Tariq (DB Ops) | Database Operations | SB-S06 (approves), SB-D02 (first approver), SB-D07 |
| `dbops-lead` | Farah Naqvi (DB Ops Lead) | Database Operations | SB-D02 (second approver) |
| `admin` | Platform Administrator | — | Main Vigil window in SB-D01 to SB-D06 |

The names are invented (SL-DR-01). Teams without members (complete list: IT Applications, IT Infrastructure, VAS Operations, VAS Service Owners, NOC Shift Lead, NOC Management, Customer Care) are configuration data: component and service owners, informed parties and escalation levels (DD-F-01, DD-F-02, DD-F-05). Nobody acknowledges or approves as a member of these teams.

## 6. Faults (resolves SL-O-02 and SL-O-16)

The mechanics and sizes are in DD-F-10. The demo control panel triggers each fault (SL-A3-03).

| ID | Fault | What the audience is told | Effect in the lab |
| --- | --- | --- | --- |
| SL-F-1 | Subscriber DB /data full | "Archive shipping on the subscriber database has just caught up after a link outage, and it wrote two days of transaction log archives back to the data disk." | Archive files with modification times 26 to 47 hours old fill `/data/txlog_archive` until 64 MiB stay free. Database writes fail; the balance-check and bundle-purchase APIs return errors; USSD sessions time out; SMS queues grow; synthetic USSD sessions fail. 126 alerts (DD-K-01). |
| SL-F-2 (resolves SL-O-02) | Subscriber DB stays read-only | "This time the database also switched itself to read-only mode." | Database writes keep failing after disk space is freed, so every storm alert except the 2 disk_full alerts keeps firing (124 alerts). |
| SL-F-3 | Unrelated report export failure | Not announced; pointed out in SB-S02. | Every run of the report export job on the Reporting server fails while SL-F-3 is active, so one Zabbix alert, "Report export job failed", fires and stays firing until "Clear all faults" or a reset. Injected by the same control-panel button as SL-F-1. |
| SL-F-4 (resolves SL-O-16) | Cloud AI unreachable | "We now cut this server off from the cloud AI providers." | Outbound connections from Vigil to the Claude API and OpenAI API addresses are rejected at once. Nothing else is blocked: Jira, Teams, email, webhooks and both local models stay reachable (PM-ASM-04). |

The control panel buttons are (complete list): "Storm: SL-F-1 + SL-F-3"; "Storm re-run: SL-F-1"; "Storm with read-only DB: SL-F-1 + SL-F-2"; "Cut cloud AI: SL-F-4"; "Clear all faults"; "Reset". The control panel keeps its own record of active faults: a fault is active from its button until "Clear all faults" or a reset completes. "Clear all faults" removes every active fault and is complete when every lab alert has been resolved at its source and 12 s have passed, longer than Alertmanager's 10 s group interval (DD-F-10). Storm buttons are refused while SL-F-1, SL-F-2 or SL-F-3 is active or a clear or reset is running; an active SL-F-4 does not block them.

## 7. Primary demo script, 15 minutes or less (resolves SL-O-03)

### 7.1 Scene plan (complete list)

| ID | Scope lock scene | Wall clock | Budget | Tag | Tag reason |
| --- | --- | --- | --- | --- | --- |
| SB-S00 | Opening | 00:00–01:00 | 1:00 | — | No product behaviour |
| SB-S01 | SL-A1-01 Alert storm | 01:00–03:00 | 2:00 | Real | Real faults in the lab, real Zabbix and Alertmanager, real webhooks |
| SB-S02 | SL-A1-02 One incident, with reasons | 03:00–04:30 | 1:30 | Real | Grouping, hypothesis and impact computed by Vigil |
| SB-S03 | SL-A1-03 Diagnostics | 04:30–05:15 | 0:45 | Real | Read-only commands run on the lab DB server |
| SB-S04 | SL-A1-04 AI investigation and routing | 05:15–06:15 | 1:00 | Real | Live AI call; routing rules |
| SB-S05 | SL-A1-05 Ticket and notification | 06:15–08:00 | 1:45 | Real | Real Jira, Teams and email; real login |
| SB-S06 | SL-A1-06 Approved fix | 08:00–09:45 | 1:45 | Real | Real similarity search, approval, rechecks and command on the lab DB server |
| SB-S07 | SL-A1-07 Recovery check | 09:45–12:00 | 2:15 | Real | Real resolve notifications and observation window |
| SB-S08 | SL-A1-08 Workflow glimpse | 12:00–12:30 | 0:30 | Real | Live highlight of the run (visible from SB-S02 on) |
| SB-S09 | SL-A1-09 Dashboard | 12:30–13:30 | 1:00 | Simplified | Real computation over the live incidents plus the 50 seeded fictional incidents; the baselines are illustrative (SB-V-38, SB-V-39) |
| SB-S10 | Close | 13:30–14:00 | 0:30 | — | No product behaviour |

The total is 14:00, within the 15-minute limit; the 60 s left is margin. The storm is injected at wall clock 01:00 (T+0). SB-S01 to SB-S07 are Real (`01-scope-lock` Section 3).

### 7.2 Timeline the script depends on

| ID | Event | Expected time | Basis |
| --- | --- | --- | --- |
| SB-TL-01 | Last storm alert received | At most T+120 s (wall clock 03:00); the lab target is no gap above 25 s between storm alerts | SL-A1-01; DD-F-10 timing targets |
| SB-TL-02 | Storm incident leaves `forming` | At most 45 s after the last storm alert (40 s settle period plus the 5 s stability check), so at most T+165 s (03:45); never before T+40 s (01:40) | DD-F-07; DM-R-15 |
| SB-TL-03 | Diagnostics finished | At most 15 s after SB-TL-02, so at most T+180 s (04:00); tested by SB-T-04 | 3 diagnostics |
| SB-TL-04 | AI investigation and summary finished; ticket created; messages sent | At most 60 s after SB-TL-03, so at most T+240 s (05:00), with Claude as the SL-T-1 and SL-T-2 provider; tested by SB-T-04 | SB-V-31 |
| SB-TL-05 | Escalation step starts waiting | Right after SB-TL-04: never before T+40 s (01:40) and at most T+240 s (05:00). With the 600 s timer no escalation can happen before wall clock 11:40 | DD-F-06 |
| SB-TL-06 | Acknowledgement | Between wall clock 07:15 and 07:45 (operator rule in SB-S05), at least 3 minutes 55 s before the earliest possible escalation | Script |
| SB-TL-07 | Approval request sent | At most 5 s after the acknowledgement (the similar-incident search runs on the local embedding model) | DD-F-06 |
| SB-TL-08 | Action executed | The operator approves by wall clock 09:30 at the latest; the action ends at most 15 s later (09:45) | DD-F-04 |
| SB-TL-09 | All storm alerts resolved at their sources | At most 60 s after the action ends (10:45) | DD-F-10 timing targets |
| SB-TL-10 | Incident resolved; Jira ticket resolved | At most 65 s after SB-TL-09 (60 s observation window plus one evaluation), so at most 11:50; the 300 s maximum wait is never reached | DD-F-04, DD-F-06; DM-R-37 |

### 7.3 Scenes

#### SB-S00 Opening (00:00–01:00)

| Part | Content |
| --- | --- |
| Screen | The incident list, empty (`40-demo-cut` DC-S02). The banner reads "Demo environment — fictional data". The customer name is Jazz. |
| Narrator | "This is Techfinity, connected to a lab built like an IT and service NOC: USSD, the balance-check and bundle-purchase APIs, SMS, and a subscriber database, monitored by Zabbix and Prometheus, two tools common in IT NOCs. Everything you will see happens live. All data is fictional." |
| Operator | Nothing. |

#### SB-S01 Alert storm (01:00–03:00) — SL-A1-01, Real

| Part | Content |
| --- | --- |
| Operator | At 01:00 brings the control panel to the front and presses "Storm: SL-F-1 + SL-F-3", then returns to the incident list. |
| Narrator | Tells the SL-F-1 story (Section 6). "Watch what reaches the NOC in the next two minutes." |
| Screen | Alerts arrive and are grouped while the storm builds. Incidents still forming are shown without numbers and are never shown as several numbered storm incidents (DM-E32-R3); they are shown only in the incoming alerts strip (`40-demo-cut` DC-S02-02). |
| Exact values | 126 storm alerts (31 from Zabbix, 95 from Alertmanager) plus 1 SL-F-3 alert, all by webhook, all within 120 s (SB-TL-01). The 7 storm alert types (DD-F-03): Disk full; Database write errors; USSD gateway timeouts; Balance-check API errors; Bundle-purchase API errors; SMS queue growth; Synthetic USSD session failed. |
| Narrator | "That is 127 alerts. In most NOCs that is 127 lines on a screen and a long first hour." |

#### SB-S02 One incident, with reasons (03:00–04:30) — SL-A1-02, Real

| Part | Content |
| --- | --- |
| Screen | The incident list shows two numbered incidents once both leave `forming` (by 03:45, SB-TL-02): the storm incident and the report incident. The operator opens the storm incident workspace. |
| Exact values | Title "Subscriber DB server: Disk full — 3 services affected". Alerts: 126. Root cause, labelled "Hypothesis": Subscriber DB server (`srv-db-01`), the component on which the most other affected components depend (6 of 6). Customer impact: Balance Check (USSD), Bundle Purchase, SMS Notifications. |
| Grouping reasons | The operator expands three alerts (a Balance-check API alert, a Synthetic USSD session alert and an SMS queue alert) and the narrator reads each reason from the screen. Each reason is the final reason of DM-R-58: the related alert is the earliest-fired related member, usually a disk or database alert, so a typical path is "Balance-check API → Subscriber database → Subscriber DB server". Varies: the related alert, path and seconds depend on the alerts' fire times, not on arrival order. Acceptance (complete list): every member alert has a reason; each reason is `same_component`, a dependency path that exists in the service map (DM-E28-R2 included), or `only_alert`; every time difference is 300 s or less. |
| Narrator | Points at the report incident in the list: "This report job failed at the same moment, but it has nothing to do with the database, so Techfinity keeps it apart." |
| Report incident (resolves SL-O-05) | Title "Report export job: Report export job failed — 1 service affected"; severity P4; owner IT Applications; no informed teams; its workflow takes the not-P1 branch: an AI summary, a Jira ticket and a message to IT Applications only, no diagnostics, no approval, no action, no escalation; it stays open until its fault is cleared (SB-D02 step 5, or a reset). It is not opened during the primary demo. |

#### SB-S03 Diagnostics (04:30–05:15) — SL-A1-03, Real

| Part | Content |
| --- | --- |
| Screen | The evidence section of the storm incident: three diagnostic results from the Subscriber DB server (`lab-db-01`), each with source "lab-db-01 via runner" and its collection time. |
| Exact values (resolves SL-O-06) | Diagnostics (complete list, DD-F-04): "Disk usage of /data" — "/data is 100% full (<used> of <size> GiB used, 0.1 GiB free)"; "Largest folders under /data" — "Largest folder under /data: /data/txlog_archive (<size> GiB)"; "Data written under /data, last 15 minutes" — "<gib> GiB written to <files> files under /data in the last 15 minutes". Values in angle brackets are measured (SB-O-03). |
| Narrator | "Techfinity did not wait for anyone to log in to the server. It ran three approved read-only checks and attached the results. It holds read-only access and change access separately." |

#### SB-S04 AI investigation and routing (05:15–06:15) — SL-A1-04, Real

| Part | Content |
| --- | --- |
| Screen | The AI investigation section of the storm incident and the assignment. |
| Exact values (resolves SL-O-07) | Severity P1; owner Database Operations; informed VAS Service Owners. Matched routing rule: "Critical service affected" (DD-F-05). Runbooks given to the AI (complete list): RB-API-01, RB-DB-01, RB-USSD-01 (DD-K-08). Provider: Claude (SB-V-31). |
| Varies (AI wording) | Acceptance criteria (complete list): the likely cause says that `/data` on the Subscriber DB server is full because of transaction log archives; the AI confirms the hypothesis; it references at least one diagnostic evidence entry; it states something that remains uncertain (Example: why the archives were written back, or whether the database will accept writes once space is freed); it cites at least one supplied runbook (PM-M17-03); RB-DB-01 is the expected citation. An output that fails PM-M17-03 fails the scene and SB-T-02. A run that cites only another supplied runbook passes; SB-T-02 records the citation, and fewer than 4 of 5 runs citing RB-DB-01 is raised in Round 5 (prompt design). |
| Narrator | "The AI explains, with evidence, and says what it does not know. It does not decide severity or ownership: those come from routing rules the customer controls." |

#### SB-S05 Ticket and notification (06:15–08:00) — SL-A1-05, Real

| Part | Content |
| --- | --- |
| Screen 1 (Jira tab) | The new ticket. Title: "Demo environment — fictional data - Subscriber DB server: Disk full — 3 services affected" (DM-E02-R2). Content (complete list, SL-A1-05): incident title; "126 alerts" and the 7 alert types; customer impact; root-cause hypothesis; AI summary; evidence summary; link to the incident in Vigil. |
| Screen 2 (Teams, channel "NOC L1") | The incident message with the environment label prefix, the AI summary and the Acknowledge link. The same message reached the channels Database Operations and VAS Service Owners and the matching mailboxes (SB-V-15). |
| Screen 3 (mail client) | The same message in the NOC L1 mailbox. |
| Operator | Copies the Acknowledge link from the Teams message into the phone window, logs in as `noc-l1`, and presses Acknowledge between 07:15 and 07:45 (SB-TL-06). Logs out. |
| Exact values | The Acknowledge page shows the incident title, severity P1, owner Database Operations and the AI summary; after acknowledging it shows "Acknowledged by Hira Baig (NOC L1)" with the time. The incident timeline shows the acknowledgement with user and time. |
| Narrator | "The link carries no authority by itself: Hira had to log in. Techfinity records who acknowledged and when." |

#### SB-S06 Approved fix (08:00–09:45) — SL-A1-06, Real

| Part | Content |
| --- | --- |
| Screen 1 (incident workspace) | The similar-incident match (resolves SL-O-08): PI-1001 "Subscriber DB /data full after archive catch-up run", labelled "Fictional record", similarity score 1.00, recorded success count of its fix 4. The proposed action: "Purge archived transaction logs". |
| Screen 2 (Teams, channel "Approvals — Usman") | The approval request message with the Approval link. The same request reached "Approvals — Farah", because every eligible approver receives it (PM-M14-04). |
| Operator | Copies the Approval link into the phone window, logs in as `dbops-engineer`, reads out the page, presses Approve by 09:30 at the latest (SB-TL-08). Logs out. |
| Approval page (exact values, SL-A1-06 complete list) | Action name: Purge archived transaction logs. Exact parameters: `path` = /data/txlog_archive; `older_than_minutes` = 1440; `max_bytes` = 12884901888. Target server: Subscriber DB server (lab-db-01). Limits (resolves SL-O-09): deletes only *.arc files directly under /data/txlog_archive; deletes only files last modified more than 24 hours ago; deletes at most 12 GiB, oldest files first; runs only on the Subscriber DB server (lab-db-01); attempt 1 of 1. Expiry: 900 s after the request was created. Evidence the proposal is based on: the three diagnostic results, the AI investigation and the similarity match. Recovery checks that will be used: "Subscriber DB services recovered" — every alert of the incident resolved at its source, and no alert of Synthetic USSD sessions firing — held for 60 s. |
| After Approve | Vigil rechecks (complete list, SL-A1-06): approval not expired; action, parameters and target unchanged; incident still open; precondition "/data used percentage ≥ 90" true (value 100). Then it runs the action on `lab-db-01`. The action result is added as evidence: every injected file removed (count and GiB measured, SB-O-03). |
| Narrator | "The engineer approved this exact command, with these parameters, on this server, until this time. Techfinity checked again before running it." |

#### SB-S07 Recovery check (09:45–12:00) — SL-A1-07, Real

| Part | Content |
| --- | --- |
| Screen | The incident workspace. The workflow graph highlights "Verify recovery". The alerts turn resolved one after another (by 10:45, SB-TL-09). |
| Narrator | "The command succeeded, but that is not why this incident will close." |
| Exact values (resolves SL-O-01) | Observation window 60 s. The recovery evidence shows both conditions holding and both monitoring sources healthy. The incident becomes Resolved by 11:50 (SB-TL-10). |
| Screen 2 (Jira tab) | The ticket status is resolved and its last comment holds the recovery evidence. |
| Narrator | "Techfinity closes an incident only when monitoring confirms recovery, including a real test USSD session." |

#### SB-S08 Workflow glimpse (12:00–12:30) — SL-A1-08, Real

| Part | Content |
| --- | --- |
| Screen | The read-only workflow graph of the storm incident's run: Incident created or reopened → Severity → Check the DB server → AI investigation → AI summary → Create Jira ticket → Notify owner, informed and NOC L1 → Wait for acknowledgement → Find similar past incident → DB Ops approval → Run approved fix → Verify recovery → Resolve Jira ticket, with the run finished. |
| Narrator | "This is the workflow the incident followed. It is not code; your team can change it. The highlight followed every step live, within 2 seconds." |

#### SB-S09 Dashboard (12:30–13:30) — SL-A1-09, Simplified

| Part | Content |
| --- | --- |
| Screen | The dashboard: four metrics, each next to its baseline labelled "Illustrative baseline". |
| Values (resolves SL-O-10) | Data source: every live incident of the current demo run that has a number, plus the 50 seeded fictional incidents (DM-R-53); no time filter is applied, and the seeded incidents lie in the 30 days before the reset. After SB-S07 the counted incidents are 52 (storm, report, 50 seeded). Exact values: alerts suppressed 650 (575 seeded + 127 live alerts − 52 counted incidents); incidents merged 47 (46 seeded + the storm incident; the report incident has 1 alert). Time to acknowledge 5.7 min: (284 seeded minutes + the storm incident's 5.75 to 6.75 minutes) ÷ 51; the report incident is not acknowledged. Time to recover 40.5 min: (2,056 seeded minutes + the storm incident's 8.8 to 10.8 minutes) ÷ 51; the report incident is not resolved. Baselines (SB-V-39): 11.0 min; 72.0 min; 0; 0. |
| Narrator | "Over the last 30 days of fictional history plus today's incidents, time to acknowledge and time to recover against a typical manual baseline; and how many alerts never became separate tickets." |

#### SB-S10 Close (13:30–14:00)

| Part | Content |
| --- | --- |
| Narrator | "One incident instead of 126 alerts, evidence collected directly, an AI explanation with stated uncertainty, a fix approved and verified, and every step recorded. We can go deeper on any part." |
| Operator | Nothing. |

## 8. Deep dives (SL-A2), shown after the primary demo on request

Deep dives are optional. They run in the order below; any subset MAY be shown, keeping that order and the rules of Section 9. Each has a time budget; SB-T-10 checks SB-D02's.

### SB-D01 Guided setup — SL-A2-01, Real (budget 3:00)

1. The main window logs in as `admin` and opens the setup screen.
2. The admin opens the Zabbix source, which the seed import created and connected, shows its address and tests it: "Passed"; health Healthy. Nothing is saved.
3. The admin changes the Alertmanager address to a wrong one, saves and tests: "Failed" with the reason; health becomes Disconnected and the readiness gaps show "Alertmanager — Jazz lab: source not healthy". The admin restores the correct address, saves and tests: "Passed"; health Healthy and the gap disappears. Stale is explained by the narrator with the thresholds of SB-V-11 and is not provoked. The only incident observing recovery, the report incident, still has its alert firing, so the Disconnected source changes no outcome (DM-R-36).
4. The admin imports the service map file (DD-F-02, the same content) and sees the counts: 4 services, 12 components, 15 identity keys, 6 dependencies.
5. Readiness gaps (exact values, DD-K-12): "Reporting server (srv-rpt-01): server with no owner"; "Management Reports (svc-mgmt-reports): service with no criticality". No source is unhealthy.

Narrator: "Setup tells you what is missing before an incident does."

### SB-D02 Change it live, and nobody acknowledges — SL-A2-06 with SL-A2-03, Real (budget 18:00)

Precondition: the storm incident of the primary demo has been resolved for at least 330 s before step 6 (SB-R-03).

1. **Edit the workflow.** In the workflow editor the admin starts a draft from version 1, drags an Approval step from the palette onto the canvas between "DB Ops approval" and "Run approved fix", connects it (approved → Run approved fix; rejected and expired → Escalate with evidence; the first approval's approved output now leads to the new step), sets its eligible team to Database Operations and its expiry to 900 s, and labels it "DB Ops second approval". While doing so the admin also shows (complete list): zoom and pan to the approval area, then "Fit to screen" (SL-WE-06); selecting the new step and moving it, then "Auto layout" (SL-WE-03, SL-WE-06); deleting a connection by mistake and pressing Undo, then Redo and Undo again so the connection is back (SL-WE-03, SL-WE-05). The admin then opens "Wait for acknowledgement" and changes its timer from 600 s to 120 s. The draft is saved; version 1 stays active (DD-F-06 `draft_changes_for_version_2`).
2. **Change a routing rule (resolves SL-O-21).** On the routing-rules screen the admin adds Customer Care to the informed teams of the rule "Critical service affected" and saves. The storm incident's severity and owner team are unchanged by this rule change (DD-K-05).
3. **Test before publishing.** The admin runs a test replay of the primary demo's storm incident against the draft. The result is labelled "Test". Exact values: the path takes the P1 branch; the Notification step's note lists the teams `customer-care`, `db-ops`, `noc-l1` and `vas-service-owners` (DM-R-49; wording `40-demo-cut` DC-TX-09); both Approval steps show "Simulated approval", the second with the rule that its approver must differ from the first; the routing dry run uses the routing rules saved in step 2. Varies: the Escalation step shows one or two simulated escalations (NOC Shift Lead, then NOC Management), because the storm incident was acknowledged between 135 s and 365 s after its wait started (the simulated clock reaches the Escalation step no later than the real wait start, because Ticket and Notification steps add 0 s, DM-R-47, so the simulated gap is at least as long); acceptance: at least one simulated escalation, to NOC Shift Lead. No ticket, message, AI call or command happens (`01-scope-lock` Section 3).
4. **Publish.** The admin publishes the draft as version 2.
5. **Clear the report fault.** The operator presses "Clear all faults" in the control panel and waits until it is complete. The report incident resolves (its recovery check passes) and its Jira ticket is resolved.
6. **Storm re-run.** The operator presses "Storm re-run: SL-F-1". A new storm incident opens and follows version 2. Its Notification step also informs Customer Care (Teams channel "Customer Care").
7. **Nobody acknowledges (SL-A2-03, resolves SL-O-14).** Nobody acknowledges. 120 s after its wait started, the incident escalates to level 1, NOC Shift Lead: a message with the evidence summary in the channel "NOC Shift Lead", the escalation on the incident and a comment in the Jira ticket. 120 s later it escalates to level 2, NOC Management, in the same way. Level 2 is the last level, so the step then continues to the similar-incident search.
8. **Two approvers.** The approval request reaches Usman and Farah. The operator approves as `dbops-engineer` in the phone window; the operator gives the second approval within 10 minutes of the first request, so the first approval is still valid at execution (DM-R-32); the second approval request then reaches Farah only, because the second approver must differ; the operator approves as `dbops-lead`. The action runs, the alerts resolve and the incident is resolved as in the primary demo.
9. **Version history and rollback.** Version history shows version 1 (seed) and version 2 published by Platform Administrator with its time and change list, in this order (`40-demo-cut` DC-TX-08): added "DB Ops second approval"; changed "Wait for acknowledgement" (timer 600 s → 120 s); changed "DB Ops approval" (outgoing connections). The admin rolls back to version 1 in one step, which creates and activates version 3 with the content of version 1 (SL-B-03).

### SB-D03 Cloud AI unavailable — SL-A2-05, Simplified (budget 4:00 plus the local model's answer time)

Simplified scope (SB-D-11): fallback and "AI investigation unavailable" are shown with re-runs on the primary demo's storm incident. That routing continues by rules without AI is shown by the incident's severity, owner and informed teams, which were set by routing rules before any AI step and do not change; a new storm during SL-F-4 is not run in the scene (it is tested by SB-T-09).

1. The operator presses "Cut cloud AI: SL-F-4".
2. On the AI settings screen the admin removes the local model from the SL-T-1 order, leaving Claude, then OpenAI, and saves.
3. In the primary demo's storm incident the admin presses "Re-run AI investigation". Claude and OpenAI are unreachable; the incident shows "AI investigation unavailable" with the reason no provider reachable, next to the earlier accepted result (DM-E68-R1). Severity, owner and informed teams are unchanged: routing works by rules without AI.
4. The admin restores the SL-T-1 order Claude, OpenAI, local model, and re-runs. Claude and OpenAI are unreachable and the local model answers; the result shows the local model as its provider. The AI usage log shows, for this re-run: Claude unreachable, OpenAI unreachable, local model succeeded.
5. SL-T-3 is not affected: it uses the local embedding model and has no fallback (CTX-D-016).

### SB-D04 Keep data in-house — SL-A2-07, Real (budget 2:00 plus the local model's answer time)

SL-F-4 stays active.

1. The admin sets SL-T-1 and SL-T-2 to the local model only (SL-T-3 already uses the local embedding model only) and saves (DM-R-45).
2. The admin re-runs the AI investigation of the primary demo's storm incident; the local model answers.
3. The AI usage log, filtered to this re-run, shows only in-house providers and no request to an external provider.

### SB-D05 AI settings — SL-A2-02, Real (budget 2:00)

1. The AI settings screen shows, per AI task, the chosen provider and fallback order and the allowed providers (SL-T-1 and SL-T-2: Claude, OpenAI, local model; SL-T-3: local embedding model, OpenAI).
2. Data routing: the admin sets the category "Incident history" to in-house providers only and saves.
3. Usage and cost per provider and per task: the calls of this demo run so far, with cost for Claude; OpenAI shows only unreachable calls (SB-D03) with cost 0; the local models show cost 0. Claude's cost varies (prices DM-O-13, Round 5).
4. The operator presses "Clear all faults" (SL-F-4 ends).

### SB-D06 Audit trail — SL-A2-08, Real (budget 2:00)

The audit trail holds every event of DM-E08-R1; the narrator points out a selection. Filtered by the primary demo's storm incident it includes: incident created; alerts grouped (126 events); the acknowledgement by Hira Baig (NOC L1); approval requested; approved by Usman Tariq (DB Ops); action executed; recovery verified; incident resolved. Filtered by the storm incident of SB-D02 it includes: escalations to NOC Shift Lead and NOC Management; two approval requests; approved by Usman Tariq (DB Ops); approved by Farah Naqvi (DB Ops Lead). Filtered by event type "configuration changed" it includes: the service map import and the source address changes of SB-D01; the workflow published as version 2 and the rollback that created version 3; the routing rule change; the AI settings changes of SB-D03 to SB-D05. Each entry shows who and when.

### SB-D07 The fix does not work — SL-A2-04, Real (budget 12:00; always last)

1. The operator presses "Reset" and waits until it is complete (60 s or less); the main window and the phone window log in again (after every reset the earlier run's sessions are refused, `50-architecture` AR-B-02).
2. The operator presses "Storm with read-only DB: SL-F-1 + SL-F-2". The storm incident opens as in the primary demo.
3. The operator acknowledges as `noc-l1` and approves as `dbops-engineer`. The action runs and frees the space: the 2 disk_full alerts resolve, the other 124 keep firing.
4. "Verify recovery" waits up to its maximum of 300 s (resolves PM-O-08), then leaves by "not recovered". The narrator: "The command worked. The service did not recover, so the incident stays open."
5. "Escalate with evidence" escalates at once to level 1, NOC Shift Lead, with the evidence summary (Teams, email, the incident and a Jira comment). The incident stays Open. No second attempt is made: the action's attempt limit is 1 (resolves SL-O-15).

After SB-D07, SL-F-1 and SL-F-2 stay active in the control panel (SL-F-1's files were removed by the action) until the next reset.

## 9. Demo order, reset and repeatability

| ID | Rule | Trace |
| --- | --- | --- |
| SB-R-01 | Each presentation starts from a reset (SB-SET-07). | SL-A3-03 |
| SB-R-02 | The primary demo (SB-S00 to SB-S10) runs first. Deep dives run after it in the order SB-D01 to SB-D07; any subset MAY be shown in that order. | CTX-D-007 |
| SB-R-03 | A storm re-run (SB-D02 step 6) starts only when every earlier storm incident of the run has been resolved for at least 330 s (the 300 s reopen window plus 30 s margin), so it creates a new incident and does not reopen the earlier one (DM-R-24 a). The operator checks the earlier incident's resolved time before pressing the button. | DM-R-24 |
| SB-R-04 | A storm button is pressed only after "Clear all faults" or a reset has completed, so no lab alert is still firing (DM-R-24 d). The control panel refuses storm buttons while SL-F-1, SL-F-2 or SL-F-3 is active; SL-F-4 does not produce lab alerts and does not block them (amendment SB-A-08). | DM-R-24 |
| SB-R-05 | SB-D07 is always last, because its incident stays open (DM-R-24 b, c), and it starts with a reset. | DM-R-24 |
| SB-R-06 | Repeatability (SL-A3-04): from a reset, the primary demo gives on every run (complete list): one storm incident with 126 alerts and one separate report incident; hypothesis Subscriber DB server; severity P1 and owner Database Operations; best match PI-1001 with score 1.00; the action executed once; the storm incident resolved; the Jira ticket resolved. | SL-A3-04 |

## 10. Demo values (complete list of values fixed in this round)

| ID | Open item resolved | Value | Where |
| --- | --- | --- | --- |
| SB-V-01 | SL-O-01 | Observation window 60 s (both recovery check definitions). | DD-F-04 |
| SB-V-02 | SL-O-02 | SL-F-2: the mock database stays in read-only mode after space is freed. | Section 6; DD-F-10 |
| SB-V-03 | SL-O-03 | The script and timing of Section 7. | Section 7 |
| SB-V-04 | SL-O-04 | Grouping window 300 s. | DD-F-07 |
| SB-V-05 | SL-O-05 | Report incident: P4, owner IT Applications, no informed teams; Jira ticket and a message to IT Applications; no diagnostics, approval, action or escalation. | SB-S02; DD-F-05, DD-F-06 |
| SB-V-06 | SL-O-06 | Data path /data; the 3 largest folders; recent period 15 minutes. | DD-F-04 |
| SB-V-07 | SL-O-07 | Storm incident: P1, owner Database Operations, informed VAS Service Owners. | DD-F-05 |
| SB-V-08 | SL-O-08 | Best match PI-1001, score 1.00 (2 decimal places), success count 4; action `purge-archived-txlogs` with `path` /data/txlog_archive, `older_than_minutes` 1440, `max_bytes` 12884901888. | DD-F-04, DD-F-09 |
| SB-V-09 | SL-O-09 | Approval expiry 900 s; the four limit texts of DD-F-04 plus the attempt limit. | DD-F-04, DD-F-06 |
| SB-V-10 | SL-O-10 | Dashboard data source: live incidents of the current demo run plus the 50 seeded incidents; no time filter (DM-R-53). | SB-S09; DM-R-52, DM-R-53 |
| SB-V-11 | SL-O-12 | Stale after 120 s without contact; disconnected after 300 s without contact or after a failed test. | DD-F-03 |
| SB-V-12 | SL-O-14 | Escalation levels: 1 NOC Shift Lead, 2 NOC Management; timer 600 s in version 1, 120 s in version 2. | DD-F-05, DD-F-06 |
| SB-V-13 | SL-O-15 | Attempt limit 1. | DD-F-04 |
| SB-V-14 | SL-O-16 | SL-F-4 blocks the Claude API and OpenAI API host names only. | Section 6; DD-F-10 |
| SB-V-15 | SL-A1-05 recipients | The storm Notification step sends to the owner team, the informed parties and NOC L1 (SB-D-03). | DD-F-06 |
| SB-V-16 | SL-O-18 | Display names of Section 5. | DD-F-01 |
| SB-V-17 | SL-O-21 | Add Customer Care to the informed teams of "Critical service affected". | DD-F-05 |
| SB-V-18 | SL-O-22 | Workflow version 1 of DD-F-06 (19 steps), with the severity Condition step and its P1 branch. | DD-F-06 |
| SB-V-19 | PM-O-02 (values) | Identity key values of DD-F-02. The key names and precedence used, Zabbix [component, host] and Alertmanager [component, instance], are an input to Round 5 (DM-O-04, DD-ASM-01). | DD-F-02 |
| SB-V-20 | PM-O-08 | Recovery check maximum wait 300 s on the P1 branch, 14,400 s on the not-P1 branch. | DD-F-06 |
| SB-V-21 | PM-O-12 (values) | Connection test every 30 s per source. | DD-F-03 |
| SB-V-22 | PM-O-14 (values) | Reopen window 300 s; the demo order of Section 9. | DD-F-07; Section 9 |
| SB-V-23 | PM-O-16 | Minimum similarity 0.90. The report incident's workflow branch runs no similarity search, so it can never receive a proposal; no past incident shares its alert type, service or components (DD-K-09). | DD-F-07 |
| SB-V-24 | DM-O-01 | Settle period 40 s; maximum forming time 300 s; stability check every 5 s. | DD-F-07 |
| SB-V-25 | DM-O-02 | Reopen window 300 s; closure check every 30 s. | DD-F-07 |
| SB-V-26 | DM-O-03 | Health check interval 30 s. | DD-F-03 |
| SB-V-27 | DM-O-07 | The 5 runner operations, their command templates, timeouts and output parsers. | DD-F-04 |
| SB-V-28 | DM-O-09 | Runbook limit 3. | DD-F-07 |
| SB-V-29 | DM-O-10 | Default data routing: every category may go to every provider. | DD-F-07 |
| SB-V-30 | DM-O-14 | Default recovery check: every alert of the incident resolved, held for 60 s. | DD-F-04 |
| SB-V-31 | SL-O-20 (proposal) | SL-T-1 and SL-T-2: Claude, then OpenAI, then the local model. SL-T-3: the local embedding model (CTX-D-011). Confirmed or changed in Round 5 (SB-O-01). | DD-F-07 |
| SB-V-32 | Demo users and teams | 5 users, 9 teams, memberships and permissions of DD-F-01. | DD-F-01 |
| SB-V-33 | Service map | 4 services, 12 components, 15 identity keys, 6 dependencies. | DD-F-02 |
| SB-V-34 | Storm size | 126 alerts (31 Zabbix, 95 Alertmanager) plus 1 SL-F-3 alert. | DD-F-03 |
| SB-V-35 | Alert types | The 18 types of DD-F-03. | DD-F-03 |
| SB-V-36 | Runbooks | 7 runbooks (DD-F-08); the storm gets RB-API-01, RB-DB-01, RB-USSD-01. | DD-F-08 |
| SB-V-37 | Past incidents | 50 records (DD-F-09). | DD-F-09 |
| SB-V-38 | Seeded dashboard (starting state) | Time to acknowledge 5.7 min; time to recover 41.1 min; alerts suppressed 525; incidents merged 46. | DD-K-11 |
| SB-V-39 | Baselines | Time to acknowledge 11.0 min; time to recover 72.0 min; alerts suppressed 0; incidents merged 0; each labelled "Illustrative baseline". | DD-F-07 |
| SB-V-40 | Environment label and customer name | "Demo environment — fictional data"; customer name Jazz (CTX-O-05). | DD-F-01 |
| SB-V-41 | PM-O-03 (Round 3 part) | Workflow version 1 needs no extra step to wait for a stable group: the engine holds every live run while its incident is forming (DM-R-16, DM-A-14); the assignment is frozen when the incident opens (DM-R-17). | DD-F-06 |
| SB-V-42 | PM-O-15 (Round 3 part) | The seeded history fields are those of DD-F-09: opened, acknowledged and recovered times as offsets, alert count, alert types, services, hypothesis component, fix and success count; one set of 50 records serves the dashboard and similar-incident search (DM-R-52). | DD-F-09 |

## 11. Tests that need running software (complete list)

These are carried into the build plan (Round 6). Each proves an assumption or a value of this round.

| ID | Test | Pass condition | Proves |
| --- | --- | --- | --- |
| SB-T-01 | Lab storm timing and values | 10 storms in a row from a cleared lab, each meeting every timing target of DD-F-10 (126 firing entries, each alert exactly once, all within 120 s, no gap above 25 s, both disk_full alerts included; resolves within 60 s after the action; no re-firing within 300 s). The spike records the measured values of DD-F-10. | DD-ASM-02, DD-ASM-03, DD-ASM-05, SL-ASM-01, DM-ASM-05; SB-TL-01, SB-TL-09 |
| SB-T-02 | Primary demo repeatability | 5 runs from a reset, driven through Vigil's public interface (logging in as `noc-l1` and `dbops-engineer`, links taken from Vigil's notification records), each giving the SB-R-06 result and every exact value of SB-S01 to SB-S09. Jira status is read through the Jira API (read only); Teams and email delivery through Vigil's notification records (`sent`). | SL-A3-04 |
| SB-T-03 | Similarity | With the chosen local embedding model: the storm's best match is PI-1001 with score 1.00 (a tie on the rounded score also goes to PI-1001, DM-E66-R1); the same text gives the same vector twice. | DD-ASM-04, SL-ASM-04 |
| SB-T-04 | Step times | Over 5 runs: diagnostics finish within 15 s after the incident leaves `forming`; SL-T-1 and SL-T-2 with Claude, ticket creation and message sending finish within 60 s after that; the action ends within 15 s after Approve (rechecks included). The local model's SL-T-1 answer time is recorded for CTX-O-04. | SB-TL-03, SB-TL-04, SB-TL-08 |
| SB-T-05 | Reset and clear | "Clear all faults" resolves every lab alert within 40 s and completes within 52 s; a full reset, whose lab clear runs in parallel with the Vigil reset (DD-F-10, SB-D-12), completes within 60 s, 5 times in a row, including after SB-D07 (SL-F-1 and SL-F-2 active). | SL-A3-03; DM-ASM-08 |
| SB-T-06 | Teams posting | Through the route Round 5 chooses (Section 13, item 5), a message with line breaks and a link arrives in each of the 9 demo channels, and the link can be copied from the Teams desktop client. | SL-ASM-02 |
| SB-T-07 | Jira | On the demo project: create an issue with a label; find it by that label through `/rest/api/3/search/jql`; add a comment; move it to resolved; move it back to open. | PM-ASM-03, DM-ASM-07, DM-ASM-09 |
| SB-T-08 | Rehearsal | Two full rehearsals of the primary demo, each 15:00 or less, every scene within its budget ±30 s. | SL-A1 |
| SB-T-09 | Cloud AI cut | On 3 different days, with SL-F-4 active for 10 minutes: every Claude and OpenAI call fails as unreachable within 5 s; a re-run falls back to the local model; Jira, Teams, email and webhooks still work. Once: with SL-F-4 active and the SL-T-1 order set to Claude and OpenAI only, a storm re-run gives an incident with severity P1, owner Database Operations and informed VAS Service Owners, shows "AI investigation unavailable", and still sends its ticket and messages (SL-A2-05 "continue routing by rules"). | SB-ASM-03, PM-ASM-04, SL-A2-05 |
| SB-T-10 | Deep dive timing | One rehearsal of SB-D02: the escalations happen 120 s apart, the second approval request reaches only `dbops-lead`, and the scene ends within its 18:00 budget. | SL-A2-03, SL-A2-06 |

## 12. Public Jazz facts used (SL-DR-02)

None. No USSD short code, product name, bundle name or other public Jazz fact appears in the demo or the data (owner decision SB-D-06). Services are named by function. Adding a public fact later requires its public source and a change under SL-CR-02.

## 13. Fact check of external products (CTX-Q-6)

Checked on 10 October 2026 against official documentation or the vendor's own published code. "Confirmed" means the documentation states it; the build tests named in Section 11 still prove it in the lab.

| # | Claim used by this round | Result | Source |
| --- | --- | --- | --- |
| 1 | Alertmanager `group_by: ['...']` sends every alert as its own group; `group_wait`, `group_interval`, `repeat_interval` behave as DD-F-10 uses them; webhook `send_resolved` defaults to true; each alert in a webhook message carries `status`, `labels`, `startsAt`, `endsAt` and `fingerprint`. | Confirmed. Supports SL-ASM-01 (design), DM-ASM-01 and DM-ASM-02 for Alertmanager, DM-ASM-05. | [Alertmanager configuration](https://prometheus.io/docs/alerting/latest/configuration/) |
| 2 | Alertmanager offers read-only API paths usable for a connection test and for the control panel's clear check. | Confirmed: `/api/v2/status` and `/api/v2/alerts`. The API defines no authentication of its own; protecting it is a Round 5 deployment choice. Supports PM-ASM-02 for Alertmanager. | [Alertmanager API v2 (OpenAPI)](https://raw.githubusercontent.com/prometheus/alertmanager/main/api/v2/openapi.yaml) |
| 3 | Prometheus alerting rules: `for` holds an alert pending before it fires; without `keep_firing_for` an alert stops firing at the first evaluation where its condition is false. Prometheus resends active alerts to Alertmanager at most every `--rules.alert.resend-delay` (default 1 minute); Alertmanager does not notify the receiver again before `repeat_interval`. | Confirmed. Supports "no repeated firing entry" (DM-R-02). | [Alerting rules](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/); [Prometheus flags](https://prometheus.io/docs/prometheus/latest/command-line/prometheus/) |
| 4 | A Zabbix webhook media type runs a script that posts a custom JSON message with custom headers (Authorization); `{EVENT.ID}` identifies the same problem in its problem and recovery notifications; `{EVENT.VALUE}` is 1 for a problem and 0 for a recovery; `{EVENT.TAGSJSON}` carries the event tags; recovery messages can use `{EVENT.RECOVERY.DATE}` and `{EVENT.RECOVERY.TIME}`; recovery operations send the recovery to the same media type. | Confirmed from the webhook integrations Zabbix ships (Opsgenie and Slack templates, release 7.0): the Opsgenie script closes the alert it created by `{EVENT.ID}` on a recovery. Supports DM-ASM-01 and DM-ASM-02 for Zabbix. Note: Zabbix date and time macros are in the Zabbix server's time zone; the lab runs in UTC (DD-F-10). The Zabbix manual pages could not be read by the fetch tool; the escalation behaviour of "steps 1-1, no repeat" remains to be shown by SB-T-01. | [Zabbix Opsgenie webhook](https://raw.githubusercontent.com/zabbix/zabbix/release/7.0/templates/media/opsgenie/media_opsgenie.yaml); [Zabbix Slack webhook](https://raw.githubusercontent.com/zabbix/zabbix/release/7.0/templates/media/slack/media_slack.yaml) |
| 5 | Microsoft Teams can receive messages with links without a bot (SL-ASM-02). | Changed facts: Microsoft disabled Office 365 connectors in Teams between 18 and 22 May 2026. Microsoft's recommended replacement is Workflows webhooks (the "Send webhook alerts to a channel" workflow), which post as the Workflows (Flow bot) identity and support standard, shared and private channels. Each channel needs its own workflow and webhook address, and the address works as a secret (DD-O-04). SB-T-06 still proves line breaks and copyable links. | [Microsoft 365 Developer Blog: Retirement of Office 365 connectors within Microsoft Teams (updated 14 April 2026)](https://devblogs.microsoft.com/microsoft365dev/retirement-of-office-365-connectors-within-microsoft-teams/); Message center MC1181996 as mirrored at [mc.merill.net](https://mc.merill.net/message/MC1181996) |
| 6 | Jira Cloud: create issues with labels, comment, transition, and find an issue by label through the REST API. | Partly confirmed: the old search endpoint `/rest/api/3/search` has been removed from Jira Cloud; search uses `/rest/api/3/search/jql`, paged by `nextPageToken`. Creating, commenting and transitioning were not readable from the reference pages; they stay assumptions PM-ASM-03 and DM-ASM-07, tested by SB-T-07. | [Atlassian KB: JQL search with the Jira Cloud REST API](https://confluence.atlassian.com/display/JIRAKB/Run+JQL+search+query+using+Jira+Cloud+REST+API) |
| 7 | Anthropic offers no embeddings API (SL-ASM-03). | Confirmed: "Anthropic does not offer its own embedding model." | [Claude Platform docs: embeddings](https://platform.claude.com/docs/en/build-with-claude/embeddings) |
| 8 | GNU `df --output=pcent` is the used percentage; GNU `find -cmin` tests the status change time; a file's status change time changes on any write or inode change (so a file created and then back-dated by `touch` keeps a current change time); `fallocate` reserves space without writing data. | Confirmed. The manual describes it as "percentage of used divided by size" without stating the rounding, and implementations divide by used plus available space; so the design leaves 64 MiB free (about 99.6% used whichever size is used), which shows 100 whether rounded or rounded up, and the precondition threshold is 90. | [GNU df](https://www.gnu.org/software/coreutils/manual/html_node/df-invocation.html); [GNU find age tests](https://www.gnu.org/software/findutils/manual/html_node/find_html/Age-Ranges.html); [inode(7)](https://man7.org/linux/man-pages/man7/inode.7.html); [fallocate(1)](https://man7.org/linux/man-pages/man1/fallocate.1.html) |

Not checked in this round (Round 5 chooses the products): the local model runtime, its token reporting (DM-ASM-06) and the local embedding model's determinism (SL-ASM-04, SB-T-03).

## 14. Explicitly out of scope for this document

Screen layouts and wording (Round 4). Technology stack, connector field names, seed bundle format, reset mechanism (SL-O-17), hosting and AI model names and prices (Round 5). Lab implementation and build packages (Round 6).

## 15. Decisions, changes to earlier documents and assumptions

### 15.1 Decisions made in this round

Status "Decided by owner" means Abdul gave the answer himself. "Agreed by owner" means Abdul agreed the decision under CTX-Q-7 on 10 October 2026. "Proposed" means Claude made the decision within the scope lock and earlier documents; Abdul MAY change any of them through the `00-context` decision log.

| ID | Decision | Reason | Status | Trace |
| --- | --- | --- | --- | --- |
| SB-D-01 | Demo setup: Microsoft Teams meeting; the senior manager narrates; Abdul operates every screen; mobile pages in a phone-size browser window; attendees do not join a demo Teams channel. | Owner answers (CTX-O-03). | Decided by owner | CTX-O-03, SL-X-16 |
| SB-D-02 | The storm is a catch-up run of archive shipping that suddenly fills `/data` on the Subscriber DB server; the fix is the registered purge of archives older than 24 hours. | It explains a sudden fill (what the growth diagnostic shows), keeps every injected file eligible for the purge, and matches the demo document's story of archived logs filling the disk. | Agreed by owner | SL-A1-01, SL-A1-06 |
| SB-D-03 | The storm Notification step also informs the informed parties (VAS Service Owners), besides the owner team and NOC L1. | Informed parties are told about the incident; this also makes the SL-A2-06 routing change visible (Customer Care is notified). SL-A1-05 requires NOC L1 and the owner team and does not exclude others. | Agreed by owner | SL-A1-05, SL-A2-06 |
| SB-D-04 | The not-P1 branch of workflow version 1 creates a ticket, notifies only the owner team and waits up to 4 hours for recovery; only if that wait ends does it escalate with evidence (PM-WV-04). | A P4 incident pages nobody during the demo; the report incident stays quiet and resolves when its fault is cleared. | Agreed by owner | SL-O-05, PM-WV-03, PM-WV-04 |
| SB-D-05 | The recent-growth diagnostic measures data written (files with a recent change time) instead of filesystem statistics history. | No dependency on sysstat sampling; deterministic; checked against GNU documentation (Section 13 item 8). | Proposed | SL-O-06 |
| SB-D-06 | No public Jazz fact is used; all names are invented and generic. | Owner decision; a wrong fact in front of Jazz would damage CTX-G-1. | Decided by owner | SL-DR-01, SL-DR-02 |
| SB-D-07 | Deep dive order SB-D01 to SB-D07, with SB-D07 always last and starting with a reset. | Meets DM-R-24 without control-panel logic that reads Vigil. | Proposed | DM-R-24, SL-A3-03 |
| SB-D-08 | A lab Reporting server running a mock report export job produces SL-F-3, and the SMS queue mock includes its dispatcher (SB-A-02). The Reporting server is a new lab element; its build effort is small (one host, one status file, one Zabbix item). The owner decides whether it is recorded as a clarification of SL-A3-01 or under SL-CR-02. | SL-F-3 needs a component unrelated to every storm component (DM-R-14); none of the six SL-A3-01 services is unrelated to the database. | Agreed by owner | SL-A3-01, SL-A3-02, SL-CR-02 |
| SB-D-09 | The control panel has a "Clear all faults" button, used before a storm re-run and inside reset. This is a clarification of SL-A3-03 (SB-A-03). | DM-R-24 (d) requires every lab alert to be resolved before a storm re-run; clearing faults is the inverse of triggering them. | Agreed by owner | SL-A3-03, DM-R-24 |
| SB-D-10 | Baselines for "alerts suppressed" and "incidents merged" are 0, meaning that without correlation every alert is its own ticket. | The baseline then states one clear assumption; it is labelled illustrative. | Proposed | SL-A1-09 |
| SB-D-11 | SB-D03 is Simplified: fallback and "AI investigation unavailable" are shown with re-runs; "continue routing by rules" is shown by the unchanged rule-based assignment and proved by SB-T-09 with a storm during SL-F-4. | A full storm under SL-F-4 would add 8 minutes or more and an open incident to the deep dives. | Agreed by owner | SL-A2-05 |
| SB-D-12 | A reset clears the lab faults in parallel with the Vigil reset; both together take 60 s or less. Clear targets: alerts resolved within 40 s, complete 12 s later. Input to SL-O-17 (Round 5). | The clear alone uses most of the 60 s; in sequence there would be no time left to seed Vigil. | Agreed by owner | SL-A3-03, DM-R-24, SL-O-17 |
| SB-D-13 | Seven teams without members (IT Applications, IT Infrastructure, VAS Operations, VAS Service Owners, NOC Shift Lead, NOC Management, Customer Care) are seeded as configuration data: component and service owners, informed parties and escalation levels. This is a clarification of SL-A3-07 and DM-E-04 (SB-A-06). | Routing needs owner teams for components and services; SL-A3-07 lists only the teams that have demo users. | Agreed by owner | SL-A3-07, SL-B-09 |

### 15.2 Changes to earlier documents (complete list)

Written on 10 October 2026 when the owner closed this round (CTX-D-018).

| ID | Document and item | Change |
| --- | --- | --- |
| SB-A-01 | `20-domain-model` DM-E-16 `alert_type` meaning | "Demo set: the seven types of SL-A1-01 plus the SL-F-3 type (values Round 3)" becomes "Demo set: the 18 types of `31-demo-data` DD-F-03: the seven types of SL-A1-01, the SL-F-3 type, and ten types used only by past incidents". |
| SB-A-02 | `01-scope-lock` SL-A3-01 | Clarification, no item added to list A: the SMS queue mock includes its SMS dispatcher, and a mock report export job on a lab Reporting server exists only to produce fault SL-F-3; neither is an additional lab service. Written only if the owner agrees SB-D-08. |
| SB-A-03 | `01-scope-lock` SL-A3-03 | Clarification, no item added to list A: triggering faults includes clearing them ("Clear all faults"), which a storm re-run and a reset use. Written only if the owner agrees SB-D-09. |
| SB-A-04 | `01-scope-lock` Section 11, SL-ASM-02 and SL-ASM-03 "Verified by" | SL-ASM-02: "Route identified in Round 3 (`30-jazz-storyboard` Section 13): Office 365 connectors were disabled in May 2026; Microsoft's replacement is Workflows webhooks. Still to be proved by build test SB-T-06 and the Round 5 design (DD-O-04)." SL-ASM-03: "Confirmed in Round 3 (`30-jazz-storyboard` Section 13)." |
| SB-A-05 | `00-context` | Records the adoption of this round, its decisions, CTX-O-03 closed by SB-D-01, and the Round 3 status. |
| SB-A-06 | `01-scope-lock` SL-A3-07 and `20-domain-model` DM-E-04 `team_key` meaning | Clarification, no item added to list A: further teams without members (component and service owners, informed parties, escalation levels) are configuration data under SL-B-09, listed in `31-demo-data` DD-F-01. Written only if the owner agrees SB-D-13. |
| SB-A-08 | `20-domain-model` DM-R-24 (d) | "every lab fault MUST be cleared" becomes "every lab fault that produces lab alerts (SL-F-1, SL-F-2, SL-F-3) MUST be cleared"; SL-F-4 produces no lab alert, so a storm MAY start while it is active (SB-R-04, SB-T-09). |
| SB-A-07 | `20-domain-model` DM-V-06 `allowed_prefixes` | "the value MUST be an absolute path starting with one of these prefixes" becomes "the value MUST be an absolute path that equals one of these prefixes or starts with one of them followed by `/`", so the prefix `/data` does not admit `/data2`. |

### 15.3 Assumptions

The data assumptions are DD-ASM-01 to DD-ASM-05 (`31-demo-data` README). This document adds (complete list):

| ID | Assumption | Verified by |
| --- | --- | --- |
| SB-ASM-01 | The operator can show the Teams client, the Jira ticket of the current incident and a mail client in one shared screen during a Teams meeting without showing anything other than the demo accounts. | Rehearsal SB-T-08 |
| SB-ASM-02 | Claude as the SL-T-1 and SL-T-2 provider answers within the time of SB-T-04, so SB-TL-04 holds. | SB-T-04 |
| SB-ASM-03 | The SL-F-4 mechanism of DD-F-10 makes every cloud AI call fail within the AI gateway's connect timeout (5 s, Round 5) for as long as SL-F-4 is active, and affects no other traffic. | SB-T-09 |

## 16. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| SB-O-01 | Closed by `50-architecture` AR-D-13 (agreed by owner), 12 Oct 2026 (was: confirmation of the SL-T-1 and SL-T-2 provider order, SB-V-31). | Claude, reviewed by Abdul | R5 (SL-O-20) |
| SB-O-02 | Closed by `40-demo-cut` DC-TX-03, 10 Oct 2026 (was: display wording of the evidence summaries). | Claude | R4 |
| SB-O-03 | Measured lab values (DD-F-10, DD-O-03) shown in SB-S03 and SB-S06. | Abdul | Lab spike SB-T-01, before the first rehearsal |

Open items resolved by this round are listed in Section 10. Carried open items still open: CTX-O-01, CTX-O-02, CTX-O-04, CTX-O-05; SL-O-11 (format), SL-O-17, SL-O-19, SL-O-20; PM-O-01, PM-O-09 (connector capability), PM-O-10 (with DD-O-04), PM-O-11, PM-O-17; DM-O-04 (field names), DM-O-05, DM-O-06, DM-O-08, DM-O-11, DM-O-12, DM-O-13; DD-O-01 to DD-O-04.

## 17. Quality-check results

| Check | Result |
| --- | --- |
| SB-Q-01 Input versions | The repository held `00-context` v1.6, `01-scope-lock` v1.6, `10-product-map` v1.1 and `20-domain-model` v1.0 (restored to commit eded314 on 10 October 2026); these are the inputs. |
| CTX-Q-1 Coverage both ways | Every open item that earlier documents assign to Round 3 is resolved in Section 10 (SB-V-01 to SB-V-42) or carried in Section 16 with its round. Every scene SL-A1-01 to SL-A1-09 and deep dive SL-A2-01 to SL-A2-08 has a scene; the editor capabilities SL-WE-01 to SL-WE-10 are shown in SB-D02 and SB-S08. Items beyond list A and list B found in review are recorded as clarifications for the owner (SB-D-08, SB-D-09, SB-D-13; amendments SB-A-02, SB-A-03, SB-A-06). SB-D03 is Simplified with its narrower scope stated (SB-D-11). |
| CTX-Q-2 Consistency | Compared with `00-context` v1.6, `01-scope-lock` v1.6, `10-product-map` v1.1 and `20-domain-model` v1.0. Conflicts resolved by amendment, not silently: alert type set (SB-A-01); lab elements and the clear button (SB-A-02, SB-A-03); teams without members (SB-A-06); path prefix meaning (SB-A-07); storms while SL-F-4 is active (SB-A-08); assumption status after the fact check (SB-A-04). |
| CTX-Q-3 Scenario walkthrough | Section 7: every scene SL-A1-01 to SL-A1-09 has its script, data (DD files) and defined outcome. |
| CTX-Q-4 Independent review | Done, 10 Oct 2026, on v0.1 by a separate Claude reviewer that read only `docs/` (not `_superseded/`): 32 findings (3 High, 15 Medium, 14 Low), resolved in v0.2. High: (1) `/data/db` (9 GiB) would have stayed the largest folder, so SB-S03 and RB-DB-01 failed on every run — device 16 GiB, `/data/db` 6 GiB, injection about 8.8 GiB, new DD-K-14 check; (2) the report job's next scheduled run would succeed and resolve the report incident during the primary demo — every run fails while SL-F-3 is active; (3) the clear alone used the whole 60 s reset — clear runs in parallel with the Vigil reset (SB-D-12). Medium: schema writers named for all 6 LA-02 alerts; the last injected file back-dated; SL-F-4 changed from a host-name mapping to an outbound reject in Vigil's network namespace; best match moved to the first key PI-1001 so it wins ties; PM-WV-04 met on the not-P1 branch; teams without members recorded (SB-D-13); editor capabilities SL-WE-03, -05, -06 added to SB-D02; audit scene shows the acknowledgement and is a selection; SB-D03 Simplified (SB-D-11) with SB-T-09 storm under SL-F-4; dashboard has no time filter; no purge job in the lab; Teams fact check cites Microsoft; checks strengthened; SB-D01 no longer reconnects Zabbix and shows the source gap; command templates use pipefail and %.0f. Low: banned words, wrong references, PM-O-03 and PM-O-15 recorded (SB-V-41, SB-V-42), grouping-reason acceptance, team keys in the replay note, action timing in SB-T-04, scene budgets, data plausibility, runbook citation rule, path prefix (SB-A-07), key precedence as a Round 5 input, df wording, SB-D07 fault state. Pass 2 (verification) on v0.2: 23 resolved, 9 partly resolved, 9 new findings (0 High, 2 Medium, 7 Low), all resolved in v0.3: SB-T-03 names PI-1001; storms during SL-F-4 recorded as amendment SB-A-08; clear settle 12 s (longer than Alertmanager's 10 s group interval); SL-F-3 inject wording; DD-ASM-03 40 s; SB-D01 observing-incident sentence; SB-D05 scene reference; du parser TAB; quality records; SB-V-10 wording; DD-F-06 comment; replay clock note; success counts of manual fixes explained; df wording. The remaining partly resolved point: the SL-F-4 reject could also block other services that share the AI providers' addresses, and DNS rotation inside the 30 s re-resolve could let a call through; accepted as SB-ASM-03, tested by SB-T-09. |
| CTX-Q-5 Claude Code readiness test | Pass 1, 10 Oct 2026, on v0.1 by a fresh Claude given only `docs/`: 13 questions (9 affecting the primary demo or repeatability), answered in v0.2 (they overlap the review's High and Medium findings) except the owner decisions SB-D-08 and SB-D-09, which are put to owner review. Pass 2 on v0.2: 11 answered, 1 partly answered, 4 new questions (1 affecting repeatability: SB-T-03 named the wrong match), all answered in v0.3. Remaining questions: 0, apart from the owner decisions SB-D-08, SB-D-09, SB-D-11 and SB-D-13. |
| CTX-Q-6 Fact check | Done, Section 13. |
| CTX-Q-7 Owner review | Done, 10 Oct 2026. Seven review items, all agreed as proposed: (1) the storm story and fix, SB-D-02; (2) the Reporting server for SL-F-3, SB-D-08, recorded as a clarification of SL-A3-01; (3) "Clear all faults" and the parallel reset, SB-D-09 and SB-D-12; (4) teams without members, SB-D-13; (5) SB-D03 Simplified, SB-D-11; (6) notification recipients by severity, SB-D-03 and SB-D-04; (7) the key timing values (settle 40 s, escalation timer 600 s and 120 s, approval expiry 900 s, observation window 60 s, maximum wait 300 s, primary demo 14:00). Adoption recorded as CTX-D-018; demo setup as CTX-D-019; scope-lock clarifications as CTX-D-020; Teams route as CTX-D-021. Amendments SB-A-01 to SB-A-08 written. |
| Data checks | `tools/check_data.py`: 47 checks, all pass (DD Section 5). |

## 18. Change history

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 10 Oct 2026 | First draft (Round 3, restarted). |
| 0.2 | 10 Oct 2026 | Resolved independent review pass 1 and readiness test pass 1 (Section 17). |
| 0.3 | 10 Oct 2026 | Resolved verification pass 2 and readiness test pass 2 (Section 17). Draft for owner review. |
| 1.0 | 10 Oct 2026 | Owner review recorded (CTX-D-018 to CTX-D-021); amendments SB-A-01 to SB-A-08 written; Round 3 closed. |
| 1.1 | 10 Oct 2026 | Round 4 amendments (`40-demo-cut` DC-A-04, CTX-D-022, CTX-D-024): narrator lines say "Techfinity"; admin display name "Platform Administrator"; demo Team "Techfinity Demo"; SB-S01 forming display, SB-D02 step 3 wording and step 9 order; SB-O-02 closed. |
| 1.2 | 12 Oct 2026 | Round 5 amendments (`50-architecture` AR-A-14, CTX-D-025): SB-SET-03 items (3) to (5); SB-SET-07 GPU host check; SB-D07 step 1 log-in wording; SB-ASM-01 wording; SB-O-01 closed. |
