# Techfinity NOC Demo — What Will Be Delivered

Oct 3, 2026 · @Abdul Wadood

## Purpose

This document sets out exactly what I will deliver for the demo: what it will do, what it will not do, what it is built with, and when it will be ready. The demo is built for telecom operators in general, not one company; the customer name and logo can be changed for each audience, starting with Jazz. Please tell me what you want added, removed or changed. Once you confirm, this is the scope I build to.

## What the demo will do

The demo runs one incident start to finish in about 15 minutes. Everything happens live, triggered from a real Zabbix instance.

1. **Alert storm.** A cell-site power failure in one region fires around 200 Zabbix alerts: site down, transmission links down, sector alarms on neighbouring sites.
2. **Merging alerts.** The system groups those alerts into ONE incident and names the likely root cause: power failure at site X. The dashboard shows "200 alerts → 1 incident".
3. **AI routing.** The AI assigns severity and owner (for example, Field Power Team, Central Region) and shows why, citing the runbook it used.
4. **Notification.** WhatsApp and Teams messages arrive live on a phone in the room, plus an email summary. Each has an Acknowledge button.
5. **Escalation.** In a second run, nobody acknowledges. After a short timer the system escalates to the next level and records it.
6. **Past solution.** The system finds a similar past incident: "switched site to backup generator; worked 4 of 5 times. Apply?" An engineer taps Approve on the phone. The action is **simulated** and logged, not executed.
7. **Dashboard and audit.** Time to acknowledge, alerts suppressed, incidents merged, and the full audit trail of who did what and when, shown against a manual baseline.

## What the demo includes and excludes

**Included:**

- Zabbix (running in our own environment) sending alerts to our system by webhook
- Merging alerts into one incident by site, time window and topology (which links and sectors hang off which site)
- AI classification of severity and owner, grounded in a small set of runbooks we write in NOC language
- Routing rules: team, region, severity, escalation levels
- Notifications: WhatsApp, Microsoft Teams, email, each with Acknowledge
- Escalation timer when nobody acknowledges
- Past-incident lookup with confidence, one-tap Approve/Reject, simulated execution
- Web dashboard: live incidents, metrics, audit trail
- Made-up data set: sites, regions, teams, alarm types, about 50 past incidents with their fixes, with customer name and logo configurable

**Not included:**

- Real execution of fixes on any network element
- Connection to any customer's systems (their Zabbix, ticketing, directory)
- Monitoring tools other than Zabbix
- On-call schedules and rosters
- Multi-customer setup, user management beyond a demo login
- Running on a customer's own servers, security hardening, high availability
- Mobile app (WhatsApp and Teams cover the phone)

Any excluded item can be added later. Each addition needs its own estimate and moves the date.

## Technology

The demo is built as custom software on the stack below.

| Part | Choice | Reason |
| --- | --- | --- |
| Backend | Java / Spring Boot | Team's strongest skill; standard in telco IT |
| Dashboard | React / Next.js | Same team has built telco apps with it |
| Database | PostgreSQL + pgvector | One database for incidents, audit and past-incident search |
| AI | Claude API, OpenAI as fallback | Strong reasoning; fallback keeps the demo alive if one is down |
| Alert source | Zabbix in Docker | A real Zabbix sending real webhooks, not a fake |
| Messaging | WhatsApp Business Cloud API, Teams webhook, SMTP email | The channels NOC staff already use |

**Data note:** the demo sends made-up incident text to an external AI service. In a real deployment, the AI can run on the customer's own servers if data must stay in-house.

## Timeline

The demo is ready 3 weeks after the scope is confirmed, with two people working on it full time.

&#91;embedded content: demo build plan · 3 weeks, 2 gates\]

In week 3 we do a full dry run together, so you can run the story yourself before demo day.

## Assumptions

**Assumptions:** two people work on this full time for 3 weeks; the scope is confirmed before the build starts; all demo data is made up by us; no access to any customer system is needed.

WhatsApp Business messaging needs Meta verification, which I start in week 1; a developer test number is the fallback. A recorded backup video covers any live failure on the day.

## What I need from you

Please reply with:

1. **Scope:** confirmation of the sections above, or what to add or remove.
2. **Demo date:** the target date, so the 3 weeks can be planned back from it.
3. **Setting:** in person or online, and on whose laptop and network.

Once I have these, the build starts.
