# Vigil — Demo Cut

| Field | Value |
| --- | --- |
| Document | 40-demo-cut (ID prefix `DC-`) |
| Version | 1.0 |
| Date | 9 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed). Round 4 closed. |

## 1. Purpose

This document decides, for every module of `10-product-map`, what is built for the demo and how much of it the screens show: Keep, Simplify, Stage or Later. It then defines the content of every screen of `01-scope-lock` list A4 (SL-A4-01 to SL-A4-12), the fixed product wording that screens and outbound messages use, and the content of the Jira tickets, Teams messages and emails that the audience sees. It resolves the open items assigned to Round 4: DM-E32-R3 (how forming incidents are shown), DM-O-11 (incident number format) and SB-O-01 (evidence summary wording), and it carries SB-R-08 (step id naming) into the workflow editor.

The cut is lean (owner direction, Round 4 conversation, 9 October 2026): every module keeps its records and rules, and the screens offer only what list A needs (DC-R-01).

## 2. Inputs read

- `docs/00-context.md` v1.7.
- `docs/01-scope-lock.md` v1.7.
- `docs/10-product-map.md` v1.2.
- `docs/20-domain-model.md` v1.1.
- `docs/30-jazz-storyboard.md` v1.0.
- `docs/31-demo-data/` v1.0 (all files).
- `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md` and `docs/sources/Continuum — Business Solution Document.md` v0.2 (BSD §11.1 workspaces, for background).
- Owner answers given at the start of Round 4 on 9 October 2026: lean cut; text specification only, no wireframes (DC-D-01).

## 3. Terms (complete list)

Terms defined in earlier documents keep their meaning. The terms below are added.

| Term | Meaning |
| --- | --- |
| Keep | The module's built responsibilities are built as written in `10-product-map` and `20-domain-model`, and its screens show what Section 6 defines. |
| Simplify | Built as for Keep, but the screens offer a narrower set of views or operations than the module supports. Every narrowing is listed in Section 4 (DC-N items). A Simplify item never removes a record, rule, state, permission check, validation or public operation of a module (DC-R-01). |
| Stage | Prepared output shown with no product logic (`01-scope-lock` Section 3). |
| Later | Not built now (`10-product-map` Section 3). |
| Main screen | A Vigil screen used in the main window (SB-SET-03): SL-A4-02, SL-A4-03 and SL-A4-06 to SL-A4-11. |
| Mobile page | SL-A4-04 and SL-A4-05. SL-A4-01 is laid out to work both as a main screen and as a mobile page. |
| Region | A named part of a screen, ID `DC-Snn-Rk`. |
| Screen action | A control on a screen that changes something or starts something, ID `DC-Snn-Ak`. Controls that only change the view (tabs, expanding a row, check boxes that hide entries, selecting, scrolling) are defined in their region and are not listed as actions. |
| Product wording | Fixed text defined in Section 7 and 8. It is the same for every customer and every installation (DC-R-05). |
| Seed-only configuration | Configuration that no screen edits; it changes only by seed import (DM-C-07). |

## 4. Module cut

### 4.1 Cut rules (complete list)

| ID | Rule | Trace |
| --- | --- | --- |
| DC-R-01 | **Lean cut.** A narrowing (Simplify) applies only to `vigil-web`: the screens and the operations they offer. The owning modules keep every record, rule, state machine, permission check and validation of `20-domain-model`, and every public operation needed by any screen of the full product MAY be added later without changing a module's records. | CTX-G-2, CTX-C-01; `10-product-map` Section 3 ("Built") |
| DC-R-02 | **Nothing in Vigil is Staged.** The seeded history and the illustrative baselines of the dashboard are seeded data computed by product logic (SB-S09, Simplified); they are not prepared output. Demo-only components (`10-product-map` Section 11.1) are outside Vigil and are not tagged. | `01-scope-lock` Section 3; SB Section 7.1 |
| DC-R-03 | **No built responsibility becomes Later.** This document moves no `Built` responsibility of `10-product-map` to Later (SL-CR-02). The Later items it adds (Section 9) are screen capabilities that no list A item requires. Where a screen offers only part of a built responsibility, the rest stays built through another path that this document names (Example: PM-M22-01 — sources are created by seed import and connected on screen by address, DC-D-06). | SL-CR-02; `10-product-map` Section 3 |
| DC-R-04 | **Editable configuration (complete list).** Screens edit only: the address of an existing monitoring source's connector instance (DC-S07-A2); the service map, by import (DC-S07-A3); the outcome parts of existing routing rules (DC-S08-A1); AI task assignments and data routing rules (DC-S09-A1, DC-S09-A2); workflow drafts, publish and rollback (DC-S06). Every other configuration entity is seed-only. | DM-C-07; SL-B-09 |
| DC-R-05 | **No demo text in the product.** Every text a screen or message shows comes from configuration, seeded data, a record, or the product wording of Sections 7 and 8. No screen contains text written for Jazz or for the demo. | PM-R-08, SL-B-10 |
| DC-R-06 | **Permissions.** A screen MUST hide every control whose permission the logged-in user does not hold (DM-E06-R1); where Section 6 defines a "View only" text, that text is shown instead. Hiding a control is not the permission check; `vigil-api` checks every call (PM-R-09). | SL-B-13; PM-M23-02 |

### 4.2 Cut per module (complete list)

"Built" lists every built responsibility of the module (`10-product-map` Sections 6 and 19). "Narrowing" lists, completely, what the screens do not offer although the module supports it. Later items already in `10-product-map` stay Later and are not repeated here, except by ID.

| ID | Module | Cut | Built (complete list) | Narrowing (complete list) | Later carried | Trace |
| --- | --- | --- | --- | --- | --- | --- |
| DC-M01 | `vigil-tenancy` | Keep | PM-M01-01, PM-M01-02, PM-M01-03 (PM-M01-03 stays subject to the SL-O-17 decision in Round 5) | None. Tenant settings have no screen and are seed-only (DC-R-04). | PM-M01-L1, PM-M01-L2 | SL-B-01, SL-A3-06 |
| DC-M02 | `vigil-identity` | Simplify | PM-M02-01 to PM-M02-04 | DC-N-01: the login screen offers username and password only: no password change, no password reset, no "remember me". | PM-M02-L1, PM-M02-L2 | SL-A4-01, SL-C-06 |
| DC-M03 | `vigil-audit` | Simplify | PM-M03-01 to PM-M03-03 | DC-N-02: the audit trail offers two filters, incident and event type (DC-S11-R1), and one fixed order; the module's user and time-range filters (PM-M03-03) are not offered on screen; no export, no saved filters. | PM-M03-L1 | SL-A2-08 |
| DC-M04 | `vigil-connectors` | Simplify | PM-M04-01 to PM-M04-04 | DC-N-03: of all connector instance fields, a screen edits only the `address` of the connector instance of an existing monitoring source (DC-S07-A2). Creating connector instances, changing credentials and every non-monitoring connector are seed-only. | PM-M04-L1 to PM-M04-L3 | SL-A2-01; DC-D-06 |
| DC-M05 | `vigil-runner` | Keep | PM-M05-01 to PM-M05-04 | None. The runner has no screen; operations and grants are seed-only. | PM-M05-L1, PM-M05-L2 | SL-A1-03, SL-A1-06 |
| DC-M06 | `vigil-intake` | Simplify | PM-M06-01 to PM-M06-06 | DC-N-04: no screen shows raw alerts, webhook calls or normalisation results; alerts are shown only as incident members (DC-S03-R3). Monitoring sources cannot be created on a screen (DC-N-03). | PM-M06-L1, PM-M06-L2 | SL-B-02, SL-A2-01 |
| DC-M07 | `vigil-servicemap` | Simplify | PM-M07-01 to PM-M07-06 | DC-N-05: no service map viewer; the setup screen shows only the current generation's import record and counts (DC-S07-R2). | PM-M07-L1 | SL-A2-01 |
| DC-M08 | `vigil-correlation` | Keep | PM-M08-01 to PM-M08-10 | None. | PM-M08-L1 to PM-M08-L3 | SL-A1-02 |
| DC-M09 | `vigil-incidents` | Simplify | PM-M09-01 to PM-M09-09, PM-M09-11, PM-M09-12 (PM-M09-10 withdrawn) | DC-N-06: the incident list has one fixed order and no sorting, filtering, search or paging (DC-S02-R2). DC-N-07: forming incidents are listed but cannot be opened (DC-D-02). DC-N-08: the workspace shows only the incident's latest live run (DC-S03-R9). DC-N-09: no acknowledge control on the desktop workspace; acknowledging is done on the Acknowledge page (DC-S04). | PM-M09-L1, PM-M09-L2 | SL-A4-02, SL-A4-03 |
| DC-M10 | `vigil-routing` | Simplify | PM-M10-01 to PM-M10-05 | DC-N-10: the routing rules screen edits only the outcome parts of existing rules (`severity`, `owner_mode`, `owner_team_key`, `informed_team_keys`, `inform_affected_service_owners`); rule names, conditions, adding, removing and reordering rules are shown read-only (DC-D-05). DC-N-11: escalation levels and earlier routing rule set revisions have no screen; their changes appear in the audit trail (DC-S11). | PM-M10-L1 to PM-M10-L3 | SL-A2-06, SL-A4-08; SL-O-21 |
| DC-M11 | `vigil-workflows` | Simplify | PM-M11-01 to PM-M11-08 | DC-N-12: a test replay result is shown as a table, not drawn on the canvas; only the latest replay started in the browser session is shown; test step changes are not shown live (DC-S06-R6). DC-N-13: version history shows each version's change list; earlier versions' content is not opened on the canvas or compared side by side (DC-S06-R7). | PM-M11-L1 to PM-M11-L4 | SL-A2-06, SL-WE-09 |
| DC-M12 | `vigil-engine` | Keep | PM-M12-01 to PM-M12-11 | None (DC-N-08 is a screen narrowing of `vigil-incidents` data). | PM-M12-L1, PM-M12-L2 | SL-B-03, SL-B-11 |
| DC-M13 | `vigil-diagnostics` | Keep | PM-M13-01 to PM-M13-03 | None. Diagnostic definitions are seed-only. | PM-M13-L1, PM-M13-L2 | SL-A1-03 |
| DC-M14 | `vigil-actions` | Keep | PM-M14-01 to PM-M14-09 | None. Registered actions and policies are seed-only. The `preauthorized` and `blocked` policy outcomes are built and tested but not shown (SL-C-17). | PM-M14-L1 to PM-M14-L3 | SL-A1-06, SL-B-04 |
| DC-M15 | `vigil-recovery` | Keep | PM-M15-01 to PM-M15-04 | None. Recovery check definitions are seed-only. | PM-M15-L1 | SL-A1-07 |
| DC-M16 | `vigil-knowledge` | Simplify | PM-M16-01 to PM-M16-05 | DC-N-14: runbooks are shown by key, title and version only, with no text viewer; past incidents are shown as one read-only list (DC-S02-R3) with no detail view; knowledge settings are seed-only. | PM-M16-L1, PM-M16-L2 | SL-A1-04, SL-A1-06, SL-DR-03 |
| DC-M17 | `vigil-investigation` | Keep | PM-M17-01 to PM-M17-07 | None. | PM-M17-L1 | SL-A1-04, SL-A2-07 |
| DC-M18 | `vigil-ai-gateway` | Simplify | PM-M18-01 to PM-M18-05 | DC-N-15: AI provider configurations (model name, prices, location) have no screen; provider location appears in the usage log; the usage log has two filters only (DC-S09-R5). | PM-M18-L1, PM-M18-L2 | SL-A2-02 |
| DC-M19 | `vigil-ticketing` | Keep | PM-M19-01 to PM-M19-04 | None. | PM-M19-L1, PM-M19-L2 | SL-A1-05, SL-A1-07 |
| DC-M20 | `vigil-notifications` | Simplify | PM-M20-01 to PM-M20-05 | DC-N-16: no screen lists notification records or delivery addresses; notified teams appear in the incident timeline (DC-S03-R8). Delivery addresses are seed-only. | PM-M20-L1, PM-M20-L2 | SL-A1-05 |
| DC-M21 | `vigil-reporting` | Simplify | PM-M21-01, PM-M21-02 | DC-N-17: the dashboard shows the four metrics as numbers against their baselines, with no charts, trends or period selection (DC-S10). | PM-M21-L1, PM-M21-L2 | SL-A1-09 |
| DC-M22 | `vigil-setup` | Simplify | PM-M22-01 to PM-M22-04 | DC-N-03 applies to "connect" in PM-M22-01: on screen, connecting means setting the address of an existing source and testing it; sources are created by seed import (DC-D-06). DC-N-21: source health thresholds and check intervals are not shown. | PM-M22-L1, PM-M22-L2 | SL-A2-01 |
| DC-M23 | `vigil-api` | Keep | PM-M23-01 to PM-M23-05 | None. | PM-M23-L1 | SL-B-13, SL-B-14 |
| DC-M24 | `vigil-web` | Simplify | PM-M24-01 to PM-M24-05 | DC-N-18: main screens are laid out for a desktop browser window at least 1280 px wide and 720 px high; only SL-A4-01, SL-A4-04 and SL-A4-05 are laid out for 360 px. DC-N-19: English only, one light colour theme. DC-N-20: supported browser: the current stable release of Google Chrome (DC-UI-01). | PM-M24-L1, PM-M24-L2 | SL-A4; SL-C-10 |
| DC-M25 | `vigil-packs` | Later | — | — | PM-M25-L1 | SL-C-08 |
| DC-M26 | `vigil-ai-evaluation` | Later | — | — | PM-M26-L1 | SL-C-16 |
| DC-M27 | `vigil-platform-health` | Later | — | — | PM-M27-L1 | SL-C-07 |
| DC-M28 | `vigil-discovery` | Later | — | — | PM-M28-L1 | SL-C-12 |

### 4.3 Connectors, step types and AI tasks (complete list)

| ID | Item | Cut | Trace |
| --- | --- | --- | --- |
| DC-M29 | Connectors PM-K-01 to PM-K-10 | Keep. Connection tests on screen only for PM-K-01 and PM-K-02 (PM-M04-04; PM-M04-L3 stays Later). | `10-product-map` Section 7 |
| DC-M30 | Connectors PM-K-11 to PM-K-14 | Later (unchanged). | SL-C-01, SL-C-03, SL-C-14 |
| DC-M31 | Step types PM-S-01 to PM-S-11 (DM-S-01 to DM-S-11) | Keep, all in the editor palette (SL-WE-01). | SL-B-03 |
| DC-M32 | AI tasks SL-T-1, SL-T-2, SL-T-3 | Keep. | `01-scope-lock` Section 5.5 |

### 4.4 Demo-only components (complete list)

These are outside Vigil (CTX-C-03) and are not tagged Keep or Simplify. Their content for the demo is fixed by `30-jazz-storyboard` and `31-demo-data`; the only screen among them, the demo control panel, is DC-S12.

| ID | Component | Content in this document | Trace |
| --- | --- | --- | --- |
| DC-M33 | `demo-lab`, `demo-monitoring`, `demo-faults`, `demo-data`, backup video (PM-DC-01, PM-DC-02, PM-DC-03, PM-DC-05, PM-DC-06) | None beyond the earlier documents. | SL-A3-01, SL-A3-02, SL-A3-08 |
| DC-M34 | `demo-control` (PM-DC-04) | Screen content DC-S12. | SL-A3-03, SL-A4-12 |

## 5. Rules common to all Vigil screens (complete list)

| ID | Rule | Trace |
| --- | --- | --- |
| DC-UI-01 | **Devices.** Main screens MUST be usable in the current stable Google Chrome at a window size of 1280 × 720 px or larger. SL-A4-01, SL-A4-04 and SL-A4-05 MUST be usable at 360 × 780 px (the phone window, SB Section 3) without horizontal scrolling, and also in the main window. | SL-A4-04, SL-A4-05; SB-SET-03 |
| DC-UI-02 | **Frame of a main screen (complete list, top to bottom).** (1) The environment label banner: full width, above everything else, showing `environment_label` (DM-E-02) when it is not empty; it does not scroll away. (2) The header: the customer logo when one is set, otherwise the customer name as text; the product name (DC-W-30); the navigation of DC-UI-03; the logged-in user's display name; "Log out"; and the time zone note "Times in UTC{offset}" (Example: "Times in UTC+05:00"), where the offset is the browser's. (3) The screen content. | SL-A3-06, SL-DR-03, PM-M24-02 |
| DC-UI-03 | **Navigation (complete list, in this order).** "Incidents" (SL-A4-02), "Dashboard" (SL-A4-10), "Workflow" (SL-A4-06), "Routing rules" (SL-A4-08), "AI settings" (SL-A4-09), "Setup" (SL-A4-07), "Audit trail" (SL-A4-11). A user who holds `view` sees every item. A user without `view` sees no navigation. | SL-A4; DM-E06-R1 |
| DC-UI-04 | **Frame of a mobile page and of the login screen (complete list).** The environment label banner as in DC-UI-02; one header line with the customer logo or name and the product name (DC-W-30); on mobile pages, the user's display name, "Log out" and the time zone note. No navigation. | SL-A4-04, SL-A4-05, SL-DR-03 |
| DC-UI-05 | **Formats (complete list).** These formats apply to every text Vigil shows or sends, whether composed by `vigil-web` or stored by a module. Times: the browser's time zone; `HH:mm:ss` when the date is today, otherwise `dd MMM yyyy HH:mm:ss` (Example: "09 Oct 2026 14:05:32"). Dates without a time: `dd MMM yyyy`. Configured durations (step parameters, settings and definitions: timers, expiries, observation windows, maximum waits, check intervals): whole seconds as "{n} s" (Example: "600 s"). Measured or counting durations (elapsed, remaining, time since): up to 60 s as "45 s"; below 1 hour as "12 min 05 s"; otherwise "2 h 05 min". Metric minutes: one decimal place and "min" (Example: "4.3 min"). Counts: digits with a comma every three digits from 1,000 up, except exact parameter values on the Approval page (DC-S05-R3), which are shown exactly as stored. Counts with a noun: the singular noun when the count is 1 (Example: "1 grouped alert", "1 firing alert"); this applies to every pattern in Sections 6, 7 and 8 and to the template summary of DM-E69-R1. Configured durations never get thousands separators (Example: "86400 s"). Lists of names: joined by ", " in alphabetical order of the shown name, unless a pattern states another order. Similarity scores: two decimal places. Money: "USD" and four decimal places (Example: "USD 0.0123"). Incident numbers: DC-W-01. Severities: "P1" to "P4", each with its own colour; the text is always shown, so colour is never the only signal. Users: `display_name`. Teams: `name`. Services and components: `name`, with the key in brackets where this document says so; component kind and criticality as stored (Example: "server", "critical"). A module as actor, including the seed import: "System". | CTX-W-08 |
| DC-UI-06 | **State names shown (complete list).** Incident: Forming, Open, Resolved, Closed (merged incidents are never shown, DM-E32-R3). Source health: Healthy, Stale, Disconnected. Alert: Firing, Resolved. Run: Running, Waiting, Finished, Cancelled, Failed. Approval request: Pending, Approved, Rejected, Expired, Preauthorized, Blocked, Refused, Cancelled. Action execution: Recorded, Rechecking, Not executed, Executing, Reconciling, Succeeded, Failed, Unresolved. Recovery verification: Observing, Recovered, Not recovered, Cancelled. Ticket: Open, Resolved. Draft: Open, Published. | DM Section 10 |
| DC-UI-07 | **Fixed labels (complete list, PM-M24-05).** "Hypothesis" next to every root-cause hypothesis; "Test" on every test replay result; "Illustrative baseline" from the baseline record's `label` (DM-E-80) next to every baseline; each past incident's `record_label` (DM-E-64) next to every past incident shown. | SL-A1-02, SL-A2-06, SL-A1-09, SL-DR-03 |
| DC-UI-08 | **Common states (complete list).** Loading: the region shows "Loading…". Empty: the region shows its empty text from Section 6. Error: when a request fails, the region shows "Could not load. Retry" with a Retry control; an action that fails shows the refusal reason returned by `vigil-api` next to the control. Unauthorized: a screen whose required permission (Section 6) the user does not hold shows "You do not have permission to view this page." and "Log out". Not logged in: every Vigil screen except the login screen redirects to SL-A4-01 and returns to the requested address after login. Session ended: the same redirect, with the login message "Your session has ended. Log in again." Live updates lost: a banner under the header "Live updates paused — reconnecting…"; when the connection returns, the screen reloads its data and removes the banner. | BSD §11.1 (states); SL-B-13 |
| DC-UI-09 | **Live updates.** The incident list (DC-S02) and the incident workspace (DC-S03) MUST show an incident change or a step change within 2 seconds of the change (PM-M23-04, SL-A1-08). Alert states and recovery evaluations shown in the workspace (DC-S03-R3, DC-S03-R7) MUST reflect a change within 5 seconds; the mechanism is decided in Round 5 (DC-O-02). All other screens load their data when opened, after each of their own actions, and on their Refresh action where Section 6 lists one. | SL-A1-08, SB-S07; PM-O-01 |
| DC-UI-10 | **Confirmation dialogs (complete list).** Publish (DC-S06-A8), Rollback (DC-S06-A10), Service map import (DC-S07-A3), and Reset in the demo control panel (DC-S12-A6). No other action asks for confirmation. | SL-A2-06, SL-A2-01, SL-A3-03 |
| DC-UI-11 | **Generated texts.** Texts stored in records (evidence summaries, timeline texts, audit summaries, test replay notes, change lists and change summaries, recheck details) and all outbound content (Section 8) contain no clock times. Texts that `vigil-web` composes when it shows them (waiting texts, refusal messages DC-W-13 and DC-W-14, status lines with times) use the browser time zone. A generated one-line text longer than its field limit is cut to the limit minus one character and ends with "…". Runs of line breaks and spaces inside an inserted text (Example: an AI summary) become one space in one-line texts and in outbound content; screens show multi-line texts (likely cause, uncertainty, summary) with their line breaks. | DM-C-11; DC-D-10 |
| DC-UI-12 | **Force.** Every rule in Sections 4.1 and 5 and every region content, action result and wording item in Sections 6, 7 and 8 is required behaviour with the force of MUST, as DM-C-12 states for the domain model. | CTX-W-02 |
| DC-UI-13 | **Counters.** "Time since", "elapsed" and "remaining" values on screens count each second in the browser. | SB-S01, SB-S07 |

## 6. Screens

Each screen lists its regions (complete list, in screen order), its actions (complete list) and the exact demo values it must show, by reference to `30-jazz-storyboard`. Product wording referred to as `DC-W-nn` is in Section 7.

### 6.1 DC-S01 Login (SL-A4-01)

Purpose: log a named user in (SL-B-13). Reached by anyone; no permission required (DM-E06-R2).

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S01-R1 | Frame | DC-UI-04. | SL-DR-03 |
| DC-S01-R2 | Form | "Username" field; "Password" field (masked); "Log in" button; message line. | PM-M02-02 |
| DC-S01-R3 | Logged in, no destination | Shown after login when there is no requested address and the user does not hold `view`: "Logged in as {display name}. Open the Acknowledge or Approval link from your notification." and "Log out". A user holding `view` goes to the incident list instead. | SL-A3-07 |

| ID | Action | Permission | Result |
| --- | --- | --- | --- |
| DC-S01-A1 | Log in | None | Success: session starts (DM-SM02-T1); the user goes to the requested address or as in DC-S01-R3. Failure (wrong username or password, inactive user, tenant not ready): message "Username or password is incorrect." The message is the same for every failure. |

### 6.2 DC-S02 Incident list (SL-A4-02)

Purpose: the incident queue, and the list of past incidents. Permission: `view`. Live (DC-UI-09).

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S02-R1 | Grouping in progress | Shown only while at least one incident is `forming`. Heading "Grouping in progress". One row per forming incident (DC-D-02), in `sequence` order, each showing: the badge "Forming"; the incident's current `title`; "{alert_count} alerts" (singular rule, DC-UI-05); a source badge for each kind in `alert_sources` ("Zabbix", "Alertmanager"); the time since `first_alert_fired_at`, counting each second (DC-UI-13) (Example: "1 min 12 s"); and the note "Not yet an incident — opens when grouping completes". No number. A row disappears when its incident becomes `open` (it then appears in DC-S02-R2) or `merged`. Rows cannot be opened (DC-N-07). | DM-E32-R3; SB-S01 |
| DC-S02-R2 | Incidents | Tab "Incidents". A table with one row per incident in state `open`, `resolved` or `closed`. Columns (complete list): Number (DC-W-01); Title; Severity; State; Stage (DC-W-12; empty when the incident is not `open`); Owner team; Alerts (`alert_count`); Sources (badges as in R1); Acknowledged (display name of `acknowledged_by`, or "—"); Detected (`first_alert_fired_at`); Reopened ("×{reopen_count}" when above 0, otherwise empty). Order (fixed): `open` incidents first, then `resolved`, then `closed`; within each state, highest number first. A row opens the incident workspace. Empty text: "No incidents." | SL-A4-02; SB-S00, SB-S02 |
| DC-S02-R3 | Past incidents | Tab "Past incidents". A read-only table of every active past incident (DM-E-64). Columns (complete list): Key; Title; Opened (date of `opened_at`, `dd MMM yyyy`); Alerts (`alert_count`); Affected services (service names from the current service map, or the key when the service is not in it); Hypothesis component (name, or the key when not in the current map); Fix (`fix_description`, first 120 characters, then "…"); Linked action (registered action name, or "—"); Fix success count; Record label (`record_label`). Order: newest `opened_at` first. Rows do not open (DC-N-14). Empty text: "No past incidents". | SL-DR-03; PM-M24-05; DC-D-07 |

Actions: switching between the two tabs; opening a row of DC-S02-R2. No other action.

Exact demo values: SB-S00 (empty list, banner, customer name); SB-S01 (forming rows, rising counts, both source badges, a second forming row for the reporting server); SB-S02 (one numbered storm incident and one SL-F-3 incident with the titles, severities and owners of SB-S02). The past incidents tab lists 50 records, each labelled "Fictional record" (DD-F-09).

### 6.3 DC-S03 Incident workspace (SL-A4-03)

Purpose: everything about one incident. Permission: `view`. Live (DC-UI-09). The workspace has two tabs: "Overview" (regions R2 to R8) and "Workflow" (region R9). The header R1 is shown above both tabs.

DC-S03-R0 (forming or merged incident). When the requested incident is `forming`, the workspace shows only the frame and the text "This incident is still forming. It opens when grouping completes." When it is `merged`, it shows "This group was merged into {number}." with a link, where the number is that of the last incident in its `merged_into_incident_id` chain; while that incident is still forming, the text is "This group was merged into a forming incident." with no link (DC-D-02).

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S03-R1 | Header | Number and title; Severity; State; Stage (DC-W-12, only while `open`); "Owner: {team}"; "Informed: {teams, alphabetical, or 'none'}"; acknowledgement ("Acknowledged by {display name} at {time}" or "Not acknowledged"); escalation ("Escalated to level {n}" when `escalation_level` > 0); "Reopened {n} times" when `reopen_count` > 0; the AI status line DC-W-07 when `ai_status = unavailable`; the ticket ("Jira {external key} — {ticket state}" linking to the ticket `url`, or "No ticket"); the action "Re-run AI investigation" (DC-S03-A1). | SL-A4-03 (ticket link); SB-S04, SB-S05 |
| DC-S03-R2 | Impact and assignment | (a) "Customer impact": one line per affected service, in `service_key` order: service name, criticality (or "no criticality"), and "via {component names, alphabetical}". Empty text: "No affected service identified". (b) The root-cause hypothesis, labelled "Hypothesis": component name, component key in brackets, kind and criticality (as stored), and the line "Most dependents among affected components: {count}" from the hypothesis computation; when `tie_break` is not `none`, also "Tie broken by {criticality / component key}". Empty text: "No hypothesis (no alert matched a known component)". (c) "Assignment": severity, owner team, informed teams, and "Rule: {rule name} ({rule_key}), rule set revision {n}" from the applied routing evaluation. (d) "Summary": the latest incident summary text (DM-E-69) with "(AI, {provider display name})" or "(template)". Empty text: "No summary yet". | SL-A1-02, SL-A1-04, SL-A1-05 |
| DC-S03-R3 | Alerts | Heading "{alert_count} alerts — {z} Zabbix, {a} Alertmanager · {f} firing, {r} resolved". A table of member alerts (active memberships), in `fired_at` order (ties: source kind, then alert key, alphabetical). Columns (complete list): Alert type (display name); Title (alert `title`); Component (name; "Unknown component" for an unknown component; the key when the component is not in the current service map); Source; Fired (`fired_at`); Status ("Firing", or "Resolved {time}"); Reason (the one-line grouping reason DC-W-02, from `final_reason`). Each row expands to show: the relation path oriented as in DC-W-02 (every arrow reads "depends on"), the time difference in seconds, and the related alert's title, component and fired time. Empty text: "No alerts". | SL-A1-02, SL-A1-07; DM-E-34 |
| DC-S03-R4 | Evidence | Evidence entries in `collected_at` order. Each row (complete list): kind (DC-W-04 kind name); summary (DM-E-38 `summary`, wording DC-W-03 to DC-W-06); source (`source_system`); collected (`collected_at`). A `diagnostic_result` row expands to show the result's named single output values (not row lists), in the order the parser defines them (DD-F-04 `output_parsers`), as a two-column table (value name, value). Empty text: "No evidence collected". | SL-A1-03; SB-S03 |
| DC-S03-R5 | AI investigation | The latest `accepted` AI investigation result (DM-E68-R1): provider display name and model name; time; origin ("Workflow step" or "Re-run by {display name}"); verdict ("Confirms the hypothesis" or "Disputes the hypothesis"; nothing when `verdict` is empty); "Likely cause" text; "Supporting evidence": the summaries of the referenced evidence entries; "Uncertainty" text; "Runbooks cited": key, title and version of each `runbook_refs` entry. Below it, "Providers tried for the latest investigation" (DC-W-08): one line per AI usage record of the latest SL-T-1 attempt, in `fallback_position` order (the latest attempt is the request group of the newest SL-T-1 usage record of the incident, whether or not a result was stored; its time is the earliest `started_at` of that group, also used for "(at {time})" in DC-W-07), and the link "Show in AI usage log" (opens DC-S09 filtered by that request group, DC-S09-R5). When `ai_status = unavailable`, the AI status line DC-W-07 is shown above the result. Empty text when no result was ever accepted: "AI investigation not run" (when `ai_status = not_run`) or only the status line (when unavailable). | SL-A1-04, SL-A2-05, SL-A2-07; SB-S04, SB-D05, SB-D06 |
| DC-S03-R6 | Response | (a) "Similar past incident": for the incident's latest similarity search result: on `match`, the past incident key, title, its `record_label`, "Similarity {score}" and "Fix succeeded {success_count} times", and the fix description; on `no_match`, "No similar past incident at or above {min_similarity} (best score {score})" or "No similar past incident" when no score; when no search ran, "Not searched". (b) "Proposed action" (the run's proposal): action name, target ("{target_key} ({target component name})"), parameters (name and exact value, as DC-S05-R3), limits, and "Attempt {n} of {limit}" taken from the snapshot of the run's latest approval request (omitted while the run has no approval request). Empty text: "No action proposed". (c) "Approvals": one line per approval request of the latest run, in `sequence_in_run` order: "Request {n}: {state}", the deciding user's display name and decision time when decided, and "expires {time}" while pending. (d) "Action": per action execution of the latest run: state; the four rechecks in order with "Passed" or "Failed" and their `detail`; rechecks stop at the first failure (DM-R-32), and the ones after it show "Not checked"; the result line (the `action_execution` evidence summary, DC-W-05); started and finished times. | SL-A1-06; SB-S06 |
| DC-S03-R7 | Recovery | Per recovery verification of the latest run: definition name; state; one line per condition with "Holding" or "Not holding" and its detail (DC-W-09); "Monitoring sources healthy" or "Monitoring source not healthy: {names}", from the current source health held by `vigil-intake` (refreshed within 5 seconds, DC-UI-09); while `observing` and `window_started_at` is set, "Observation window {elapsed} of {window}" counting up each second in the browser; "Maximum wait until {time}"; the decision time when decided. Empty text: "Recovery not checked yet". | SL-A1-07; SB-S07 |
| DC-S03-R8 | Timeline | Timeline entries (DM-E-39), newest first: time, actor (DC-UI-05), text (DC-W-10). A check box "Show alert joins", off by default, hides entries of type `alert_joined` while off. Empty text: "No timeline entries". | SL-A4-03; PM-M09-09 |
| DC-S03-R9 | Workflow (tab) | The read-only canvas (SL-WE-10) showing the content of the incident's latest live run (DM-E-50 `content`), laid out as stored. Above it: "Workflow version {n}" and the run state; when the run has ended, its end reason text (DC-W-31) instead of the state. Each step shows the editor's step box (DC-S06-R3). Steps with a completed execution show their named output on the connection taken; the step running or waiting is highlighted, and a waiting step also shows the wait text DC-W-11; steps not reached are dimmed. Selecting a step shows: started, finished, named output, and visit number, for each of its executions in the run. The highlight MUST move within 2 seconds of a step change (SL-A1-08). Test-mode step changes are never shown here (DM-R-59). Empty text when the incident has no live run: "No workflow run". | SL-A1-08, SL-WE-10; SB-S08 |

| ID | Action | Permission | Enabled when | Result |
| --- | --- | --- | --- | --- |
| DC-S03-A1 | Re-run AI investigation | `rerun_ai_investigation` | State `open`, `resolved` or `closed` (DM-E68-R2), and no re-run started from this browser is still running | Starts the re-run (PM-M17-07); the request returns once the re-run has started. In this browser the control shows "Running…" until an `ai_status_changed` timeline entry or a new `ai_investigation` evidence entry for the incident arrives through the incident push; regions R1 and R5 then update. Other browsers show no progress. |
| DC-S03-A2 | Show in AI usage log | `view` | The incident has at least one SL-T-1 attempt | Opens DC-S09 with the usage log filtered by the request group of the latest SL-T-1 attempt (the same group as R5 "Providers tried"). |
| DC-S03-A3 | Open Jira ticket | `view` | A ticket link exists | Opens the ticket `url` in a new browser tab. |

Exact demo values: SB-S02 (title, 138 alerts, 30 Zabbix and 108 Alertmanager, hypothesis, impact, severity, owner, informed, grouping reason criteria); SB-S03 (three evidence summaries and their sources); SB-S04 (AI result acceptance criteria, runbooks, routing rule); SB-S05 (acknowledgement line); SB-S06 (best match PI-0342, 1.00, 4; action result); SB-S07 (all 138 resolved, state Resolved, window 60 s); SB-S08 (version 1 path); SB-D05 and SB-D06 (providers tried, AI unavailable).

### 6.4 DC-S04 Acknowledge page (SL-A4-04, mobile)

Purpose: acknowledge one incident (SL-A1-05). Reached from the link in an incident notification or escalation message (DC-OUT-03, DC-OUT-04); the link names the incident and grants nothing by itself (SL-B-13). Login required. Permission to see the page: `acknowledge` or `view`; others get the unauthorized state.

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S04-R1 | Frame | DC-UI-04. | SL-DR-03 |
| DC-S04-R2 | Incident | Number and title; Severity; State; "Owner: {team}"; customer impact (service names, comma-separated, or "No affected service identified"); the hypothesis, labelled "Hypothesis" (component name, or "none"); "{alert_count} alerts"; the latest incident summary text. | SL-A1-05 |
| DC-S04-R3 | Acknowledgement | When the incident has an acknowledgement for the current episode: "Acknowledged by {display name} at {time}". Otherwise, for a user holding `acknowledge`: the "Acknowledge" button. For a user holding only `view`: "View only". | SL-A1-05; DM-E35-R1 |

| ID | Action | Permission | Result |
| --- | --- | --- | --- |
| DC-S04-A1 | Acknowledge | `acknowledge` | Accepted (DM-E35-R1): R3 shows "Acknowledged by {display name} at {time}". Refused: the message DC-W-13 for the reason, and R3 is reloaded. |
| DC-S04-A2 | Log out | Logged in | Session ends (DM-SM02-T2); the login screen is shown. |

Exact demo values: SB-S05 ("Acknowledged by Ayesha Siddiqui (NOC L1)" with the time), SB-D03-8.

### 6.5 DC-S05 Approval page (SL-A4-05, mobile)

Purpose: decide one approval request (SL-A1-06). Reached from the link in an approval request message (DC-OUT-05); the link names the request and grants nothing by itself. Login required. Permission to see the page: `approve_action` or `view`. The page shows the request's stored snapshot (DM-V-12), never a recomputed one, and sends its `snapshot_hash` with the decision.

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S05-R1 | Frame | DC-UI-04. | SL-DR-03 |
| DC-S05-R2 | Request | "Approval request {sequence_in_run} for {incident number}: {incident title}"; state; when decided: "{state} by {display name} at {time}" (or "Expired at {time}"). | SL-A1-06; SB-D03-8 |
| DC-S05-R3 | What will run | The SL-A1-06 complete list, each from the snapshot: "Action": `action_name`; "Parameters": one line per parameter, in the order of the operation's parameter definitions (DM-E-12), `{name}: {value}`, the value exactly as stored, without unit conversion or thousands separators (DC-D-08); "Target": "{target_key} ({target_name})"; "Limits": each `limits` text, then "Attempt {attempt_number} of {attempt_limit}"; "Expires": "{expires_at time} ({remaining} left)", the remaining time counting down each second in the browser, and "Expired" once passed. | SL-A1-06; DM-V-12 |
| DC-S05-R4 | Why | All from the snapshot, including `based_on.record_label` (DC-A-06). "Based on": "{past_incident_key} ({record_label}): {past_incident_title} — similarity {score}, fix succeeded {success_count} times"; "Evidence": one line per snapshot evidence item, in order: kind name (DC-W-04), summary, collected time. | SL-A1-06 |
| DC-S05-R5 | Recovery checks | "{name}", then one line per condition (DC-W-09 condition text), then "Held for {observation_window}". | SL-A1-06 |
| DC-S05-R6 | Decision | For a user holding `approve_action` while the request is `pending` and not expired: the buttons "Approve" and "Reject". For a user holding only `view`: "View only". Otherwise nothing. | SL-A1-06; DM-SM11-T5, T6 |

| ID | Action | Permission | Result |
| --- | --- | --- | --- |
| DC-S05-A1 | Approve | `approve_action` | Accepted (DM-E59-R1): R2 shows "Approved by {display name} at {time}" and the line "{product name} rechecks every condition before running the action." (DC-W-30) Refused: message DC-W-14 for the reason; the page reloads the request. |
| DC-S05-A2 | Reject | `approve_action` | Accepted: R2 shows "Rejected by {display name} at {time}". Refused: as A1. No reason text is asked for. |
| DC-S05-A3 | Log out | Logged in | As DC-S04-A2. |

Exact demo values: SB-S06, read as DC-A-07 (action, parameters `path: /data/txlog_archive`, `older_than_minutes: 1440`, `max_bytes: 12884901888`, target "lab-db-01 (Subscriber DB server)", the four limit texts and "Attempt 1 of 1", expiry 900 s after the request, evidence, PI-0342, recovery check "Subscriber DB services recovered" with its two conditions and 60 s); SB-D03-8 (request 2 seen by `dbops-lead`).

### 6.6 DC-S06 Workflow editor (SL-A4-06)

Purpose: view, edit, test, publish and roll back the tenant's workflow (Section 5.6 of `01-scope-lock`). Permission to open: `view`. Users without `edit_workflow` see the active version read-only, with version history (R7), and may use only A4 (select), A7 (zoom, pan, fit) and A11 (version history). While no draft is open, the canvas shows the active version read-only.

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S06-R1 | Header | Workflow `name`; "Active: version {n}"; draft status: "No open draft", or "Draft revision {r}, based on version {n}" followed by the save state "Saved {time}", "Saving…" or "Not saved: {reason}. Retry". | SL-WE-07 |
| DC-S06-R2 | Toolbar | The controls of actions A1 and A5 to A11 that the user's permissions allow. | SL-WE-03, SL-WE-05, SL-WE-06 |
| DC-S06-R3 | Canvas | One box per step, at its stored `position`. A box shows (complete list): the step type display name (DC-W-15) and the `step_id`; the `label`; the summary line of DC-W-15; one input handle (every type except Trigger); one output handle per named output, labelled with its display text (DM Section 9); for a Condition step, one output handle per branch label, in branch order, then "otherwise". A connection is drawn from an output handle to the target's input handle. After a validation with violations, each step with a violation shows a red marker and, when selected, its messages (DC-W-18); a Condition branch violation marks that branch's handle. A violation that concerns no step is shown in the blocked message of A8 or A9. | SL-WE-01 to SL-WE-03, SL-WE-08 |
| DC-S06-R4 | Palette | Shown only while a draft is open and the user holds `edit_workflow`. The 11 step types, in this order: Trigger, Condition, Diagnostic, AI task, Approval, Action, Recovery check, Escalation, Notification, Ticket create/update, Wait/timer. | SL-WE-01; DM-E-44 |
| DC-S06-R5 | Parameter panel | For the selected step (complete list): `step_id` (read-only); type (read-only); "Label" (1 to 60 characters); one control per parameter of the type, as DC-W-16. Each value is checked when entered (SL-WE-04): an invalid value shows its message (DC-W-16) under the control and is not applied to the draft. Read-only when no draft is open or the user lacks `edit_workflow`. | SL-WE-04 |
| DC-S06-R6 | Test replay result | Shown after a test replay is started in this browser session; only the latest one is shown (DC-N-12). Content (complete list): the label "Test", shown first and larger than any other text in the region; "Source incident: {number} {title}"; "Workflow: draft revision {r}" or "Workflow: version {n}"; "Routing: rule set revision {n} (dry run)"; "Assignment: {severity}, owner {team}, informed {teams or 'none'}"; state ("Running", "Completed" or "Failed: {reason}", reasons DC-W-31); and the path table, one row per path entry (DM-E-49 `path`): #, step id, label, type, output (display text), note (DC-W-19), simulated time ("+m:ss" after the source incident's `stable_at`). | SL-A2-06; DM-E-49, DM-E49-R1 |
| DC-S06-R7 | Version history | Opened by A11. Never empty: version 1 exists from the seed import. One row per version, newest first (complete list of columns): Version; Origin ("Seed", "Published from draft revision {r}" or "Rollback to version {m}"); Published by; Published at; "Active" on the active version; Changes (one line per `change_list` entry, "{step_id} {added / removed / changed}: {details}" with details per DC-W-17; "None" when empty). Users holding `publish_workflow` see "Roll back to this version" (A10) on every row except the active version. | SL-WE-09; SL-A2-06 |
| DC-S06-R8 | Withdrawn in v0.2 by DC-D-18 (was: a problems panel; violations are shown on the steps and in the blocked message, DC-S06-R3). | — | — |

| ID | Action | Permission | Enabled when | Result |
| --- | --- | --- | --- | --- |
| DC-S06-A1 | Start editing / Continue editing | `edit_workflow` | Always | No open draft: a draft is created from the active version (DM-SM06-T1) and the label reads "Continue editing" from then on. An open draft: it is opened. |
| DC-S06-A2 | Add a step | `edit_workflow` | Draft open | Dragging a palette type onto the canvas adds a step at the drop point, with `step_id` per DC-W-17, `label` equal to the type display name, and no parameter values. |
| DC-S06-A3 | Connect | `edit_workflow` | Draft open | Dragging from an output handle to another step's input handle adds a connection. Refused with "This output is already connected." when the output already has one (DM-V-05). |
| DC-S06-A4 | Select and move | `edit_workflow` to move; anyone to select | Draft open (move) | Click selects one step or connection; Shift+click adds to the selection; dragging on empty canvas with Shift held selects every step inside the rectangle. Dragging a selected step moves every selected step. Dragging the target end of a selected connection onto another step's input handle moves the connection there (one change); dropping it anywhere else leaves it unchanged. |
| DC-S06-A5 | Delete | `edit_workflow` | Draft open, something selected | The Delete or Backspace key, or the toolbar "Delete", removes the selected steps and connections; removing a step removes its connections. |
| DC-S06-A6 | Undo, Redo | `edit_workflow` | Draft open | Ctrl+Z undoes; Ctrl+Shift+Z or Ctrl+Y redoes; toolbar "Undo" and "Redo" do the same. At least the last 50 changes of the editing session can be undone (SL-WE-05). An undo or redo is saved like any change. |
| DC-S06-A7 | Zoom, pan, fit, auto layout | Anyone for zoom, pan and fit; `edit_workflow` for auto layout | Auto layout: draft open | Mouse wheel and toolbar "+" and "−" zoom; dragging empty canvas pans; "Fit" shows every step; "Auto layout" sets every step's position by a layered left-to-right layout starting at the Trigger (the layout method is chosen in Round 6), as one undoable change. |
| DC-S06-A8 | Publish | `publish_workflow` | Draft open | Confirmation (DC-UI-10): "Publish draft revision {r} as version {n}? New incidents will follow it; runs already started keep their version." Pending changes are saved first (R9). Then validation (DM-R-68): with violations, R3 shows them and the message "Publish blocked: {k} problems to fix." is shown, followed by one line per violation that concerns no step. With none, the version is created (DM-SM06-T3) and the message "Version {n} published and active." is shown; the editor shows the new active version and "No open draft". |
| DC-S06-A9 | Test replay | `run_test_replay` | At least one incident qualifies (DM-R-68) | A dialog with (complete list): "Source incident", a choice of incidents in state `open`, `resolved` or `closed` that have a live run, each shown as "{number} {title} ({state})"; "Workflow", a choice of "Current draft (revision {r})" when a draft is open and each version as "Version {n}"; "Run". Pending changes are saved first (R9) and the draft is validated; with violations, R3 shows them and the message "Test replay blocked: {k} problems to fix." is shown, followed by one line per violation that concerns no step. Otherwise R6 shows "Running" at once and reads the result again every second until it is completed or failed. |
| DC-S06-A10 | Roll back | `publish_workflow` | A version other than the active one is chosen in R7 | Confirmation (DC-UI-10): "Roll back to version {m}? This publishes version {n} with the content of version {m}. New incidents will follow it; runs already started keep their version." Then the new version is created (PM-M11-06) and "Version {n} published and active." is shown. An open draft is not changed (DM-SM-06 note). |
| DC-S06-A11 | Version history | `view` | Always | Opens or closes R7. |

DC-S06-R9 (saving). Every accepted change to an open draft (added, moved, connected, deleted, parameter or label changed, undo, redo, auto layout) is saved automatically, at most 1 second after the last change, as the next draft revision (DM-SM06-T2). When a save is refused because the draft revision changed elsewhere (DM-E-46), R1 shows "This draft was changed elsewhere. Reload to continue." with "Reload"; unsaved changes are then discarded. Draft saves write no audit event (SB-D01).

Exact demo values: SB-D03-1 (new step `p2` labelled "DB Ops second approval", eligible Database Operations, expiry 900 s, connected as DD-F-06; R1 shows "Saved"), SB-D03-2 (timer 60 accepted), SB-D03-4 (replay labelled "Test" with the assignment, path and notes of SB-D03-4), SB-D03-5 (version 2 active; changes p2 added, p1 changed, e1 changed), SB-D03-9 (version 3 active, origin "Rollback to version 1"). Users without `edit_workflow` see version 1 read-only in the starting state (SL-WE-10 applies the same canvas).

### 6.7 DC-S07 Setup and sources (SL-A4-07)

Purpose: connect and test monitoring sources, import the service map and list readiness gaps (SL-A2-01). Permission to open: `view`; actions need `manage_setup`.

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S07-R1 | Monitoring sources | One row per active monitoring source. Columns (complete list): Name; Kind ("Zabbix" or "Alertmanager"); Address (the connector instance `address`); Webhook address (the address the source sends to; format decided in Round 5, DC-O-04; the token is never shown); Health (state name and "since {health_changed_at}"); Last contact (`last_contact_at`, or "Never"); Last test ("Passed {time}", "Failed {time}: {last_test_reason}" or "Never"). Empty text: "No monitoring sources". | SL-A2-01; SL-O-12 |
| DC-S07-R2 | Service map | "Generation {generation}, imported by {actor} at {time} from {file_name}: {services} services, {components} components, {identity_keys} identity keys, {dependencies} dependencies." The result of the latest import made on this screen is shown under it (DC-W-21). | SL-A2-01 |
| DC-S07-R3 | Readiness gaps | Heading "Readiness gaps ({n})". One line per gap (DC-W-22), in this order: servers with no owner (by component key), services with no criticality (by service key), sources not healthy (by name). Empty text: "No readiness gaps". Recomputed when the screen opens, after A1, A2 and A3, and on Refresh. | SL-A2-01; DM-E-81 |

| ID | Action | Permission | Result |
| --- | --- | --- | --- |
| DC-S07-A1 | Test connection (per source) | `manage_setup` | Runs the connection test (PM-M04-04). The row shows "Connection test passed" or "Connection test failed: {reason}", and R1 and R3 reload. No audit event (SB-D01). |
| DC-S07-A2 | Edit address (per source) | `manage_setup` | An inline field "Address" (1 to 500 characters) with "Save" and "Cancel". Save stores the new address (configuration change, area `sources`, audited), then runs A1 at once. |
| DC-S07-A3 | Import service map | `manage_setup` | A file chooser, then confirmation (DC-UI-10): "Import {file name}? It replaces the service map used for new alerts. Incidents already grouped keep their grouping." Then the import (PM-M07-01): success or refusal per DC-W-21 in R2; R3 reloads. |
| DC-S07-A4 | Refresh | `view` | Reloads R1 to R3. |

Exact demo values: SB-D02 (both sources test passed and Healthy; generation 2 with 6 services, 18 components, 12 identity keys and 12 dependencies; the two gaps "Server with no owner: Backup server (bkp-srv-01)" and "Service with no criticality: Internal Reporting").

### 6.8 DC-S08 Routing rules (SL-A4-08)

Purpose: show and change routing rules (SL-A2-06, SL-O-21). Permission to open: `view`; editing needs `manage_routing`.

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S08-R1 | Current revision | "Rule set revision {revision}, saved by {actor} at {saved_at}", and its `change_summary` (DC-W-23). | SL-B-09 |
| DC-S08-R2 | Rules | One row per rule, in evaluation order; never empty, because a rule set always ends with the default rule (DM-E41-R1). Columns (complete list): #; Name; Conditions (DC-W-20, or "Default — matches every incident" for the default rule); Severity; Owner (DC-W-24); Informed (team names, alphabetical, or "none"); "Also inform affected service owners" ("Yes" or "No"). Above the table: "Rules are checked from top to bottom; the first rule whose conditions all hold assigns the incident." | SL-A4-08; DM-E43-R1 |
| DC-S08-R3 | Withdrawn in v0.2 by DC-D-18 (was: routing revision history; earlier revisions appear in the audit trail, DC-N-11). | — | — |
| DC-S08-R4 | Withdrawn in v0.2 by DC-D-18 (was: escalation levels, read-only; DC-N-11). | — | — |

| ID | Action | Permission | Result |
| --- | --- | --- | --- |
| DC-S08-A1 | Edit (per rule) | `manage_routing` | Opens an inline editor on that row with these controls (complete list): Severity (P1 to P4); Owner ("Owner of the hypothesis component" or "Fixed team"); Owner team (active teams; labelled "Owner team" for a fixed team and "Fallback owner team" otherwise); Informed teams (any number of active teams); "Also inform affected service owners" (check box). Name and conditions stay read-only (DC-N-10). One rule is edited at a time. |
| DC-S08-A2 | Save | `manage_routing` | Saves the whole rule list, with the edited rule, as the next revision (DM-R-27). Refused (DM-E41-R1): the reason is shown under the editor and nothing changes. Accepted: "Saved as revision {n}." The rules take effect on save; incidents already open keep their assignment (DM-R-17). Refusal texts: DC-W-31. The stored rule is saved as entered; DM-E41-R2 is applied when a rule is evaluated. |
| DC-S08-A3 | Cancel | `manage_routing` | Closes the editor without saving. |

Exact demo values: rules of DD-F-05; after SB-D03-3, "Saved as revision 2.", the rule "Critical service, infrastructure or database cause" informs Customer Care, and R1 shows the change summary of DC-W-23 for that change.

### 6.9 DC-S09 AI settings (SL-A4-09)

Purpose: AI provider per task, fallback order, data routing, usage and cost (SL-A2-02, SL-A2-07). Permission to open: `view`; changes need `manage_ai_settings`.

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S09-R1 | Withdrawn in v0.2 by DC-D-18 (was: provider configurations, read-only; DC-N-15). | — | — |
| DC-S09-R2 | Task assignments | Never empty (one assignment per task, DM-E-72). One row per task, in this order, named as DC-W-31: "Incident investigation" (SL-T-1), "Incident summary" (SL-T-2), "Similar-incident search" (SL-T-3); the task IDs are not shown. Each shows its providers in order joined by " → " (Example: "Claude → OpenAI → Local model"); the first is the chosen provider, the rest the fallback order. The "Similar-incident search" row also shows the line "Similar-incident search: ready", or "Re-embedding past incidents with {provider}; similar-incident search is unavailable until it finishes." while `embedding_state = reembedding`. | SL-A2-02; DM-E-72, DM-E-67 |
| DC-S09-R3 | Data routing | Never empty (one rule per category, DM-E-73). A table: one row per data category, in the order of DM-R-42, with its name and description (DC-W-25); one column per active provider; a check box in each cell (checked = allowed). Under it: "A provider is used for a request only when it is allowed for every kind of data in that request." | SL-A2-02; SL-O-13 |
| DC-S09-R4 | Usage and cost | Two tables over every AI usage record of the tenant: "By provider" (one row per active provider, in the order Claude, OpenAI, Local model, Local embedding model) and "By task" (one row per task, in the order of DC-W-31); rows with no records show zeros. Columns (complete list): Calls (every usage record, so Calls = Succeeded + Unreachable or error + Not sent); Succeeded; Unreachable or error (outcomes `unreachable` and `provider_error`); Not sent (outcome `refused_by_data_routing`); Input units; Output units; Cost (USD). | SL-A2-02; PM-M18-04 |
| DC-S09-R5 | Usage log | AI usage records, newest first, 50 at a time, with "Show more". Columns (complete list): Time (`started_at`); Task (name, DC-W-31); Provider; Location ("External" or "In-house"); Position (`fallback_position`); Outcome (DC-W-08); Input units; Output units ("not reported" for both when a `succeeded` record has 0 input and 0 output units, DM-ASM-06); Cost; Incident (number); Investigation (the first 8 characters of `request_group_id`). Filters (complete list): Incident (a choice of incidents, or "Any"); Investigation (set only by DC-S03-A2; shown as "Investigation {8 characters}" with a control to remove it). While the Investigation filter is set, a summary line is shown above the table: "Requests sent to external providers: none", or "Requests sent to external providers: {provider display name} ({outcome}), …" listing every record whose `provider_location` is `external` and whose outcome is not `refused_by_data_routing`. Empty text: "No AI usage yet". | SL-A2-07; SB-D06-2 |

| ID | Action | Permission | Result |
| --- | --- | --- | --- |
| DC-S09-A1 | Edit task providers (per task) | `manage_ai_settings` | For Incident investigation and Incident summary: an ordered list with "Move up", "Move down" and "Remove" per provider and "Add provider", which offers the active allowed providers not yet in the list; "Save" and "Cancel". For Similar-incident search: a choice of exactly one allowed provider; "Save" and "Cancel". Save is refused, with the reason shown (DC-W-31), when the list is empty, holds a provider not allowed for the task, or holds a duplicate (DM-E-72). Accepted: "Saved." (configuration change, area `ai_settings`). Saving a different SL-T-3 provider starts re-embedding (DM-SM15-T1). |
| DC-S09-A2 | Save data routing | `manage_ai_settings` | Saves every changed category (configuration change, area `ai_settings`). Message "Saved." |
| DC-S09-A3 | Refresh | `view` | Reloads R2 to R5, keeping the filters. |

Exact demo values: SB-D04 (Claude → OpenAI → Local model for SL-T-1 and SL-T-2; Local embedding model for SL-T-3; every category allowed to every provider; usage and cost so far), SB-D05-3 and SB-D05-4 (order without and with the local model), SB-D06 (local model only; the filtered usage log shows only Local model, In-house, and "Requests sent to external providers: none").

### 6.10 DC-S10 Dashboard (SL-A4-10)

Purpose: the four metrics against their illustrative baselines (SL-A1-09). Permission: `view`. Not live (DC-UI-09).

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S10-R1 | Metrics | Four tiles, in this order: "Time to acknowledge", "Time to recover", "Alerts suppressed", "Incidents merged". Each tile shows (complete list): the metric name; the value (DC-UI-05; "no data" when the mean has no value, DM-R-53); the baseline as "{label}: {value}" (Example: "Illustrative baseline: 14.0 min"); the definition line (DC-W-26). | SL-A1-09; DM-R-53 |
| DC-S10-R2 | Data note | "Based on {n} incidents: {l} handled by {product name} in this environment (DC-W-30) and {p} past incidents." where l counts the live incidents and p the active past incidents of DM-R-53. | SL-A1-09; SL-O-10 |

| ID | Action | Permission | Result |
| --- | --- | --- | --- |
| DC-S10-A1 | Refresh | `view` | Recomputes and reloads R1 and R2. |

Exact demo values: SB-V-40 (seeded history alone: 4.3 min, 28.2 min, 634, 46; after one primary run with 138 storm alerts: alerts suppressed 771 and incidents merged 47; baselines 14.0 min, 95.0 min, 0, 0), SB-S09.

### 6.11 DC-S11 Audit trail (SL-A4-11)

Purpose: who did what and when (SL-A2-08). Permission: `view`.

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S11-R1 | Filters | Incident (a choice of numbered incidents, each shown as "{number} {title}", highest number first, or "Any"); Event type (any number of the 18 event types of DM-E08-R1, by display name DC-W-27; all by default); "Apply" and "Clear". (DC-N-02) | PM-M03-03; SB-D01 |
| DC-S11-R2 | Events | Events matching the filters, in ascending `sequence` (order of occurrence), the first 200, then 200 more per "Show more". Columns (complete list): Time (`occurred_at`); Actor (DC-UI-05); Event type (DC-W-27); Incident (the incident's number; "Merged into {number}" for a merged incident, where the number is that of the last incident in its `merged_into_incident_id` chain, or "Merged into a forming incident" while that one is still forming; "Forming" while it is forming; empty when `incident_id` is empty); Summary (`summary`, wording DC-W-28). A row expands to show `details`: for `configuration_changed`, the area name (DC-W-31), then one line "{field}: {before} → {after}" per top-level field whose before and after differ, each value as compact text cut at 200 characters (for `seed_import`, whose `after` value holds the seed import record's `parts`, one line "{module}: {outcome}, {counts}" per part instead); for other types, one line per detail field, "{name}: {value}". Empty text: "No audit events match the filters." | SL-A2-08; SB-D01 |

| ID | Action | Permission | Result |
| --- | --- | --- | --- |
| DC-S11-A1 | Apply | `view` | Reloads R2 with the filters. |
| DC-S11-A2 | Clear | `view` | Resets every filter to its default and reloads R2. |

Exact demo values: SB-D01 (event list for the storm incident filter; configuration changes after SB-D02 and SB-D03).

### 6.12 DC-S12 Demo control panel (SL-A4-12, demo-only)

Purpose: run the faults, storm re-runs and reset (SL-A3-03). Part of `demo-control`, not of Vigil: it does not use the Vigil frame, and it has its own login, used only by the presenter (SL-A3-03). Its fault button list and rules are fixed by `30-jazz-storyboard` Section 6 and DD-F-10 `control_panel`.

| ID | Region | Content (complete list) | Trace |
| --- | --- | --- | --- |
| DC-S12-R0 | Login | Shown until the presenter logs in: "Username", "Password" (the control panel's own account; its password comes from a deployment secret, SL-B-14), "Log in"; message on failure "Username or password is incorrect." | SL-A3-03 |
| DC-S12-R1 | Header | "Demo control — not part of the product"; the lab clock "Lab time {HH:mm:ss} UTC"; "Log out". | SL-A3-03; SB-R-07 |
| DC-S12-R2 | Buttons | The six buttons of SB Section 6, in its order (A1 to A6). | SL-A3-02, SL-A3-03 |
| DC-S12-R3 | Status | "Active faults: {fault IDs}" or "No active faults"; the running operation with its elapsed seconds ("Injecting… {s} s", "Clearing faults — waiting until monitoring shows no firing alert… {s} s", "Resetting… {s} s"); the result of the last operation (DC-W-29). | SL-A3-03 |
| DC-S12-R4 | Withdrawn in v0.2 by DC-D-18 (was: a log of operations; R3 shows the last result). | — | — |

Every button is refused while another operation of the panel is running (DC-W-29).

| ID | Action | Result |
| --- | --- | --- |
| DC-S12-A1 | "Storm: SL-F-1 + SL-F-3" | Injects both (DD-F-10), or refuses (storm guard, DC-W-29). |
| DC-S12-A2 | "Storm re-run: SL-F-1 only" | Injects SL-F-1, or refuses (storm guard). |
| DC-S12-A3 | "Storm with read-only DB: SL-F-1 + SL-F-2" | Injects both, or refuses (storm guard). |
| DC-S12-A4 | "Cut cloud AI: SL-F-4" | Injects SL-F-4, or refuses when SL-F-4 is already active (DC-W-29). |
| DC-S12-A5 | "Clear all faults" | Clears every active fault and completes per DD-F-10 `clear_completion`. |
| DC-S12-A6 | "Reset" | Confirmation (DC-UI-10): "Reset to the starting state? Earlier demo runs stay stored." Then the reset (SL-A3-03), including clearing faults; the elapsed time is shown; complete within 60 seconds (SB-T-05). What the Vigil windows must do after a reset (log in again, or nothing) depends on SL-O-17 (Round 5, DC-O-03). |

## 7. Product wording (complete list)

Placeholders are written `{name}`. DC-UI-05 (formats) and DC-UI-11 (generated texts) apply to every item.

### 7.1 Incidents and alerts

| ID | Item | Wording | Trace |
| --- | --- | --- | --- |
| DC-W-01 | Incident number (resolves DM-O-11) | "INC-" followed by `number` with at least four digits, zero-padded (Examples: "INC-0001", "INC-0042", "INC-12345"). Used on every screen and in every message and ticket that names an incident. Numbers are per tenant (DM-E-32). | DM-O-11 |
| DC-W-02 | Grouping reason line | By `relation` (complete list): `same_component` — "Same component as {related alert type display name}, {Δ} s apart"; `depends_on` — "{component name} depends on {related component name} ({path}), {Δ} s apart"; `depended_on_by` — "{related component name} depends on {component name} ({path}), {Δ} s apart"; `same_unknown_identity` — "Same unknown source identity ({first identity pair value}) as {related alert title}, {Δ} s apart"; `only_alert` — "Only alert of this incident". {path} is the `relation_path` joined with " → " so that every arrow reads "depends on": as stored for `depends_on`, reversed for `depended_on_by`. {Δ} is `time_delta` in seconds. | SL-A1-02; DM-E-29, DM-E-34 |
| DC-W-10 | Timeline entry text (DM-E-39 `text`), by `entry_type` (complete list) | `created` — "Incident created from {alert type display name} on {component name or 'unknown component'}"; `alert_joined` — "{alert type display name} on {component name or 'unknown component'} joined"; `merged_in` — "Merged with another forming group ({n} alerts moved)"; `hypothesis_changed` — "Hypothesis: {old component name or 'none'} → {new component name or 'none'}"; `impact_changed` — "Customer impact: {service names, comma-separated, or 'none identified'}"; `assignment_changed` — "Assigned {severity}, owner {team}, informed {teams or 'none'} (rule {rule name})"; `state_changed` — "State: {from state name} → {to state name}"; `acknowledged` — "Acknowledged by {display name}"; `escalated` — "Escalated to level {level}: {team names} ({cause: 'not acknowledged in time' or 'escalated by the workflow'})"; `evidence_added` — "Evidence: {evidence summary}"; `ai_status_changed` — "AI investigation available" or the DC-W-07 line; `notified_team_added` — "{team name} notified ({purpose: 'incident notification', 'escalation' or 'approval request'})"; `title_changed` — "Title: {new title}". | SL-A4-03; PM-M09-09 |
| DC-W-12 | Stage (DM-SM-05 note) | From the step running or waiting in the incident's latest live run: Diagnostic or AI task — "Investigation"; Approval or Action — "Response"; Recovery check — "Recovery". When that step is of another type, the stage of the most recent completed step of the run whose type has a stage. No stage yet, or no running or waiting step: "—". | BSD-BR-008; DM-D-03 |
| DC-W-13 | Acknowledge refusals (DM-E35-R1, complete list) | Composed by `vigil-web` from the refusal returned by `vigil-api` (DC-UI-11). Already acknowledged: "Already acknowledged by {display name} at {time}." Incident not `open`: "This incident is {state name} and can no longer be acknowledged." No permission: "You do not have permission to acknowledge." Not in a notified team: "You are not in a team that was notified about this incident." | SL-A1-05, SL-B-13 |

### 7.2 Evidence and AI

| ID | Item | Wording | Trace |
| --- | --- | --- | --- |
| DC-W-03 | Evidence summaries for `diagnostic_result`, `ai_investigation`, `similarity_match` (resolves SB-O-01 for the last two) | `diagnostic_result`: the definition's `summary_template` filled from the result's values (DM-E-53); only a succeeded result gets an entry (DM-S-03). `ai_investigation`: "AI investigation ({provider display name}): {'confirms' or 'disputes'} the hypothesis {hypothesis component name}; runbooks {runbook keys, comma-separated}", or, when `verdict` is empty, "AI investigation ({provider display name}); runbooks {runbook keys}". `similarity_match`: "Similar past incident {past_incident_key} ({record_label}), similarity {score}, fix succeeded {success_count} times: {past incident title}". | SB-O-01; SL-A1-04, SL-A1-06 |
| DC-W-04 | Evidence kind names (complete list) | `diagnostic_result` "Diagnostic"; `ai_investigation` "AI investigation"; `similarity_match` "Similar incident"; `approval` "Approval"; `action_execution` "Action"; `recovery_verification` "Recovery". | SL-A4-03 |
| DC-W-05 | Evidence summaries for `action_execution` and the action result line (resolves SB-O-01 for these) | `vigil-actions` adds one entry when an execution reaches a final state (DC-D-09). By state (complete list): `succeeded` — "{action name} on {target_key}: succeeded, attempt {n} of {limit} — {result summary}", where {result summary} is the registered action's `result_summary_template` (DC-A-01) filled from the execution's operation run output values; when a placeholder has no value, the line ends after "of {limit}"; after a reconciliation, "succeeded (confirmed by state check), attempt {n} of {limit}" without a result summary. `failed` — "{action name} on {target_key}: failed, attempt {n} of {limit} — {operation run error, first 80 characters, or 'exit status {exit_status}'}"; after a reconciliation, "failed (confirmed by state check), attempt {n} of {limit}". `not_executed` — "{action name} on {target_key}: not executed — recheck failed: {recheck name}". `unresolved` — "{action name} on {target_key}: result unknown — the state check could not be completed". Recheck names: `approval_valid` "approval valid"; `unchanged` "action, parameters and target unchanged"; `incident_open` "incident open"; `precondition` "precondition". Recheck details (DM-E-60 `recheck.detail`): `approval_valid` — "Approved by {display names, or 'policy'}; still valid" or "Approval expired before execution"; `unchanged` — "Same action, parameters and target as approved" or "Changed: {action / parameters / target / hypothesis component}"; `incident_open` — "Incident is open" or "Incident is {state name}"; `precondition` — "{operation name} ({parameter name}={value}, …): {result_field} = {value read} (required {operator symbol} {required value})", operator symbols `=`, `≠`, `>`, `≥`, `<`, `≤` (Example for the demo action: "Used percentage of one filesystem (mount=/data): value = 100 (required ≥ 90)"). | SB-O-01; SL-A1-06; PM-M14-09 |
| DC-W-06 | Evidence summaries for `approval` and `recovery_verification` (resolves SB-O-01 for these) | `approval`: `vigil-actions` adds one entry when an approval request reaches a final state other than `cancelled` (DC-D-09). By state (complete list): `approved` — "{action name} on {target_key}: approved by {display name} (request {sequence_in_run})"; `preauthorized` — "{action name} on {target_key}: preauthorized by policy"; `rejected` — "{action name} on {target_key}: rejected by {display name} (request {sequence_in_run})"; `expired` — "{action name} on {target_key}: approval request {sequence_in_run} expired unanswered"; `blocked` — "{action name} on {target_key}: blocked by policy"; `refused` — "{action name} on {target_key}: not requested — attempt limit reached" or "{action name} on {target_key}: not requested — an earlier execution is unresolved". `recovery_verification`: `recovered` — "Recovered ({definition name}): {holding phrases joined by ' and '}, held for {observation window}"; `not_recovered` — "Not recovered after {max wait} ({definition name}): {failing phrases joined by '; '}". Holding phrases: `incident_alerts_resolved` "all {n} incident alerts resolved"; `component_no_firing_alert` "no firing alert on {component name}" (adding " of type {alert type display names}" when `alert_types` is not empty). Failing phrases: "{f} of {n} incident alerts still firing"; "{m} firing alerts on {component name}"; "monitoring source {name} not healthy". | SB-O-01; SL-A1-07, SL-A2-04 |
| DC-W-07 | AI status line | "AI investigation unavailable: {reason}". On screens (DC-S03-R1, DC-S03-R5) " (at {time of the attempt})" follows; stored texts carry no time (DC-UI-11). Reasons (complete list): `no_provider_reachable` "no AI provider could be reached"; `no_provider_allowed` "data routing allows none of the providers in the order"; `output_failed_checks` "the AI output failed the checks". | SL-A2-05; DM-E-32 |
| DC-W-08 | Provider names, outcomes, providers tried (complete list) | Provider display names by kind (complete list): `claude` "Claude"; `openai` "OpenAI"; `local_model` "Local model"; `local_embedding` "Local embedding model". Outcome words: `succeeded` "succeeded"; `unreachable` "unreachable"; `provider_error` "provider error"; `refused_by_data_routing` "not sent (data routing)". "Providers tried" line: "{provider display name} — {outcome word} ({'External' or 'In-house'})". | SL-A2-02, SL-A2-05, SL-A2-07 |
| DC-W-09 | Recovery condition text (complete list) | Condition text (Approval page and workspace): `incident_alerts_resolved` — "All alerts of the incident resolved at their source"; `component_no_firing_alert` — "No firing alert on {component name}" (adding " of type {alert type display names}" when set). Detail while observing (workspace): `incident_alerts_resolved` — "{r} of {n} resolved"; `component_no_firing_alert` — "No firing alert" or "{m} firing alerts". | SL-A1-06, SL-A1-07 |
| DC-W-14 | Approval refusals (DM-E59-R1, complete list) | Composed by `vigil-web` from the refusal returned by `vigil-api` (DC-UI-11). Not pending: "This request is already {state name}." Expired: "This request expired at {time}." No permission: "You do not have permission to approve actions." Not in an eligible team: "You are not in a team that may approve this request." Excluded approver: "You approved an earlier step of this run. A different approver is needed." Snapshot changed: "This request changed after you opened it. Review it again." | SL-A1-06, SL-B-03, SL-B-13 |

### 7.3 Workflow

| ID | Item | Wording | Trace |
| --- | --- | --- | --- |
| DC-W-11 | Waiting text on the canvas (complete list) | Approval — "Waiting for approval — expires {time}"; Recovery check — "Observing recovery — window {elapsed} of {window}" or, when conditions do not hold, "Observing recovery — conditions not holding; gives up at {max_wait_until time}"; Escalation — "Waiting for acknowledgement — next escalation to level {L + 1} at {timer due time}", where L is the incident's current `escalation_level`; when L is already the last level, "Waiting for acknowledgement — last level; {team names} notified again at {timer due time}"; Wait/timer — "Waiting until {due time}" (duration) or "Waiting for acknowledgement until {due time}" (event). | SL-A1-08 |
| DC-W-15 | Step type display names and box summary lines (complete list) | Display names (complete list, also the palette order of DC-S06-R4): `trigger` "Trigger"; `condition` "Condition"; `diagnostic` "Diagnostic"; `ai_task` "AI task"; `approval` "Approval"; `action` "Action"; `recovery_check` "Recovery check"; `escalation` "Escalation"; `notification` "Notification"; `ticket` "Ticket create/update"; `wait` "Wait/timer". Summary lines: Trigger — "Incident created or reopened"; Condition — "{n} branches"; Diagnostic — "{n} diagnostics on the hypothesis component"; AI task — the task name (DC-W-31); Approval — "Eligible: {team names} · expires after {expiry}"; Action — "Runs the approved proposal"; Recovery check — "Gives up after {max_wait}"; Escalation — "Wait for acknowledgement · timer {timer}" or "Escalate now"; Notification — "To: {'owner team', 'informed parties', team names}" in that order; Ticket create/update — "Create ticket" or "Resolve ticket"; Wait/timer — "Wait {duration}" or "Until acknowledged · timeout {timeout}". A missing required value shows "—" in its place. | SL-WE-01; DM Section 9 |
| DC-W-16 | Parameter labels, controls and messages (DM Section 9) | Each control is shown with its label (complete list: parameter key — label — control). Trigger: `event` — "Starts on" — a choice with the single value "Incident created or reopened". Condition: `branches` — "Branches" — a list of branches, each with "Branch label" and a list of comparisons (field, operator, value; DC-W-20), with "Add branch", "Remove branch", "Move up", "Move down", "Add comparison", "Remove comparison"; the operator choice offers only the operators of the field's value type (DM-R-26); the value control is a choice for enums, teams, services, components and alert types (active teams, the current service map, the alert type definitions; shown by name), several choices for `in` and `not_in`, one choice for `contains` and `not_contains` on set fields, a whole number for integers, and "Yes"/"No" for booleans. Diagnostic: `diagnostics` — "Diagnostics" — an ordered choice of active diagnostic definitions by name; `target` — "Run on" — a choice with the single value "Hypothesis component". AI task: `task` — "Task" — a choice of the three task names (DC-W-31). Approval: `eligible_teams` — "Eligible teams" — several choices of active teams; `expiry` — "Expiry" — whole seconds. Recovery check: `max_wait` — "Maximum wait" — whole seconds. Escalation: `mode` — "Mode" — "Wait for acknowledgement" or "Escalate now"; `timer` — "Timer" — whole seconds, shown only for "Wait for acknowledgement". Notification: `recipients` — "Recipients" — check boxes "Owner team", "Informed parties", "Named teams"; `named_teams` — "Named teams" — several choices of active teams, shown only when "Named teams" is checked. Ticket create/update: `operation` — "Operation" — "Create" or "Resolve". Wait/timer: `mode` — "Mode" — "Duration" or "Until event"; `duration` — "Duration" and `timeout` — "Timeout" — whole seconds; `event` — "Event" — the single value "Incident acknowledged". Seconds fields show the unit "s" after the number. Messages (complete list): "Required."; "Enter a whole number of seconds from {min} to {max}."; "Choose at least one."; "Label must be 1 to 60 characters."; "Branch label must be 1 to 64 letters, digits, '.', '_' or '-', and not 'otherwise'."; "Branch labels must be different." | SL-WE-04; DM-V-06 |
| DC-W-17 | Step ids and change list details | New step id (carries SB-R-08): the type letter (trigger `t`, condition `c`, diagnostic `d`, AI task `a`, approval `p`, action `x`, recovery check `r`, escalation `e`, notification `n`, ticket `k`, wait `w`) followed by 1 + the highest number that follows that letter in any step id of the form letter-plus-digits found in any version, in the current content of any draft, or in any draft's `used_step_ids` (DC-A-08), or 1 when there is none; so an id deleted from a draft is never given again (DM-V-05). Change list details (DM-E-47 `details`): `added` — "Added {type display name} '{label}'"; `removed` — "Removed {type display name} '{label}'"; `changed` — the changed parts joined by "; ": "label '{before}' → '{after}'", "{parameter label}: {before} → {after}" (labels and value forms as in the parameter panel, DC-W-16: seconds as "{n} s", teams by name, choices by their text; lists and sets comma-separated; "none" when empty), "connection {output display text}: {before target step id or 'none'} → {after target step id or 'none'}". Entries are ordered by `step_id`. Example (SB-D03-5): "e1 changed: Timer: 600 s → 60 s"; "p1 changed: connection approved: x1 → p2"; "p2 added: Added Approval 'DB Ops second approval'". | SB-R-08; SL-WE-09 |
| DC-W-18 | Validation messages (DM-E48-R1, complete list) | `one_trigger` — "The workflow must have exactly one Trigger step." (on each Trigger when there are several; on "Workflow" when there is none); `reachable_from_trigger` — "This step cannot be reached from the Trigger."; `no_unconnected_step` — "This step has no incoming connection." or "This step is not connected."; `condition_branch_connected` — "Branch '{label}' is not connected."; `action_guarded` — "This Action step is not preceded by an Approval step on every path." or "A path after this Action step does not reach a Recovery check step."; `cycle_has_wait` — "This loop has no Wait/timer step."; `parameter_valid` — "{parameter label}: {DC-W-16 message}", or "{parameter label}: team {name} is not active.", or "{parameter label}: diagnostic {name} is not active." | SL-WE-08 |
| DC-W-19 | Test replay notes (DM-E-49 `note`, DM-R-49) (complete list) | Trigger — no note. Condition — "Branch {label} taken" or "otherwise taken". Diagnostic, AI task, Action, Recovery check — "Stored result reused" or "no stored result" (AI task SL-T-2: "no stored result (template summary)"). Approval — "no proposal"; for the run's first Approval execution "Simulated approval (eligible: {team names, comma-separated})"; for every later one "Simulated approval (eligible: {team names}); approver must differ from the approvers of the earlier Approval steps". Action refused by the guard — "Refused: {guard reason, DC-W-31}". Escalation, wait mode — "Simulated escalations: level {l} {team names} at {+m:ss}; …", ending with "; acknowledged at {+m:ss}" when the acknowledgement ended the wait, or "Acknowledged at {+m:ss}; no escalation"; escalate now — "Simulated escalation: level {l} {team names}". Notification — "Would notify: {team names, alphabetical}". Ticket create/update — "Simulated". Wait/timer — "Simulated wait of {duration}", "Acknowledged at {+m:ss}" or "Timed out after {timeout}". Simulated times are shown as "+m:ss" after the source incident's `stable_at` (minutes MAY exceed 59). | SL-A2-06; SB-D03-4 |
| DC-W-20 | Comparison text | "{field} {operator} {value}", comparisons joined by " AND ". Fields (complete list, DM-R-25): `severity` "Severity"; `owner_team` "Owner team"; `informed_teams` "Informed teams"; `hypothesis_component` "Hypothesis component"; `hypothesis_component_kind` "Hypothesis component kind"; `hypothesis_criticality` "Hypothesis criticality"; `affected_services` "Affected services"; `affected_service_count` "Affected service count"; `highest_service_criticality` "Highest service criticality"; `alert_count` "Alert count"; `alert_types` "Alert types"; `alert_sources` "Alert sources"; `acknowledged` "Acknowledged"; `escalation_level` "Escalation level"; `ai_investigation_available` "AI investigation available"; `reopen_count` "Reopen count". Operators (complete list, DM-R-26): `equals` "is"; `not_equals` "is not"; `in` "is one of"; `not_in` "is not one of"; `at_least` "is at least"; `at_most` "is at most"; `contains` "contains"; `not_contains` "does not contain"; `greater_than` "is greater than"; `greater_or_equal` "is greater than or equal to"; `less_than` "is less than"; `less_or_equal` "is less than or equal to"; `is` "is". Values: enum values as stored (Example: "critical"); team, service, component and alert type keys by name; several values comma-separated; booleans "yes" or "no". Example (rule `p1-critical-infra`): "Highest service criticality is at least critical AND Hypothesis component kind is one of server, database". | SL-A4-08, SL-WE-04 |

### 7.4 Setup, routing, AI settings, dashboard

| ID | Item | Wording | Trace |
| --- | --- | --- | --- |
| DC-W-21 | Service map import results | Success: "Service map generation {n} imported: {s} services, {c} components, {k} identity keys, {d} dependencies." Refusal: "Import refused — nothing changed:" followed by one line per failed rule (DM-R-55, complete list): "{section}: key {key} appears more than once"; "{section} {key}: component {ref} does not exist"; "components {key}: runs_on_key {ref} is not a server"; "components {key}: a server cannot have runs_on_key"; "components {key}: only a server can have target_key"; "identity_keys: {source_kind} {name} = {value} names more than one component"; "dependencies: {from} → {to} is listed more than once"; "dependencies: {key} depends on itself"; "dependencies: cycle {key → … → key}"; "services {key}: has no components"; "{section} {key}: {field} value '{value}' is not allowed"; and, when the file cannot be read, "The file could not be read: {reason}". | SL-A2-01; DM-R-55 |
| DC-W-22 | Readiness gap lines (DM-E-81) (complete list) | `server_without_owner` — "Server with no owner: {component name} ({component_key})"; `service_without_criticality` — "Service with no criticality: {service name}"; `source_not_healthy` — "Source not healthy: {source name} ({health state name})". | SL-A2-01 |
| DC-W-23 | Routing change summary (DM-E-41 `change_summary`) | Revision 1 (seed): "Initial rule set". Otherwise one part per changed rule, joined by "; ": "{rule name}: {changed fields}", where each changed field is "{field} {before} → {after}" with fields "severity", "owner", "owner team", "informed teams" (team names, "none" when empty) and "inform affected service owners" ("yes"/"no"), joined by ", "; added, removed and moved rules (seed import only) as "Added rule {name}", "Removed rule {name}", "Order changed". Example (SB-D03-3): "Critical service, infrastructure or database cause: informed teams none → Customer Care". | SL-A2-06, SL-A2-08 |
| DC-W-24 | Owner text (complete list) | `fixed_team` — "{team name}"; `hypothesis_component_owner` — "Owner of the hypothesis component (fallback {team name})". | SL-A4-08 |
| DC-W-25 | Data categories (DM-R-42) (complete list) | `alert_data` "Alert data — alert types, titles, sources and times"; `service_context` "Service context — names and relations from the service map"; `diagnostic_output` "Diagnostic output — values and text returned by servers"; `runbook_text` "Runbook text"; `incident_history` "Incident history — content of past incidents"; `incident_record` "Incident record — what {product name} wrote about the incident: title, AI results, summaries, approvals, actions and recovery checks". | SL-A2-02; SL-O-13 |
| DC-W-26 | Metric definition lines (DM-R-53) (complete list) | Time to acknowledge — "Mean time from detection to the first acknowledgement"; Time to recover — "Mean time from detection to the first resolution"; Alerts suppressed — "Alerts received minus incidents"; Incidents merged — "Incidents built from two or more alerts". | SL-A1-09 |

### 7.5 Audit trail and control panel

| ID | Item | Wording | Trace |
| --- | --- | --- | --- |
| DC-W-27 | Audit event type names (DM-E08-R1, complete list) | `incident_created` "Incident created"; `alert_grouped` "Alert grouped"; `hypothesis_changed` "Hypothesis changed"; `assignment_changed` "Assignment changed"; `incident_acknowledged` "Acknowledged"; `incident_escalated` "Escalated"; `ai_result_accepted` "AI result accepted"; `ai_result_rejected` "AI result rejected"; `policy_check_result` "Policy check"; `approval_requested` "Approval requested"; `approval_decided` "Approval decided"; `action_executed` "Action executed"; `action_reconciled` "Action reconciled"; `recovery_verified` "Recovery verified"; `incident_state_changed` "Incident state changed"; `notification_sent` "Notification sent"; `ticket_changed` "Ticket changed"; `configuration_changed` "Configuration changed". | SL-A2-08 |
| DC-W-28 | Audit summaries (DM-E-08 `summary`) | `incident_created` — "Incident created from {alert type display name} on {component name or 'unknown component'}"; `alert_grouped` — "{alert type display name} on {component name or 'unknown component'}: {'new incident', 'joined' or 'reopened the incident'}"; `hypothesis_changed` — "Hypothesis: {old name or 'none'} → {new name or 'none'}"; `assignment_changed` — "{severity}, owner {team}, informed {teams or 'none'} (rule {rule_key}, revision {n})"; `incident_acknowledged` — "Acknowledged by {display name}"; `incident_escalated` — "Escalated to level {level}: {team names} ({cause as DC-W-10})"; `ai_result_accepted` — "AI investigation accepted from {provider display name} ({'workflow step' or 're-run'})"; `ai_result_rejected` — "AI output rejected from {provider display name}: {first rejection reason}"; `policy_check_result` — "Policy check for {action name}: {'approval required', 'preauthorized' or 'blocked'}"; `approval_requested` — "Approval request {sequence_in_run} for {action name} on {target_key}, expires after {expiry}"; `approval_decided` — "{action name}: {'approved by {display name}', 'rejected by {display name}', 'expired', 'preauthorized', 'blocked', 'refused ({refusal reason, DC-W-31})' or 'no proposal'}"; `action_executed` — "{action name} on {target_key}: attempt {n}, {outcome}"; `action_reconciled` — "{action name} on {target_key}: reconciled as {outcome}"; `recovery_verified` — "Recovery check {definition name}: {'recovered' or 'not recovered'}"; `incident_state_changed` — "State: {from} → {to}"; `notification_sent` — "{'Incident notification', 'Escalation' or 'Approval request'} to {team name or user display name} by {'Teams' or 'email'}: {'sent' or 'failed'}"; `ticket_changed` — "Jira {'create', 'reopen', 'resolve' or 'escalation comment'} {external key or 'no key'}: {'done' or 'failed'}"; `configuration_changed`, by area (complete list): `tenant_settings` "Tenant settings changed: {field names}"; `users_and_teams` "Users and teams changed"; `sources` "Monitoring source {name}: address changed"; `service_map` "Service map generation {n} imported: {counts as DC-W-21}"; `routing_rules` "Routing rule set revision {n} saved: {change summary}"; `escalation_levels` "Escalation policy revision {n} saved"; `workflows` "Workflow version {n} published from draft revision {r}" or "Workflow version {n} published by rollback to version {m}"; `ai_settings` "AI providers for {task name, DC-W-31}: {providers joined by ' → '}" or "Data routing changed: {category names}"; `registered_actions` "Registered action {name} changed"; `seed_import` "Seed import {bundle_name} {bundle_version}: {'succeeded' or 'failed'}". | SL-A2-08; SB-D01 |
| DC-W-29 | Control panel messages (demo-only) | Storm guard refusals (complete list): "Refused: a fault is active. Press 'Clear all faults' first."; "Refused: clearing faults is still running."; "Refused: storms are not injected between 23:50 and 00:20 UTC." Overlap refusals (complete list): "Refused: another operation is running."; "Refused: SL-F-4 is already active." When several refusals apply, only the first in this order is shown: clearing faults is still running; another operation is running; a fault is active; SL-F-4 is already active; the 23:50 to 00:20 UTC window. Results (complete list): "Injected {fault IDs} at {HH:mm:ss} UTC."; "All faults cleared in {s} s."; "Clearing failed after {s} s: {reason}"; "Reset complete in {s} s."; "Reset failed after {s} s: {reason}". | SL-A3-03; DD-F-10 |
| DC-W-30 | Product name | "Vigil". The only place the product name is defined; every pattern that says {product name} uses it. Agreed by owner (CTX-D-022). | DC-D-04 |
| DC-W-31 | Other display texts and reasons (complete list) | AI task names: SL-T-1 "Incident investigation"; SL-T-2 "Incident summary"; SL-T-3 "Similar-incident search". Run end reasons (DM-E-50 `end_reason`): `path_ended` "Run finished"; `incident_merged` "Run cancelled: incident merged"; `incident_closed` "Run cancelled: incident closed"; `incident_reopened` "Run cancelled: incident reopened"; `step_failed_repeatedly` "Run failed: a step did not answer"; `step_limit_reached` "Run failed: step limit reached". Test replay states: "Running", "Completed", "Failed"; failure reasons: "the draft has problems to fix"; "the incident is not open, resolved or closed"; "the incident has no workflow run"; "the test run failed ({run end reason text})". Configuration area names (DM-V-11): `tenant_settings` "Tenant settings"; `users_and_teams` "Users and teams"; `sources` "Monitoring sources"; `service_map` "Service map"; `routing_rules` "Routing rules"; `escalation_levels` "Escalation levels"; `workflows` "Workflow"; `ai_settings` "AI settings"; `registered_actions` "Registered actions"; `seed_import` "Seed import". Approval refusal reasons (DM-E-58): `attempt_limit_reached` "attempt limit reached"; `unresolved_execution` "an earlier execution is unresolved". Action guard reasons (DM-R-31): "no proposal"; "no approval for the proposal since the last execution"; "a path after this step does not reach a Recovery check step". Routing save refusals (DM-E41-R1): "The last rule must be the only rule without conditions."; "Team {name or key} is not active."; "Rule {name}: {field} cannot be compared with '{operator}'."; "Rule {name}: '{value}' is not a valid value for {field}." AI settings save refusals (DM-E-72): "Choose at least one provider."; "{provider} is not allowed for {task name}."; "{provider} is listed twice."; "Choose exactly one provider." Draft save failure reasons: "the draft was changed elsewhere"; "the connection was lost"; any refusal returned by `vigil-workflows`, shown as returned. | DM-E-50, DM-E-49, DM-V-11, DM-E41-R1, DM-E-72 |
| DC-W-32 | Evidence source and collection time (DM-E-38 `source_system`, `collected_at`) | By kind (complete list): `diagnostic_result` — "{target_key} via runner, operation {operation_key}" (as SB-S03), collected at the operation run's finish time; `ai_investigation` — "{provider display name} ({model name}) via AI gateway", collected when the result was stored; `similarity_match` — "Past incident history via {provider display name}", collected at `searched_at`; `approval` — "Approval request {sequence_in_run}", collected when the request reached its final state; `action_execution` — "{target_key} via runner, operation {operation_key}", collected when the execution reached its final state; `recovery_verification` — "Monitoring alert states", collected at `decided_at`. | DM-E-38; SB-S03 |

## 8. Outbound content (complete list)

The audience sees these in Jira, Teams and the mailbox (SB-S05, SB-S06, SB-D03-7). DC-OUT-00 (general). Each item below is one line of text, in the order given; how lines become Jira's description format and a Teams message is decided in Round 5, with the rule that each line shows as its own paragraph, each "- " line as a list item, and no text is read as markup (DC-ASM-01, DC-ASM-02). When an incident has no evidence entry, the "Evidence:" line reads "Evidence: none" and no "- " lines follow. {prefix} is `environment_label` followed by " - " (space, hyphen, space), or nothing when the label is empty (DM-E02-R2). Outbound content contains no clock times, because the reader's time zone is unknown (DC-UI-11, DC-D-10). Links are absolute HTTPS addresses of the named page; their paths are decided in Round 5 (DC-O-04).

| ID | Item | Content (complete list, in order) | Trace |
| --- | --- | --- | --- |
| DC-OUT-01 | Jira ticket on create | Title: "{prefix}{incident title}". Description lines: "Alerts: {alert_count} grouped alerts — {alert type display names, alphabetical, comma-separated}"; "Customer impact: {service names, comma-separated, or 'none identified'}"; "Root-cause hypothesis: {component name} ({component key})" or "Root-cause hypothesis: none"; "Summary: {incident summary text}"; "Evidence:" followed by one line "- {summary}" per evidence entry (PM-M09-11 order); "Incident in {product name}: {workspace link}" (DC-W-30). The ticket also carries the incident identifier label of DM-E76-R2 (format Round 5). No other field is set from incident data (DC-D-15). | SL-A1-05; SB-S05 |
| DC-OUT-02 | Jira updates (complete list) | Resolve: the comment "Resolved after recovery verification: {recovery_verification evidence summary}", then the transition to the resolved status (Round 5, DM-O-12). Escalation: the comment "Escalated to level {level}: {team names} — {'not acknowledged within {timer}' or 'escalated by the workflow'}." followed by "Evidence:" and one "- {summary}" line per evidence entry. Reopen: the comment "Reopened: {alert type display name} on {component name} fired within the reopen window.", then the transition to an open status (DM-SM14-T3). | SL-A1-07, SL-A2-03, SL-A2-04 |
| DC-OUT-03 | Incident notification (Teams message and email) | Line 1 (also the email subject): "{prefix}{severity} {incident number}: {incident title}"; "Owner: {team} · Informed: {teams or 'none'}"; "Customer impact: {service names or 'none identified'}"; "Summary: {incident summary text}"; "Acknowledge: {Acknowledge page link}". | SL-A1-05; SB-S05 |
| DC-OUT-04 | Escalation message (Teams message and email) | Line 1 (also the email subject): "{prefix}Escalation level {level} — {severity} {incident number}: {incident title}"; "{'Not acknowledged within {timer}.' or 'Escalated by the workflow.'}"; "Owner: {team} · Informed: {teams or 'none'}"; "Summary: {incident summary text}"; "Evidence:" and one "- {summary}" line per evidence entry; "Acknowledge: {Acknowledge page link}". | SL-A2-03, SL-A2-04; PM-M20-03 |
| DC-OUT-05 | Approval request message (Teams message and email, to each user) | Line 1 (also the email subject): "{prefix}Approval needed — {incident number}: {incident title}"; "Action: {action name} on {target_key} ({target name})"; "This request expires {expiry} after it was sent."; "Summary: {incident summary text}"; "Review and decide: {Approval page link}". | SL-A1-06; SB-S06 |

DC-OUT-06 (length). When a Teams message or email would be longer than 4,000 characters (DM-E-78 `content`), the summary line is cut first (DC-UI-11); if still too long, evidence lines are removed from the last one and replaced by one line "- … and {k} more". The Jira description and comments use the same rule with the same limit. In every item, a component that is unknown or not in the current service map is written "unknown component" or by its key respectively.

## 9. Later (not built) items added by this document (complete list)

These are screen capabilities of the full product that no list A item requires (DC-R-03). Each leaves the module records unchanged (DC-R-01).

| ID | Item | Trace |
| --- | --- | --- |
| DC-L-01 | Service map viewer: services, components, dependencies, owners and earlier generations on screen. | BSD §11.1 Setup center; BSD-BR-005; PM-M07-L1 |
| DC-L-02 | Creating, deleting and reordering routing rules, editing their names and conditions, and editing escalation levels on screen. | BSD §11.2 Business settings; BSD-BR-007 |
| DC-L-03 | Creating new monitoring sources (connector instance, monitoring source and webhook token) and changing connector credentials on screen; in the build, sources are created by seed import (DC-D-06). | BSD §11.1 Setup center; BSD-BR-001 |
| DC-L-04 | Opening and comparing earlier workflow versions on the canvas; listing earlier test replay results; drawing a test replay path on the canvas. | BSD §11.1 Workflow studio; BSD-BR-016 |
| DC-L-05 | Sorting, filtering, searching and paging the incident list (in addition to the impact ordering of PM-M24-L1). | BSD §11.1 Command center |
| DC-L-06 | Views of raw alerts, webhook calls and normalisation results. | BSD-BR-002; PM-M06-L2 |
| DC-L-07 | Runbook text viewer and past incident detail view. | BSD §12.1 Knowledge |
| DC-L-08 | Earlier workflow runs of a reopened incident in the workspace. | BSD §11.1 Execution view |
| DC-L-09 | Dashboard charts, trends and period selection. | BSD §13.2; PM-M21-L1 |
| DC-L-10 | Screens for tenant settings, AI provider configurations, delivery addresses, registered actions and policies, diagnostic definitions, recovery check definitions and knowledge settings. | BSD §11.1 Administration, AI administration; BSD §11.2 |
| DC-L-11 | Acknowledging from the desktop workspace; a reason text when rejecting an approval. | BSD §11.1 Incident workspace, Action review |
| DC-L-12 | Layouts for other screen sizes, browsers other than Google Chrome, and a dark theme (other languages stay with PM-M24-L2). | BSD §6.2 UI |
| DC-L-13 | A list of notification records and delivery statuses per incident. | BSD-BR-010 |
| DC-L-14 | Saved audit trail filters. | BSD §13.2; PM-M03-L1 |

## 10. Scenario walkthrough of the primary demo (CTX-Q-3, complete list of scenes)

| Scene | Screen and regions | Data shown | Outcome on screen |
| --- | --- | --- | --- |
| SL-A1-01 (SB-S01) | DC-S02-R1 in the main window; DC-S12 for the fault | Forming incidents: title, alert count, alert sources, time since the first alert | Forming rows appear within 30 s, counts rise, both source badges show; no number is shown (DM-E32-R3) |
| SL-A1-02 (SB-S02) | DC-S02-R2; DC-S03-R1, R2, R3 | Incident number, title, severity, owner, informed; members with `final_reason`; hypothesis computation; affected services | One numbered storm incident with 138 alerts (30 + 108), reasons per DC-W-02, "Hypothesis: Subscriber DB server", three services; a separate P4 incident in the list |
| SL-A1-03 (SB-S03) | DC-S03-R4 | Three `diagnostic_result` evidence entries | The three summaries of SB-S03 with source and time |
| SL-A1-04 (SB-S04) | DC-S03-R5, DC-S03-R2 (c) | Latest accepted AI result; applied routing evaluation | Verdict, likely cause, evidence, uncertainty, runbooks; P1, Database Operations, VAS Service Owners, rule `p1-critical-infra` |
| SL-A1-05 (SB-S05) | Jira (DC-OUT-01), Teams and email (DC-OUT-03), DC-S04 in the phone window, then DC-S03-R1 | Ticket content; notification content; acknowledgement | Ticket title of SB-S05; messages with prefix, summary and link; "Acknowledged by Ayesha Siddiqui (NOC L1) at {time}" |
| SL-A1-06 (SB-S06) | DC-S03-R6 (a); Teams approvals channel (DC-OUT-05); DC-S05; DC-S03-R6 (b) to (d) | Similarity search result; approval snapshot; approval decision; action execution with rechecks | PI-0342, "Similarity 1.00", "Fix succeeded 4 times"; Approval page with the SB-S06 values; "Approved by Bilal Qureshi (DB Ops)"; four rechecks passed; result line per DC-W-05 |
| SL-A1-07 (SB-S07) | DC-S03-R3 heading, DC-S03-R7, DC-S03-R1; Jira (DC-OUT-02) | Alert states; recovery verification; incident state; ticket comment | Resolved count rises to 138 of 138; window counts to 60 s; state Resolved; Jira resolved with the recovery summary |
| SL-A1-08 (SB-S08) | DC-S03-R9 | The run's content and step executions | Version 1 path with outputs; highlight moved within 2 s at each step change |
| SL-A1-09 (SB-S09) | DC-S10 | Four metrics and baselines (DM-R-53) | Values of SB-V-40 against "Illustrative baseline" values |

Result: every primary scene has a screen, its data and an outcome on screen. The deep dives (SL-A2) are covered in Section 11.

## 11. Traceability

### 11.1 Scope lock items to this document (complete list)

| Scope lock item | Covered by |
| --- | --- |
| SL-A1-01 to SL-A1-09 | Section 10 (each scene) |
| SL-A2-01 | DC-S07; DC-M22, DC-D-06 |
| SL-A2-02 | DC-S09-R2 to R5, A1, A2 |
| SL-A2-03 | DC-S03-R1, R8; DC-W-11; DC-OUT-02, DC-OUT-04 |
| SL-A2-04 | DC-S03-R6, R7; DC-W-05, DC-W-06; DC-OUT-02, DC-OUT-04 |
| SL-A2-05 | DC-S03-R5; DC-W-07, DC-W-08 |
| SL-A2-06 | DC-S06 (all regions and actions); DC-S08; DC-W-17, DC-W-19, DC-W-23 |
| SL-A2-07 | DC-S03-A1, A2; DC-S09-R5 |
| SL-A2-08 | DC-S11; DC-W-27, DC-W-28 |
| SL-A3-01, SL-A3-02, SL-A3-08 | DC-M33 (no content in this document) |
| SL-A3-03 | DC-S12; DC-M34 |
| SL-A3-04 | DC-R-05 (no demo logic); repeatable values shown come from records (Sections 6 and 7) |
| SL-A3-05 | Not a screen item (Round 5 hosting); DC-UI-08 login redirect |
| SL-A3-06 | DC-UI-02, DC-UI-04; DC-OUT-01, DC-OUT-03 to DC-OUT-05 |
| SL-A3-07 | DC-R-06; DC-UI-03; actions' permission columns; DC-S01-R3 |
| SL-A4-01 to SL-A4-12 | DC-S01 to DC-S12 respectively |
| SL-T-1, SL-T-2, SL-T-3 | DC-M32; DC-S03-R5, R2 (d), R6 (a); DC-S09-R2 |
| SL-WE-01 | DC-S06-R4, A2; DC-W-15 |
| SL-WE-02 | DC-S06-R3, A3 |
| SL-WE-03 | DC-S06-A4, A5 |
| SL-WE-04 | DC-S06-R5; DC-W-16 |
| SL-WE-05 | DC-S06-A6 |
| SL-WE-06 | DC-S06-A7 |
| SL-WE-07 | DC-S06-R1, saving rule |
| SL-WE-08 | DC-S06-R3, A8, A9; DC-W-18 |
| SL-WE-09 | DC-S06-R7; DC-W-17 |
| SL-WE-10 | DC-S03-R9 |
| SL-B-01 to SL-B-12, SL-B-14 | DC-R-01 (built in every module, not shown); DC-M01 to DC-M24 |
| SL-B-13 | DC-S01, DC-S04, DC-S05 (login required; link grants nothing); DC-R-06 |
| SL-DR-01, SL-DR-02, SL-DR-04 | DC-R-05 (all shown data comes from `31-demo-data`) |
| SL-DR-03 | DC-UI-02, DC-UI-04, DC-UI-07; DC-S02-R3; DC-OUT-01, DC-OUT-03 to DC-OUT-05 |
| SL-CR-01 to SL-CR-03, SL-CP-1 to SL-CP-3 | DC-R-03 (no item added to list A); checkpoints are Round 6 |
| SL-X-01 to SL-X-16 | Not product items; SL-X-01 and SL-X-02 are reflected in DC-S04, DC-S05 and DC-OUT-03 to DC-OUT-05 (links, Teams and email only); SL-X-04 in DC-S08 and DC-S06 |
| SL-C-01 to SL-C-19 (SL-C-11 withdrawn) | Not built; Later items of `10-product-map` carried in Section 4.2 and DC-L-01 to DC-L-14; SL-C-10 by DC-UI-01 (mobile pages only); SL-C-17 by DC-M14 (not shown); SL-C-18 by the draft revision rule in DC-S06 |
| SL-ASM-01 to SL-ASM-04 | Not affected by this document (Round 5) |
| SL-O-01 to SL-O-18, SL-O-20 to SL-O-23 | Values from Round 2 and Round 3 shown on the screens named in Section 6 "Exact demo values"; SL-O-11 (format) and SL-O-17, SL-O-20 stay with Round 5 |
| SL-O-19 | Round 6 (screens of checkpoint SL-CP-1) |
| SL-F-1 to SL-F-4 | DC-S12-A1 to A5 |

### 11.2 Open items resolved here (complete list)

| Open item | Resolved by |
| --- | --- |
| DM-E32-R3 (display of forming incidents) | DC-S02-R1, DC-S03 forming text; DC-D-02 |
| DM-O-11 (incident number format) | DC-W-01; DC-D-03 |
| SB-O-01 (evidence summary wording and action result) | DC-W-03, DC-W-05, DC-W-06; DC-D-09 |
| SB-O-01 (evidence source wording, related) | DC-W-32 |
| SB-R-08 "Round 4 carries this rule into the editor screen content" | DC-W-17; DC-S06-A2 |

## 12. Explicitly out of scope for this document

Visual design: colours, fonts, icons, spacing, logo size and placement. Exact layout beyond the region order given. Public interface operations, URL paths and link formats (Round 5). The stack, the canvas library and the auto-layout method (Round 5 and Round 6). The reset mechanism (SL-O-17, Round 5). Build packages and the screens of checkpoint SL-CP-1 (Round 6). The lab and the demo-only components other than the control panel screen (earlier documents).

## 13. Decisions, changes to earlier documents and assumptions

### 13.1 Decisions made in this round (complete list)

Status "Agreed by owner" means Abdul agreed the decision under CTX-Q-7 on 9 October 2026. Status "Decided by owner" means Abdul gave the answer himself. "Proposed" means Claude made the decision within the scope lock and earlier documents; Abdul MAY change any of them through the `00-context` decision log.

| ID | Decision | Reason | Status | Trace |
| --- | --- | --- | --- | --- |
| DC-D-01 | Lean cut (DC-R-01): screens offer only what list A needs, while modules keep their full records and rules. Round 4 is a text specification without wireframes. | One builder (CTX-C-01); the pilot extends screens without rebuilding modules (CTX-G-2). | Decided by owner | CTX-C-01, CTX-G-2 |
| DC-D-02 | Forming incidents are shown in a "Grouping in progress" area above the incident table, without numbers, and cannot be opened; merged rows disappear; the workspace shows a short text for a forming or merged incident. | Meets DM-E32-R3; the audience watches the storm build up without seeing several storm incidents; the workspace never has to follow a merge. | Agreed by owner | DM-E32-R3; SB-S01 |
| DC-D-03 | Incident numbers are shown as "INC-" and four or more digits. | Distinct from past incident keys ("PI-"); familiar to NOC staff. | Proposed | DM-O-11 |
| DC-D-04 | The product name "Vigil" is shown in the screen header, on the login and mobile pages, in the Approval page confirmation, on the dashboard data note and in the Jira link line. It is defined once (DC-W-30), so a different name changes one item. | The storyboard narration presents the product as Vigil (SB-S00), and a product needs a visible name. `00-context` Section 3 says the name is used only by Abdul, Muzaffar and Claude, so this needs the owner's agreement and a change to that section. | Agreed by owner | SB-S00; CTX §3 |
| DC-D-05 | The routing rules screen edits only the outcome of an existing rule (severity, owner, informed teams, informing service owners); names, conditions, adding, removing and reordering rules, and escalation levels are read-only. | SL-A2-06 changes one informed team; the rest is seed data in the demo and needs a condition editor, rule ordering and their tests. | Agreed by owner | SL-A2-06, SL-O-21 |
| DC-D-06 | "Connect a source" in SL-A2-01 means, on screen: set the address of one of the existing monitoring sources and test it. Creating a new source, and changing credentials, are done by seed import. PM-M22-01 stays built through these two paths. This narrows how SL-A2-01 is shown, so it is recorded as a scope-lock clarification on owner agreement (DC-A-09), as CTX-D-015 and CTX-D-019 were. | Credentials live in the deployment secret store (PM-O-11); a new source also needs a new webhook token that only deployment can create. The demo shows the connection test, not source creation (SB-D02). | Agreed by owner | SL-A2-01, SL-B-14 |
| DC-D-07 | The incident list has a second tab, "Past incidents", that lists the 50 seeded past incidents read-only with their record label. | SL-DR-03 requires the incident history list to label its records as fictional, and no other screen lists them. It also backs the dashboard narration about the last 30 days. | Agreed by owner | SL-DR-03; PM-M24-05 |
| DC-D-08 | The Approval page shows parameter values exactly as stored, with no unit conversion; the human reading (24 hours, 12 GiB) is in the limit texts. | The approver approves exactly what runs (SL-A1-06); unit metadata would be a new parameter field. | Proposed | SL-A1-06 |
| DC-D-09 | A registered action gets a `result_summary_template` (DC-A-01), and `vigil-actions` adds an evidence entry each time an approval request reaches a final state other than `cancelled` and each time an action execution reaches a final state. | SB-S06 shows the number of files and GiB removed; the template makes this data, as diagnostics already are (DM-E-53). The entry rule makes PM-M14-09 exact. | Agreed by owner | SB-O-01, SB-S06; PM-M14-09 |
| DC-D-10 | Screens show times in the browser's time zone with the offset in the header; outbound messages and tickets contain no clock times; the control panel shows UTC. | The reader's time zone is unknown for messages; the lab and its storm window run in UTC (SB-R-07). | Proposed | CTX-W-08 |
| DC-D-11 | Main screens are built for desktop Google Chrome at 1280 × 720 px or larger; Login, Acknowledge and Approval also work at 360 × 780 px; English and one light theme. | The operator uses browser profiles (SB-SET-03); one browser is chosen here so layouts are built and tested once. | Agreed by owner | SB-SET-03; SL-A4-04, SL-A4-05 |
| DC-D-12 | Workflow drafts save automatically within 1 second of each change; there is no "Save" button. | "The draft is saved" (SB-D03-1) without an extra operator step; every undo point is saved. | Proposed | SL-WE-07 |
| DC-D-13 | A test replay result is a table showing the latest replay of the browser session; the path is not drawn on the canvas. | SB-D03-4 reads the path, notes and assignment; a table shows them fully. | Proposed | SL-A2-06 |
| DC-D-14 | Alert states and recovery evaluations in the workspace refresh within 5 seconds; the 2-second rule stays for incident and step changes. | `vigil-api` pushes only incident and step changes today (PM-M23); 5 seconds is enough for SB-S07. Round 5 decides whether to push or poll (DC-O-02). | Proposed | SB-S07; PM-M23-04 |
| DC-D-15 | The Jira ticket holds exactly the SL-A1-05 content and the identifier label; no Jira priority or other field is set from incident data. | SL-A1-05 gives a complete list. | Proposed | SL-A1-05 |
| DC-D-16 | The Approval page has "Approve" and "Reject", with no reason field. | Rejection is part of the approval model (DM-SM11-T6); a reason field is Later (DC-L-11). | Proposed | SL-A1-06 |
| DC-D-17 | The audit trail lists events in order of occurrence, 200 at a time, with two filters (incident, event type). | SB-D01 uses exactly these two filters and reads the events in order; the demo run produces a few hundred events. | Proposed | SL-A2-08 |
| DC-D-18 | Lean removals in v0.2 (complete list): routing revision history and escalation levels on the routing screen; provider configurations on the AI settings screen; health thresholds on the setup screen; the editor problems panel; the control panel log; the audit user and time filters. Configured durations are shown in seconds ("60 s", "600 s"). | No list-A item or storyboard step needs them (independent review); seconds match the storyboard values (SB-S06, SB-S07, SB-D03-2). | Proposed | DC-R-01; SB-S06, SB-S07 |

### 13.2 Changes to earlier documents (complete list)

| ID | Document and item | Change | Status |
| --- | --- | --- | --- |
| DC-A-01 | `20-domain-model` DM-E-55 Registered action | Adds the field `result_summary_template: Text(200), Req Y` — "Builds the result line of a succeeded execution from the action operation's output values; placeholders `{name}` name output values (DC-W-05)." Written as `20-domain-model` v1.2. | Done |
| DC-A-02 | `31-demo-data` DD-F-04 `registered_actions` | Adds `result_summary_template: "Removed {files_removed} files ({gib_removed} GiB)"` to `purge-archived-txlogs`; DD-R-10 extends to this template. `tools/check_data.py` passes unchanged. Written as `31-demo-data` v1.1. | Done |
| DC-A-03 | `10-product-map` PM-M14-09 | Clarification: an evidence entry is added when an approval request reaches a final state other than `cancelled`, and when an action execution reaches a final state (DC-D-09). Written as `10-product-map` v1.3, Section 20. | Done |
| DC-A-04 | `20-domain-model` DM-R-49, Notification | "note listing the team keys that would be notified" becomes "note listing the names of the teams that would be notified" (DC-W-19), so every test note names teams the same way (SB-D03-4). Written as `20-domain-model` v1.2. | Done |
| DC-A-05 | `00-context` | Records the adoption of this document, Round 4 status, and, if DC-D-04 is agreed, a Section 3 change allowing "Vigil" as the name shown on screens. Written as `00-context` v1.8. | Done |
| DC-A-06 | `20-domain-model` DM-V-12 Approval snapshot | `based_on` adds `record_label` (copy of the past incident's `record_label`), so the Approval page shows the fictional-record label from the snapshot (DC-S05-R4, SL-DR-03). Written as `20-domain-model` v1.2. | Done |
| DC-A-07 | `30-jazz-storyboard` SB-S06 | Reading of the exact values, no value changed: the Approval page shows the parameters as stored (`path: /data/txlog_archive`, `older_than_minutes: 1440`, `max_bytes: 12884901888`); "24 hours" and "12 GiB" appear in the limit texts (DC-D-08); the precondition recheck reads "Used percentage of one filesystem (mount=/data): value = 100 (required ≥ 90)" (DC-W-05); the expiry "15 minutes after the request" is shown as the absolute expiry time with the remaining time on the Approval page and as "expires after 900 s" in messages and the step box (DC-UI-05). Written as `30-jazz-storyboard` v1.1. | Done |
| DC-A-08 | `20-domain-model` DM-E-46 Workflow draft | Adds the field `used_step_ids: Set<Key>, Req Y` — "Every step id that has been in the draft's content in any revision; used by the step id rule (DC-W-17) so a deleted id is never given again (DM-V-05)." Written as `20-domain-model` v1.2. | Done |
| DC-A-09 | `01-scope-lock` SL-A2-01 and `00-context` decision log | Clarification, no item added: "connect" means setting the address of an existing monitoring source and testing it; sources are created by seed import (DC-D-06). Written as `01-scope-lock` v1.8 and a `00-context` decision, only if the owner agrees DC-D-06. | Done |
| DC-A-10 | `20-domain-model` DM-SM14-T2 | "with the recovery evidence (PM-M19-03): the summary and content of the evidence entry" becomes "with the recovery evidence (PM-M19-03): the summary of the evidence entry" (DC-OUT-02). Written as `20-domain-model` v1.2. | Done |

### 13.3 Assumptions (complete list)

| ID | Assumption | Verified by |
| --- | --- | --- |
| DC-ASM-01 | Jira Cloud's API accepts a description and comments in a form that shows each DC-OUT line as its own paragraph and each "- " line as a list item, without reading any text as markup (for REST API v3 this means building Atlassian Document Format; the API version and conversion are chosen in Round 5). | Round 5 fact check; SB-T-07 |
| DC-ASM-02 | The Teams posting route chosen for SL-ASM-02 keeps line breaks and shows a link that can be copied (SB-SET-04). | Round 5 fact check; SB-T-06 |

## 14. Open items (complete list of items opened by this document)

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| DC-O-01 | Closed by CTX-D-022, 9 Oct 2026 (was: owner agreement to DC-D-04, product name shown on screens, and the matching change to `00-context` Section 3). | Abdul | Round 4 owner review |
| DC-O-02 | Mechanism that shows alert states and recovery evaluations in the workspace within 5 seconds (push of PM-EV-02 through `vigil-api`, or polling). | Claude | R5 (with PM-O-01) |
| DC-O-03 | What the Vigil windows and the phone window must do after a reset (stay logged in, or log in again), and which tenant's banner and customer name the login screen shows before login; both depend on SL-O-17. | Claude | R5 (with SL-O-17 and DC-O-04) |
| DC-O-04 | Paths of the workspace, Acknowledge page and Approval page links, and the webhook address shown in DC-S07-R1. | Claude | R5 |
| DC-O-05 | Canvas library and automatic layout method (DC-S06-A7). | Claude | R5 (library), R6 (method) |
| DC-O-06 | Closed by CTX-D-021, 9 Oct 2026 (was: owner agreement to DC-D-06 and the scope-lock clarification DC-A-09). | Abdul | Round 4 owner review |

Carried open items still open after this round: CTX-O-01, CTX-O-02, CTX-O-04, CTX-O-05; SL-O-11 (format), SL-O-17, SL-O-19, SL-O-20; PM-O-01, PM-O-09 (connector capability), PM-O-10, PM-O-11, PM-O-17; DM-O-04 (field names), DM-O-05, DM-O-06, DM-O-08, DM-O-12, DM-O-13; DD-O-01, DD-O-02, DD-O-03; SB-O-02, SB-O-03.

## 15. Quality-check results

| Check | Result |
| --- | --- |
| DC-Q-N1 Input versions | The repository and the project mirror both held `00-context` v1.7, `01-scope-lock` v1.7, `10-product-map` v1.2, `20-domain-model` v1.1, `30-jazz-storyboard` v1.0 and `31-demo-data` v1.0 at the start of Round 4 (versions and content compared). In the repository, the Round 3 files were staged in git but not yet committed. |
| CTX-Q-1 Coverage both ways | Section 11.1 lists all 140 scope-lock IDs (checked by the independent reviewer). Section 4.2 lists every built responsibility of `10-product-map`, including the Round 2 amendments, with Built and Later IDs matching (checked by the reviewer). Every region, action and wording item has a trace; items found to trace to no list-A need were removed (DC-D-18). |
| CTX-Q-2 Consistency | Compared with every earlier document. Conflicts resolved by decision or amendment, not silently: approval snapshot lacks the record label (DC-A-06); SB-S06 parameter reading (DC-A-07); step id reuse inside a draft (DC-A-08); "connect" narrowed (DC-D-06, DC-A-09); resolve comment content (DC-A-10); test note team names (DC-A-04); action result wording needs data (DC-A-01, DC-A-02); evidence entry timing (DC-A-03); product name shown on screens versus `00-context` Section 3 (DC-D-04, DC-O-01). |
| CTX-Q-3 Scenario walkthrough | Section 10. |
| CTX-Q-4 Independent review | Done, 9 Oct 2026, on v0.1 by a separate Claude reviewer: 23 findings (1 High, 9 Medium, 13 Low), all resolved in v0.2. High: (1) "connect" narrowed without a decision — recorded as a scope-lock clarification on owner agreement (DC-D-06, DC-A-09) and DC-R-03 reworded. Medium: product name defined once (DC-W-30) and module actors shown as "System"; record label added to the approval snapshot (DC-A-06); raw parameter values accepted as the reading of SB-S06 (DC-A-07); precondition detail names its parameters; configured durations in seconds (DC-UI-05); owner team no longer removed from stored rules; connections movable by their end (DC-S06-A4); unauthorized state per screen permission; missing display texts listed (DC-W-31). Low: acknowledgement texts without the repeated team; step ids never reused (DC-A-08); pending changes saved before publish and test replay; step type display names; empty texts; Refresh rule aligned; lean removals (DC-D-18); trace nits; force convention and IDs (DC-UI-12, DC-OUT-00, DC-S03-R0, DC-S06-R9); Jira format moved to Round 5 (DC-ASM-01); task IDs not shown; three ambiguities settled; control panel login (DC-S12-R0). |
| CTX-Q-4 pass 2 | Done, 9 Oct 2026, on v0.2 by a fresh Claude (verification): every resolution present; 6 partly resolved and 16 new findings (0 High, 4 Medium, 12 Low). All resolved in v0.3: DC-O-06 for the owner decision on DC-D-06; record label in the similarity summary; expiry reading added to DC-A-07; withdrawn references removed; merged-incident chain; control panel refusal order; oriented relation path in the alert expansion; resolve comment amendment (DC-A-10); test replay result read every second; force convention extended to Sections 4.1 and 5; withdrawal decision IDs; operator choice per value type; usage table rows and Location text; Section 8 length rule (DC-OUT-06); singular rule scope; "(complete list)" labels; empty texts; view-only controls defined; full refusal text. |
| CTX-Q-5 Claude Code readiness test | Pass 1, 9 Oct 2026, on v0.1 by a fresh Claude given only the `docs/` folder and asked to build every screen and every generated text: 23 questions (10 affecting the primary demo), all answered in v0.2: duration format and one formatter for all texts (DC-UI-05); no clock times in stored texts (DC-UI-11); singular nouns and list order (DC-UI-05); run end reasons and other enum texts (DC-W-31); hide controls (DC-R-06); seed actor "System"; counters (DC-UI-13); date format; evidence source and time (DC-W-32); diagnostic expansion; attempt number source; no-match text; escalation waiting text; acknowledgement text; parameter labels and change-list values (DC-W-16, DC-W-17); usage counts; configuration details display; merged incidents in the audit trail; control panel login and overlap refusals; line breaks in outbound content; "Evidence: none". Pass 2 on v0.2 (with the verification pass): 10 questions, all answered in v0.3 (complete list): re-run progress (DC-S03-A1); providers tried without a stored result (DC-S03-R5); login screen tenant before login (DC-O-03); Approval page parameter order; rechecks stop at the first failure; unknown and unmapped components; source names for the health line; audit incident choice and seed import details; forming row count text; no thousands separators in durations. Remaining questions: 0. |
| CTX-Q-6 Fact check | Not required for Round 4 (CTX-Q-6 applies to R3, R5 and R6). The two external-product dependencies are assumptions DC-ASM-01 and DC-ASM-02. |
| CTX-Q-7 Owner review | Done, 9 Oct 2026. Seven decisions reviewed (DC-D-02, DC-D-04, DC-D-05, DC-D-06, DC-D-07, DC-D-09, DC-D-11): all agreed as proposed. Adoption recorded as CTX-D-020; scope-lock clarification of SL-A2-01 as CTX-D-021; product name as CTX-D-022. Amendments DC-A-01 to DC-A-10 written to the earlier documents; `tools/check_data.py` passes DD-K-01 to DD-K-14 after DC-A-02. |

## 16. Change history

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 9 Oct 2026 | First draft (Round 4). |
| 0.2 | 9 Oct 2026 | Resolved independent review findings and readiness test questions (Section 15). |
| 0.3 | 9 Oct 2026 | Resolved verification pass and readiness pass 2 (Section 15). Draft for owner review. |
| 1.0 | 9 Oct 2026 | Owner review recorded (CTX-D-020 to CTX-D-022); amendments DC-A-01 to DC-A-10 written; Round 4 closed. |
