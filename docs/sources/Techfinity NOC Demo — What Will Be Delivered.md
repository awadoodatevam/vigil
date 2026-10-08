# Techfinity NOC Demo — What Will Be Delivered

Oct 7, 2026 · @Abdul Wadood

## Purpose

This document sets out exactly what I will deliver for the demo: what it will do, what it will not do, what it is built with, and how long it takes. Please tell me what to add, remove or change. Once you confirm, this is the scope I build to.

The scope is set by what the demo must show, not by a fixed time window. The demo should give a clear picture of the whole product described in the Continuum Business Solution Document (BSD), not only one incident. The timeline is estimated from that scope.

The demo is built for a telecom **IT/service NOC**: the team that runs servers, SMS and USSD platforms, APIs and the services that depend on them. Network NOC scenarios (cell sites, transmission, radio and core alarms) are a later step. The first demo is presented online, from a server we host under our own domain. The customer name and logo can be changed for each audience, starting with Jazz.

## What the demo will do

The full demo runs in six parts, about 35 minutes. Everything happens live in a small lab we run ourselves: a mock USSD gateway, balance and bundle APIs, a database and an SMS queue, monitored by a real Zabbix and a real Prometheus Alertmanager. A 15-minute version (Part 2, a glimpse of the workflow studio, and the dashboard) is available for short meetings.

### Part 1: Set up (about 5 minutes)

1. **Guided setup.** Connect Zabbix and Alertmanager, test each connection, and see each source's health: healthy, stale or disconnected.
2. **Service map.** Import the services, servers, owners and criticality. Readiness gaps are listed, for example a server with no owner.
3. **AI settings.** Choose the AI model for each task (Claude, OpenAI, or a local model on our own server), the fallback order, and which data may be sent to which model.

### Part 2: Handle an incident (about 10 minutes)

The workflow view stays open beside the incident and highlights the step it is on.

1. **Alert storm.** The disk on the database server behind the USSD gateway fills up. Within two minutes Zabbix and Alertmanager fire around 150 alerts: disk full, database write errors, USSD gateway timeouts, balance-check and bundle-purchase API errors, a growing SMS queue, and failed test USSD sessions.
2. **Merging alerts.** The system groups them into ONE incident using the service map and time window, and shows why each alert was grouped. It names the likely root cause (disk full on database server X) and the customer impact (balance check and bundle purchase over USSD are down). An unrelated alert at the same moment stays a separate incident.
3. **Diagnostics.** The system runs approved read-only checks on the database server itself (disk usage, largest folders, recent growth) and attaches the results as evidence.
4. **AI investigation and routing.** The AI states the likely cause, the evidence for it and what is still uncertain. It assigns severity and owner (for example, P1, Database Operations team, VAS service owner informed) and cites the runbook it used.
5. **Ticket and notification.** A Jira ticket is created with the alerts, impact and evidence. WhatsApp and Teams messages arrive, plus an email summary, each with an Acknowledge button.
6. **Approved fix.** The system finds a similar past incident: "archived logs filled the disk; purged logs older than 7 days; worked 4 of 5 times." The approval screen shows the exact action, target server, limits and expiry. An engineer taps Approve on the phone; the system rechecks the conditions and runs the fix on our lab server.
7. **Recovery check.** The incident does not close because the fix ran. It closes only when the alerts clear and a test USSD session succeeds, and Jira is updated.

### Part 3: When things go wrong (about 5 minutes)

1. **Nobody acknowledges.** After a short timer the system escalates to the next level and records it on the incident and in Jira.
2. **The fix does not work.** We inject a second fault so the service stays down after the fix. The incident stays open and escalates with the evidence, with no repeated attempts beyond the set limit.
3. **The AI service is down.** We cut off the cloud AI. The system falls back to the next allowed model, or keeps routing by rules and shows that AI investigation is unavailable.

### Part 4: Change it live (about 7 minutes)

1. **Workflow studio.** Open the workflow behind the incident as a graph: trigger, diagnostics, AI step, approval, action, recovery check, escalation.
2. **Edit.** Require a second approver for P1 fixes and shorten the escalation timer. Change a routing rule in settings.
3. **Test before publishing.** Replay the alert storm against the changed workflow and see the result without affecting anything live.
4. **Publish and rerun.** Publish as version 2, rerun the incident, and see the new behaviour. The version history shows who changed what; roll back to version 1 in one step.

### Part 5: Keep data in-house (about 3 minutes)

Switch the AI to the local model on our server and rerun the investigation. The AI usage log shows no request went to an external AI service.

### Part 6: Results and audit (about 5 minutes)

The dashboard shows time to acknowledge, time to recover, alerts suppressed and incidents merged against a manual baseline. Reports cover incidents, automation outcomes, and AI usage and cost. The audit trail shows who did what and when, including every approval and workflow change.

Because the demo is online, the phone moments are shown by sharing the presenter's phone screen, or attendees can join a demo Teams channel and receive the messages themselves.

## How the demo covers the BSD

All 12 capabilities in the BSD appear in the demo, and so do the six points that set Continuum apart: mixed-tool integration, direct diagnostics, assisted onboarding, customization, customer-controlled AI and verified recovery.

| BSD capability             | Where the demo shows it                                                          |
| -------------------------- | -------------------------------------------------------------------------------- |
| Guided onboarding          | Part 1: connect sources, test, readiness gaps                                    |
| Integration hub            | Parts 1–2: Zabbix and Alertmanager merged; Jira; WhatsApp, Teams, email          |
| Infrastructure diagnostics | Part 2: read-only checks on the database server                                  |
| Asset and service context  | Parts 1–2: service map, owners, impact                                           |
| Event intelligence         | Part 2: 150 alerts into 1 incident, grouping reasons, unrelated alert kept apart |
| Incident operations        | Parts 2–3: assign, acknowledge, escalate, resolve                                |
| AI-assisted investigation  | Part 2: evidence, uncertainty, runbook citation                                  |
| Workflow customization     | Part 4: view, edit, test, publish, roll back                                     |
| Controlled execution       | Part 2: exact-scope approval, recheck, run on lab server                         |
| Recovery verification      | Parts 2–3: close only on recovery; failed fix stays open                         |
| Reporting and audit        | Part 6: dashboard, reports, audit trail                                          |
| Administration             | Parts 1 and 5: AI models, fallback, data routing, local model                    |

The demo shows these as a viewer would see them. Qualities a viewer cannot see in a demo are left for the pilot, as the BSD's stages intend: multi-customer separation, company sign-in and roles, security hardening, high availability, running on customer servers, and the banking and data center packs.

## What the demo includes and excludes

**Included:**

- A lab in our own environment: mock USSD gateway, balance and bundle APIs, database and SMS queue, with controlled fault injection
- Two alert sources: Zabbix and Prometheus Alertmanager, both sending by webhook, with source health shown
- Guided setup screen: connect and test sources, import the service map, show readiness gaps
- Merging alerts into incidents by service map and time window, with grouping reasons
- Read-only diagnostics collected from the lab database server
- AI investigation and routing grounded in runbooks we write in NOC language, showing evidence and uncertainty
- Workflow engine of our own (no external workflow tool) and a workflow studio: graph view, live step highlight, edit, test against a replay, versions, publish, roll back
- Routing rules: team, service, severity, escalation levels, editable in settings
- Jira ticket created and updated automatically, in our own Jira instance
- Notifications: WhatsApp, Microsoft Teams, email, each with Acknowledge
- Escalation timer when nobody acknowledges
- Past-incident lookup with confidence; approval showing exact action, target, limits and expiry; fix run only on our lab server
- Recovery check before closing, including a failed-recovery case
- AI settings: model per task, fallback order, data routing, and one local model on our server
- Dashboard, basic reports (incidents, automation outcomes, AI usage and cost) and audit trail
- Made-up data set: services, servers, teams, alert types, about 50 past incidents with their fixes; customer name and logo configurable
- Online hosting: a cloud server under our own domain with HTTPS and a protected demo login

**Not included:**

- Running any fix on a customer system, or connecting to any customer's systems (their Zabbix, Jira, directory)
- Vendor OSS (Huawei, Ericsson) and monitoring tools other than Zabbix and Alertmanager
- Network NOC scenarios: cell sites, transmission, radio and core alarms
- On-call schedules and rosters (fixed escalation levels only)
- Multi-customer separation, company sign-in and role management beyond a demo login
- Security hardening, high availability, running on a customer's own servers
- Banking and data center packs, and an extension kit for adding connectors
- Mobile app (WhatsApp and Teams cover the phone)

Any excluded item can be added later with its own estimate.

## Technology

The demo is built as custom software on the stack below.

| Part                          | Choice                                                      | Reason                                                                                       |
| ----------------------------- | ----------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| Backend and workflow engine   | Java / Spring Boot                                          | Team's strongest skill; standard in telco IT; no external workflow tool, as the BSD requires |
| Dashboard and workflow studio | React / Next.js, with a graph editor library                | Same team has built telco apps with it                                                       |
| Database                      | PostgreSQL + pgvector                                       | One database for incidents, workflows, audit and past-incident search                        |
| Cloud AI                      | Claude API, OpenAI as fallback                              | Strong reasoning; fallback keeps the demo alive if one is down                               |
| Local AI                      | An open-weight model served on our own server               | Shows the AI can run where the data must stay                                                |
| Lab and alert sources         | Mock services, Zabbix and Prometheus Alertmanager in Docker | Real services failing and real webhooks, not a fake feed                                     |
| Ticketing                     | Jira Cloud (our own instance)                               | The ticketing tool the target NOC uses                                                       |
| Messaging                     | WhatsApp Business Cloud API, Teams, SMTP email              | The channels NOC staff already use                                                           |
| Hosting                       | Cloud server (AWS or Azure), our own domain, HTTPS          | WhatsApp and the Acknowledge buttons need a public HTTPS address; a bare IP is not enough    |

**Data note:** the cloud AI receives only made-up incident text. Part 5 shows the alternative a real customer can choose: the AI running on their own servers.

## Effort and timeline

The full demo is about 91 person-days of work, plus about 10% contingency: roughly 10 weeks with two people working full time.

| Work item                                                          | Phase            | Effort (person-days) |
| ------------------------------------------------------------------ | ---------------- | -------------------- |
| Lab services, fault injection, Zabbix and Alertmanager             | Foundation       | 5                    |
| Hosting: server, domain, HTTPS, demo login, deployment             | Foundation       | 3                    |
| Alert intake from two sources, source health                       | Foundation       | 4                    |
| Service map and alert merging with reasons                         | Foundation       | 6                    |
| Made-up data set and runbooks                                      | Foundation       | 4                    |
| Read-only diagnostics                                              | Foundation       | 3                    |
| Workflow engine: steps, approvals, timers                          | Core story       | 8                    |
| AI investigation and routing, fallback                             | Core story       | 6                    |
| Jira create and update                                             | Core story       | 3                    |
| WhatsApp, Teams and email with Acknowledge                         | Core story       | 5                    |
| Escalation timers                                                  | Core story       | 2                    |
| Past-incident search, exact-scope approval, run on lab             | Core story       | 6                    |
| Recovery check and failed-recovery case                            | Core story       | 3                    |
| Workflow studio: graph, highlight, edit, test, versions, roll back | Platform screens | 10                   |
| Guided setup screen                                                | Platform screens | 5                    |
| AI settings and local model                                        | Platform screens | 5                    |
| Dashboard, reports and audit trail                                 | Platform screens | 7                    |
| End-to-end testing, dry runs, backup video                         | Rehearsal        | 6                    |
| **Total**                                                          |                  | **91**               |

&#91;embedded content: demo build plan · 10 weeks, 3 gates\]

We check each gate together before moving on. If an earlier date is needed, the 15-minute version can be shown from Gate 2, and the full demo follows later.

## Assumptions

- Two people work on this full time; the scope is confirmed before the build starts.
- All demo data and lab services are made up by us; no access to any customer system is needed.
- Running costs (cloud server, domain, AI usage, Jira) are covered by Techfinity. The local model needs a server with a GPU to answer in seconds; on a CPU-only server it works, but slowly.
- WhatsApp Business messaging needs Meta verification, which I start in week 1; a developer test number is the fallback.
- A recorded backup video covers any live or network failure during the online session.

## What I need from you

Please reply with:

1. **Scope:** confirmation of the sections above, or what to add or remove.
2. **Demo date:** the target date, so we can check it against the estimate, or decide which parts go into a later session.
3. **Online setup:** the meeting tool (Teams, Zoom or other), who presents, and who shares the phone screen.
4. **Hosting:** which cloud account to use, the domain name, and approval for running costs.
5. **A real example (optional):** one typical incident from Jazz's IT/service NOC, if the sales lead can get it, so the story matches what they see day to day.

Once I have the first four, the build starts.
