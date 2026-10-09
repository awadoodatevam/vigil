# Vigil — Demo Scope Lock

| Field | Value |
| --- | --- |
| Document | 01-scope-lock (ID prefix `SL-`) |
| Version | 1.6 |
| Date | 9 October 2026 |
| Owner | Abdul Wadood |
| Status | Locked (owner review recorded in Section 13). Changes only through Section 8. |

## 1. Purpose and how to use this document

This is the single source of truth for what the demo contains. It has three complete lists:

- **List A — Shown:** what the audience sees, what must be true on screen, and what is needed to run the demo.
- **List B — Built in but not shown:** product foundations the audience never sees but that are built from the first build package, so the demo grows into the product without a rebuild (CTX-C-03).
- **List C — Not now:** known exclusions. This is also this document's "explicitly out of scope" section.

Anything not in list A or list B is not built. Rounds 1–6 add detail to the items here; they do not add new items. New items enter only through the change rule in Section 8.

## 2. Inputs read

`docs/00-context.md` v1.4; `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md`; `docs/sources/Continuum — Business Solution Document.md` v0.2.

## 3. Definitions (complete list)

| Term | Meaning |
| --- | --- |
| Demo run | One presentation of the demo, starting from the starting state. |
| Starting state | The state after a reset (complete list): lab healthy with all faults cleared; Zabbix and Alertmanager connected and healthy; service map loaded; 50 past incidents and their runbooks loaded; workflow version 1 published and active; default routing rules active; default AI settings active (SL-O-20); demo users seeded (SL-A3-07); no open incidents; dashboard showing seeded history. |
| Storm re-run | Demo tooling (SL-A3-03): injects fault SL-F-1 again in the lab, so real monitoring sends a new alert storm to Vigil. Live effects occur. |
| Test replay | Vigil product feature (SL-A2-06): Vigil re-processes the stored alerts of an earlier incident through a selected workflow version or unpublished draft in an isolated test run. Diagnostic and AI steps in a test replay MUST reuse the stored diagnostic results and AI outputs of the original incident. A test replay MUST NOT (complete list): create a live incident; call Jira, Teams, email, AI providers or any other connector; run any diagnostic or action on a target; write live audit events. Its result MUST be stored and shown as a test result, labelled "Test". |
| Re-run AI investigation | Vigil product feature (SL-A2-07): an action in the incident workspace (SL-A4-03) that runs AI task SL-T-1 again, live, through the AI gateway (SL-B-08), using the incident's current evidence. |
| Environment label | A product setting (SL-B-09): text shown as a persistent banner on every Vigil screen and prefixed to every Jira ticket title, Teams message and email that Vigil sends. Demo value: "Demo environment — fictional data". |
| Real / Simplified / Staged | Tags given to each scene in Round 3. Real: the product feature works as specified. Simplified: works, with a narrower scope stated in Round 3. Staged: prepared output shown, no product logic. Scenes SL-A1-01 to SL-A1-07 MUST be tagged Real or Simplified, never Staged. |

## 4. Audience and goals

- **Seller:** Techfinity senior manager. **Buyer:** Jazz IT/service NOC (the team running servers, USSD and SMS platforms, APIs and the services that depend on them).
- Goals are CTX-G-1 and CTX-G-2.
- **Format:** presented online from a server under our own domain (SL-A3-05).

## 5. List A — Shown (complete list)

### 5.1 A1 — Primary demo, 15 minutes or less

| ID | Scene | Requirement | Trace |
| --- | --- | --- | --- |
| SL-A1-01 | Alert storm | When fault SL-F-1 is injected, Vigil MUST receive, within 120 seconds of injection, between 100 and 200 alerts inclusive (an alert is one alert entry; one webhook call MAY carry several), not counting the SL-F-3 alert, with at least one alert from Zabbix and at least one from Alertmanager, all by webhook. The alerts MUST cover these types (complete list): disk full; database write errors; USSD gateway timeouts; balance-check API errors; bundle-purchase API errors; SMS queue growth; failed synthetic USSD sessions. | BSD-BR-002; SL-ASM-01 |
| SL-A1-02 | One incident, with reasons | Vigil MUST group all SL-A1-01 alerts into exactly one incident using the service map and a time window (length SL-O-04). For each alert, the incident MUST show why it was grouped (service-map relation and time). Vigil MUST show a candidate root cause selected by this rule: the affected component on which the largest number of other affected components depend, directly or indirectly, according to the service map; ties are broken by higher criticality, then by component ID in alphabetical order. It MUST be labelled "Hypothesis". Customer impact MUST be shown as the affected business services from the service map. The SL-F-3 alert MUST become a separate incident, MUST NOT be grouped with the first, and MUST NOT trigger any action; its other visible handling is defined in SL-O-05. | BSD-BR-005, BSD-BR-006; BSD §6.2 |
| SL-A1-03 | Diagnostics | Vigil MUST run read-only diagnostic checks on the lab database server (complete list: disk usage per filesystem; largest folders under the configured data path; disk usage growth over a recent period; exact definitions SL-O-06) using read-only permission only (SL-B-05), and attach the results to the incident as evidence with source and collection time. | BSD-BR-003 |
| SL-A1-04 | AI investigation and routing | AI task SL-T-1 MUST state the likely cause (confirming or disputing the SL-A1-02 hypothesis), reference the evidence that supports it, state what remains uncertain, and cite the runbook it used by ID. Severity and owner MUST be assigned by the routing rules (configuration data, SL-B-09), not by the AI; AI output MUST NOT change them. Expected severity, owner and informed parties: SL-O-07. | BSD-BR-007, BSD-BR-011, BSD-BR-021; BSD RULE-03 |
| SL-A1-05 | Ticket and notification | Vigil MUST create a Jira ticket in our own Jira Cloud project containing (complete list): incident title; grouped alert count and alert types; customer impact; root-cause hypothesis; AI summary (SL-T-2); evidence summary; link to the incident in Vigil. Vigil MUST send a Microsoft Teams message and an email to the NOC L1 team and the owning team, each containing the incident summary and a link to the Acknowledge page (SL-A4-04). Any member of either team MAY acknowledge; in the demo, user noc-l1 acknowledges. Acknowledging MUST require login as a named demo user (SL-B-13) and MUST be recorded with user and time. If SL-T-2 is unavailable, the ticket and messages MUST use a summary generated from a fixed template of incident fields instead. | BSD-BR-009, BSD-BR-010; SL-ASM-02 |
| SL-A1-06 | Approved fix | Vigil MUST search the 50-incident demo history for similar incidents (SL-T-3) and show the best match with its similarity score and the recorded success count of its fix. Vigil MUST propose the registered action linked to that match. Vigil MUST send the approval request by Teams and email to the approver demo user with a link to the Approval page (SL-A4-05). The Approval page MUST show (complete list): action name; exact parameters; target server; limits; expiry time; the evidence the proposal is based on; the recovery checks that will be used. After Approve, Vigil MUST recheck (complete list): approval not expired; action, parameters and target unchanged since approval; incident still open; action precondition still true. Only then MUST Vigil execute the action, on the lab database server only. Exact values: SL-O-08, SL-O-09. | BSD-BR-012; BSD RULE-05, RULE-06 |
| SL-A1-07 | Recovery check | Vigil MUST NOT resolve the incident because the action succeeded. Vigil MUST resolve it only when (complete list): every grouped alert is resolved at its source; the synthetic USSD check reports success through monitoring; both conditions have held for the observation window (SL-O-01). Vigil MUST then update the Jira ticket to resolved, with the recovery evidence. Clarification (CTX-D-015): "the synthetic USSD check reports success through monitoring" means no alert of the synthetic check component is firing, and the conditions count only while every active monitoring source is healthy (`20-domain-model` DM-R-35, DM-R-36). | BSD-BR-014, BSD-BR-010; BSD RULE-08 |
| SL-A1-08 | Workflow glimpse | The incident workspace MUST show the incident's workflow run as a graph of its steps (step types per SL-B-03). The step currently running MUST be highlighted, and the highlight MUST update within 2 seconds of a step change. | BSD-BR-015, BSD-BR-022 |
| SL-A1-09 | Dashboard | The dashboard MUST show (complete list): time to acknowledge; time to recover; alerts suppressed (alerts received minus incidents created); incidents merged. Each MUST be compared with a baseline whose values are invented and labelled on screen "Illustrative baseline". Data source: SL-O-10. | BSD-BR-023 |

### 5.2 A2 — Deep dives, optional, shown after the primary demo on request

| ID | Deep dive | Requirement | Trace |
| --- | --- | --- | --- |
| SL-A2-01 | Guided setup | An admin MUST be able to connect Zabbix and Alertmanager, test each connection, and see each source's health as healthy, stale or disconnected (thresholds SL-O-12). The admin MUST be able to import the service map (services, servers, owners, criticality, dependencies) from a file (format SL-O-11). Vigil MUST list readiness gaps (complete list of gap types: server with no owner; service with no criticality; source not healthy). | BSD-BR-001 (without discovery, see SL-C-12), BSD-BR-004, BSD-BR-005 |
| SL-A2-02 | AI settings | An admin MUST be able to choose, for each AI task in Section 5.5, one provider from that task's allowed providers. The admin MUST be able to set the fallback order and which data categories (SL-O-13) may be sent to which provider. Vigil MUST show AI usage and cost per provider and per task. | BSD-BR-019 (without evaluation, see SL-C-16), BSD-BR-020 |
| SL-A2-03 | Nobody acknowledges | If no acknowledgement arrives before the configured timer expires, Vigil MUST escalate to the next fixed escalation level (levels and timers SL-O-14) and record the escalation on the incident and in the Jira ticket. | BSD-BR-009 |
| SL-A2-04 | The fix does not work | When fault SL-F-2 is active, the service stays down after the approved action. The incident MUST stay open and MUST escalate with the evidence. Vigil MUST NOT make more action attempts than the configured limit (SL-O-15). | BSD-BR-012, BSD-BR-014; BSD RULE-07 |
| SL-A2-05 | Cloud AI unavailable | When fault SL-F-4 is active (scope SL-O-16), Vigil MUST fall back to the next provider allowed for the task. If no allowed provider is reachable, Vigil MUST continue routing by rules and MUST show on the incident that AI investigation is unavailable. Clarification (CTX-D-016): fallback applies to SL-T-1 and SL-T-2; SL-T-3 has one provider and changes provider only through re-embedding (Section 5.5; `20-domain-model` DM-E72-R2). | BSD-BR-021 |
| SL-A2-06 | Change it live | In the drag-and-drop workflow editor (Section 5.6), an admin MUST be able to: drag a second Approval step into the P1 branch of the workflow and connect it, so P1 actions need two different approvers (in the demo, dbops-engineer approves first and dbops-lead second); and shorten the escalation timer in the Escalation step's parameter panel. An admin MUST be able to change one routing rule in the routing-rules settings screen (SL-A4-08; exact rule SL-O-21). Vigil MUST run a test replay of the earlier incident against the changed draft and show the result. Vigil MUST let the admin publish the draft as version 2; after a storm re-run, the new incident MUST follow version 2. Vigil MUST show version history with who changed what and when, and MUST roll back to version 1 in one step. | BSD-BR-015, BSD-BR-016 |
| SL-A2-07 | Keep data in-house | After an admin switches every AI task to the local model, a re-run AI investigation on the incident MUST use only the local model. The AI usage log MUST show that no request went to an external AI provider during that re-run. | BSD-BR-020 |
| SL-A2-08 | Audit trail | Vigil MUST show who did what and when, including every acknowledgement, approval, action, workflow change and routing-rule change. | BSD-BR-025 |

### 5.3 A3 — Demo operations and demo configuration

Items marked "demo-only" are outside the Vigil product (CTX-C-03).

| ID | Item | Requirement | Trace |
| --- | --- | --- | --- |
| SL-A3-01 | Jazz lab (demo-only) | The lab MUST run these mock Jazz services in our own environment (complete list): USSD gateway; balance-check API; bundle-purchase API; database server; SMS queue; synthetic USSD session check. They MUST be monitored by real Zabbix and real Prometheus with Alertmanager. | CTX-G-1; demo-only |
| SL-A3-02 | Fault injection (demo-only) | These faults MUST be triggerable on demand (complete list): **SL-F-1** database server disk full; **SL-F-2** a second fault that keeps the service down after the fix (definition SL-O-02); **SL-F-3** one unrelated alert at the same time as SL-F-1; **SL-F-4** cloud AI providers unreachable (scope SL-O-16). | CTX-G-1; demo-only |
| SL-A3-03 | Demo control panel (demo-only) | A separate demo-only application, not part of Vigil, with its own login used only by the presenter. It MUST: trigger SL-F-1 to SL-F-4; start a storm re-run; reset to the starting state. Reset MUST complete in 60 seconds or less. Reset MUST NOT modify or delete records of earlier demo runs (mechanism SL-O-17). The control panel MUST call Vigil only through Vigil's public interfaces. | CTX-G-1; SL-B-10; demo-only |
| SL-A3-04 | Repeatability | From a reset, the primary demo MUST run to completion 5 times in a row with the same result, meaning (complete list): one storm incident plus one separate SL-F-3 incident; the same root-cause hypothesis; the same severity and owner; the same best past-incident match and similarity score; the action executed once; the incident resolved; the Jira ticket resolved. The alert count MAY vary within 100 to 200. | CTX-G-1 |
| SL-A3-05 | Hosting | Vigil MUST be served from a cloud server under our own domain, over HTTPS only, behind the demo login. | CTX-G-1; SL-B-14 |
| SL-A3-06 | Demo configuration values | The demo MUST set these product settings (SL-B-09): customer name and logo = Jazz (subject to CTX-O-05); environment label = "Demo environment — fictional data". | SL-DR-03; CTX-O-05 |
| SL-A3-07 | Demo users | Vigil MUST be seeded with these named users and teams (complete list), display names SL-O-18: **presenter**, no team, view access to all Vigil screens; **noc-l1**, team NOC L1, acknowledges; **dbops-engineer**, team Database Operations, acknowledges and approves actions; **dbops-lead**, team Database Operations, acknowledges and approves actions (second approver in SL-A2-06); **admin**, no team, setup, routing rules, AI settings, re-run AI investigation (CTX-D-013), workflow draft editing, test replay, publish and rollback. Users, teams and permissions are seeded data; there are no user or role management screens (SL-C-06). | SL-B-13; BSD-BR-024 |
| SL-A3-08 | Backup video (demo-only) | A recording of the full demo (SL-A1 and SL-A2) MUST exist before the first live presentation, for use if the live session or network fails. | CTX-G-1; demo-only |

### 5.4 A4 — Screens (complete list)

Vigil screens SL-A4-01 to SL-A4-11 are product screens. SL-A4-12 belongs to the separate demo control panel application (SL-A3-03). Detailed content of each screen is defined in Round 4.

| ID | Screen |
| --- | --- |
| SL-A4-01 | Login |
| SL-A4-02 | Incident list |
| SL-A4-03 | Incident workspace: grouped alerts with grouping reasons, root-cause hypothesis, customer impact, evidence, AI investigation with a "Re-run AI investigation" action, timeline, workflow graph with live highlight, ticket link |
| SL-A4-04 | Acknowledge page (mobile layout, usable at 360 px screen width) |
| SL-A4-05 | Approval page (mobile layout, usable at 360 px screen width) |
| SL-A4-06 | Workflow editor: drag-and-drop canvas with step palette and parameter panel (Section 5.6), test replay, version history, publish, rollback |
| SL-A4-07 | Setup and sources: connect, test, source health, service map import, readiness gaps |
| SL-A4-08 | Routing rules settings |
| SL-A4-09 | AI settings: provider per task, fallback order, data routing, usage and cost |
| SL-A4-10 | Dashboard |
| SL-A4-11 | Audit trail |
| SL-A4-12 | Demo control panel (demo-only) |

### 5.5 AI tasks (complete list)

| ID | AI task | Allowed providers (complete list) | Used in |
| --- | --- | --- | --- |
| SL-T-1 | Incident investigation: likely cause, supporting evidence, uncertainty, runbook citation | Claude; OpenAI; local model | SL-A1-04, SL-A2-07 |
| SL-T-2 | Incident summary text for the Jira ticket and messages | Claude; OpenAI; local model | SL-A1-05 |
| SL-T-3 | Text embedding for similar-incident search | Local embedding model (default, used in the demo); OpenAI | SL-A1-06 |

The SL-T-3 search text MUST be built only from fields that are identical on every run (complete list): alert types; affected services; root-cause hypothesis component. Similarity scores MUST be displayed and compared rounded to 2 decimal places. The demo uses the local embedding model for SL-T-3 (CTX-D-011): the same input gives the same score (SL-ASM-04), so the match in SL-A1-06 is identical on every run (SL-A3-04); search keeps working during fault SL-F-4; and it supports the data-stays-in-house story of SL-A2-07. When the SL-T-3 provider changes, Vigil MUST re-embed all stored past incidents with the new provider before the next similarity search. Similarity scores are only comparable within one provider.

### 5.6 Workflow editor capabilities (complete list)

| ID | Capability | Trace |
| --- | --- | --- |
| SL-WE-01 | A palette lists every step type in SL-B-03. Dragging a step type onto the canvas MUST add a new step of that type. | CTX-D-010; BSD-BR-015 |
| SL-WE-02 | Steps MUST be connected by dragging from a step's output to another step's input. A Condition step MUST have one labelled output per branch. | CTX-D-010 |
| SL-WE-03 | Steps and connections MUST be selectable, movable and deletable; several steps MAY be selected and moved together. | CTX-D-010 |
| SL-WE-04 | Selecting a step MUST open a parameter panel showing that step type's parameters, with values checked against their type before they are accepted. | CTX-D-010; BSD-BR-015 |
| SL-WE-05 | Every editor change MUST be undoable and redoable, for at least the last 50 changes in the editing session. | CTX-D-010 |
| SL-WE-06 | The canvas MUST support zoom, pan, fit-to-screen and an automatic layout command. | CTX-D-010 |
| SL-WE-07 | Edits MUST be saved as a draft. The published version stays active and unchanged until the draft is published. | BSD-BR-016 |
| SL-WE-08 | Before a test replay or publish, Vigil MUST validate the draft against these rules (complete list): exactly one Trigger step; every step reachable from the Trigger; no unconnected step and no unconnected Condition branch; on every path, each Action step is preceded by an Approval step and followed by a Recovery check step (this graph check supports, and does not replace, the engine enforcement in SL-B-04); any cycle passes through a Wait/timer step; every required parameter has a valid value. Each violation MUST be shown on the step it concerns, and publish MUST be blocked until all are fixed. | BSD-BR-016; SL-B-04 |
| SL-WE-09 | Version history MUST show, for each version, who published it, when, and the list of steps added, removed or changed compared with the previous version. | BSD-BR-016, BSD-BR-025 |
| SL-WE-10 | The same canvas, read-only, MUST show a running incident's workflow with live step highlight (SL-A1-08). | BSD-BR-022 |

## 6. List B — Built in but not shown (complete list)

Built from the first build package onward.

| ID | Foundation | Trace |
| --- | --- | --- |
| SL-B-01 | Every stored record MUST carry a tenant identifier. | BSD-BR-024 |
| SL-B-02 | Every received alert MUST be stored unchanged with its source identity and receive time. Processing MUST NOT modify or delete stored alerts. | BSD-BR-002 |
| SL-B-03 | Workflows MUST be stored as versioned definitions and executed by Vigil's own workflow engine; no external workflow product is used. Step behaviour is code. The composition of steps and their parameters is data. Step types and their named outputs (complete list): trigger (next); condition (one output per branch, plus "otherwise"); diagnostic (done, failed); AI task (done, unavailable); approval (approved, rejected, expired); action (succeeded, failed); recovery check (recovered, not recovered); escalation (next); notification (sent, failed); ticket create/update (done, failed); wait/timer (waits for a duration or until an event such as acknowledgement; outputs: event received, timed out). An action whose result is unknown is first reconciled by the engine (SL-B-11) and then leaves by "succeeded" or "failed". A condition is a structured comparison (field, operator, value); several comparisons MAY be combined with AND; fields and operators are a complete list defined in Round 2 (SL-O-23). There is no free-form scripting step and no free-form expression language. An Approval step's parameters MUST include the eligible team(s), and the approver of an Approval step MUST differ from every approver of earlier Approval steps in the same run. A workflow run MUST complete on the version it started with. Rollback MUST create a new version whose content equals the chosen earlier version and publish it (rolling back from version 2 to version 1 creates version 3). | BSD-BR-015, BSD-BR-016, BSD-BR-028; BSD §11.2 |
| SL-B-04 | Every action that changes a target system MUST follow one path: policy check, then approval or preauthorization, then recheck, then execution, then recovery verification. Vigil MUST NOT provide any other way to run an action. The policy check MUST support the outcomes (complete list): blocked; approval required; preauthorized. The engine MUST enforce this path for every action, whatever the workflow graph contains. When the policy check returns "preauthorized", the Approval step MUST record "preauthorized" and leave by "approved" without a human. When it returns "blocked", the action MUST NOT run and the Approval step MUST leave by "rejected". The demo configuration uses "approval required" only. The "preauthorized" and "blocked" outcomes MUST have automated tests. | BSD-BR-012, BSD-BR-014, BSD-BR-030 (partial) |
| SL-B-05 | Read-only diagnostic permission and action permission MUST be granted separately. | BSD-BR-024; BSD RULE-04 |
| SL-B-06 | An append-only audit log MUST record decisions, acknowledgements, approvals, actions, outcomes and configuration changes. | BSD-BR-025 |
| SL-B-07 | Monitoring sources, diagnostic and action targets, ticketing systems, notification channels and AI providers MUST each be reached through a common connector interface for their type. Adding a new connector MUST NOT require changes to the core. | BSD-BR-017 |
| SL-B-08 | Every AI call MUST go through one AI gateway inside Vigil that enforces data routing, applies the fallback order, and records usage and cost. | BSD-BR-019, BSD-BR-020, BSD-BR-021; BSD RULE-10 |
| SL-B-09 | Routing rules, escalation levels, timers, approval settings and branding MUST be configuration data, not code. | BSD-BR-007, BSD-BR-015 |
| SL-B-10 | Lab services are treated as customer infrastructure: Vigil MUST reach them only through its connectors. The synthetic USSD check MUST reach Vigil only as a monitoring signal through Zabbix or Prometheus. Vigil MUST NOT call fault injection or the demo control panel, and MUST NOT contain demo-specific logic. Demo behaviour comes only from configuration values (SL-A3-06) and seeded data. | BSD §11.1 |
| SL-B-11 | Workflow runs MUST be durable. State MUST be saved after each step. After a restart, a run MUST resume from its last completed step. If an action's result is unknown, Vigil MUST check the target's actual state before any retry, so no action runs twice unintentionally. | BSD-BR-013 |
| SL-B-12 | Incidents MUST follow explicit lifecycle states, including reopening. The states are defined in Round 2. | BSD-BR-008 |
| SL-B-13 | Every user action MUST be performed and recorded as a named user. Permissions MUST be data. The Acknowledge and Approval pages MUST require login; a link alone grants no authority. | BSD-BR-024, BSD-BR-025; BSD RULE-05 |
| SL-B-14 | Minimum security (complete list): HTTPS only; secrets MUST NOT be stored in the repository; each webhook source MUST authenticate with its own token; the demo login MUST NOT use a default password. | BSD-BR-024 |

## 7. Demo data rules

- **SL-DR-01** All data MUST be invented by us. No real Jazz system names, IP addresses, hostnames, employee names, customer data or internal figures may be used.
- **SL-DR-02** The data MUST be Jazz-flavoured: services, terms and incident types typical of a Pakistani mobile operator's IT/service NOC (for example USSD balance check, bundle purchase, SMS). Publicly available facts about Jazz (for example public USSD short codes or product names) MAY be used; each one MUST be listed with its public source in Round 3.
- **SL-DR-03** The environment label (Section 3) MUST be set to "Demo environment — fictional data", so it appears on every Vigil screen and in every Jira ticket title, Teams message and email. The incident history list MUST additionally label its records as fictional.
- **SL-DR-04** The data set is defined completely in Round 3 (`31-demo-data/`) and contains exactly 50 past incidents with their fixes, plus the service map, servers, teams, alert types and runbooks written in NOC language.

## 8. Change control and checkpoints

- **SL-CR-01** After this document is locked, every new idea goes to list C by default.
- **SL-CR-02** A new item MAY enter list A only if one or more items with an estimated build effort equal to or greater than the new item leave list A. Abdul decides; Claude estimates both. Each change MUST be recorded in the decision log of `00-context.md` and in this document's change history.
- **SL-CR-03** The scope is reopened only at these checkpoints (complete list):
  - **SL-CP-1:** the first end-to-end slice works: alert storm to resolved incident. The exact screens included are defined in `60-build-plan` (SL-O-19).
  - **SL-CP-2:** the 15-minute primary demo (SL-A1) is complete.
  - **SL-CP-3:** the full demo (SL-A1 and SL-A2) is complete.

## 9. Differences from the demo document (complete list)

All differences are adopted by decision CTX-D-005 unless another decision is named.

| ID | Demo document said | Scope lock says | Decision |
| --- | --- | --- | --- |
| SL-X-01 | Acknowledge and Approve buttons inside WhatsApp and Teams | Links to Vigil's own mobile pages, after login | CTX-D-006 |
| SL-X-02 | WhatsApp, Teams and email | Teams and email; WhatsApp moved to SL-C-01 | CTX-D-006 |
| SL-X-03 | Workflow studio with graph editing | Full drag-and-drop workflow editor (Section 5.6) with live highlight, test replay, versions, publish and rollback | CTX-D-010 |
| SL-X-04 | "Change a routing rule in settings" | Kept in a routing-rules settings screen (SL-A4-08); approver count and timer are edited in the drag-and-drop workflow editor (Section 5.6) | CTX-D-005, CTX-D-010 |
| SL-X-05 | 35-minute demo primary, 15-minute version optional | 15-minute demo primary (SL-A1); other parts are deep dives (SL-A2) | CTX-D-007 |
| SL-X-06 | Guided setup with discovery | Connect and test sources; import the service map from a file; list readiness gaps | CTX-D-005 |
| SL-X-07 | Dashboard plus several reports | Dashboard, AI usage and cost, audit trail only | CTX-D-005 |
| SL-X-08 | No demo control panel | Demo control panel added (SL-A3-03), reset in 60 seconds or less | CTX-D-005 |
| SL-X-09 | Local model on a GPU server | Kept; local model speed measured early; acceptable answer time decided in R5 (CTX-O-04) | CTX-D-005 |
| SL-X-10 | Two people full time | One builder with Claude Code | CTX-D-004 |
| SL-X-11 | Made-up telecom data, customer name configurable | Invented Jazz-flavoured data under SL-DR-01 to SL-DR-04 | CTX-D-003 |
| SL-X-12 | Stack fixed: Java/Spring Boot, React/Next.js, PostgreSQL + pgvector, AWS or Azure | Stack open, decided in R5 | CTX-D-008 |
| SL-X-13 | 91 person-days, 10 weeks, 3 gates | No dates (CTX-C-05); checkpoints SL-CP-1 to SL-CP-3 | CTX-D-005 |
| SL-X-14 | Optional real incident example from Jazz | Not possible (CTX-C-02); all incidents invented | CTX-D-003 |
| SL-X-15 | Running costs covered by Techfinity | Requires approval by Muzaffar (CTX-O-02) | CTX-D-005 |
| SL-X-16 | Attendees MAY join a demo Teams channel | Decided with the online setup (CTX-O-03) | CTX-D-005 |

## 10. List C — Not now (complete list of known exclusions)

Anything not in list A or list B is also excluded, whether or not it appears here.

| ID | Excluded item |
| --- | --- |
| SL-C-01 | WhatsApp messaging. MAY be added only at checkpoint SL-CP-2 under SL-CR-02. |
| SL-C-02 | Connecting to any Jazz or other customer system, or running any action outside our own lab. |
| SL-C-03 | Vendor OSS (for example Huawei, Ericsson) and monitoring tools other than Zabbix and Prometheus Alertmanager. |
| SL-C-04 | Network NOC scenarios: cell sites, transmission, radio and core alarms. |
| SL-C-05 | On-call schedules and rosters. Escalation uses fixed levels only. |
| SL-C-06 | Company sign-in (SSO), user and role management screens, and multi-customer administration. |
| SL-C-07 | Security hardening beyond SL-B-14, high availability, disaster recovery, and installation on customer servers. |
| SL-C-08 | Banking and data center packs. |
| SL-C-09 | Connector SDK and extension kit for third parties. |
| SL-C-10 | Native mobile app. The phone experience is the mobile web pages SL-A4-04 and SL-A4-05. |
| SL-C-11 | Withdrawn by CTX-D-010 (drag-and-drop editor moved into list A, Section 5.6). |
| SL-C-12 | Automatic discovery of servers and services. |
| SL-C-13 | Reports beyond the dashboard (SL-A1-09), AI usage and cost (SL-A2-02) and the audit trail (SL-A2-08). |
| SL-C-14 | Ticketing systems other than Jira Cloud. |
| SL-C-15 | Machine-learning correlation, anomaly detection and forecasting. Grouping uses the service map and a time window. |
| SL-C-16 | AI model evaluation and promotion workflow. |
| SL-C-17 | A demo scene showing a preauthorized automatic action. The path exists and is tested (SL-B-04) but is not shown. |
| SL-C-18 | Several users editing the same workflow at the same time. |
| SL-C-19 | Creating new step types in the editor. Step behaviour is code (SL-B-03). |

BSD requirements not covered by this demo (complete list): BSD-BR-018 (industry pack mechanism; the Jazz data is content, not a pack mechanism); BSD-BR-026 (availability and recovery profile, see SL-C-07); BSD-BR-027 (platform and runner failure handling; partially covered: only monitoring-source health is shown, in SL-A2-01); BSD-BR-029 (customer onboarding, handover and offboarding).

## 11. Assumptions

| ID | Assumption | Verified by |
| --- | --- | --- |
| SL-ASM-01 | Prometheus Alertmanager's grouping and timing settings can be configured to deliver 100 to 200 individual alerts to a webhook within 120 seconds. By default it batches alerts and waits before sending. | Fact check in R5; early build spike |
| SL-ASM-02 | A Microsoft-supported mechanism exists to post Teams messages containing links into a channel or chat without building a bot. Microsoft is retiring the older Office 365 connector webhooks; the replacement route must be confirmed. | Fact check in R5; early build spike |
| SL-ASM-03 | Anthropic offers no embeddings API, so Claude is not listed for SL-T-3. This does not affect the demo default (CTX-D-011). | Fact check in R5 |
| SL-ASM-04 | The selected local embedding model returns the same vector for the same input text on the same server. | Fact check in R5; early build spike |

## 12. Open items (complete list)

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| SL-O-01 | Recovery observation window length (SL-A1-07) | Claude, reviewed by Abdul | R3 |
| SL-O-02 | Definition of fault SL-F-2 | Claude, reviewed by Abdul | R3 |
| SL-O-03 | Primary demo script content and per-scene timing within 15 minutes | Claude, reviewed by Abdul | R3 |
| SL-O-04 | Grouping time window length (SL-A1-02) | Claude, reviewed by Abdul | R3 |
| SL-O-05 | Expected visible handling of the SL-F-3 incident (priority, ticket, notification) | Claude, reviewed by Abdul | R3 |
| SL-O-06 | Exact diagnostic definitions: data path, number of largest folders, growth period (SL-A1-03) | Claude, reviewed by Abdul | R3 |
| SL-O-07 | Expected owner team and informed parties for the storm incident (SL-A1-04); its severity MUST be P1 | Claude, reviewed by Abdul | R3 |
| SL-O-08 | Past-incident best match, similarity score display, success count, proposed action and its parameters (SL-A1-06) | Claude, reviewed by Abdul | R3 |
| SL-O-09 | Approval expiry time and action limits (SL-A1-06) | Claude, reviewed by Abdul | R3 |
| SL-O-10 | Dashboard data source: current demo run plus seeded history (SL-A1-09) | Claude, reviewed by Abdul | R3 |
| SL-O-11 | Service map file format (SL-A2-01) | Claude | R2 (structure), R5 (format) |
| SL-O-12 | Source health thresholds for stale and disconnected (SL-A2-01) | Claude, reviewed by Abdul | R3 |
| SL-O-13 | Data categories used in AI data routing (SL-A2-02) | Claude | R2 |
| SL-O-14 | Escalation levels and timer values (SL-A2-03, SL-A2-06) | Claude, reviewed by Abdul | R3 |
| SL-O-15 | Action attempt limit (SL-A2-04) | Claude, reviewed by Abdul | R3 |
| SL-O-16 | Which cloud AI providers fault SL-F-4 makes unreachable | Claude, reviewed by Abdul | R3 |
| SL-O-17 | Reset mechanism that keeps earlier runs intact (recommended: a new tenant per demo run, seeded from the starting state). Must also define: how Zabbix and Alertmanager webhooks and logged-in users reach the current run; how seeding, including SL-T-3 embeddings, fits within the 60-second reset. | Claude | R5 |
| SL-O-18 | Display names of demo users | Claude, reviewed by Abdul | R3 |
| SL-O-19 | Screens included in checkpoint SL-CP-1 | Claude | R6 |
| SL-O-20 | Default AI settings for SL-T-1 and SL-T-2: provider and fallback order (SL-T-3 default fixed by CTX-D-011) | Claude, reviewed by Abdul | R5 |
| SL-O-21 | The routing rule changed in SL-A2-06. The change MUST NOT alter the storm incident's severity or owner team. | Claude, reviewed by Abdul | R3 |
| SL-O-22 | Definition of workflow version 1, step by step. It MUST include a severity Condition step with a P1 branch. | Claude, reviewed by Abdul | R3 |
| SL-O-23 | Complete list of fields and operators usable in Condition steps | Claude | R2 |

Project-wide open items CTX-O-01 to CTX-O-05 are listed in `00-context.md` Section 14.

## 13. Quality-check results

| Check | Result |
| --- | --- |
| CTX-Q-4 Independent review | Done, 8 Oct 2026, in two passes. Pass 1: 31 findings (9 High, 16 Medium, 6 Low), resolved in v1.1: MUST/MAY wording; prefixed IDs; reset compatible with SL-B-02 and SL-B-06; lab boundary SL-B-10; demo users (SL-A3-07); login on Acknowledge/Approval pages (SL-B-13); minimum security (SL-B-14); storm re-run and test replay defined; root-cause ownership split between grouping rule and AI; screens (SL-A4) and AI tasks (SL-T) listed; SL-B-11 and SL-B-12 added; preauthorization decided (SL-B-04, SL-C-17); SL-X-12 to SL-X-16, SL-O-04 to SL-O-20, SL-ASM-01 and SL-ASM-02 added; traces corrected. Pass 2 (verification): 2 partly resolved and 6 new findings (2 High, 4 Medium) plus 6 Low, resolved in v1.2: remaining MUST wording; allowed providers per AI task and re-embedding rule (SL-ASM-03); test replay reuses stored outputs; re-run AI investigation defined; teams added to demo users and notification recipients defined; demo control panel made a separate application; environment label made a product setting covering tickets and messages; SL-O-17 scope widened; root-cause tie-break; template fallback for SL-T-2; A3 trace column; SL-O-21 split from SL-O-03. |
| CTX-Q-4 pass 3 | Review of the v1.3 owner-review changes, 8 Oct 2026: 10 Medium findings, none High; all resolved in v1.4 (stale SL-X-04; workflow v1 and P1 branch, SL-O-22; routing change constrained, SL-O-21; distinct approvers; named outputs per step type; structured conditions, SL-O-23; engine enforcement of SL-B-04 with preauthorized/blocked behaviour; run-on-start-version and rollback rule; test replay of drafts and admin permissions; stable SL-T-3 search text, rounding and SL-ASM-04). |
| CTX-Q-7 Owner review | Done, 8 Oct 2026. Seven decisions reviewed: five agreed as proposed. Changed: workflow edits through forms replaced by a full drag-and-drop editor (CTX-D-010). Refined: SL-T-3 provider decided on what is best for the demo, not on cost or API availability (CTX-D-011). |

## 14. Change history

| Version | Date | Change |
| --- | --- | --- |
| 1.0 | 8 Oct 2026 | Initial scope lock (Round 0). |
| 1.1 | 8 Oct 2026 | Resolved independent review pass 1 findings (see Section 13). |
| 1.2 | 8 Oct 2026 | Resolved independent review pass 2 findings (see Section 13). |
| 1.3 | 8 Oct 2026 | Owner review: drag-and-drop workflow editor added (Section 5.6, SL-A2-06, SL-A4-06, condition step type, SL-C-11 withdrawn, SL-C-18 and SL-C-19 added); SL-T-3 local embedding default. Status set to Locked. |
| 1.4 | 8 Oct 2026 | Resolved independent review pass 3 findings (see Section 13). |
| 1.5 | 9 Oct 2026 | SL-A3-07 clarified: admin may re-run AI investigation (CTX-D-013, from Round 1). No item added to list A. |
| 1.6 | 9 Oct 2026 | SL-A1-07 and SL-A2-05 clarified (CTX-D-015, CTX-D-016, from Round 2). No item added to list A. |
