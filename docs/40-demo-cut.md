# Vigil — Demo Cut

| Field | Value |
| --- | --- |
| Document | 40-demo-cut (ID prefix `DC-`) |
| Version | 1.3 |
| Date | 22 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed). Round 4 closed. Amended by Round 5 (`50-architecture` AR-A-17), Round 6 (`60-build-plan` BP-A-12) and during the build (`60-build-plan` BP-A-28). |

## 1. Purpose

This document decides, for every module of the Vigil product, what is built for the demo: Keep, Simplified, Staged or Later (Section 4), so that the demo is the first version of the product and not a separate build (CTX-G-2, CTX-C-03). It then defines the content of every screen of `01-scope-lock` SL-A4 (what each screen shows, in what order, which actions it offers, to whom, and the exact wording the audience sees), the content of every message and Jira ticket Vigil sends, and the product wording that earlier rounds left to this round.

It resolves the open items assigned to Round 4: DM-O-11 (incident number format), SB-O-02 (wording of evidence summaries), the display of forming incidents (DM-E32-R3, SB-S01) and the display wording of test replay notes (SB-D02 step 3).

Visual design (colours, fonts, icons, spacing) is not defined here, except where a rule below names it. The technology used to build the screens is Round 5.

## 2. Inputs read

- `docs/00-context.md` v1.7.
- `docs/01-scope-lock.md` v1.7.
- `docs/10-product-map.md` v1.1.
- `docs/20-domain-model.md` v1.1.
- `docs/30-jazz-storyboard.md` v1.0.
- `docs/31-demo-data/` v1.0: every file, including `tools/check_data.py`.
- `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md` (story baseline only, CTX §8).
- `docs/sources/Continuum — Business Solution Document.md` v0.2 (background; BSD §11.1 read in detail for screen expectations).

- Owner statement in an earlier conversation of this project (October 2026), held in the project's memory: the demo is branded "Techfinity", not "Techfinity Continuum". Used only for DC-D-02, which is put to owner review.

Not read: `docs/_superseded/`. Its README states that its files are not inputs to any round and are not read when Rounds 3 and 4 are redone. Only its file and heading list was seen; no content of it was used.

## 3. Terms (complete list)

Terms defined in earlier documents keep their meaning. The terms below are added.

| Term | Meaning |
| --- | --- |
| Cut tag | One of Keep, Simplified, Staged, Later (Section 4.1), given to a module or to a part of one. |
| Main window | A Vigil browser window at desktop size. Every desktop screen MUST be usable at 1280 px × 720 px without horizontal scrolling (1280 px is two thirds of the shared screen of SB-SET-03, ASSUMPTION DC-ASM-03). |
| Mobile page | SL-A4-04 and SL-A4-05. Laid out for a screen 360 px wide (`01-scope-lock` SL-A4-04, SL-A4-05). |
| Product display name | The name of the product shown on screens, in messages and in Jira (DC-U-02). |
| `{label}` | The tenant's environment label (DM-E-02); demo value "Demo environment — fictional data". |
| `{product}` | The product display name. |
| Viewer | The logged-in user looking at a screen. |
| Viewer time | A time shown in the time zone of the viewer's browser (DC-U-07). |
| Outbound | Content Vigil sends to an external system: Jira tickets and comments, Teams messages, emails. |
| Section anchor | A link at the top of a long screen that scrolls the screen to one of its sections. |
| Follow mode | A read-only canvas mode that keeps the current step of a run in view at 80% zoom (DC-S03-06). |
| Module actor | An `Actor` of kind `module` (DM-V-01): a change made by Vigil itself. Shown as "{product} (automatic)" (DC-U-06). |
| Incoming alerts strip | The top part of the incident list that shows alert intake and grouping in progress (DC-S02-02). |

## 4. Module cut

### 4.1 Cut tags (complete list)

| Tag | Meaning | Allowed for |
| --- | --- | --- |
| Keep | Built as specified in earlier documents, and shown or tested as the storyboard states. | Any module, connector, step type, AI task or demo-only component. |
| Simplified | Built with a narrower scope stated in this document. The narrower part is listed as a Later item (Section 10), so the product can grow into it without a rebuild (CTX-G-2). A responsibility that is "Built" in `10-product-map` MAY be narrowed this way but MUST NOT be removed (`10-product-map` Section 3, "Built"). | Any product module or screen. |
| Staged | Prepared output, no product logic (`01-scope-lock` Section 3). | Nothing in this document. No module, screen or scene is Staged (DC-R-03). |
| Later | Not built. | Only items that are "Later (not built)" in `10-product-map`, list C of `01-scope-lock`, or Section 10 of this document. |

### 4.2 Cut rules (complete list)

| ID | Rule | Trace |
| --- | --- | --- |
| DC-R-01 | Every product module of `10-product-map` Section 4.1 has exactly one cut tag in Section 5.1. Modules PM-M25 to PM-M28 are Later. | CTX-Q-1 |
| DC-R-02 | A Simplified module or screen MUST state, as a complete list, what is built, and MUST name the Later item that holds the rest. The built part MUST use the same records, requests and permissions that the full feature will use, so the Later item adds to it without changing it. | CTX-G-2, CTX-C-03 |
| DC-R-03 | Nothing is Staged. Demo values come only from configuration and seeded data (SL-B-10, PM-R-08); the invented values the audience sees (baselines, past incidents, names) are seeded data and are labelled as such on screen (DC-U-10). | SL-B-10, PM-R-08, SL-DR-03 |
| DC-R-04 | No screen, wording or outbound content in this document adds a capability to list A or list B of `01-scope-lock`. Every screen item traces to a scope lock item, a storyboard scene, or a "Built" responsibility of `10-product-map`. | SL-CR-01, CTX-Q-1 |
| DC-R-05 | Every product wording in Section 8 and outbound content in Section 9 is product behaviour: it is code or configuration of the Vigil product, the same for every tenant, and contains no Jazz-specific text. Jazz-specific text comes only from seeded data. | SL-B-10, PM-R-08 |
| DC-R-06 | Where this document gives an exact text, the build MUST produce exactly that text, with the placeholders filled. Placeholders are written `{name}` and are defined where the text is defined or in DC-TX-00. | CTX-W-07 |
| DC-R-07 | Every statement in Sections 6 to 9 of what a screen shows or does, or of what a text says, is required behaviour with the force of MUST (as DM-C-12 states for the domain model); Vigil MUST NOT behave otherwise. MAY marks an option. | CTX-W-02 |
| DC-R-08 | **Reads for display.** A screen reads a record only through `vigil-api`, which asks the record's owning module (PM-R-02). Every module listed in PM-I-04 MUST return its own records to `vigil-api` for display, filtered by incident, run, key or time range, ordered and paged as the screen states, and counted or summed where the screen shows a count or total (amendment DC-A-01). These are reads only; they change no record and write no audit event. | PM-R-01, PM-R-02, PM-I-04 |

## 5. Cut per module

### 5.1 Product modules (complete list)

"Built for the demo" lists what the demo build contains in addition to the module's responsibilities in `10-product-map`, which all stay as specified unless the row says otherwise. Screens are named by their DC-S ID (Section 7).

| ID | Module | Cut | Built for the demo | Not built (Later) | Trace |
| --- | --- | --- | --- | --- | --- |
| DC-M-01 | `vigil-tenancy` | Keep | PM-M01-01 to PM-M01-03. Tenant settings change only by seed import (DM-C-07); they are shown on every screen (DC-U-01, DC-U-03). Tenant settings add the product display name (amendment DC-A-09). | Settings screen for customer name, logo and environment label (DC-L-13); PM-M01-L1, PM-M01-L2. | SL-A3-06, SL-B-01 |
| DC-M-02 | `vigil-identity` | Keep | PM-M02-01 to PM-M02-04. Login screen DC-S01. | PM-M02-L1, PM-M02-L2. | SL-A3-07, SL-B-13 |
| DC-M-03 | `vigil-audit` | Keep | PM-M03-01 to PM-M03-03. Audit trail screen DC-S11 with the summaries of DC-TX-10. | PM-M03-L1. | SL-A2-08, SL-B-06 |
| DC-M-04 | `vigil-connectors` | Keep | PM-M04-01 to PM-M04-04, all ten connector kinds of `10-product-map` Section 7 (PM-K-01 to PM-K-10). Connector instances and credential references are created only by seed import (DM-C-07). | Creating connector instances and credential references from a screen (DC-L-01); PM-M04-L1 to PM-M04-L3. | SL-B-07, SL-B-14 |
| DC-M-05 | `vigil-runner` | Keep | PM-M05-01 to PM-M05-04; the five operations of DD-F-04. | PM-M05-L1, PM-M05-L2. | SL-A1-03, SL-A1-06, SL-B-05 |
| DC-M-06 | `vigil-intake` | Keep | PM-M06-01 to PM-M06-06. Returns its records for the incoming alerts strip (DC-S02-02) and the source cards (DC-S07-02) under DC-R-08; runs the connection test of DC-S07-02 on request and records its result (DM-R-39, DM-R-40; new responsibility PM-M06-07, amendment DC-A-18). | PM-M06-L1, PM-M06-L2. | SL-A1-01, SL-A2-01, SL-B-02 |
| DC-M-07 | `vigil-servicemap` | Keep | PM-M07-01 to PM-M07-06. Import on DC-S07. | Service map viewer (DC-L-07); PM-M07-L1. | SL-A1-02, SL-A2-01 |
| DC-M-08 | `vigil-correlation` | Keep | PM-M08-01 to PM-M08-10. Returns its hypothesis computations and correlation settings for display (DC-R-08; added to PM-I-04 by DC-A-01), used by DC-S03-03, DC-S03-04 and DC-TX-02. | PM-M08-L1 to PM-M08-L3. | SL-A1-02 |
| DC-M-09 | `vigil-incidents` | Keep | PM-M09-01 to PM-M09-12, except the withdrawn PM-M09-10. Incident numbers in the format of DC-TX-01; timeline texts of DC-TX-04. The incident records when its AI status last changed and the request group of its latest SL-T-1 attempt (amendment DC-A-13); an escalation records its timer (DC-A-10). | PM-M09-L1, PM-M09-L2. | SL-A1-02 to SL-A1-07, SL-B-12 |
| DC-M-10 | `vigil-routing` | Keep | PM-M10-01 to PM-M10-05. Routing rules screen DC-S08: edit, add, remove and reorder rules (the default rule stays last; DC-D-13). Passes the matched rule name and the escalation timer to `vigil-incidents` with its requests (amendment DC-A-10). | Escalation level editing screen (DC-L-02); PM-M10-L1 to PM-M10-L3. | SL-A1-04, SL-A2-03, SL-A2-06, SL-A4-08 |
| DC-M-11 | `vigil-workflows` | Keep | PM-M11-01 to PM-M11-08. Change list details and order of DC-TX-08 (amendment DC-A-14); validation messages of DC-TX-07; a failed test replay records its reason (amendment DC-A-15). | Viewing an earlier version's graph (DC-L-03); test replay history (DC-L-04); PM-M11-L1 to PM-M11-L4. | SL-A2-06, SL-B-03, SL-WE-07 to SL-WE-09 |
| DC-M-12 | `vigil-engine` | Keep | PM-M12-01 to PM-M12-11. Test-mode notes of DC-TX-09, using team display names read from `vigil-identity` (amendment DC-A-02). | PM-M12-L1, PM-M12-L2. | SL-A1-08, SL-B-03, SL-B-04, SL-B-11 |
| DC-M-13 | `vigil-diagnostics` | Keep | PM-M13-01 to PM-M13-03. Returns its active diagnostic definitions for the editor's Diagnostic step panel (DC-R-08; added to PM-I-04 by DC-A-01). | PM-M13-L1, PM-M13-L2. | SL-A1-03 |
| DC-M-14 | `vigil-actions` | Keep | PM-M14-01 to PM-M14-09. Approval page content DC-S05, with the past incident's label in the snapshot (amendment DC-A-16); evidence summaries of DC-TX-03. The "preauthorized" and "blocked" outcomes are built and covered by automated tests, never shown (SL-C-17). | PM-M14-L1 to PM-M14-L3. | SL-A1-06, SL-B-04 |
| DC-M-15 | `vigil-recovery` | Keep | PM-M15-01 to PM-M15-04. Returns its recovery verifications for the live recovery progress of DC-S03-11 (DC-R-08; added to PM-I-04 by DC-A-01). Its last evaluation records the unhealthy sources and holds the condition texts of DC-TX-03 (amendment DC-A-12). | PM-M15-L1. | SL-A1-07 |
| DC-M-16 | `vigil-knowledge` | Keep | PM-M16-01 to PM-M16-05. Past incidents and runbooks are shown only where an incident refers to them (DC-S03-09, DC-S03-10). | Runbook and past-incident browsing screens (DC-L-08); PM-M16-L1, PM-M16-L2. | SL-A1-04, SL-A1-06, SL-DR-04 |
| DC-M-17 | `vigil-investigation` | Keep | PM-M17-01 to PM-M17-07. The AI investigation result also stores the runbooks supplied to SL-T-1 (amendment DC-A-03), shown on DC-S03-09. Rejection reasons use the texts of DC-TX-11. | PM-M17-L1. | SL-A1-04, SL-A1-05, SL-A2-05, SL-A2-07 |
| DC-M-18 | `vigil-ai-gateway` | Keep | PM-M18-01 to PM-M18-05. AI settings screen DC-S09. Usage and cost are shown over all usage records of the tenant, without a time filter. | Usage time filter and export (DC-L-11); PM-M18-L1, PM-M18-L2. | SL-A2-02, SL-A2-05, SL-A2-07 |
| DC-M-19 | `vigil-ticketing` | Keep | PM-M19-01 to PM-M19-04. Ticket content of DC-OUT-01 to DC-OUT-04. | PM-M19-L1, PM-M19-L2. | SL-A1-05, SL-A1-07, SL-A2-03 |
| DC-M-20 | `vigil-notifications` | Keep | PM-M20-01 to PM-M20-05. Message content of DC-OUT-05 to DC-OUT-07. | Delivery status per channel on the incident workspace (DC-L-09); PM-M20-L1, PM-M20-L2. | SL-A1-05, SL-A1-06, SL-A2-03 |
| DC-M-21 | `vigil-reporting` | Keep | PM-M21-01, PM-M21-02. Dashboard DC-S10. | Time filters, trends and charts (DC-L-10); PM-M21-L1, PM-M21-L2. | SL-A1-09 |
| DC-M-22 | `vigil-setup` | Simplified | Built (complete list): PM-M22-02 to PM-M22-04 as specified; PM-M22-01 narrowed to: for each monitoring source loaded by the seed import, the admin edits its address, tests its connection and sees its health (DC-S07-02); recorded as a clarification of SL-A2-01 and an amendment of PM-M22-01 (DC-A-11), as SB-D01 already reads "connect" this way. The seed bundle is loaded only through the reset of the demo control panel, through `vigil-api` (PM-M22-03). | Adding a monitoring source and entering its credentials on a screen (DC-L-01); seed bundle import screen (DC-L-12); PM-M22-L1, PM-M22-L2. | SL-A2-01; reason: DC-D-03 |
| DC-M-23 | `vigil-api` | Keep | PM-M23-01 to PM-M23-05. PM-I-04 adds `vigil-correlation`, `vigil-recovery` and `vigil-diagnostics` (amendment DC-A-01). Live update targets of DC-U-08. | PM-M23-L1. | SL-A1-08, SL-A3-03, SL-B-13, SL-B-14 |
| DC-M-24 | `vigil-web` | Keep | Screens DC-S01 to DC-S11 with the content of Section 7. | PM-M24-L1, PM-M24-L2; DC-L-03 to DC-L-11, DC-L-13, DC-L-14. | SL-A4-01 to SL-A4-11 |
| DC-M-25 | `vigil-packs` | Later | — | PM-M25-L1. | SL-C-08 |
| DC-M-26 | `vigil-ai-evaluation` | Later | — | PM-M26-L1. | SL-C-16 |
| DC-M-27 | `vigil-platform-health` | Later | — | PM-M27-L1. | SL-C-07 |
| DC-M-28 | `vigil-discovery` | Later | — | PM-M28-L1. | SL-C-12 |

Result: 23 modules Keep, 1 Simplified (`vigil-setup`), 4 Later, none Staged. The scope lock already removed every item that one builder could not carry (CTX-D-005); this round found one further narrowing that saves real work without changing what the audience sees (DC-D-03).

### 5.2 Connectors, step types and AI tasks (complete list)

| ID | Item | Cut | Shown in | Trace |
| --- | --- | --- | --- | --- |
| DC-M-29 | Connectors PM-K-01 Zabbix, PM-K-02 Alertmanager | Keep | SB-S01, SB-D01 | SL-A1-01, SL-A2-01 |
| DC-M-30 | Connector PM-K-03 Linux server | Keep | SB-S03, SB-S06 | SL-A1-03, SL-A1-06 |
| DC-M-31 | Connector PM-K-04 Jira Cloud | Keep | SB-S05, SB-S07 | SL-A1-05, SL-A1-07 |
| DC-M-32 | Connectors PM-K-05 Microsoft Teams, PM-K-06 Email | Keep | SB-S05, SB-S06 | SL-A1-05 |
| DC-M-33 | Connectors PM-K-07 Claude, PM-K-08 OpenAI, PM-K-09 Local model | Keep | SB-S04, SB-D03, SB-D04 | SL-T-1, SL-T-2 |
| DC-M-34 | Connector PM-K-10 Local embedding model; PM-K-08 OpenAI for SL-T-3 | Keep. OpenAI for SL-T-3 and re-embedding are built and tested, not shown. | SB-S06 (local embedding) | SL-T-3; `01-scope-lock` Section 5.5 |
| DC-M-35 | Connectors PM-K-11 to PM-K-14 | Later | — | SL-C-01, SL-C-03, SL-C-14 |
| DC-M-36 | Step types PM-S-01 to PM-S-11 (all eleven) | Keep. All are in the editor palette (SL-WE-01). Wait/timer is in no seeded workflow version. | DC-S03, DC-S06 | SL-B-03 |
| DC-M-37 | AI tasks SL-T-1, SL-T-2, SL-T-3 | Keep | SB-S04 to SB-S06, SB-D03, SB-D04 | `01-scope-lock` Section 5.5 |

### 5.3 Demo-only components (complete list)

| ID | Component | Cut | Content defined in | Trace |
| --- | --- | --- | --- | --- |
| DC-M-38 | `demo-lab` (PM-DC-01) | Keep | DD-F-10 | SL-A3-01 |
| DC-M-39 | `demo-monitoring` (PM-DC-02) | Keep | DD-F-03 (lab alert catalogue), DD-F-10; alert summaries for Alertmanager alerts (amendment DC-A-17) | SL-A3-01, SL-A1-01 |
| DC-M-40 | `demo-faults` (PM-DC-03) | Keep | DD-F-10, `30-jazz-storyboard` Section 6 | SL-A3-02 |
| DC-M-41 | `demo-control` (PM-DC-04) | Keep | Screen DC-S12 | SL-A3-03, SL-A4-12 |
| DC-M-42 | `demo-data` (PM-DC-05) | Keep | `31-demo-data/` | SL-DR-01 to SL-DR-04 |
| DC-M-43 | Backup video (PM-DC-06) | Keep | SB-SET-08 | SL-A3-08 |

## 6. Rules common to all Vigil screens (complete list)

These rules apply to screens DC-S01 to DC-S11. The demo control panel (DC-S12) is not a Vigil screen and follows only its own section.

| ID | Rule | Trace |
| --- | --- | --- |
| DC-U-01 | **Environment banner.** When the environment label is not empty, every screen MUST show it as a banner across the full width at the very top, above everything else, on every scroll position (it does not scroll away), in a colour that differs from every other element of the screen. Text: `{label}`, exactly. The banner cannot be closed. | SL-DR-03, SL-B-09, PM-M24-02 |
| DC-U-02 | **Product display name.** `{product}` is the tenant setting `product_display_name` (amendment DC-A-09), seeded with the other tenant settings; value "Techfinity" (DC-D-02, agreed by owner). It is shown in the header, on the login screen, in the browser tab title (DC-U-15) and in outbound content where Section 9 names it. Documents and code keep using the working name Vigil (CTX §3). Which tenant's value the login screen shows before login is decided with the reset mechanism (SL-O-17, Round 5). | SL-B-09; CTX §3 |
| DC-U-03 | **Header (desktop screens).** Below the banner, one header row with, from left to right (complete list): `{product}`; the customer logo, or when the tenant has no logo the customer name as text (demo: "Jazz", subject to CTX-O-05); the navigation of DC-U-04; the time zone note "Times in UTC{±hh:mm}" (the viewer's offset, DC-U-07); the viewer's display name; "Sign out". | SL-A3-06, PM-M24-02 |
| DC-U-04 | **Navigation.** Items, in this order (complete list): Incidents (DC-S02), Dashboard (DC-S10), Workflow (DC-S06), Routing rules (DC-S08), AI settings (DC-S09), Setup (DC-S07), Audit trail (DC-S11). A viewer sees the navigation only with the `view` permission (DM-E06-R1). The current screen's item is marked. Until SL-CP-3, the navigation lists only the screens already built, in this order (`60-build-plan` BP-R-22). | SL-A4-02 to SL-A4-11; DM-E06-R1 |
| DC-U-05 | **Permissions on screens.** A screen is shown to a viewer with `view`. Every control that changes something is shown only to a viewer who holds that action's permission (DM-E06-R1); other viewers see the same content without the control. Vigil MUST check the permission again on the server for every action (PM-M23-02); a refused action shows the message "You do not have permission to do this." A viewer without `view` who opens a desktop screen, or who logs in without a target page, sees only the no-access page DC-S01-05. | SL-B-13, PM-R-09 |
| DC-U-06 | **Names, not keys.** Screens and texts show display names (complete list of kinds): team names; user display names; component, service and alert type display names; monitoring source names; provider display names (DC-TX-01); step labels. A key is shown only where this document says so, in parentheses after the name (Example: "Subscriber DB server (srv-db-01)"). A module actor (Section 3) is shown as "{product} (automatic)" everywhere. | SL-A1-02, SL-A3-07 |
| DC-U-07 | **Times and durations.** (1) On screens, times are viewer times. A time of the current day is shown as `HH:MM:SS` (24-hour); any other time as `D Mon YYYY, HH:MM` (Example: "9 Oct 2026, 16:20"). (2) Durations are shown as: less than 120 s → `{s} s`; otherwise, whole minutes → `{m} min`; otherwise `{m} min {s} s`. (3) Exception: on every workflow canvas (DC-S03-06, DC-S06) and in the editor's panels, change lists and test replay notes, durations are shown in seconds, `{s} s`, as they are entered. (4) Texts that a module stores in a record (evidence summaries, timeline texts, audit summaries, recheck details) and outbound content use rule (2) for durations, except test replay notes and version change lists, which use rule (3); they use the outbound time format of DC-TX-01 (UTC) for times, because they are written once and read by many viewers. Demo examples: escalation cause "not acknowledged within 2 min" (timer 120 s); "Not recovered within 5 min" (maximum wait 300 s); test note "(not acknowledged within 120 s)". (5) Duration inputs are in seconds, with the unit "s" next to the field. (6) A relative age ("12 s ago") is used only where a rule says so. | CTX-W-08 |
| DC-U-08 | **Live updates.** Screens that show changing data MUST show each change within 2 seconds of it happening, without a page reload, for (complete list): the incident list (DC-S02); the incident workspace (DC-S03), including member alert statuses, recovery progress and the workflow canvas; the Acknowledge and Approval pages (state of the incident or request only); the setup screen (source health, last test and readiness gaps, within 5 seconds). Step changes and incident changes are pushed (PM-M23-04); for the other data in this list the mechanism (push or polling) is Round 5 (PM-O-01). Other screens show data as of opening and offer a "Refresh" control. | SL-A1-08, PM-M23-04 |
| DC-U-09 | **Screen states (complete list).** Every screen MUST show: **loading** (a loading indicator in place of the content; no partial value is shown as final); **empty** (the empty text given in the screen's section; where a part has no empty text, the part is not shown); **error** ("Could not load this page. {reason}" with a "Try again" control); **partial** (when one part of a screen fails to load and the others load, that part shows "Could not load this part. {reason}" with "Try again", and the other parts are shown); **action failed** (when an action other than a refusal defined in this document fails, "Could not {action name in lower case}. {reason}" under the control, and nothing on the screen changes); **unauthorized** (DC-U-05). On the screens of DC-U-08 only, **live updates interrupted**: when the live update channel has been down for more than 5 seconds, the line "Live updates interrupted. Reconnecting… Last update {time}." under the header, removed on reconnection. `{reason}` is the error text returned by `vigil-api` (Round 5), or "No connection." when no answer arrived within 15 seconds. | BSD §11.1 (loading, empty, stale, disconnected, partial and unauthorized states) |
| DC-U-10 | **Kind tags (complete list).** Each of these words is shown as a small visible tag on every item of its kind: "Hypothesis" — the root-cause hypothesis (SL-A1-02); "Observation" — a `diagnostic_result` evidence line; "AI analysis" — the AI investigation section and an `ai_investigation` evidence line; "History match" — a `similarity_match` evidence line; "Proposed action" — the action proposal; "Approval" — an `approval` evidence line; "Execution result" — an action execution and an `action_execution` evidence line; "Verified outcome" — a recovery verification with outcome recovered and its evidence line; "Not recovered" — one with outcome not recovered and its evidence line; "Test" — every test replay result and every line of the test replay panel (DM-E49-R1); "Illustrative baseline" — every dashboard baseline (SL-A1-09); `{record_label}` of the past incident (demo: "Fictional record") — every past incident shown (SL-DR-03). No other tag uses these words. The same words MAY appear in texts defined in Sections 7 to 9. | BSD §11.1 (observation, hypothesis, proposed action, execution result, verified outcome; amendment DC-A-08); SL-A1-02, SL-A1-09, SL-A2-06, SL-DR-03 |
| DC-U-11 | **Confirmations.** The actions of the table below, and no others, ask for confirmation in a dialog with the question and the two buttons given, before anything is sent. "Cancel" closes the dialog and sends nothing. | SL-A1-06, SL-A2-06, SL-A2-07, SL-A3-03 |

DC-U-11 confirmations (complete list):

| Action | Question | Buttons |
| --- | --- | --- |
| Approve (DC-S05-04) | "Approve {action name} on {target name}?" | "Approve", "Cancel" |
| Reject (DC-S05-04) | "Reject {action name} on {target name}?" | "Reject", "Cancel" |
| Publish (DC-S06-14) | "Publish the draft as version {n}? New incidents will follow version {n}. Runs that have started stay on their version." | "Publish", "Cancel" |
| Roll back (DC-S06-15) | "Roll back to version {m}? This publishes version {k} with the content of version {m}." | "Roll back", "Cancel" |
| Re-run AI investigation (DC-S03-09) | "Re-run the AI investigation of {number}? This calls the AI providers of the current AI settings." | "Re-run", "Cancel" |
| Save the provider of similar-incident search (DC-S09-02) | "Change the similar-incident search provider to {provider display name}? All {n} past incidents are embedded again, and similar-incident search is unavailable until that finishes." | "Change provider", "Cancel" |
| Reset (DC-S12-02) | "Reset now? This ends the current demo run." | "Reset", "Cancel" |

| ID | Rule | Trace |
| --- | --- | --- |
| DC-U-12 | **Long lists.** Lists that can exceed 50 rows show 50 rows and a "Show more" control that adds the next 50 (complete list of such lists: incident list, audit trail, AI usage log). The member alerts of an incident are grouped (DC-S03-05) and are not paged. | SL-A4-02, SL-A4-11 |
| DC-U-13 | **Links into Vigil.** The links in messages (Acknowledge, Approval) and in Jira (incident) are plain addresses under the Vigil domain that name the incident or approval request; they carry no token and grant no authority (SL-B-13). Opening one without a session shows DC-S01 and, after login, the linked page. The address format is Round 5 (DC-O-02). | SL-B-13, PM-M20-03 |
| DC-U-14 | **Tests stay apart.** A test replay result is never shown on a live incident, never counted in a metric and never written to the audit trail (DM-C-09, DM-R-59). | SL-A2-06; BSD §11.1 ("A demonstration result must never be shown as live infrastructure execution") |
| DC-U-15 | **Browser tab titles (complete list).** "{screen name} — {product}", with screen names: "Sign in" (DC-S01), "Incidents" (DC-S02), "{number} {title}" (DC-S03), "Acknowledge" (DC-S04), "Approve" (DC-S05), "Workflow" (DC-S06), "Setup" (DC-S07), "Routing rules" (DC-S08), "AI settings" (DC-S09), "Dashboard" (DC-S10), "Audit trail" (DC-S11), "No access" (DC-S01-05). Each of these screens except DC-S03 shows the same screen name as its page heading (on DC-S01 above the fields, on DC-S04 and DC-S05 their own headings replace it); DC-S03's heading is DC-S03-02. | SL-A4-01 to SL-A4-11 |
| DC-U-16 | **Saved.** A successful save of configuration shows "Saved." next to the control for 5 seconds, except where a screen defines another text. | SL-A4-07 to SL-A4-09 |

## 7. Screens

Each screen lists its content as complete lists, in screen order. "Shown to" names the permission needed (DM-E06-R1). The demo values the audience sees are those of `30-jazz-storyboard`; the scene walkthrough in Section 11 maps them to the items here.

### 7.1 DC-S01 Login (SL-A4-01)

| ID | Item | Content |
| --- | --- | --- |
| DC-S01-01 | Shown to | Anyone, without a session. |
| DC-S01-02 | Content (complete list) | Banner (DC-U-01); `{product}`; the customer logo or name; heading "Sign in"; field "Username"; field "Password" (hidden input); button "Sign in". On a window narrower than 600 px the same content in one column. |
| DC-S01-03 | Sign in | Correct username and password of an active user → a session starts (DM-SM02-T1) and Vigil opens the page that was requested before login, or, when none was, the incident list (with `view`) or DC-S01-05 (without `view`). Wrong username or password, or an inactive user → the message "Username or password is incorrect." and the password field is cleared. No other message reveals whether the username exists. |
| DC-S01-04 | Session end | When a session expires (DM-SM02-T3) the next action or page load opens DC-S01 with the message "Your session ended. Sign in again." and returns to the same page after login. "Sign out" ends the session (DM-SM02-T2) and opens DC-S01 with the message "You are signed out." |
| DC-S01-05 | No-access page | For a logged-in user without `view` who is not on a mobile page: banner, header with only `{product}`, the customer, the user's display name and "Sign out", the heading "No access" and the text "There is nothing to show here for your account. Open the link in your {product} message to acknowledge or approve." |

### 7.2 DC-S02 Incident list (SL-A4-02)

| ID | Item | Content |
| --- | --- | --- |
| DC-S02-01 | Shown to | `view`. Page heading "Incidents". |
| DC-S02-02 | Incoming alerts strip (top of the screen) | Complete list, left to right: (1) "Alerts received in the last 10 minutes: {n}" — the number of alerts (DM-E-21) whose `first_received_at` is within the last 10 minutes; (2) only while at least one incident is `forming`: "Grouping in progress: {m} {alert or alerts} {is or are} being grouped" — the sum of `alert_count` of the tenant's `forming` incidents; (3) "Monitoring: {h} of {s} sources healthy" — active sources and those with health `healthy`; when h < s the text is shown in the warning colour followed by one "{source name}: {Stale or Disconnected}" per other source; it links to DC-S07; (4) the latest 10 alerts by `first_received_at`, newest first, one line each: viewer time of `first_received_at`, source kind ("Zabbix" or "Alertmanager"), alert type display name, alert title. When no alert was received in the last 10 minutes, item 4 shows "No alerts in the last 10 minutes." Updated within 2 seconds (DC-U-08). |
| DC-S02-03 | Forming incidents | A `forming` incident is never shown as a row and never with a number (DM-E32-R3). Its alerts are visible only in DC-S02-02 items 1, 2 and 4. It becomes a row, with its number, when it moves to `open`. |
| DC-S02-04 | Rows | One row per incident in state `open`, `resolved` or `closed` (not `forming`, not `merged`), ordered by number, highest first. Columns (complete list): Number (DC-TX-01); Title; Severity (P1 to P4); State ("Open", "Resolved", "Closed"); Stage (DC-TX-01, empty when none); Owner (team name); Acknowledged ("No", or "{user display name}, {time}"); Alerts (`alert_count`); Detected (`first_alert_fired_at`); Ticket (the Jira issue key as a link that opens Jira in a new browser tab, or empty). A click on a row opens DC-S03 for that incident. |
| DC-S02-05 | Empty state | "No incidents yet. {product} is watching {s} monitoring sources." |
| DC-S02-06 | Later | Filters, sorting other than by number, search (DC-L-05); ordering by impact (PM-M24-L1). |

### 7.3 DC-S03 Incident workspace (SL-A4-03)

The incident workspace is the screen of SB-S02 to SB-S08. Its parts, from top to bottom: header (DC-S03-02), workflow band (DC-S03-06, heading "Workflow"), then two columns: the main column, 66% of the width, with the sections DC-S03-03 to DC-S03-11 in that order, and the side column with DC-S03-12 to DC-S03-14. Main column section headings, which are also the labels of the section anchors at the top of the main column (complete list, in order): "Hypothesis and customer impact" (DC-S03-03, DC-S03-04); "Alerts ({alert_count})" (DC-S03-05); "Evidence ({n})" (DC-S03-08); "AI investigation" (DC-S03-09); "Response" (DC-S03-10); "Recovery" (DC-S03-11). Side column headings (complete list, in order): "Run steps", "Timeline", "Notified".

| ID | Item | Content |
| --- | --- | --- |
| DC-S03-01 | Shown to | `view`. Only incidents in state `open`, `resolved` or `closed` can be opened (a `forming` incident has no workspace, DC-L-14; a `merged` incident opens the incident it was merged into). |
| DC-S03-02 | Header (complete list, in this order) | Page heading "{number} {title}"; then labelled values: "State: {state}"; "Stage: {stage}" (only when there is a stage, DC-TX-01); "Severity: {severity}"; "Owner: {team}"; "Informed: {teams, alphabetical by name, comma-separated}" or "Informed: none"; "Rule: {matched routing rule name}" (from the applied routing evaluation, `assignment_evaluation_id`, and its rule set revision); "Acknowledged: {user display name}, {time}" or "Acknowledged: no"; only when `escalation_level` > 0, "Escalated: level {n}, {team names of that level}"; "Detected: {time}" (`first_alert_fired_at`); only when resolved, "Resolved: {time}"; "Ticket: Jira {key}" as a link that opens Jira in a new browser tab, or "Ticket: none"; only for a reopened incident, "Reopened: {reopen_count} {time or times}" (DC-TX-00). |
| DC-S03-03 | Hypothesis and customer impact (complete list) | Tag "Hypothesis", then "{component name} ({component key})", then "{component kind}, criticality {criticality}" (DC-TX-01), then the sentence of DC-TX-02 for the dependent count (demo: "The other 6 affected components all depend on it."). When there is no hypothesis: "No hypothesis: no alert matched a known component." Then "Customer impact:" and one line per affected service in `service_key` order: "{service name} — criticality {criticality} — via {names of via components, alphabetical, comma-separated}". When none: "Customer impact: none identified." |
| DC-S03-04 | Hypothesis source | Under the hypothesis, the line "Chosen by the service map rule: the affected component that most other affected components depend on." and, when the latest hypothesis computation has `tie_break` other than `none`, "Tie broken by higher criticality." or "Tie broken by component ID." |
| DC-S03-05 | Alerts | The heading and the line "Firing {f} · Resolved {r}". Member alerts are grouped by alert type, one group per type, ordered by alert type display name; each group line shows "{type display name} ({count})" and can be expanded. An expanded group lists its alerts ordered by `fired_at` (ties: source kind, then alert key), one line each (complete list): fired time; source kind; alert title; component name (or "Unknown component"); status ("Firing" or "Resolved"); a "Why grouped" control that expands the reason text of DC-TX-02 for that alert, from its `final_reason`. Statuses update within 2 seconds (DC-U-08). |
| DC-S03-06 | Workflow band | A read-only canvas (SL-WE-10) of the incident's latest live run, the full width of the screen and 240 px high, with the run's state (DC-TX-01) and, when the incident has had more than one live run, "Run {n} of {n}". Step cards and connections are drawn as in the editor (DC-S06-05, DC-S06-06) without the ability to change them, with the styles of DC-S03-07. Default mode is follow mode: zoom 80%, panned so that the current step is in the centre; each step change moves the view to the new current step within 0.5 seconds. Controls (complete list): "Follow" (on by default; turned off by any manual pan or zoom, turned on again by pressing it); zoom in; zoom out; "Fit" (whole workflow in the band); "Expand" (opens the same canvas over the whole window below the header, with the same controls and a "Close" control). |
| DC-S03-07 | Step styles on a run canvas (complete list) | Not reached: grey. Completed: normal colour with its named output display text as a tag on the card (Example: "done"), and "×{visit}" when visited more than once. Running: highlighted (accent outline). Waiting: highlighted, with "Waiting" and, for an Escalation step in "wait for acknowledgement" mode and for an Approval step, a countdown "{m}:{ss}" to the step's pending timer. Cancelled: grey with "Cancelled". Connections that the run has taken are drawn in the accent colour; others in grey. The highlight changes within 2 seconds of a step change (SL-A1-08). Test-mode step changes never appear here (DM-R-59). |
| DC-S03-08 | Evidence | One line per evidence entry in `collected_at` order (complete list of parts): kind tag (DC-U-10); the summary (DC-TX-03); "Source: {source_system}" (DC-TX-03); "Collected: {time}". For a `diagnostic_result` entry an expand control shows the output values as "{name}: {value}" lines in the order in which the operation's output parser defines them (DD-F-04 `output_parsers`). Empty: "No evidence yet." |
| DC-S03-09 | AI investigation | Tag "AI analysis". Shows the latest accepted SL-T-1 result (DM-E68-R1), complete list in this order: "Likely cause: {text}"; "Hypothesis: Confirms" or "Hypothesis: Disputes" (not shown when `verdict` is empty, DM-E68-R3); "Evidence used:" and one line per referenced evidence entry with its summary; "Still uncertain: {text}"; "Runbooks given: {runbook keys of `runbooks_supplied`, in order, comma-separated}" (DC-A-03); "Runbooks cited: {runbook keys, comma-separated}"; "Provider: {provider display name}"; "{time} · From the workflow" or "{time} · Re-run by {user display name}". When `ai_status` is `unavailable`, a warning above it: "AI investigation unavailable — {reason text, DC-TX-01}. {ai_status_changed_at}" (DC-A-13; DM-E68-R1). When no result was ever accepted: "No AI investigation yet." or the warning alone. Below: "Summary (AI):" or "Summary (template):" and the latest incident summary text, or "No summary yet." Control "Re-run AI investigation", shown only with `rerun_ai_investigation` and only when the incident is `open`, `resolved` or `closed` (DM-E68-R2), with confirmation (DC-U-11); while a re-run is running it shows "Re-running…" and is disabled. When the incident has an `ai_last_request_group_id` (DC-A-13), a link "AI usage of the latest investigation" opens DC-S09 with the usage log filtered to that request group. |
| DC-S03-10 | Response | Shown once the run's first AI task step with task SL-T-3 has completed. Complete list of parts, in order: (1) **Similar past incident**: for `match` — "{past incident key} {title}" with the tag `{record_label}`, "Similarity {score, 2 decimal places}", "Fix succeeded {success_count} {time or times}", "Fix: {fix_description}"; for `no_match` — "No similar past incident (best score {score} is below the minimum {min_similarity})." or, when no score was found, "No similar past incident."; when the step left by `unavailable` — "Similar-incident search unavailable." (2) **Proposed action** (tag "Proposed action"), once a proposal exists: action name; parameters as "{name} = {value}" lines in the order of the operation's parameter definitions (DM-E-12); "Target: {target name} ({target key})" (DC-TX-01); "Policy: {policy check result}" (DC-TX-01). When the run's first Approval step completed without a proposal: "No action was proposed." (3) **Approvals**: one line per approval request of the run, in `sequence_in_run` order: "Approval {sequence_in_run}"; "Eligible: {team names}"; state (DC-TX-01); "by {user display name} at {time}" when decided by a person; "expires {time}" while pending. (4) **Executions** (tag "Execution result"): one block per action execution, in `attempt_number` order: "Attempt {n} of {limit}"; state (DC-TX-01); the four rechecks, each "{recheck name}: passed" or "{recheck name}: failed" with its detail (DC-TX-01); the result summary (DC-TX-03); "Started: {time}"; "Finished: {time}". |
| DC-S03-11 | Recovery | Shown once a Recovery check step of the run has started. Per recovery verification of the run, in `started_at` order (complete list of parts): "Check: {check name}"; each condition as "{condition detail text}: Holding" or "{condition detail text}: Not holding", using the texts of DC-TX-03 stored in the last evaluation (DC-A-12); "Monitoring sources: all healthy" or "Monitoring sources: {names of the unhealthy sources} not healthy"; while observing and all conditions hold: "Holding for {s} s of {observation window}", counting up each second from `window_started_at`; while observing and not all hold: "Waiting for the conditions (gives up at {max_wait_until})"; at the end: tag "Verified outcome" with "Recovered at {decided_at}", or tag "Not recovered" with "Not recovered by {decided_at}". Updated within 2 seconds (DC-U-08). |
| DC-S03-12 | Run steps (side column, top) | The step executions of the latest live run in execution order, one line each (complete list): step label; step type display name; started time; and the named output display text, "Running", "Waiting" or "Cancelled". This is the Execution view of BSD §11.1 for the demo (waiting states, failures, attempts). Empty: "No workflow run." |
| DC-S03-13 | Timeline (side column) | Timeline entries (DM-E-39) newest first, each with time, actor (DC-U-06) and text (DC-TX-04). Complete list of collapsing rules: (1) every entry dated at or before the incident's `stable_at` is collapsed into one entry, dated `stable_at`, with the text "Grouping: {n} {alert or alerts} joined; hypothesis {final hypothesis component name, or 'none'}; {k} {group or groups} merged", where n is the number of the incident's memberships with `joined_at` at or before `stable_at` (memberships moved in by a merge included) and k the number of `merged_in` entries; it expands to the entries it holds; (2) after `stable_at`, consecutive `alert_joined` entries are collapsed into one expandable entry "{n} {alert or alerts} joined". |
| DC-S03-14 | Notified (side column) | One line per notified team (DM-E-37), ordered by `first_notified_at`, then team name: "{team name} — {purpose} — {time}" (DC-TX-01). Empty: "No team notified yet." |

### 7.4 DC-S04 Acknowledge page (SL-A4-04, mobile)

| ID | Item | Content |
| --- | --- | --- |
| DC-S04-01 | Shown to | A logged-in user. With `acknowledge`: the page with its button. With `view` only: the same page without the button and with the line "View only." Without either: "You do not have permission to acknowledge incidents." |
| DC-S04-02 | Layout | One column, usable at 360 px wide without horizontal scrolling; body text at least 16 px; every control at least 44 px high. Top: banner (DC-U-01), then a bar with `{product}`, the customer name, the user's display name and "Sign out". |
| DC-S04-03 | Content (complete list, in order) | Heading "Acknowledge incident"; "{number} {title}"; "Severity: {severity}"; "Owner: {team}"; "Detected: {time}"; "Alerts: {alert_count}"; "Summary: {latest incident summary text}"; "State: {state}". |
| DC-S04-04 | Action | Button "Acknowledge" (full width). No confirmation. On success the button is replaced by "Acknowledged by {user display name} at {time}." |
| DC-S04-05 | Refusals (complete list, DM-E35-R1; the first that applies, in this order, is shown) | (1) Incident not `open` → "This incident is {resolved or closed}. It does not need acknowledgement." (2) Already acknowledged in this episode → "Already acknowledged by {user display name} at {time}." (3) Not a member of a notified team → "You are not in a team that was notified about this incident." The refusal replaces the button. |
| DC-S04-06 | Live state | When the incident is acknowledged by someone else or changes state while the page is open, the page shows the matching refusal within 2 seconds (DC-U-08). |

### 7.5 DC-S05 Approval page (SL-A4-05, mobile)

| ID | Item | Content |
| --- | --- | --- |
| DC-S05-01 | Shown to | A logged-in user. With `approve_action`: the page with its buttons. With `view` only: the same content without buttons and with "View only." Without either: "You do not have permission to approve actions." |
| DC-S05-02 | Layout | As DC-S04-02. |
| DC-S05-03 | Content (complete list; the SL-A1-06 parts come from the request snapshot DM-V-12; the incident number and title come from `vigil-incidents` and the request state from `vigil-actions`) | Heading "Approve action"; for a request with `sequence_in_run` > 1: "Approval {n}: your approval must differ from earlier approvals in this run."; "{number} {title}"; "State: {request state}" (DC-TX-01). Then, in this order and with these headings: **Action** — action name. **Parameters** — one line per parameter, `{name} = {value}`, in a fixed-width font, in the order of the operation's parameter definitions (DM-E-12). **Target server** — "{target name} ({target key})" (DC-TX-01). **Limits** — one bullet per limit text, then "Attempt {attempt_number} of {attempt_limit}". **Expires** — "{time}" and, while pending, "in {m} min {s} s", counting down each second. **Based on** — "{past incident key} {title}" with the tag `{record_label}` (DC-A-16), "Similarity {score}", "Fix succeeded {success_count} {time or times}"; then one line per evidence item of the snapshot: kind tag (DC-U-10), summary, collected time. **Recovery checks that will be used** — "Check: {check name}", one line per condition text (DC-TX-03, requirement form), and "Held for {observation window} while every monitoring source is healthy." |
| DC-S05-04 | Actions | Buttons "Approve" and "Reject", each with confirmation (DC-U-11). The decision carries the snapshot hash shown (DM-E-59). |
| DC-S05-05 | After a decision | "Approved by {user display name} at {time}. {product} will check the conditions again before it runs the action." or "Rejected by {user display name} at {time}." |
| DC-S05-06 | Refusals (complete list, DM-E59-R1; the first that applies, in this order, is shown) | (1) Request cancelled → "This request was cancelled because the incident's workflow run ended." (2) Request in state `preauthorized`, `blocked` or `refused` → its state text (DC-TX-01). (3) Already decided → "Already {approved or rejected} by {user display name} at {time}." (4) Expired → "This request expired at {time}." (5) Not in an eligible team → "You are not in a team that can approve this action ({eligible team names})." (6) User in `excluded_user_ids` → "You approved an earlier step of this run. A different approver is needed." (7) Snapshot hash changed → "This request changed after you opened it. Reload the page to see the current request." The refusal replaces the buttons. |
| DC-S05-07 | Live state | The countdown runs each second; a change of the request's state while the page is open is shown within 2 seconds (DC-U-08). |

### 7.6 DC-S06 Workflow editor (SL-A4-06)

| ID | Item | Content |
| --- | --- | --- |
| DC-S06-01 | Shown to | `view`: the active version, read-only. `edit_workflow`: starting, editing and saving the draft. `run_test_replay`: test replay. `publish_workflow`: publish and roll back. Page heading "Workflow". |
| DC-S06-02 | Modes | **Viewing** (no open draft, or a viewer without `edit_workflow`): the active version on the canvas, read-only; selecting a step shows its parameters read-only. **Editing** (an open draft and a viewer with `edit_workflow`): the draft on the canvas with the palette and an editable parameter panel. "Edit draft" opens the open draft, or starts one from the active version when none is open (DM-SM06-T1). A banner in Editing mode: "Draft based on version {base}. Version {active} stays active until you publish." |
| DC-S06-03 | Top bar (complete list) | "Workflow: {name}"; "Active: version {n}"; in Editing mode "Draft revision {r}" and, when the canvas has changes not yet saved, "Unsaved changes"; controls "Edit draft" (Viewing mode, `edit_workflow`), "Save draft", "Undo", "Redo", "Auto layout" (these four in Editing mode only), "Test replay" (both modes, `run_test_replay`), "Publish" (Editing mode, `publish_workflow`; disabled while there are unsaved changes, with the hint "Save the draft first."), "Versions" (both modes), zoom in, zoom out, "Fit" (both modes). |
| DC-S06-04 | Palette (SL-WE-01, Editing mode) | The eleven step types (DM Section 9) in this order, each with its display name (DC-TX-05): Trigger, Condition, Diagnostic, AI task, Approval, Action, Recovery check, Escalation, Notification, Ticket, Wait. Dragging one onto the canvas adds a step of that type at the drop point, appended at the end of the content's `steps` list, with the label and parameter defaults of DC-TX-05 and a new `step_id` of the form `s-` followed by 8 lowercase hexadecimal characters chosen at random; a value that equals a `step_id` in the draft or in any version of the workflow is drawn again (DM-V-05, never reused). |
| DC-S06-05 | Step card | Shows (complete list): label; step type display name; the one-line card summary of DC-TX-05; one input handle (none on a Trigger); one output handle per named output, labelled with the output's display text (DM Section 9; for a Condition, one per branch label plus "otherwise"). In Editing mode, a step with a validation problem has a red outline (DC-S06-12). |
| DC-S06-06 | Connecting (SL-WE-02) | Dragging from an output handle to another step's input handle creates a connection, drawn with the output's display text. When the output already has a connection, the new connection replaces it, as one change. A connection cannot end on a Trigger. |
| DC-S06-07 | Select, move, delete (SL-WE-03) | A click selects a step or a connection; Shift+click adds to the selection; dragging a selected step moves every selected step. The Delete or Backspace key, or the "Delete" control in the parameter panel, deletes the selection; deleting a step deletes its connections. |
| DC-S06-08 | Parameter panel (SL-WE-04) | Opens on the right when exactly one step is selected. Shows "Label" (1 to 60 characters) and the step type's parameters with the display names, inputs and read-only notes of DC-TX-05. A value is checked against its type and bounds (DM Section 9) when entered; a value that fails shows the message of DC-TX-07 under the field and is not applied, and the step keeps its previous value. In Viewing mode every field is read-only. |
| DC-S06-09 | Undo and redo (SL-WE-05) | Every change on the canvas is one undo step (complete list): add step; delete selection; move (one per drag); connect; replace a connection; label or parameter change; auto layout. Undo and redo cover at least the last 50 changes of the editing session, by the controls or Ctrl+Z / Cmd+Z and Ctrl+Shift+Z / Cmd+Shift+Z. Saving keeps the undo history. Leaving the editor ends the editing session. With unsaved changes, leaving by a navigation item or link asks "You have unsaved changes. Leave without saving?" with the buttons "Leave" and "Stay"; closing or reloading the browser tab triggers the browser's own leave warning, whose text the browser sets. |
| DC-S06-10 | Zoom, pan, fit, auto layout (SL-WE-06) | Mouse wheel or the zoom controls zoom in steps of 10% between 25% and 200%; dragging the empty canvas pans; "Fit" shows the whole workflow; "Auto layout" arranges every step of the draft left to right by connection order (algorithm Round 5), as one change. |
| DC-S06-11 | Save draft (SL-WE-07) | Saves the canvas content as the next draft revision (DM-SM06-T2) and shows "Draft saved (revision {r})." A refusal because the revision changed shows "This draft was saved in another window. Reload to continue." |
| DC-S06-12 | Validation display (SL-WE-08) | When a test replay of the draft starts or "Publish" is pressed, Vigil validates the saved draft revision (DM-R-68). When it has violations: each violation is shown as a red outline on its step (and on the Condition output it names), with its message (DC-TX-07) on hover and in a "Problems ({n})" list under the top bar; a click on a problem selects its step; for a test replay the result is recorded as failed with the reason "The draft has {n} {problem or problems}." (DC-A-15) and shown in the test replay panel; for Publish nothing is published and "The draft has {n} {problem or problems}. Fix them to publish." is shown. The Problems list is cleared by the next validation that passes. |
| DC-S06-13 | Test replay (SL-A2-06) | "Test replay" opens a dialog with (complete list): "Incident" — the incidents that can be replayed (DM-R-68), as number and title, newest first, or "No incident can be replayed yet." when there is none; "Workflow" — "Current draft (revision {r})" when a draft is open (disabled while there are unsaved changes, with the hint "Save the draft first."), and every version as "Version {n}"; "Start" and "Cancel". The result opens in the test replay panel (tag "Test" on the panel and on every line), complete list: heading "Test replay of {number} on draft revision {r}" or "Test replay of {number} on version {n}"; state "Running", "Completed" or "Failed: {failure reason}" (DC-TX-11); "Routing (dry run, rules revision {r}): {severity}, owner {team}, informed {teams or 'none'}"; one line per step on the path: position, step label, step type display name, named output display text, simulated time "+{m}:{ss}" from the start of the simulated clock, note (DC-TX-09); the footer "Test only. No ticket, message, AI call or command was made." The panel stays open until closed or until another replay starts. |
| DC-S06-14 | Publish (SL-A2-06, SL-WE-07) | "Publish" asks for confirmation (DC-U-11). On success: "Version {n} published." and the screen returns to Viewing mode on the new active version. |
| DC-S06-15 | Versions panel (SL-WE-09) | "Versions" opens a side panel listing every version, newest first, each with (complete list): "Version {n}", and "Active" on the active one; origin ("Seed", "Published from a draft", "Rollback to version {m}"); publisher (DC-U-06) and time; the change list lines of DC-TX-08, or "First version" for version 1. With `publish_workflow`, every version that is not active has "Roll back to this version", which asks for confirmation (DC-U-11) and on success shows "Version {k} published (rollback to version {m})." |
| DC-S06-16 | Later | Viewing an earlier version's graph and comparing versions on the canvas (DC-L-03); test replay history and marking the test path on the canvas (DC-L-04). |

### 7.7 DC-S07 Setup and sources (SL-A4-07)

| ID | Item | Content |
| --- | --- | --- |
| DC-S07-01 | Shown to | `view`; editing, testing and importing need `manage_setup`. Page heading "Setup". Sections in this order (complete list): "Monitoring sources", "Service map", "Readiness gaps". |
| DC-S07-02 | Monitoring sources (Simplified, DC-M-22) | One card per active monitoring source, ordered by name (complete list of contents): name; kind ("Zabbix" or "Alertmanager"); "Address: {address}" (from its connector instance, DM-E-10); "Health: {Healthy, Stale or Disconnected}" (in the warning colour when not healthy) and "last contact {age} ago" (relative age), or "no contact yet"; "Last test: Passed, {time}", "Last test: Failed, {time}: {reason}" or "Not tested yet"; "Stale after {stale_after} without contact. Disconnected after {disconnected_after} without contact or after a failed test. Tested every {health_check_interval}."; controls "Test connection" and "Edit address". "Test connection" asks `vigil-intake` to run the connection test now with the saved settings (PM-I-06, PM-M04-04); `vigil-intake` records the result and recomputes health (DM-R-39, DM-R-40) exactly as for a periodic test; the card shows "Testing…" and then the result; no configuration change is made. "Edit address" shows the field "Address" with "Save" and "Cancel"; an address that is not an http or https address of 1 to 500 characters is refused with "Address: must be an http or https address of at most 500 characters."; "Save" changes the connector instance's address through `vigil-setup` and `vigil-connectors`, which writes one configuration change (area `sources`, DM-C-07); saving does not change the health by itself. Health and the last test update within 5 seconds (DC-U-08). |
| DC-S07-03 | Service map | "Current service map: generation {g}, {file_name}, imported by {actor, DC-U-06} at {time}: {services} services, {components} components, {identity_keys} identity keys, {dependencies} dependencies." For the seeded generation, `file_name` is the file name of the service map part of the seed bundle (Round 5 format; content DD-F-02). Control "Import service map file" (file chooser, then "Import"). Success: "Imported generation {g}: {services} services, {components} components, {identity_keys} identity keys, {dependencies} dependencies." Refusal (DM-E24-R1): "The file was not imported. {n} {problem or problems}:" and one line per problem (DC-TX-11). The note "An import replaces the service map used for new alerts. Incidents keep the components their alerts were matched to." (DM-R-06). |
| DC-S07-04 | Readiness gaps | One line per gap (DC-TX-11), ordered by gap type (server with no owner, service with no criticality, source not healthy), then by name. Empty: "No readiness gaps." Updated within 5 seconds (DC-U-08). |
| DC-S07-05 | Later | Adding a monitoring source, showing its webhook address and entering credentials (DC-L-01); importing a seed bundle from a screen (DC-L-12). |

### 7.8 DC-S08 Routing rules (SL-A4-08)

| ID | Item | Content |
| --- | --- | --- |
| DC-S08-01 | Shown to | `view`; changes need `manage_routing`. Page heading "Routing rules". |
| DC-S08-02 | Explanation | "Rules are checked from top to bottom. The first rule whose conditions all match sets the severity, owner and informed teams of a new incident. A change applies to incidents assigned after it is saved; incidents already open keep their assignment." Then "Revision {r}, saved by {actor, DC-U-06} at {time}." |
| DC-S08-03 | Rule cards | One card per rule in list order, each with (complete list): position number; name; "When: {conditions as text (DC-TX-06), joined by ' and '}" or "Always (default rule)"; "Severity: {P}"; owner — "Owner: {team}" (fixed team) or "Owner: owner of the hypothesis component; otherwise {team}"; informed — "Informed: {teams}", with " and the owners of affected services" added when that option is on, "Informed: owners of affected services" when it is on and the team list is empty, or "Informed: none". |
| DC-S08-04 | Editing | "Edit rules" turns every card into a form. Fields per rule (complete list): "Name"; "Conditions": rows of field, operator and value (routing fields of DC-TX-06 only), with "Add condition" and a remove control per row (the default rule has no conditions and none can be added); "Severity" (P1 to P4); "Owner": "Fixed team" or "Owner of the hypothesis component", with "Team" or "Fallback team" (active teams); "Informed teams" (several active teams); "Also inform the owners of affected services". Controls: "Add rule" (adds a rule named "New rule" with one empty condition row, above the default rule); per rule other than the default: "Move up", "Move down" (never below the default rule) and "Remove"; "Save" and "Cancel". "Save" saves the whole list as the next revision (DM-R-27); refusals (DM-E41-R1 and incomplete fields) are shown under the rule and field they concern with the texts of DC-TX-11; success shows "Saved as revision {r}." |
| DC-S08-05 | Later | Editing escalation levels (DC-L-02). |

### 7.9 DC-S09 AI settings (SL-A4-09)

| ID | Item | Content |
| --- | --- | --- |
| DC-S09-01 | Shown to | `view`; changes need `manage_ai_settings`. Page heading "AI settings". Sections in this order (complete list): "Providers per task", "Data routing", "Usage and cost", "Usage log". |
| DC-S09-02 | Providers per task (SL-A2-02) | One block per task, in this order: "Incident investigation" (SL-T-1), "Incident summary" (SL-T-2), "Similar-incident search" (SL-T-3). Each block shows "Allowed: {provider kind names of the allowed kinds (DM-E-71), comma-separated}". For SL-T-1 and SL-T-2: the ordered provider list, one line each: "{position}. {provider display name}" with the location tag "External" or "In-house", and "chosen" on the first line and "fallback {k}" on the others, where k is the position minus 1; controls "Move up", "Move down", "Remove", "Add provider" (configured active providers of an allowed kind not yet in the list) and "Save". For SL-T-3: one "Provider" selection, the text "Similarity scores compare only within one provider. Changing it embeds all {n} past incidents again, and similar-incident search is unavailable until that finishes.", "Embeddings: Ready" or "Embeddings: Re-embedding", and "Save" with confirmation (DC-U-11). Refusals (DM-E-72) are shown under the block (DC-TX-11); success "Saved." (DC-U-16). |
| DC-S09-03 | Data routing (SL-A2-02) | "A request goes to a provider only if every kind of data in it is allowed for that provider." A table: one row per data category (display name and description, DC-TX-01) in the order of DM-R-42, one column per active provider (display name and location tag) in `provider_key` order; each cell a checkbox "allowed"; "Save"; success "Saved." |
| DC-S09-04 | Usage and cost (SL-A2-02) | Two tables, "By provider" (one row per configured provider) and "By task" (one row per AI task), over every AI usage record of the tenant, each with these columns (complete list): Requests (records with outcome succeeded, unreachable or provider error); Succeeded; Unreachable; Provider errors; Not sent by data routing; Input units; Output units; Cost (USD, 4 decimal places). A total row. A succeeded record with 0 input and 0 output units counts as "not reported"; a units cell whose succeeded records are all "not reported" shows "not reported" (DM-ASM-06). Empty (no usage record): "No AI usage yet." |
| DC-S09-05 | Usage log (SL-A2-05, SL-A2-07) | One line per AI usage record, newest first (complete list): time; task display name; provider display name with location tag; position ("chosen" when `fallback_position` is 1, otherwise "fallback {fallback_position − 1}"); outcome (DC-TX-01); input and output units; cost; incident number (or empty). When opened from DC-S03-09, the log is filtered to one request group and shows above the lines "One investigation of {number}, started {time of its earliest usage record}" with a control "Show all", and the line "Requests to external providers: {n}", counting the records of the group with location external and outcome succeeded, unreachable or provider error. Empty: "No AI usage yet." |

### 7.10 DC-S10 Dashboard (SL-A4-10)

| ID | Item | Content |
| --- | --- | --- |
| DC-S10-01 | Shown to | `view`. Page heading "Dashboard". |
| DC-S10-02 | Tiles | Four tiles in this order (complete list): "Time to acknowledge", "Time to recover", "Alerts suppressed", "Incidents merged" (DM-R-53). Each tile shows: the name; the value ("{x.x} min" for times, a whole number for counts, "no data" when the metric has none); the definition of DC-TX-01; the baseline value, in the metric's unit, with the tag "Illustrative baseline" (DC-U-10). |
| DC-S10-03 | Data line | Under the tiles: "Includes {k} live {incident or incidents} and {p} imported past {incident or incidents} labelled '{record_label}'." When the imported past incidents carry more than one label, every label is listed, alphabetical, joined by "', '". |
| DC-S10-04 | Refresh | Values are computed when the screen opens and when "Refresh" is pressed. |
| DC-S10-05 | Later | Time filters, trends and charts (DC-L-10); further reports (SL-C-13). |

### 7.11 DC-S11 Audit trail (SL-A4-11)

| ID | Item | Content |
| --- | --- | --- |
| DC-S11-01 | Shown to | `view`. Page heading "Audit trail". |
| DC-S11-02 | Filters (complete list) | "Incident" (numbered incidents and "All"): an incident selects the audit events whose `incident_id` is that incident or an incident merged into it, directly or through earlier merges (`merged_into_incident_id`, read from `vigil-incidents`); "Event" (the event display names of DC-TX-10 and "All"); "Who" (every user, "{product} (automatic)" and "All"); "From" and "To" (date and time). A change of filter applies at once. Above the list: "{n} {event or events}". Empty: "No events match the filters." |
| DC-S11-03 | Rows | Newest first (DC-U-12). Columns (complete list): time as `D Mon YYYY, HH:MM:SS` (seconds always shown in this screen); who (DC-U-06); event display name (DC-TX-10); subject (the incident number; "Merged into {number}" for a merged incident; "Forming incident" for an incident that never got a number; or the configuration area display name, DC-TX-01); summary (DC-TX-10). |
| DC-S11-04 | Details | A click on a row expands it. For "Configuration changed": the area display name and two columns "Before" and "After" with the values as indented `{name}: {value}` lines; values that differ are marked. For other events: the details as `{name}: {value}` lines. |

### 7.12 DC-S12 Demo control panel (SL-A4-12, demo-only)

This is the screen of `demo-control` (PM-DC-04), a separate application (SL-A3-03). It never reads Vigil's incidents (DD-F-10 `vigil_state`). `{product}` here is a setting of the control panel's own deployment with the same value as the tenant setting of DC-U-02. Times are shown as `HH:MM:SS` in the time zone of the operator's browser. Until its first read of the lab and of `vigil-api` after opening, the panel shows "Connecting…" in place of DC-S12-04 and DC-S12-05, and every button is disabled.

| ID | Item | Content |
| --- | --- | --- |
| DC-S12-01 | Access | Its own login, used only by the operator, with a username and a password from a deployment secret (SL-B-14): fields "Username", "Password", button "Sign in"; a wrong username or password shows "Username or password is incorrect." Usable at 640 px wide. A line across the top: "Demo control panel — demo-only, not part of {product}." |
| DC-S12-02 | Buttons (complete list, `30-jazz-storyboard` Section 6) | Each with a one-line description: "Storm: SL-F-1 + SL-F-3" — "Fills /data on the Subscriber DB server and fails the report export job."; "Storm re-run: SL-F-1" — "Fills /data on the Subscriber DB server again."; "Storm with read-only DB: SL-F-1 + SL-F-2" — "Fills /data and sets the database read-only."; "Cut cloud AI: SL-F-4" — "Blocks {product}'s calls to the Claude and OpenAI APIs."; "Clear all faults" — "Ends every active fault and waits until every lab alert is resolved."; "Reset" — "Clears the lab and brings {product} to the starting state." "Reset" asks for confirmation (DC-U-11). |
| DC-S12-03 | Guards (complete list; DD-F-10 `storm_guard`) | A refused button is disabled and shows its reason under it. While "Clear all faults" or "Reset" is running, every button is refused with "Wait: {Clear all faults or Reset} is running." Otherwise: each storm button is refused while SL-F-1, SL-F-2 or SL-F-3 is active, with "Clear all faults first (active: {active fault IDs, comma-separated})."; "Cut cloud AI: SL-F-4" is refused while SL-F-4 is active, with "Already active."; "Clear all faults" is refused when no fault is active, with "No active faults."; "Reset" is never refused otherwise. |
| DC-S12-04 | Active faults (complete list of lines) | "Active faults:" and one line per active fault, ordered by fault ID: "{fault ID} {fault name} since {time}"; or "No active faults." |
| DC-S12-05 | Operation status | Exactly one of (complete list): "Idle"; "Clearing faults… {s} s — firing lab alerts: Zabbix {z}, Alertmanager {a}"; "Resetting… {s} s — lab: {Clearing or Clear}, {product}: {Resetting or Ready}". After an operation, the last result stays shown: "Clear all faults completed in {s} s", "Reset completed in {s} s", or "{operation} failed after {s} s: {error text returned by the lab or by {product}}". When the control panel cannot reach the lab or `vigil-api`: "Cannot reach {the lab or {product}}: {error text}." |
| DC-S12-06 | Storm clock | After a storm button: "T+{m}:{ss} since '{button}'", counting up each second, until the next storm button or a reset. It shows the operator the T+ times the script uses (`30-jazz-storyboard` Section 7.2). |
| DC-S12-07 | Link | "Open {product}" opens the Vigil login in a new tab. |

## 8. Product wording

Every text in this section is product behaviour (DC-R-05, DC-R-06). Where a record field holds the text (Example: a timeline entry's `text`), the owning module writes it with this wording; where a screen composes it, `vigil-web` does.

### 8.0 DC-TX-00 Placeholder rules (complete list)

| Rule | Content |
| --- | --- |
| Counts | A count before a noun uses the singular noun when the count is 1 and the plural otherwise, and a verb that follows it agrees ("is" or "are", "was" or "were"). Written in this document as "{n} {noun}" or as "{singular or plural}" (Example: "1 alert", "126 alerts"; "1 branch and otherwise"). For "{n} {time or times}", 1 gives "1 time". |
| Lists | A list placeholder ("{team names}", "{service names}") is joined by ", " and ordered alphabetically by the text shown, unless the placeholder states another order. An empty list is "none" unless the text states another word. |
| Times and durations | DC-U-07. |
| Names | DC-U-06. |
| `{action name}`, `{target name}`, `{target key}` | The registered action's name; the name of the `server` component whose `target_key` is the target (DM-E26-R1), and the runner `target_key` (demo: "Subscriber DB server", "lab-db-01"). |

### 8.1 DC-TX-01 Names, states and formats (complete list)

| Item | Wording |
| --- | --- |
| Incident number (resolves DM-O-11) | `INC-` followed by the incident `number`, padded with leading zeros to 4 digits (more digits when the number exceeds 9999). Example: `INC-0002`. Numbers are per tenant (DM-E-32), so they restart at `INC-0001` in every new tenant. |
| Incident state | `forming` "Forming"; `open` "Open"; `resolved` "Resolved"; `closed` "Closed"; `merged` "Merged". |
| Stage (BSD-BR-008, DM-SM-05 note) | Shown only for an `open` incident, from the step type of its latest live run's step that is running or waiting: Diagnostic or AI task → "Investigation"; Approval or Action → "Response"; Recovery check → "Recovery"; any other step type, or no running or waiting step → no stage (empty). |
| Severity | "P1", "P2", "P3", "P4". |
| Source kind | `zabbix` "Zabbix"; `alertmanager` "Alertmanager". |
| Component kind | "Server", "Application", "Database", "Queue", "Check". |
| Criticality | "Critical", "High", "Medium", "Low"; empty → "not set". |
| Provider kind name | `claude` "Claude"; `openai` "OpenAI"; `local_model` "Local model"; `local_embedding` "Local embedding model". |
| Provider display name | "{provider kind name} — {model_name}" (Example: "Claude — {model}"). Where this document says "provider kind name", the model name is left out. |
| Location tag | `external` "External"; `in_house` "In-house". |
| AI task | SL-T-1 "Incident investigation"; SL-T-2 "Incident summary"; SL-T-3 "Similar-incident search". |
| AI unavailable reason | `no_provider_reachable` "no allowed AI provider was reachable"; `no_provider_allowed` "data routing allows no provider for this data"; `output_failed_checks` "the AI output failed the checks and was not used". |
| AI usage outcome | `succeeded` "Succeeded"; `unreachable` "Unreachable"; `provider_error` "Provider error"; `refused_by_data_routing` "Not sent (data routing)". |
| Data category (DM-R-42), name — description | `alert_data` "Alert data" — "Alert types, titles, sources and times"; `service_context` "Service context" — "Names and relations from the service map"; `diagnostic_output` "Diagnostic output" — "Values and text returned from servers"; `runbook_text` "Runbook text" — "Runbook content"; `incident_history` "Incident history" — "Content of past incidents"; `incident_record` "Incident record" — "What {product} recorded about the incident". |
| Approval request state | `pending` "Waiting for approval"; `approved` "Approved"; `rejected` "Rejected"; `expired` "Expired"; `preauthorized` "Preauthorized by policy"; `blocked` "Blocked by policy"; `refused` "Refused: attempt limit reached" or "Refused: an earlier execution has an unknown result"; `cancelled` "Cancelled". |
| Policy check result | `approval_required` "Approval required"; `preauthorized` "Preauthorized"; `blocked` "Blocked". |
| Action execution state | `recorded`, `rechecking` "Checking conditions"; `not_executed` "Not executed: a recheck failed"; `executing` "Running"; `reconciling` "Checking the result"; `succeeded` "Succeeded"; `failed` "Failed"; `unresolved` "Result unknown". |
| Recheck name and detail (DM-E-60 `recheck`; stored texts, DC-U-07 rule 4) | `approval_valid` "Approval still valid" — detail "approved by {user display names}; valid until {earliest expires_at, outbound time format}"; `unchanged` "Action, parameters and target unchanged" — detail "unchanged since approval" or "changed: {action, hypothesis component or target}"; `incident_open` "Incident still open" — detail "{state}"; `precondition` "Precondition" — detail "{operation name} ({parameters as name=value}): {value read} {operator symbol} {threshold}" (demo: "Used percentage of one filesystem (mount=/data): 100 ≥ 90"). Operator symbols: `equals` "=", `not_equals` "≠", `greater_than` ">", `greater_or_equal` "≥", `less_than` "<", `less_or_equal` "≤". |
| No proposal | "No action was proposed." |
| Notified purpose | `incident_notification` "incident notification"; `escalation` "escalation"; `approval_request` "approval request". |
| Escalation cause | `acknowledgement_timeout` "not acknowledged within {timer}", where timer is the escalation's `timer` (amendment DC-A-10); `escalate_now` "workflow escalation step". |
| Run state | `running` "Running"; `waiting` "Waiting"; `finished` "Finished"; `cancelled` "Cancelled"; `failed` "Failed". |
| Metric definition (DM-R-53) | Time to acknowledge: "Mean time from detection to first acknowledgement". Time to recover: "Mean time from detection to first verified recovery". Alerts suppressed: "Alerts received minus incidents". Incidents merged: "Incidents made from 2 or more alerts". |
| Configuration area (DM-V-11) | `tenant_settings` "Customer settings"; `users_and_teams` "Users and teams"; `sources` "Monitoring sources"; `service_map` "Service map"; `routing_rules` "Routing rules"; `escalation_levels` "Escalation levels"; `workflows` "Workflows"; `ai_settings` "AI settings"; `registered_actions` "Registered actions"; `seed_import` "Seed import". |
| Outbound time | `YYYY-MM-DD HH:MM:SS UTC` (Example: "2026-10-10 09:12:00 UTC"). |

### 8.2 DC-TX-02 Hypothesis sentence and grouping reasons

**Hypothesis sentence** (DC-S03-03). Let m be the number of affected components other than the hypothesis component, and n the number of them that depend on it, directly or indirectly (from the latest hypothesis computation, DM-E-30, read under DC-R-08). Complete list of cases: m = 0 → "It is the only affected component."; n = m = 1 → "The other affected component depends on it."; n = m > 1 → "The other {m} affected components all depend on it."; n < m → "{n} of the other {m} affected components depend on it." Demo: "The other 6 affected components all depend on it." (SB-S02 "6 of 6").

**Grouping reason** (DC-S03-05), from the member's `final_reason`. `{related}` is "'{related alert title}' ({related alert source kind})"; `{Δ}` is `time_delta` and `{W}` the grouping window, both as durations (DC-U-07); `{path}` is the display names of the components in `relation_path`, in stored order. Complete list:

| Relation | Wording |
| --- | --- |
| `same_component` | "Same component as {related}. Fired {Δ} apart, within the {W} window." |
| `depends_on` | "Depends on the component of {related}: {path joined by ' → '}. Fired {Δ} apart, within the {W} window." |
| `depended_on_by` | "The component of {related} depends on this one: {path joined by ' ← '}. Fired {Δ} apart, within the {W} window." |
| `same_unknown_identity` | "Unknown component, with the same {identity name} '{identity value}' as {related}. Fired {Δ} apart, within the {W} window." |
| `only_alert` | "The only alert of this incident." |
| Any relation, for an alert whose decision is `reopened` (DM-R-21) | The sentence of its relation, with the last sentence replaced by "Reopened the incident {Δ} after {related} fired." |

Demo example (Example, not an exact value; SB-S02 "Varies"): "Depends on the component of 'Disk full: /data free space below 1 GiB' (Zabbix): Balance-check API → Subscriber database → Subscriber DB server. Fired 14 s apart, within the 5 min window."

### 8.3 DC-TX-03 Evidence summaries, sources and condition texts (resolves SB-O-02)

Summaries are at most 200 characters (DM-E-38); a longer text is cut to 199 characters followed by "…". Complete list of evidence kinds:

| Kind | Summary | `source_system` |
| --- | --- | --- |
| `diagnostic_result` | The definition's `summary_template` filled with the output values (DD-F-04). | "{target_key} via runner, operation {operation_key}" |
| `ai_investigation` | "AI investigation by {provider kind name}: {confirms or disputes} the hypothesis {component name}; cites {runbook keys, in cited order, comma-separated}." Without a verdict (DM-E68-R3): "AI investigation by {provider kind name}; cites {runbook keys}." | "{provider kind name}, model {model_name}" |
| `similarity_match` | "Similar past incident {key} '{title}' ({record_label}), similarity {score}; fix succeeded {success_count} {time or times}." | "Incident history, {provider kind name}" |
| `approval` | By request state: approved "Approved by {user display name}: {action name} on {target name}."; rejected "Rejected by {user display name}: {action name} on {target name}."; expired "Approval expired with no decision: {action name} on {target name}."; preauthorized "Preauthorized by policy: {action name} on {target name}."; blocked "Blocked by policy: {action name} on {target name}."; refused "Approval refused ({attempt limit reached or an earlier execution has an unknown result}): {action name} on {target name}." | "Approval {sequence_in_run} of the run" |
| `action_execution` | "{action name} on {target name}: {state text}." When the operation produced output values, "; {result text}" is added before the full stop. Result text per action output parser (complete list for the built action operations): `removed_files` → "{files_removed} files removed ({gib_removed} GiB)". For `not_executed`: "{action name} on {target name}: not executed, recheck '{recheck name}' failed." | "{target_key} via runner, operation {operation_key}" |
| `recovery_verification` | Recovered: "Recovered: '{check name}' held for {observation window} with every monitoring source healthy." Not recovered: "Not recovered within {max_wait}: {texts of the conditions not holding, joined by '; '}." | "Monitoring: {active source names}" |

Each condition text is stored in the verification's last evaluation as the condition's `detail` (amendment DC-A-12), so screens and outbound content read the same text. Evidence entries of kind `approval` are created when an approval request reaches `approved`, `rejected`, `expired`, `preauthorized`, `blocked` or `refused` (PM-M14-09); a `cancelled` request and a "no proposal" step create none.

**Recovery condition texts** (DM-V-07; used on DC-S03-11, DC-S05-03, outbound and summaries). Complete list:

| Condition | Text when shown as a requirement, or holding | Text when not holding |
| --- | --- | --- |
| `incident_alerts_resolved` | "Every alert of the incident is resolved at its source" | "{f} {alert or alerts} of the incident {is or are} still firing" |
| `component_no_firing_alert`, empty `alert_types` | "No alert of {component name} is firing" | "{f} {alert or alerts} of {component name} {is or are} firing" |
| `component_no_firing_alert`, with `alert_types` | "No {alert type display names joined by ' or '} alert of {component name} is firing" | "{f} {alert type display names joined by ' or '} {alert or alerts} of {component name} {is or are} firing" |
| Source health guard (DM-R-36) | "Every monitoring source is healthy" | "{source names} not healthy" |

### 8.4 DC-TX-04 Timeline texts (complete list, DM-E-39 `entry_type`)

| Entry type | Text |
| --- | --- |
| `created` | "Incident created from alert '{title}' ({source kind})." |
| `alert_joined` | "Alert '{title}' ({source kind}) joined." |
| `merged_in` | "{n} alerts merged in from another group." |
| `hypothesis_changed` | "Hypothesis: {component name}." when there was none before; otherwise "Hypothesis changed from {old name} to {new name}." |
| `impact_changed` | "Customer impact: {service names, comma-separated}." or "Customer impact: none identified." |
| `assignment_changed` | "Assigned {severity}, owner {team}, informed {teams} (rule '{rule name}')." The rule name is passed by `vigil-routing` with its request (amendment DC-A-10). |
| `state_changed` | `forming` → `open`: "Opened as {number}: the alert group is stable."; `open` → `resolved`: "Resolved: recovery verified."; `resolved` → `open`: "Reopened by alert '{title}' ({source kind})."; `resolved` → `closed`: "Closed: the reopen window has passed."; `forming` → `merged`: "Merged into another group." |
| `acknowledged` | "Acknowledged by {user display name}." |
| `escalated` | "Escalated to level {n}: {team names} ({escalation cause})." |
| `evidence_added` | "Evidence: {evidence summary}" |
| `ai_status_changed` | "AI investigation available." or "AI investigation unavailable: {reason}." |
| `notified_team_added` | "{team name} notified ({purpose})." |
| `title_changed` | "Title: {title}" |

### 8.5 DC-TX-05 Step types in the editor (complete list; parameters per DM Section 9)

Output display texts are those of DM Section 9. Parameters are listed as display name, input, default when a step is added from the palette (SL-WE-01). A default of "none" leaves a required parameter empty, which validation reports (DC-TX-07) until the admin sets it.

| Step type | Display name | Default label | Parameters: display name — input — default | Card summary | Read-only notes in the panel |
| --- | --- | --- | --- | --- | --- |
| `trigger` | Trigger | "Incident created or reopened" | Event — fixed text "Incident created or reopened" — that value | "On incident created or reopened" | — |
| `condition` | Condition | "Condition" | Branches — ordered list; each branch has "Branch name" (a key, not "otherwise") and "Conditions" (rows of field, operator, value, DC-TX-06, joined by "and"); controls "Add branch", "Add condition", remove, move up, move down — one branch named "branch-1" with no condition | "{n} branches and otherwise" | "Branches are tested from top to bottom; 'otherwise' is taken when none matches." |
| `diagnostic` | Diagnostic | "Diagnostics" | Diagnostics — ordered choice of active diagnostic definitions, by name — none; Target — fixed text "Hypothesis component's server" — that value | "{n} checks on the hypothesis component's server" | — |
| `ai_task` | AI task | The chosen task's display name | Task — choice of the three AI task display names — Incident investigation | "{task display name}" | — |
| `approval` | Approval | "Approval" | Eligible teams — choice of several active teams — none; Expiry — seconds, 60 to 86,400 — 900 | "Eligible: {team names}; expires after {expiry}" | "The approver must differ from the approvers of earlier Approval steps in the run." |
| `action` | Action | "Run approved action" | None | "Runs the approved action" | "Runs the action proposed for this incident, only after an approval in this run, and checks the conditions again first." |
| `recovery_check` | Recovery check | "Verify recovery" | Maximum wait — seconds, 60 to 86,400 — 300 | "Gives up after {max_wait}" | "Uses the recovery check linked to the action that ran, or the default recovery check when no action ran." |
| `escalation` | Escalation | "Escalation" | Mode — "Wait for acknowledgement" or "Escalate now" — Wait for acknowledgement; Timer — seconds, 30 to 86,400, only for "Wait for acknowledgement" — 600 | "Escalates every {timer} until acknowledged" or "Escalates one level now" | "Escalation levels (routing configuration): {level n: team names; …}" |
| `notification` | Notification | "Notify" | Recipients — checkboxes "Owner team", "Informed teams", "Named teams" — Owner team; Named teams — choice of several active teams, shown when "Named teams" is checked — none | "To {the checked recipients in the order Owner team, Informed teams, then the named team names}" (demo `s-notify`: "To Owner team, Informed teams, NOC L1") | "The message carries a link to the Acknowledge page." |
| `ticket` | Ticket | "Ticket" | Operation — "Create" or "Resolve" — Create | "Create ticket" or "Resolve ticket" | — |
| `wait` | Wait | "Wait" | Mode — "For a duration" or "Until an event" — For a duration; Duration — seconds, 1 to 86,400 — 60; Event — fixed text "Incident acknowledged"; Timeout — seconds, 1 to 86,400 — 600 | "Wait {duration}" or "Wait for acknowledgement, up to {timeout}" | — |

### 8.6 DC-TX-06 Condition and routing fields and operators (DM-R-25, DM-R-26)

Field display names (complete list): `severity` "Severity"; `owner_team` "Owner team"; `informed_teams` "Informed teams"; `hypothesis_component` "Hypothesis component"; `hypothesis_component_kind` "Hypothesis component kind"; `hypothesis_criticality` "Hypothesis criticality"; `affected_services` "Affected services"; `affected_service_count` "Number of affected services"; `highest_service_criticality` "Highest service criticality"; `alert_count` "Number of alerts"; `alert_types` "Alert types"; `alert_sources` "Alert sources"; `acknowledged` "Acknowledged"; `escalation_level` "Escalation level"; `ai_investigation_available` "AI investigation available"; `reopen_count` "Times reopened". The routing rules screen offers only the fields marked "routing: yes" in DM-R-25.

Operator display texts (complete list): ordered enum, key or enum — `equals` "is", `not_equals` "is not", `in` "is one of", `not_in` "is not one of", `at_least` "is at least", `at_most` "is at most"; set of keys — `contains` "includes", `not_contains` "does not include"; integer — `equals` "=", `not_equals` "≠", `greater_than` ">", `greater_or_equal` "≥", `less_than` "<", `less_or_equal` "≤"; boolean — `is` "is".

Value inputs: an ordered enum, enum or key field offers a choice of its values by display name (teams from `vigil-identity`; components and services of the current service map generation as "{name} ({key})"; alert types by display name); `in`, `not_in` and set fields offer a choice of several; integers a number field; booleans "Yes" or "No". A comparison is shown as text as "{field} {operator} {value}", with several values joined by ", " (Example: "Highest service criticality is at least Critical").

### 8.7 DC-TX-07 Validation and parameter messages (complete list)

| Code or check | Message |
| --- | --- |
| `one_trigger` | "A workflow needs exactly one Trigger step." (on every Trigger when there are several; in the Problems list only when there is none) |
| `reachable_from_trigger` | "This step cannot be reached from the Trigger." |
| `no_unconnected_step` | "This step has no incoming connection." |
| `condition_branch_connected` | "Output '{label}' is not connected." |
| `action_guarded` | "An Action step needs an Approval step before it on every path, and a Recovery check step after each of its outputs." |
| `cycle_has_wait` | "This loop has no Wait step." |
| `parameter_valid` | "{parameter display name}: {problem}", where problem is one of (complete list): "a value is needed"; "must be from {min} to {max}"; "'{name or key}' is not an active team"; "'{name or key}' is not an active diagnostic"; "at least one is needed"; "branch names must be different and must not be 'otherwise'"; "'{value}' is not a valid value for {field display name}". |
| Label | "Label: must be 1 to 60 characters." |

The same texts are shown in the parameter panel when a value is refused on entry (DC-S06-08).

### 8.8 DC-TX-08 Version change list details (complete list; DM-E-47 `change_list`, SL-WE-09)

| Change | Details text |
| --- | --- |
| `added` | "Added '{label}'" |
| `removed` | "Removed '{label}'" |
| `changed` | "Changed '{label}' ({parts})", with parts from this complete list, in this order, joined by ", ": "type {old} → {new}" (step type display names); "label '{old}' → '{new}'"; for each changed parameter "{parameter display name in lower case} {old} → {new}" (values as shown on the step card: durations in seconds, teams by name); "outgoing connections". |

Order of the lines (amendment DC-A-14): added steps first, in the order of the new version's `steps`; then changed steps, in the same order; then removed steps, in the order of the previous version's `steps`. A step added from the palette is at the end of `steps` (DC-S06-04). The demo version 2 shows exactly, in this order (SB-D02 step 9): "Added 'DB Ops second approval'"; "Changed 'Wait for acknowledgement' (timer 600 s → 120 s)"; "Changed 'DB Ops approval' (outgoing connections)".

### 8.9 DC-TX-09 Test replay notes (DM-R-49; resolves the display wording of SB-D02 step 3)

Team names come from `vigil-identity` (amendment DC-A-02), in the order of their team keys. Complete list:

| Step type and case | Note |
| --- | --- |
| Trigger, Condition | No note. |
| Diagnostic, AI task, Action, Recovery check with a stored result | "Stored result of the original run." |
| Diagnostic, AI task, Action, Recovery check without one | "No stored result." |
| Approval, no simulated proposal | "No action would be proposed." |
| Approval, first in the run | "Simulated approval. Eligible: {team names}." |
| Approval, later in the run | "Simulated approval. Eligible: {team names}. The approver must differ from the approvers of earlier Approval steps." |
| Action refused by the engine guard | "Refused: {refusal reason}." |
| Escalation | "Already acknowledged; no escalation." when the source incident was acknowledged at or before the step's simulated start. Otherwise one sentence per event, joined by " ": "Simulated escalation to level {n}: {team names} (not acknowledged within {timer})." for each timeout; "Simulated escalation to level {n}: {team names}." for "Escalate now"; "Acknowledged at +{m}:{ss}." when the acknowledgement ends the wait. |
| Notification | "Simulated: would notify {team names}." Demo: "Simulated: would notify Customer Care, Database Operations, NOC L1, VAS Service Owners." |
| Ticket | "Simulated: no ticket change." |
| Wait | "Simulated wait of {duration}.", "Acknowledged at +{m}:{ss}." or "Timed out after {timeout}." |

### 8.10 DC-TX-10 Audit trail events (complete list, DM-E08-R1)

| Event type | Display name | Summary |
| --- | --- | --- |
| `incident_created` | Incident created | "Incident created from alert '{title}' ({source kind})." |
| `alert_grouped` | Alert grouped | "Alert '{title}' ({source kind}) {created a new incident, joined the incident, or reopened the incident}." with "; {k} groups merged" added when the decision merged incidents. |
| `hypothesis_changed` | Hypothesis changed | "Hypothesis: {old name or 'none'} → {new name or 'none'}." |
| `assignment_changed` | Assignment changed | "Assigned {severity}, owner {team}, informed {teams or 'none'} by rule '{rule name}'." |
| `incident_acknowledged` | Incident acknowledged | "Acknowledged by {user display name}." |
| `incident_escalated` | Incident escalated | "Escalated to level {n}: {team names} ({escalation cause})." |
| `ai_result_accepted` | AI result accepted | "{AI task display name} by {provider kind name} accepted." |
| `ai_result_rejected` | AI result rejected | "{AI task display name} by {provider kind name} rejected: {rejection reasons, in stored order, joined by '; '}." (texts DC-TX-11) |
| `policy_check_result` | Policy check | "Policy for {action name}: {policy check result}." |
| `approval_requested` | Approval requested | "Approval {n} requested for {action name} on {target name} from {eligible team names}." |
| `approval_decided` | Approval decided | "Approval {n}: {approval request state text} — {action name} on {target name}." with "by {user display name}" after the state for a human decision; for no proposal: "No action was proposed." |
| `action_executed` | Action executed | "{action name} on {target name}, attempt {n}: {action execution state text}." |
| `action_reconciled` | Action reconciled | "{action name} on {target name}, attempt {n}: result checked, {Succeeded, Failed or Result unknown}." |
| `recovery_verified` | Recovery verified | "{Recovered or Not recovered}: '{check name}'." |
| `incident_state_changed` | Incident state changed | "{from state} → {to state}." |
| `notification_sent` | Notification sent | "{purpose} to {recipient name} by {Teams or email}: {sent or failed}." |
| `ticket_changed` | Ticket changed | "Jira {key or 'no ticket'}: {create, reopen, resolve or record escalation} {done or failed}." |
| `configuration_changed` | Configuration changed | By area (complete list): customer settings "Customer settings changed."; users and teams "Users and teams changed."; sources "Monitoring source '{name}': address changed."; service map "Service map imported as generation {g} ({services} services, {components} components, {identity_keys} identity keys, {dependencies} dependencies)."; routing rules "Routing rules saved as revision {r}: {change_summary}." where `change_summary` (DM-E-41) is one part per rule that differs from the previous revision, in the new list order, removed rules last, joined by "; ": "added rule '{name}'", "removed rule '{name}'", or "changed rule '{name}' ({changed parts in this order: name, conditions, severity, owner, informed teams, inform owners of affected services, position; comma-separated})", and "no rules changed" when no rule differs (amendment `60-build-plan` BP-A-28); escalation levels "Escalation levels saved as revision {r}."; workflows "Workflow version {n} published from a draft." or "Workflow version {k} published (rollback to version {m})."; AI settings: one part per changed item, joined by "; " (complete list of parts): "provider order of {AI task display name} changed to {provider kind names, in order}", "provider of Similar-incident search changed to {provider kind name}", "data routing of {category display names} changed"; the whole prefixed by "AI settings: " and ended by "."; registered actions "Registered actions changed."; seed import "Seed bundle {bundle_name} {bundle_version} imported." |

### 8.11 DC-TX-11 Other messages (complete list)

| Where | Messages |
| --- | --- |
| Service map import problems (DM-R-55) | "{section}: key '{key}' appears more than once."; "{section} '{key}': component '{ref}' is not in the file."; "components '{key}': runs_on must name a server."; "components '{key}': a server cannot run on another component."; "components '{key}': target_key is allowed only on a server."; "identity_keys: {source_kind} {name} = '{value}' names more than one component."; "dependencies: '{key}' cannot depend on itself."; "dependencies: '{from}' → '{to}' appears more than once."; "dependencies: cycle {key} → … → {key}."; "services '{key}': needs at least one component."; "{section} '{key}': '{value}' is not a valid {field}."; "The file could not be read: {parser message}." (the parser's own message; format Round 5) |
| Readiness gaps (DM-E-81; SB-D01 exact values) | "{component name} ({component key}): server with no owner"; "{service name} ({service key}): service with no criticality"; "{source name}: source not healthy". |
| Routing rules refusals (DM-E41-R1) | `{name}` is the rule's name, or its position in the list (1, 2, …) when the name is empty (amendment `60-build-plan` BP-A-28). "Rule '{name}': a name is needed."; "Rule '{name}': add at least one condition."; "The last rule must be the only rule without conditions."; "Rule '{name}': '{key}' is not an active team."; "Rule '{name}': '{value}' is not valid for {field display name}." |
| Routing rules, incomplete fields | "Rule '{name}': complete or remove the empty condition."; "Rule '{name}': choose a team." |
| Test replay failure reasons (DM-E-49 `failure_reason`, amendment DC-A-15) | "The draft has {n} {problem or problems}."; "This incident cannot be replayed: it has no workflow run."; "This incident cannot be replayed while it is forming or merged."; "The test run failed: {run end detail}.", where the run end detail (DM-E-50 `end_detail`) is "step '{step label}' did not answer after {n} attempts"; "The test run stopped after 1,000 steps." |
| Engine guard refusal reasons (DM-E-51 `refusal_reason`, DM-R-31) | "no action proposal in this run"; "no approval after the last execution"; "the latest approval is not for this run's proposal"; "an output of this step does not reach a Recovery check step". |
| AI output rejection reasons (DM-E-68 `rejection_reasons`, PM-M17-03) | "no likely cause"; "does not say whether it confirms or disputes the hypothesis"; "no uncertainty stated"; "no evidence reference"; "refers to evidence that is not on the incident"; "no runbook cited"; "cites a runbook that was not supplied". |
| AI settings refusals (DM-E-72, DM-E-73) | "{AI task display name}: at least one provider is needed."; "Similar-incident search needs exactly one provider."; "{provider display name} is not allowed for {AI task display name}."; "{provider display name} appears twice." |

## 9. Outbound content

### 9.1 Rules (complete list)

| ID | Rule | Trace |
| --- | --- | --- |
| DC-OUT-00 | Every outbound text uses the names and formats of DC-TX-00 and DC-TX-01, times and durations by DC-U-07 rule 4, and links per DC-U-13. The environment label prefix is applied exactly as DM-E02-R2 states (Jira title; email subject and first body line; first line of the Teams message). Outbound content is defined as ordered lines of text; the encoding of the lines for each system (Jira's document format, a Teams card or plain text, email text) is decided in Round 5 (DC-O-03) and MUST keep every line, its text and its order. | SL-A1-05, SL-DR-03, DM-E02-R2 |

### 9.2 Jira (complete list of ticket changes, PM-D-10)

| ID | Change | Content |
| --- | --- | --- |
| DC-OUT-01 | Create (Ticket step `create`) | Title: `{label} - {title}` (demo, SB-S05: "Demo environment — fictional data - Subscriber DB server: Disk full — 3 services affected"). Description, exactly these parts in this order (SL-A1-05 complete list): "Incident: {title}"; "Alerts: {alert_count} alerts. Types: {alert type display names, alphabetical, comma-separated}."; "Customer impact: {service names, comma-separated, or 'none identified'}."; "Root-cause hypothesis: {component name} ({component key}). A hypothesis from the service map, not a confirmed cause." or "Root-cause hypothesis: none."; "AI summary: {summary}" when the summary's source is AI, otherwise "Summary (template): {summary}"; "Evidence:" followed by one line "- {evidence summary}" per evidence entry in `collected_at` order, or "- none yet"; "Open {number} in {product}: {incident link}". The label of DM-E76-R2 is set (form Round 5). No other field is set beyond those the connector settings require (project, issue type; DM-O-12). |
| DC-OUT-02 | Record escalation (comment) | "Escalated to level {n}: {team names} ({escalation cause})." then "Evidence:" and one line "- {evidence summary}" per evidence entry. |
| DC-OUT-03 | Resolve (Ticket step `resolve`; comment, then the resolved transition) | Lines (complete list, in order): "Resolved: recovery verified."; the recovery verification's evidence summary (DC-TX-03); "Conditions:" and one line "- {condition detail text}: held" per condition; "- Every monitoring source is healthy: held"; "Observation window: {observation window}, from {window_started_at} to {decided_at}."; "Source: {source_system}". This is the summary and content of the evidence entry that DM-SM14-T2 requires. |
| DC-OUT-04 | Reopen (Ticket step `create` on a reopened incident; comment, then the reopen transition) | "Reopened by alert '{title}' ({source kind}) received at {first_received_at}." |

### 9.3 Messages (complete list of message kinds, DM-E-78 `purpose`)

Each message is sent to Teams and email (PM-M20-02). The Teams message and the email body are the lines below, in order (complete list per message); the email subject is line 1.

| ID | Purpose | Lines |
| --- | --- | --- |
| DC-OUT-05 | Incident notification (SL-A1-05) | 1 "{label} - [{severity}] {number} {title}"; 2 "Owner: {owner team}. Informed: {informed team names or 'none'}."; 3 "Customer impact: {service names or 'none identified'}."; 4 "Summary: {summary}"; 5 "Acknowledge: {Acknowledge link}". |
| DC-OUT-06 | Escalation (SL-A2-03, SL-A2-04) | 1 "{label} - Escalation level {n}: [{severity}] {number} {title}"; 2 "Escalated to {team names} ({escalation cause})."; 3 "Owner: {owner team}. Informed: {informed team names or 'none'}."; 4 "Summary: {summary}"; 5 "Evidence:" and one line "- {evidence summary}" per evidence entry; 6 "Acknowledge: {Acknowledge link}". |
| DC-OUT-07 | Approval request (SL-A1-06) | 1 "{label} - Approval needed: {action name} on {target name} for {number}"; 2 "[{severity}] {number} {title}"; 3 "Expires: {expires_at}"; 4 "Summary: {summary}"; 5 "Approve or reject: {Approval link}". |

## 10. Later (not built) items added by this document (complete list)

Each item adds to a built part without changing it (DC-R-02).

| ID | Item | Builds on | Trace |
| --- | --- | --- | --- |
| DC-L-01 | Adding a monitoring source from the setup screen, including creating its connector instance, credential references and webhook token, showing its webhook address, entering secret values, and editing a source's name. | DC-S07-02; PM-M22-01; PM-M04-03 | BSD-BR-001; BSD §11.1 Setup center |
| DC-L-02 | Editing escalation levels on a screen. | DC-S08; PM-M10-04 | BSD §11.2 Business settings |
| DC-L-03 | Viewing an earlier workflow version's graph and comparing two versions on the canvas. | DC-S06-15 | BSD §11.1 Workflow studio ("compare versions") |
| DC-L-04 | Test replay history, and marking the test path on the canvas. | DC-S06-13; DM-E-49 | BSD §11.1 Workflow studio |
| DC-L-05 | Incident list filters, other sort orders and search. | DC-S02-04 | BSD §11.1 Command center |
| DC-L-06 | Viewing the earlier workflow runs of a reopened incident. | DC-S03-06 | BSD §11.1 Execution view |
| DC-L-07 | Service map viewer (services, components and dependencies as a graph). | DC-S07-03; PM-M07 | BSD §11.1 Setup center ("review assets") |
| DC-L-08 | Runbook and past-incident browsing screens, including the runbook text behind a citation. | DC-S03-09, DC-S03-10; PM-M16 | BSD §12.1 Knowledge |
| DC-L-09 | Notification delivery status per recipient and channel on the incident workspace. | DC-S03-14; DM-E-78 | BSD §11.1 Incident workspace ("response history") |
| DC-L-10 | Dashboard time filters, trends and charts. | DC-S10 | BSD §13.1 |
| DC-L-11 | AI usage time filter and export. | DC-S09-04, DC-S09-05 | BSD §11.1 AI administration |
| DC-L-12 | Importing a seed bundle from a screen. | PM-M22-03 | BSD-BR-029 |
| DC-L-13 | Customer settings screen (customer name, logo, environment label). | DC-M-01; DM-E-02 | BSD §11.2 Business settings |
| DC-L-14 | A view of forming incidents (each forming group with its alerts) before they are numbered. | DC-S02-02, DC-S03-01 | BSD §11.1 Incident workspace |

## 11. Scenario walkthrough (CTX-Q-3)

### 11.1 Primary demo (complete list of scenes)

| Scene | Scope lock | Screen items used | Data needed | Defined outcome on screen |
| --- | --- | --- | --- | --- |
| SB-S00 | — | DC-S02 (empty state DC-S02-05), DC-U-01, DC-U-03 | Tenant settings (DD-F-01) | Banner "Demo environment — fictional data"; customer "Jazz"; "No incidents yet. {product} is watching 2 monitoring sources." |
| SB-S01 | SL-A1-01 | DC-S12-02 ("Storm: SL-F-1 + SL-F-3"), DC-S12-06; DC-S02-02, DC-S02-03 | Lab alert catalogue (DD-F-03); alerts (DM-E-21) | The strip counts up to "Alerts received in the last 10 minutes: 127" by 03:00; "Grouping in progress: {m} alerts are being grouped" while the storm incident forms; the latest alerts scroll; no forming incident appears as a row. |
| SB-S02 | SL-A1-02 | DC-S02-04; DC-S03-02 to DC-S03-05; DC-TX-02 | Incidents, memberships with `final_reason`, hypothesis computation (DC-A-01), service map | Two rows: the storm incident ("Subscriber DB server: Disk full — 3 services affected", 126 alerts, P1) and the report incident ("Report export job: Report export job failed — 1 service affected", P4, IT Applications). In the workspace: tag "Hypothesis" on "Subscriber DB server (srv-db-01)", "The other 6 affected components all depend on it."; customer impact Balance Check (USSD), Bundle Purchase, SMS Notifications; "Alerts (126)" in 7 type groups; three expanded reasons read from DC-TX-02. |
| SB-S03 | SL-A1-03 | DC-S03-08; DC-TX-03; DC-S03-06 | Diagnostic results and evidence entries | Three "Observation" lines with the summaries of DD-F-04, each "Source: lab-db-01 via runner, operation {key}" and its collected time. |
| SB-S04 | SL-A1-04 | DC-S03-02 (severity, owner, informed, rule), DC-S03-09 | Routing evaluation; AI investigation result with `runbooks_supplied` (DC-A-03) | Header "Severity: P1", "Owner: Database Operations", "Informed: VAS Service Owners", "Rule: Critical service affected"; AI section with likely cause, "Hypothesis: Confirms", evidence used, "Still uncertain:", "Runbooks given: RB-API-01, RB-DB-01, RB-USSD-01", runbooks cited, "Provider: Claude — {model}" (SB-V-31). |
| SB-S05 | SL-A1-05 | DC-OUT-01 (Jira tab), DC-OUT-05 (Teams, mail), DC-U-13, DC-S01-03, DC-S04, DC-S03-13 | Ticket link; notification records; login session; acknowledgement | Jira title "Demo environment — fictional data - Subscriber DB server: Disk full — 3 services affected" and the seven description parts; Teams and email lines 1 to 5 of DC-OUT-05; phone window: login, then the Acknowledge page with title, P1, owner, summary; after the tap "Acknowledged by Hira Baig (NOC L1) at {time}."; timeline "Acknowledged by Hira Baig (NOC L1)." |
| SB-S06 | SL-A1-06 | DC-S03-10; DC-OUT-07; DC-S05; DC-TX-01 rechecks; DC-TX-03 | Similarity search result; past incident PI-1001; proposal; approval request snapshot; execution | Response section: "PI-1001 Subscriber DB /data full after archive catch-up run", tag "Fictional record", "Similarity 1.00", "Fix succeeded 4 times"; "Proposed action" Purge archived transaction logs with its three parameters; Approval page with every part of DC-S05-03 (expiry 900 s after creation shown as a time and countdown); after Approve, the execution block with the four rechecks passed (precondition "Used percentage of one filesystem (mount=/data): 100 ≥ 90") and the "Execution result" evidence "{files} files removed ({gib} GiB)". |
| SB-S07 | SL-A1-07 | DC-S03-05 (statuses), DC-S03-06 ("Verify recovery" highlighted), DC-S03-11, DC-OUT-03 | Alert state changes; recovery verification (DC-A-01) | "Firing" counts fall to 0; recovery shows both conditions "Holding", "Monitoring sources: all healthy", "Holding for {s} s of 60 s", then tag "Verified outcome" "Recovered at {time}"; state "Resolved"; Jira shows the resolved status and the comment of DC-OUT-03. |
| SB-S08 | SL-A1-08 | DC-S03-06 ("Fit" or "Expand"), DC-S03-07, DC-S03-12 | Workflow run and step executions | The run canvas with the taken path in the accent colour and every step on it showing its output; run state "Finished"; the run steps list from "Incident created or reopened" to "Resolve Jira ticket". |
| SB-S09 | SL-A1-09 | DC-S10 | Metrics (DM-R-53); baselines (DD-F-07) | Tiles 5.7 min, 40.5 min, 650, 47 with baselines 11.0 min, 72.0 min, 0, 0, each "Illustrative baseline"; "Includes 2 live incidents and 50 imported past incidents labelled 'Fictional record'." |
| SB-S10 | — | — | — | No new screen content. |

Result: every scene SL-A1-01 to SL-A1-09 has screen items, data and a defined outcome.

### 11.2 Deep dives (complete list)

| Scene | Scope lock | Screen items used | Defined outcome on screen |
| --- | --- | --- | --- |
| SB-D01 | SL-A2-01 | DC-S07-02 to DC-S07-04; DC-TX-11 | "Test connection" on Zabbix: "Last test: Passed, {time}", no configuration change; Alertmanager address edited with "Edit address" and saved, then tested: "Last test: Failed, {time}: {reason}", health "Disconnected", gap "Alertmanager — Jazz lab: source not healthy"; restored: "Passed", "Healthy", gap gone within 5 s; import: "Imported generation 2: 4 services, 12 components, 15 identity keys, 6 dependencies."; gaps "Reporting server (srv-rpt-01): server with no owner", "Management Reports (svc-mgmt-reports): service with no criticality". |
| SB-D02 | SL-A2-06, SL-A2-03 | DC-S06-02 to DC-S06-15; DC-S08-04; DC-TX-05, DC-TX-08, DC-TX-09; DC-S03-02; DC-OUT-02, DC-OUT-06; DC-S05-03 (second approval line) | Editor actions of step 1 (palette, connect with replacement, panel, zoom, pan, "Fit", move, "Auto layout", delete, Undo, Redo); "Escalation" timer 600 → 120; routing card for "Critical service affected" shows "Informed: Customer Care and the owners of affected services" after saving revision 2; test replay panel tagged "Test" with the dry run "P1, owner Database Operations, informed Customer Care, VAS Service Owners", the Notification note "Simulated: would notify Customer Care, Database Operations, NOC L1, VAS Service Owners.", both Approval notes, at least one "Simulated escalation to level 1: NOC Shift Lead (not acknowledged within 120 s)."; publish "Version 2 published."; escalation messages and header "Escalated: level 2, NOC Management"; second approval page "Approval 2: your approval must differ from earlier approvals in this run."; versions panel lines of DC-TX-08 in the order "Added 'DB Ops second approval'", "Changed 'Wait for acknowledgement' (timer 600 s → 120 s)", "Changed 'DB Ops approval' (outgoing connections)"; rollback "Version 3 published (rollback to version 1)." |
| SB-D03 | SL-A2-05 | DC-S12-02 ("Cut cloud AI"); DC-S09-02; DC-S03-09; DC-S09-05 | Warning "AI investigation unavailable — no allowed AI provider was reachable." above the earlier result; after restoring the order and re-running: result with "Provider: Local model — {model}"; usage log filtered to that re-run: Claude "Unreachable", OpenAI "Unreachable", Local model "Succeeded". |
| SB-D04 | SL-A2-07 | DC-S09-02; DC-S03-09; DC-S09-05 | Usage log filtered to the re-run: only In-house lines; "Requests to external providers: 0". |
| SB-D05 | SL-A2-02 | DC-S09-02 to DC-S09-04 | Allowed providers per task; data routing "Incident history" saved with only In-house providers; usage and cost by provider and by task; OpenAI rows with only "Unreachable" and cost 0.0000; local models cost 0.0000. |
| SB-D06 | SL-A2-08 | DC-S11; DC-TX-10 | Filtered by the storm incident (which includes the forming incidents merged into it, DC-S11-02): "{n} events" with "Alert grouped" ×126 and the events SB-D06 names; filtered by "Configuration changed": the service map import, source changes, version 2 publish, rollback, routing revision and AI settings changes, each with who and when. |
| SB-D07 | SL-A2-04 | DC-S12-02 ("Reset", "Storm with read-only DB"); DC-S03-11; DC-S03-02; DC-OUT-06 | Recovery: "124 alerts of the incident are still firing: Not holding" and "Waiting for the conditions (gives up at {time})", then tag "Not recovered", and the evidence summary "Not recovered within 5 min: 124 alerts of the incident are still firing; …"; header "Escalated: level 1, NOC Shift Lead"; state stays "Open"; execution block "Attempt 1 of 1" and no second attempt. |

## 12. Traceability

### 12.1 Scope lock items to this document (complete list)

Every ID in `01-scope-lock` is listed (140 IDs, excluding the group headings SL-A1, SL-A2, SL-A4, SL-T).

| Scope lock item | Covered by |
| --- | --- |
| SL-A1-01 | DC-S02-02; DC-M-06, DC-M-29, DC-M-39 |
| SL-A1-02 | DC-S02-03, DC-S02-04; DC-S03-03 to DC-S03-05; DC-TX-02; DC-M-08 |
| SL-A1-03 | DC-S03-08; DC-TX-03; DC-M-13, DC-M-30 |
| SL-A1-04 | DC-S03-02, DC-S03-09; DC-TX-03; DC-M-17; DC-A-03 |
| SL-A1-05 | DC-OUT-01, DC-OUT-05; DC-S04; DC-S03-14; DC-M-19, DC-M-20, DC-M-32 |
| SL-A1-06 | DC-S03-10; DC-S05; DC-OUT-07; DC-TX-01 (rechecks), DC-TX-03; DC-M-14 |
| SL-A1-07 | DC-S03-05, DC-S03-11; DC-OUT-03; DC-TX-03; DC-M-15 |
| SL-A1-08 | DC-S03-06, DC-S03-07, DC-S03-12; DC-U-08 |
| SL-A1-09 | DC-S10; DC-M-21 |
| SL-A2-01 | DC-S07; DC-TX-11; DC-M-22 |
| SL-A2-02 | DC-S09-02 to DC-S09-04; DC-M-18 |
| SL-A2-03 | DC-S03-02; DC-OUT-02, DC-OUT-06; DC-TX-05 (Escalation) |
| SL-A2-04 | DC-S03-10, DC-S03-11; DC-OUT-06 |
| SL-A2-05 | DC-S03-09; DC-S09-02, DC-S09-05 |
| SL-A2-06 | DC-S06; DC-S08; DC-TX-05, DC-TX-08, DC-TX-09 |
| SL-A2-07 | DC-S03-09; DC-S09-05 |
| SL-A2-08 | DC-S11; DC-TX-10; DC-M-03 |
| SL-A3-01 | DC-M-38 |
| SL-A3-02 | DC-M-40; DC-S12-02 |
| SL-A3-03 | DC-S12; DC-M-41; DC-M-22 (seed bundle loaded through reset) |
| SL-A3-04 | DC-R-03; Section 11.1 (every exact value is computed, none is prepared) |
| SL-A3-05 | DC-M-23; DC-U-13 |
| SL-A3-06 | DC-U-01, DC-U-03; DC-M-01 |
| SL-A3-07 | DC-U-04, DC-U-05; DC-S01; DC-M-02 |
| SL-A3-08 | DC-M-43 |
| SL-A4-01 | DC-S01 |
| SL-A4-02 | DC-S02 |
| SL-A4-03 | DC-S03 |
| SL-A4-04 | DC-S04 |
| SL-A4-05 | DC-S05 |
| SL-A4-06 | DC-S06 |
| SL-A4-07 | DC-S07 |
| SL-A4-08 | DC-S08 |
| SL-A4-09 | DC-S09 |
| SL-A4-10 | DC-S10 |
| SL-A4-11 | DC-S11 |
| SL-A4-12 | DC-S12 |
| SL-ASM-01 | DC-M-29 (unchanged; verified by SB-T-01) |
| SL-ASM-02 | DC-OUT-00 (rendering with SB-T-06) |
| SL-ASM-03 | DC-M-34 |
| SL-ASM-04 | DC-M-34 |
| SL-B-01 | DC-M-01 |
| SL-B-02 | DC-M-06 |
| SL-B-03 | DC-M-11, DC-M-12, DC-M-36; DC-S06 |
| SL-B-04 | DC-M-14; DC-S05 |
| SL-B-05 | DC-M-05 |
| SL-B-06 | DC-M-03; DC-S11 |
| SL-B-07 | DC-M-04 |
| SL-B-08 | DC-M-18 |
| SL-B-09 | DC-M-10; DC-U-02; DC-R-05 |
| SL-B-10 | DC-R-03, DC-R-05; DC-S12 |
| SL-B-11 | DC-M-12 |
| SL-B-12 | DC-M-09; DC-TX-01 (states), DC-TX-04 |
| SL-B-13 | DC-U-05, DC-U-13; DC-S04, DC-S05 |
| SL-B-14 | DC-S01; DC-S12-01; DC-M-23 |
| SL-C-01 | DC-M-35 |
| SL-C-02 | DC-M-05 (the lab DB server is the only target) |
| SL-C-03 | DC-M-35 |
| SL-C-04 | Not built; no module or screen (unchanged) |
| SL-C-05 | DC-M-10 (PM-M10-L1) |
| SL-C-06 | DC-M-02 (PM-M02-L1, PM-M02-L2); DC-U-05 |
| SL-C-07 | DC-M-27 |
| SL-C-08 | DC-M-25 |
| SL-C-09 | DC-M-04 (PM-M04-L1) |
| SL-C-10 | DC-S04, DC-S05 (mobile web pages) |
| SL-C-11 | Withdrawn in `01-scope-lock`; no mapping |
| SL-C-12 | DC-M-28 |
| SL-C-13 | DC-S10-05 |
| SL-C-14 | DC-M-35 |
| SL-C-15 | DC-M-08 (PM-M08-L3) |
| SL-C-16 | DC-M-26 |
| SL-C-17 | DC-M-14 |
| SL-C-18 | DC-S06-11 |
| SL-C-19 | DC-S06-04 (fixed palette) |
| SL-CP-1, SL-CP-2, SL-CP-3, SL-CR-02, SL-CR-03 | Not product items (checkpoints and change control) |
| SL-CR-01 | DC-R-04 |
| SL-DR-01, SL-DR-02 | DC-M-42; DC-R-05 |
| SL-DR-03 | DC-U-01, DC-U-10; DC-OUT-00; DC-S10-03 |
| SL-DR-04 | DC-M-42; DC-M-16 |
| SL-F-1, SL-F-2, SL-F-3, SL-F-4 | DC-S12-02; DC-M-40 |
| SL-O-01 | DC-S03-11; DC-S05-03 (shown) |
| SL-O-02 | DC-S12-02 |
| SL-O-03 | Section 11 |
| SL-O-04 | DC-TX-02 (window shown in reasons) |
| SL-O-05 | DC-S02-04 |
| SL-O-06 | DC-S03-08 |
| SL-O-07 | DC-S03-02 |
| SL-O-08 | DC-S03-10; DC-S05-03 |
| SL-O-09 | DC-S05-03 |
| SL-O-10 | DC-S10-03 |
| SL-O-11 | DC-S07-03 (format: `50-architecture` AR-B-06) |
| SL-O-12 | DC-S07-02 |
| SL-O-13 | DC-S09-03; DC-TX-01 (data categories) |
| SL-O-14 | DC-TX-05 (Escalation note); DC-S03-02 |
| SL-O-15 | DC-S05-03 ("Attempt 1 of 1"); DC-S03-10 |
| SL-O-16 | DC-S12-02 |
| SL-O-17 | DC-S12-02 ("Reset"); DC-M-22 (mechanism: `50-architecture` AR-B-01 to AR-B-04) |
| SL-O-18 | DC-U-06 |
| SL-O-19 | Not this round (Round 6) |
| SL-O-20 | DC-S09-02 (defaults: `50-architecture` Section 13.2) |
| SL-O-21 | DC-S08-04 |
| SL-O-22 | DC-S06-02 |
| SL-O-23 | DC-TX-06 |
| SL-T-1, SL-T-2, SL-T-3 | DC-M-37; DC-S09-02; DC-TX-01 |
| SL-WE-01 | DC-S06-04; DC-TX-05 |
| SL-WE-02 | DC-S06-06 |
| SL-WE-03 | DC-S06-07 |
| SL-WE-04 | DC-S06-08; DC-TX-07 |
| SL-WE-05 | DC-S06-09 |
| SL-WE-06 | DC-S06-10 |
| SL-WE-07 | DC-S06-02, DC-S06-11, DC-S06-14 |
| SL-WE-08 | DC-S06-12; DC-TX-07 |
| SL-WE-09 | DC-S06-15; DC-TX-08 |
| SL-WE-10 | DC-S03-06, DC-S03-07 |
| SL-X-01 | DC-S04, DC-S05 |
| SL-X-02 to SL-X-16 | Not product items (record of differences from the demo document); their outcomes are covered through the items they point to |

### 12.2 Open items and pending decisions resolved here (complete list)

| Item | Resolved by |
| --- | --- |
| DM-O-11 Incident number format | DC-TX-01 |
| SB-O-02 Wording of evidence summaries | DC-TX-03 |
| DM-E32-R3 How forming incidents are shown (left to Round 4) | DC-S02-02, DC-S02-03, DC-S03-01 |
| SB-S01 "how they are shown is decided in Round 4" | DC-S02-02 |
| SB-D02 step 3 "display wording Round 4" | DC-TX-09 |

### 12.3 Items of this document to their sources

Every DC item carries its trace in its own table (Trace column, or the scope lock item in its heading for screens). Items without a direct scope lock item and their source (complete list): DC-U-07 (CTX-W-08; the 1280 px main window, DC-ASM-03); DC-U-09 (BSD §11.1 states); DC-U-11 (actions that change a target, a published workflow or the whole demo run); DC-S02-02 item 3 (SL-A2-01 source health, BSD-BR-004); DC-U-15 and DC-U-16 (SL-A4 screens: screen names and save feedback); DC-TX-00 (CTX-W-07); DC-S03-12 (BSD §11.1 Execution view, SL-A1-08); DC-S03-13 collapsing (SL-A4-03 timeline); DC-S12-03 to DC-S12-05 (SL-A3-03, DD-F-10 `control_panel`); DC-S12-06 (SL-A3-03; the T+ times of `30-jazz-storyboard` Section 7.2); DC-L items (BSD sections named in Section 10).

## 13. Explicitly out of scope for this document

Visual design beyond the rules here (colours, fonts, icons, spacing); the technology of the screens and the canvas (Round 5); the public interface operations and the live update mechanism (Round 5, PM-O-01); address formats of links (Round 5, DC-O-02); the encoding of outbound content for Jira, Teams and email (Round 5, DC-O-03, DD-O-04); Jira project, issue type, transitions and label form (Round 5, DM-O-12); the reset mechanism (Round 5, SL-O-17); localisation (PM-M24-L2); build packages and the order of building screens (Round 6, SL-O-19).

## 14. Decisions, changes to earlier documents and assumptions

### 14.1 Decisions made in this round

Status "Agreed by owner" means Abdul agreed the decision under CTX-Q-7 on 10 October 2026. Status "Proposed" means Claude made the decision within the scope lock and earlier documents; Abdul MAY change any of them through the `00-context` decision log. Items marked "Owner review" are put to Abdul under CTX-Q-7.

| ID | Decision | Reason | Status | Trace |
| --- | --- | --- | --- | --- |
| DC-D-01 | Module cut of Section 5: 23 modules Keep, `vigil-setup` Simplified, 4 Later-only modules Later, nothing Staged. | The scope lock already sized the demo for one builder (CTX-D-005); a further cut of a built responsibility would remove product shape the audience sees. | Agreed by owner | CTX-C-01, CTX-C-03, CTX-G-2 |
| DC-D-02 | The product display name is a tenant setting (amendment DC-A-09), working value "Techfinity"; it appears on screens and in outbound content. The working name Vigil stays in documents and code. Where the audience would otherwise see "Vigil" (narrator lines, the admin display name, the demo Teams team), the product display name is used (amendment DC-A-04 part B). | CTX §3 says Vigil is not a brand; the owner stated that the demo is branded "Techfinity" (Section 2). | Agreed by owner | CTX §3, SL-B-09 |
| DC-D-03 | `vigil-setup` is Simplified: the admin edits the address of each seeded source, tests it and sees its health; adding a source, showing its webhook address, entering credentials and editing names are Later (DC-L-01). Recorded as a clarification of SL-A2-01 and an amendment of PM-M22-01 (DC-A-11). | SB-D01, agreed by the owner under CTX-D-018, already reads "connect" as editing and testing the seeded sources. Adding a source needs the runtime secret-writing path (PM-O-11) and webhook token creation for something no scene shows. | Agreed by owner | SL-A2-01, SB-D01 |
| DC-D-04 | Forming incidents are never rows: the incident list shows an incoming alerts strip (alerts received in 10 minutes, alerts being grouped, source health, the latest 10 alerts); an incident becomes a numbered row when it opens. A forming incident has no workspace (DC-L-14). | Meets DM-E32-R3; gives SB-S01 a live picture of the storm and the count of 127 the narrator says; never shows intermediate groups that later merge. | Agreed by owner | SL-A1-01, SL-A1-02, DM-E32-R3 |
| DC-D-05 | The incident workspace shows the workflow as a full-width band in follow mode (80% zoom, centred on the current step) with Fit and Expand, plus a run steps list. | In workflow version 1 the first and last steps are 2,640 px apart (DD-F-06); fitted into a 1280 px window its labels are too small to read in a screen share. Follow mode keeps the live highlight readable (SL-A1-08); the list gives the whole path in text (SB-S08). | Agreed by owner | SL-A1-08, SB-S08 |
| DC-D-06 | Incident numbers are shown as `INC-0001` (DM-O-11). | Common NOC format; numbers restart per tenant. | Proposed | DM-O-11 |
| DC-D-07 | Screens show times in the viewer's time zone with the offset in the header; stored texts and outbound content use UTC. | The operator's browser runs in Pakistan time (DC-ASM-04); a stored or sent text is read by many people in unknown time zones, and a tenant time zone setting would add a field no scene needs. | Agreed by owner | CTX-W-08 |
| DC-D-08 | Outbound content of Section 9: the Jira ticket holds exactly the seven parts of SL-A1-05; Teams and email messages carry severity, number, title, owner, informed teams, impact, summary and the link; escalation messages add the evidence; approval requests add the expiry; the resolve comment carries the recovery conditions and window. | SL-A1-05 is a complete list for the ticket; messages need enough to act from a phone; DM-SM14-T2 requires the recovery evidence content. | Agreed by owner | SL-A1-05, SL-A1-06, SL-A2-03 |
| DC-D-09 | Seven actions ask for confirmation, with the texts of DC-U-11; nothing else does. | These change a target system, published behaviour, external AI calls, similarity search or the whole demo run. | Proposed | SL-A1-06, SL-A2-06, SL-A3-03 |
| DC-D-10 | Workflow editor: explicit "Save draft"; Publish and a draft replay need a saved draft; new steps get the defaults of DC-TX-05, random `step_id` values and a place at the end of `steps`; a new connection from an output replaces the old one; durations on canvases are shown in seconds. | Matches DM-E-46 revisions and DM-R-68 (validation of the saved revision); one drag rewires SB-D02 step 1; seconds match the inputs and SB-D02's "600 s → 120 s". | Proposed | SL-WE-01 to SL-WE-09 |
| DC-D-11 | Screens read records only from their owning modules (DC-R-08): PM-I-04 adds `vigil-correlation`, `vigil-recovery` and `vigil-diagnostics`, and every module in PM-I-04 returns its records for display. Fields are added so screens and texts can show values the storyboard shows (amendments DC-A-03, DC-A-09, DC-A-10, DC-A-12, DC-A-13, DC-A-15, DC-A-16); test-mode notes read team names from `vigil-identity` (DC-A-02). | SB-S02 "6 of 6", SB-S04 runbooks given and matched rule, SB-S07 live recovery progress, SB-D02 team names and change list, SB-D03 unavailable time and usage of a failed re-run, and the escalation timer in messages cannot otherwise be shown without copying logic into `vigil-api` or `vigil-web`. Every added read follows the request order of PM-R-03. | Agreed by owner | SL-A1-02, SL-A1-04, SL-A1-07, SL-A2-03, SL-A2-05, SL-A2-06 |
| DC-D-12 | The timeline collapses every entry up to the moment the incident opened into one "Grouping" entry, and consecutive alert joins after it into one entry. | 126 alert joins would hide the story; nothing is removed, the entries expand. | Proposed | SL-A4-03 |
| DC-D-13 | The routing rules screen lets the admin edit, add, remove and reorder rules (the default rule stays last). | PM-M10-01 keeps routing rules "edited in the routing-rules settings screen"; the whole list is saved as one revision anyway (DM-E-41), so adding, removing and reordering cost little more than editing. The alternative is to build only the editing of existing rules that SL-O-21 needs and make the rest Later. | Agreed by owner | SL-A4-08, PM-M10-01, SL-A2-06 |
| DC-D-14 | The dashboard has no time filter and states what it includes in one line. | DM-R-53 applies no time filter; the line keeps the fictional history visible (SL-DR-03). | Proposed | SL-A1-09, SL-DR-03 |
| DC-D-15 | Version change lists list added, then changed, then removed steps (DC-TX-08, amendment DC-A-14). | DM-E-47 defines no order; this order puts the new step first, which is what SB-D02 step 9 narrates. | Proposed | SL-WE-09 |
| DC-D-16 | The audit trail's incident filter includes the forming incidents merged into the chosen incident. | The alerts of a storm are first decided into forming incidents that merge (DM-R-10 c); without this, "Alert grouped ×126" (SB-D06) would not hold. | Proposed | SL-A2-08, SB-D06 |
| DC-D-17 | The workspace states "No action was proposed." without naming which of the six cases of DM-R-29 applied. | The similar-incident line above it already explains the demo case (the report incident has no search); storing the case would add a field no scene needs. | Proposed | SL-A1-02 |

Owner review items (seven, CTX-Q-7, all agreed on 10 October 2026): (1) DC-D-01 with DC-D-03; (2) DC-D-02; (3) DC-D-04; (4) DC-D-05; (5) DC-D-07 with DC-D-08; (6) DC-D-11; (7) DC-D-13.

### 14.2 Changes to earlier documents (complete list)

Written on 10 October 2026 when the owner closed this round (CTX-D-022). None adds an item to list A or list B of `01-scope-lock`; DC-A-11 records a clarification of SL-A2-01.

| ID | Document and item | Change |
| --- | --- | --- |
| DC-A-01 | `10-product-map` Section 5, PM-I-04 | New module rule PM-R-10: "Every module listed in PM-I-04 MUST return its own records to `vigil-api` for display, filtered by incident, run, key or time range, ordered, paged, counted or summed as the screens of `40-demo-cut` state; these are reads only and write no audit event (DC-R-08)." PM-I-04's complete list adds `vigil-correlation`, `vigil-recovery` and `vigil-diagnostics`. |
| DC-A-02 | `20-domain-model` DM-R-48 and DM-R-49; `10-product-map` PM-M12 card and PM-I-45 | DM-R-48's complete list of test-mode reads adds "`vigil-identity` (team display names)". `vigil-engine`'s "Sends requests to" and the test-mode reads of PM-I-45 add `vigil-identity`. DM-R-49's notes use the wording of `40-demo-cut` DC-TX-09. |
| DC-A-03 | `20-domain-model` DM-E-68 | New field `runbooks_supplied: List<{runbook_key, version}>`, Req Y: the runbooks given to SL-T-1 by DM-R-50, in the order given. |
| DC-A-04 | `30-jazz-storyboard` | Part A (always): SB-S01 "how they are shown is decided in Round 4" becomes "shown in the incoming alerts strip (`40-demo-cut` DC-S02-02)"; SB-D02 step 3 "display wording Round 4" becomes "wording `40-demo-cut` DC-TX-09"; SB-D02 step 9 lists the change list in the order of DC-TX-08; SB-O-02 is closed by DC-TX-03. Part B (only if the owner agrees DC-D-02; complete list): (1) in the narrator lines of SB-S00, SB-S03, SB-S05, SB-S06, SB-S07 and SB-S08 and in the screen lines of SB-S00 and SB-S02 to SB-S08, the word "Vigil" is replaced by the product display name; this replacement does not apply to names of people, teams or data; (2) the admin display name "Vigil Administrator" becomes "Platform Administrator" in SB Section 5, SB-D02 step 9 and DD-F-01; (3) the demo Team "Vigil Demo" becomes "Techfinity Demo" in SB-SET-03, SB-SET-06 and DD-F-07 (delivery addresses and the Teams connector instance name). |
| DC-A-05 | `31-demo-data` DD-F-06 | Comment added to `draft_changes_for_version_2`: "The editor assigns the step_id of an added step and appends the step at the end of steps (`40-demo-cut` DC-S06-04); s-approve-2 is the value used by tools/check_data.py." No data value changes. |
| DC-A-06 | `20-domain-model` Section 16 | DM-O-11 closed by `40-demo-cut` DC-TX-01. |
| DC-A-07 | `00-context` | Records the adoption of this round, its decisions, the SL-A2-01 clarification (DC-A-11) and the Round 4 status. |
| DC-A-08 | `10-product-map` PM-M24-05 | The complete list of labels becomes the kind tags of `40-demo-cut` DC-U-10, which adds to the four existing labels "Observation", "AI analysis", "History match", "Proposed action", "Approval", "Execution result", "Verified outcome" and "Not recovered" (BSD §11.1). |
| DC-A-09 | `20-domain-model` DM-E-02; `10-product-map` PM-M01 card, PM-M01-02; `31-demo-data` DD-F-01 | DM-E-02 adds `product_display_name: Text(60)`, Req Y: the product name shown on screens and in outbound content (`40-demo-cut` DC-U-02). PM-M01's records and PM-M01-02 add it. DD-F-01 `tenant_settings` adds `product_display_name: "Techfinity"` (value subject to DC-D-02). DC-A-04 Part B (2) and (3) change DD-F-01 and DD-F-07 when the owner agrees DC-D-02. |
| DC-A-10 | `10-product-map` PM-I-11; `20-domain-model` DM-E-36 | PM-I-11's purpose adds: "passes the matched rule's name with each assignment, and the timer with each escalation, for the texts `vigil-incidents` writes". PM-I-14's purpose adds: "passes the escalation cause and timer with each escalation message and Jira record". DM-E-36 adds `timer: Duration`, Req N: the Escalation step's timer, set for cause `acknowledgement_timeout`. |
| DC-A-11 | `01-scope-lock` SL-A2-01 (clarification); `10-product-map` PM-M22-01 | SL-A2-01: "connect Zabbix and Alertmanager" is shown by editing and testing the monitoring sources loaded with the starting state (SB-D01); adding a source is Later (`40-demo-cut` DC-L-01). PM-M22-01 becomes: "MUST let an admin change the address of each monitoring source loaded by seed import and see each source's health and test result; the test itself is run by `vigil-intake` (PM-M06-07); adding a source is Later (DC-L-01)." |
| DC-A-12 | `20-domain-model` DM-E-62 | `last_evaluation` becomes `{at, conditions: [{condition, held, detail, firing_count}], sources_healthy, unhealthy_source_ids}`, where `detail` is the condition text of `40-demo-cut` DC-TX-03 for its state and `firing_count` the number of firing alerts the condition counted. The evidence entry of a recovery verification carries in its `content` the definition snapshot, `window_started_at`, `decided_at` and the last evaluation, which `vigil-ticketing` reads for DC-OUT-03. `vigil-recovery`'s "Sends requests to" adds `vigil-servicemap` (component names for the condition texts), with a new request PM-I-46 (`vigil-recovery` → `vigil-servicemap`: read component names); allowed by PM-R-03. |
| DC-A-13 | `20-domain-model` DM-E-32 | Adds `ai_status_changed_at: Timestamp`, Req N, set whenever `ai_status` changes; and `ai_last_request_group_id: Id`, Req N, set by `vigil-investigation` at every SL-T-1 attempt (workflow step or re-run) to that attempt's request group. A change of only these two fields publishes "incident changed" (PM-EV-09) but creates no timeline entry; DM-E32-R2 is amended by this exception. |
| DC-A-14 | `20-domain-model` DM-E-47 | `change_list` is ordered: added steps in the order of the new version's `steps`, then changed steps in the same order, then removed steps in the order of the previous version's `steps`. `details` uses the wording of `40-demo-cut` DC-TX-08. |
| DC-A-15 | `20-domain-model` DM-E-49 | Adds `failure_reason: Text(300)`, Req N, required when `state = failed`, with the texts of `40-demo-cut` DC-TX-11. A replay of a draft with violations creates a result in state `failed` with its `validation_result_id`. |
| DC-A-16 | `20-domain-model` DM-V-12 | `based_on` adds `record_label`. `target_name` is the name of the `server` component whose `target_key` is the target (DM-E26-R1). |
| DC-A-17 | `31-demo-data` DD-F-10 `monitoring_configuration.prometheus`; input to Round 5 (DM-O-04) | Every Prometheus alerting rule carries the annotation `summary` (complete list): LA-04 "Disk full on lab-db-01 /data"; LA-05 "Balance-check API errors on {instance} {endpoint}"; LA-06 "Bundle-purchase API errors on {instance} {endpoint}"; LA-07 "USSD gateway timeouts on {node}, menu {menu}"; LA-08 "Synthetic USSD session failed: {city}, {journey}" (placeholders are the alert's label values). |
| DC-A-18 | `10-product-map` PM-M06 | New responsibility PM-M06-07: "MUST run a connection test for a monitoring source on request through `vigil-api` (from DC-S07-02) and record it and recompute health exactly as for a periodic test (DM-R-39, DM-R-40)." Built, traced to SL-A2-01. |
| DC-A-19 | `20-domain-model` DM-E-20; DM-E-41 | DM-E-20 `title`: for Alertmanager, the alert's `summary` annotation, or the `alertname` label when there is none; for Zabbix, the event name (DC-A-17; input to Round 5, DM-O-04). DM-E-41 `change_summary` uses the wording of `40-demo-cut` DC-TX-10. |

### 14.3 Assumptions (complete list)

| ID | Assumption | Verified by |
| --- | --- | --- |
| DC-ASM-01 | A run canvas in follow mode at 80% zoom in a 240 px band of a 1280 px window shows step labels so that they can be read through a Teams screen share. | Rehearsal SB-T-08 |
| DC-ASM-02 | A canvas library is available for the chosen stack that gives zoom, pan, fit, selection of several steps, connection handles and a layered left-to-right layout, so DC-S06 needs no custom canvas engine. | Resolved in R5: `@xyflow/react` with elkjs (`50-architecture` AR-T-09; Section 20 item 15). |
| DC-ASM-03 | The shared screen of SB-SET-03 is 1920 px wide, so the main window is 1280 px wide. | Rehearsal SB-T-08 |
| DC-ASM-04 | The operator's browser runs in Pakistan time (UTC+05:00), so screen times match the audience's clock. | Rehearsal SB-T-08 |

## 15. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| DC-O-01 | Closed by CTX-D-024, 10 Oct 2026 (was: final value of the product display name). | Abdul | R4 |
| DC-O-02 | Closed by `50-architecture` Section 10.3, 12 Oct 2026 (was: address formats of the Acknowledge, Approval and incident links). | Claude | R5 |
| DC-O-03 | Closed by `50-architecture` AR-K-15, AR-K-18, AR-K-20 and Section 20 item 5, 12 Oct 2026 (was: encoding of outbound lines for Jira, Teams and email, and whether Alertmanager webhook alerts carry `annotations.summary`). | Claude | R5 |
| DC-O-04 | Closed by `50-architecture` AR-B-10, 12 Oct 2026 (was: which tenant's product display name and customer name the login screen shows before anyone logs in): the tenant that the request's host alias points to. | Claude | R5 |

Carried open items still open: CTX-O-01, CTX-O-02, CTX-O-04, CTX-O-05; SL-O-11 (format), SL-O-17, SL-O-19, SL-O-20; PM-O-01, PM-O-09 (connector capability), PM-O-10, PM-O-11, PM-O-17; DM-O-04 (field names), DM-O-05, DM-O-06, DM-O-08, DM-O-12, DM-O-13; DD-O-01 to DD-O-04; SB-O-01, SB-O-03.

## 16. Quality-check results

| Check | Result |
| --- | --- |
| DC-Q-N1 Input versions | The repository held `00-context` v1.7, `01-scope-lock` v1.7, `10-product-map` v1.1, `20-domain-model` v1.1, `30-jazz-storyboard` v1.0 and `31-demo-data` v1.0 at the start of this round; these are the inputs. |
| CTX-Q-1 Coverage both ways | Done by script on v0.1 and v0.2: every one of the 140 scope lock IDs is in Section 12.1; every DC ID referred to is defined; every ID of an earlier document cited here exists, except PM-R-10, which amendment DC-A-01 adds. Items found over-building in review and removed: the webhook address and name editing on the setup screen (now DC-L-01), the "Sent by" line of messages, the incident filter of the AI usage log, internal module names in audit details. The storm clock (DC-S12-06) stays, traced to the T+ times of the script. Routing add, remove and reorder stays as owner review item 7. |
| CTX-Q-2 Consistency | Compared with every input. Conflicts found and resolved by amendment, not silently: product map label list (DC-A-08); reads for display missing from PM-I-04 (DC-A-01); stored texts that need values another module holds (DC-A-10, DC-A-13); change list order (DC-A-14); test replay failure reason (DC-A-15); approval snapshot label (DC-A-16); recovery evidence content in Jira (DC-OUT-03 against DM-SM14-T2); `vigil-setup` narrowing against PM-M22-01 and SL-A2-01 (DC-A-11). SB-D02 step 9 listed the change list in another order; DC-A-04 part A aligns it. |
| CTX-Q-3 Scenario walkthrough | Section 11: every primary scene and deep dive has screen items, data and a defined outcome. |
| CTX-Q-4 Independent review | Done, 10 Oct 2026, on v0.1 by a separate Claude reviewer that read only the documents of Section 2: 33 findings (1 High, 19 Medium, 13 Low), all resolved in v0.2. High: the audit trail filtered by the storm incident would miss the alerts first grouped into forming incidents that later merged, so "Alert grouped ×126" (SB-D06) failed — the incident filter now includes merged incidents (DC-S11-02, DC-D-16). Medium, resolved: provider line printed the model twice; connection test route and recording; change list order against the stated demo order (DC-A-14); escalation timer and durations in stored texts (DC-A-10, DC-U-07 rule 4); rule name written by `vigil-incidents` (DC-A-10); "no proposal" sentence (DC-D-17); recovery panel sources and condition texts (DC-A-12); kind tag rule reworded (DC-U-10); product name source, owner and the other places "Vigil" shows (Section 2, DC-A-04 part B, DC-A-09); approval snapshot label (DC-A-16); diagnostic definitions and other screen reads (DC-R-08, DC-A-01); setup narrowing recorded (DC-A-11); test replay failure (DC-A-15); MUST force of Sections 6 to 9 (DC-R-07); Response section with an unavailable search; re-run usage link and unavailable time (DC-A-13); confirmation texts and buttons (DC-U-11); configuration summaries (DC-TX-10). Verification pass 2 on v0.2: 26 resolved, 7 partly resolved, 9 new findings (0 High, 4 Medium, 5 Low); all resolved in v0.3: walkthrough texts aligned with the screen texts; on-demand connection test owned by `vigil-intake` (DC-A-18) and PM-M22-01 reworded; missing requests added (`vigil-recovery` → `vigil-servicemap`, PM-I-14 carries the timer) and the product name removed from the approval evidence source; AI attempt fields exempted from the timeline (DC-A-13); Part B of DC-A-04 listed line by line; DC-R-08 covers time ranges, paging and totals; DM-E-20 and DM-E-41 amended (DC-A-19); the login-screen value tracked (DC-O-04); recovery evidence content for Jira (DC-A-12). Pass 1 Low findings, resolved in v0.2: runbook keys; singular forms (DC-TX-00); fallback numbering; empty, partial and action-failed states (DC-U-09); browser leave prompt and outbound encoding (DC-S06-09, DC-OUT-00, DC-O-03); over-building removed (see CTX-Q-1); test replay control in both modes; imprecise values (DC-ASM-03, DC-ASM-04, 66%, 0.5 s); "(complete list)" labels; amendment references; Jira resolve content (DC-OUT-03); control panel times, guards and errors; module actor display (DC-U-06). |
| CTX-Q-5 Claude Code readiness test | Done, 10 Oct 2026, on v0.1 by a fresh Claude given only the documents of Section 2: 32 questions (17 affecting the primary demo or its repeatability), answered in v0.2. Pass 2 on v0.2: 27 answered, 5 partly answered, 5 new questions (verb agreement, which duration rule applies to stored texts, header and second-approval wording in the walkthrough, login heading and no-access title); all answered in v0.3 (DC-TX-00, DC-U-07 rule 4, Section 11, DC-U-15, DC-S01, DC-S12 "Connecting…", run end detail in DC-TX-11). Remaining questions: 0. Beyond the review findings they covered: the Alertmanager alert title (DC-A-17); the grouping summary counts (DC-S03-13); step cards on the run canvas and their durations (DC-S03-06, DC-U-07); `{target name}` and `{target key}` (DC-TX-00); the "History match" tag placement (DC-U-10); list orders (parameters, sources, notified teams, diagnostic values); empty texts (Notified, AI usage, replay dialog, audit); exact section headings, labelled header values and tab titles (DC-S03, DC-U-15); refusal precedence (DC-S04-05, DC-S05-06); reason texts (DC-TX-11); save feedback (DC-U-16); the seeded service map file name (DC-S07-03). |
| CTX-Q-6 Fact check | Not applicable to Round 4 (CTX-Q-6 applies to R3, R5, R6). This document makes no claim about an external product's behaviour: link formats, outbound encoding for Jira and Teams and the browser's leave warning are stated as open items or as the browser's own behaviour, and are checked in Round 5 (DC-O-02, DC-O-03). |
| CTX-Q-7 Owner review | Done, 10 Oct 2026. Seven items, all agreed as proposed: (1) the module cut with `vigil-setup` Simplified, DC-D-01 and DC-D-03; (2) product display name "Techfinity", DC-D-02 (DC-A-04 part B written); (3) forming incidents shown only in the incoming alerts strip, DC-D-04; (4) the workflow band in follow mode, DC-D-05; (5) viewer time on screens, UTC in stored and outbound texts, and the outbound content, DC-D-07 and DC-D-08; (6) reads for display and the added fields, DC-D-11; (7) full editing of routing rules, DC-D-13. Adoption recorded as CTX-D-022; the SL-A2-01 clarification as CTX-D-023; the product display name as CTX-D-024. Amendments DC-A-01 to DC-A-19 written. |

## 17. Change history

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 10 Oct 2026 | First draft (Round 4). |
| 0.2 | 10 Oct 2026 | Resolved the independent review findings and the readiness test questions (Section 16). |
| 0.3 | 10 Oct 2026 | Resolved verification pass 2 and readiness test pass 2 (Section 16). Draft for owner review. |
| 1.0 | 10 Oct 2026 | Owner review recorded (CTX-D-022 to CTX-D-024); amendments DC-A-01 to DC-A-19 written; Round 4 closed. |
| 1.1 | 12 Oct 2026 | Round 5 amendments (`50-architecture` AR-A-17, CTX-D-025): DC-O-02, DC-O-03 and DC-O-04 closed; DC-ASM-02 resolved; traceability notes for SL-O-11, SL-O-17 and SL-O-20 updated. |
| 1.2 | 12 Oct 2026 | Round 6 amendment (`60-build-plan` BP-A-12, CTX-D-029): DC-U-04 lists only built screens until SL-CP-3. |
| 1.3 | 22 Oct 2026 | Build amendment `60-build-plan` BP-A-28 (owner decision CTX-D-051): DC-TX-11 routing refusals name an unnamed rule by its position; DC-TX-10 routing rules summary of a save that changes no rule. |
