# Vigil — Project Context

| Field | Value |
| --- | --- |
| Document | 00-context (ID prefix `CTX-`) |
| Version | 1.8 |
| Date | 9 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed) |

## 1. Purpose of this file

Read this file first in every Claude conversation in the "Noc Automation" project and at the start of every Claude Code session. It records the situation, the roles, the decisions made so far, and the rules every project document follows. If another project document conflicts with this file, this file wins until it is updated through the decision log (Section 13).

## 2. Inputs read

- `docs/sources/Continuum — Business Solution Document.md`, version 0.2, 4 October 2026.
- `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md`, 7 October 2026.
- Owner direction given in the Claude project conversation of 8 October 2026.

## 3. Product name

- **Vigil** is the working name for the whole product idea. It is not a registered brand. In planning it is used by Abdul Wadood, Muzaffar Hussain and Claude; from Round 4 it is also the product name shown on Vigil's screens and in its tickets and messages, defined in one place (`40-demo-cut` DC-W-30, CTX-D-022).
- The two source documents call the product "Continuum". Continuum and Vigil are the same product. All new documents and all code use Vigil only.
- **The repository** means the git repository in the folder `noc automation` on Abdul's computer. Code packages and modules inside it use the lowercase prefix `vigil`. Round 1 assigns working module names; Round 5 makes them final.

## 4. What Vigil is

Vigil is a NOC automation product. It receives alerts from the monitoring tools a customer already uses, merges related alerts into one incident using a service map, collects evidence directly from the affected servers, uses AI to explain the likely cause with evidence and stated uncertainty, routes the incident to the accountable team, and runs registered fixes only after a valid approval or a preauthorized policy. It closes an incident only when independent recovery checks pass. Every decision, approval, action and configuration change is recorded. Customers change behaviour through versioned workflows and settings, not code. The full product vision is described in BSD Sections 1 and 5. The first target market is telecom IT/service NOCs; banking and data centers follow later.

## 5. Situation and goals

- A Techfinity senior manager stated in a meeting that he can sell this idea, but the technical team lacks capacity to build it.
- Abdul Wadood took the initiative to build it. If the product sells on the strength of the demo, Abdul expects to own the product going forward.
- The first buyer is **Jazz** (mobile operator, Pakistan), specifically its IT/service NOC.
- **CTX-G-1:** the senior manager can confidently sell Vigil to Jazz from this demo.
- **CTX-G-2:** every part of the Vigil product built for the demo continues into the pilot and the product without a rebuild.

## 6. People and roles (complete list)

| Person | Role in this project |
| --- | --- |
| Abdul Wadood | Product owner and sole builder. Builds with Claude Code. Final decision on scope and design. |
| Muzaffar Hussain | Reviewer and sounding board. Not a developer. Approves hosting and running costs (CTX-O-02). |
| Techfinity senior manager | Seller. The demo's direct customer. Not involved in design. |
| Claude (Claude project) | Software architect and team lead. Writes round documents, build package specs and reviews. |
| Claude Code | Implements build packages from their specs. Reads `CLAUDE.md` and the `docs/` folder. |

## 7. Constraints (complete list)

- **CTX-C-01** One builder (Abdul) working with Claude Code. Every plan MUST be sized and structured for that.
- **CTX-C-02** No information can be obtained from Jazz. All Jazz-related data MUST be invented under the data rules in `01-scope-lock` Section 7.
- **CTX-C-03** The demo is the product seed. Nothing in the Vigil product is planned as throwaway. Only the items in `01-scope-lock` list A3 marked "demo-only" are outside the product, and they MUST be kept separate from it.
- **CTX-C-04** Nothing in the source documents is fixed, including the technology stack. Every change from them MUST be recorded in the decision log.
- **CTX-C-05** No delivery date is committed in these documents. The demo date is open item CTX-O-01.

## 8. Source documents and precedence

Order of authority, highest first:

1. `docs/00-context.md` (this file)
2. `docs/01-scope-lock.md`
3. Later round documents (`docs/10-` to `docs/60-`), each authoritative for its own topic
4. `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md`: the original demo story, used as a baseline only. Where it differs from the scope lock, the scope lock wins.
5. `docs/sources/Continuum — Business Solution Document.md`, the BSD: background on the full product vision. Its requirement IDs (`BSD-BR-###`), operating rule IDs (`BSD RULE-##`) and section numbers (`BSD §n`) are used for traceability. It is never used as a build specification.

## 9. Working method: rounds

Each round is one conversation in the Claude project and produces the files listed. A round starts by reading all earlier files. The master copy of every file is in the `docs/` folder of the repository; the Claude project holds a mirror updated at the end of each round. If the two differ, the repository copy wins.

| Round | Topic | Output files | Status |
| --- | --- | --- | --- |
| R0 | Context and scope | `00-context.md`, `01-scope-lock.md` | Closed, 8 Oct 2026 |
| R1 | Product map: every module of the full product, its job, what it owns, what it talks to; working module names; AI task list confirmation | `10-product-map.md` | Closed, 9 Oct 2026 |
| R2 | Core domain model: entities, relationships, lifecycles and state machines | `20-domain-model.md` | Closed, 9 Oct 2026 |
| R3 | Jazz storyboard and demo data: scene-by-scene script with Real/Simplified/Staged tags; the invented Jazz data set; exact demo values | `30-jazz-storyboard.md`, `31-demo-data/` | Closed, 9 Oct 2026 |
| R4 | Demo cut: per module, keep / simplify / stage / later, keeping product shape; screen-by-screen content | `40-demo-cut.md` | Closed, 9 Oct 2026 |
| R5 | Architecture: applications, stack, integrations, AI layer, hosting, final module names | `50-architecture.md` | Not started |
| R6 | Build plan: packages sized for Claude Code, each with spec, done-check and tests; repository conventions | `60-build-plan.md`, `CLAUDE.md` | Not started |
| Build | One package at a time; checkpoints reviewed in the Claude project | `docs/packages/P-##.md`, code | Not started |

## 10. Writing rules for all project documents

These rules exist so that Claude Code builds exactly what is specified: nothing missed, nothing extra.

- **CTX-W-01** Every requirement, decision, scene, entity, assumption and open item has a stable ID that starts with its document prefix (for example `CTX-`, `SL-`). IDs are never reused. A removed item stays in place marked "Withdrawn" with the decision ID that removed it.
- **CTX-W-02** Required behaviour uses MUST or MUST NOT. Permitted options use MAY. Descriptions of context (not requirements) use plain sentences and are never inside a requirement table. The words "should", "etc.", "and so on", "as needed", "appropriate", "user-friendly", "robust", "fast", "simple" and "flexible" are not used unless followed by an exact definition or number.
- **CTX-W-03** A list intended to be complete is labelled "(complete list)". Anything not in a complete list is excluded.
- **CTX-W-04** Every assumption is labelled `ASSUMPTION` with an ID. Every unresolved point is an open item with an ID, an owner and the round or build package that resolves it. Gaps are never filled with invented values.
- **CTX-W-05** Every item traces to its source: an ID in an earlier project document, a `BSD-BR-###` ID, a BSD operating rule (`BSD RULE-##`), or a BSD section (`BSD §n`). Demo-only items (`01-scope-lock` list A3) MAY trace to a goal (`CTX-G-#`) instead.
- **CTX-W-06** Every round document has these sections: purpose, inputs read, content, explicitly out of scope, open items, quality-check results, change history.
- **CTX-W-07** Examples are labelled "Example" and are not requirements. A value the demo audience must see is never only an example; it is either stated exactly or tracked as an open item.
- **CTX-W-08** Numbers carry units. Values that matter to the demo are exact (for example, "reset completes in 60 seconds or less").

## 11. How each round's result is checked

A round is closed only when all applicable checks pass and their results are recorded in the document's "quality-check results" section.

| ID | Check | Applies to |
| --- | --- | --- |
| CTX-Q-1 | **Coverage both ways.** Every scope lock item maps to at least one item in the round output. Every item in the output maps back to the scope lock or an earlier round; unmapped items are over-building and are removed or raised as a scope change. Exception: R1 and R2 describe the full product, so their items MAY instead be marked "Later (not built)" and traced to a BSD-BR ID or BSD section. | R1–R6 |
| CTX-Q-2 | **Consistency.** The output is compared with all earlier documents. Conflicts are resolved by a decision log entry, never silently. | R1–R6 |
| CTX-Q-3 | **Scenario walkthrough.** The primary demo (`SL-A1-01` to `SL-A1-09`) is traced step by step through the round's output. Every step must have an owning module, the data it needs and a defined outcome. | R1–R6 |
| CTX-Q-4 | **Independent review.** A separate Claude reviewer that did not write the draft checks it for ambiguity, contradictions, gaps, banned wording (CTX-W-02), traceability errors and unsupported technical claims. Findings and resolutions are summarised in the document. | R0–R6 |
| CTX-Q-5 | **Claude Code readiness test.** A fresh Claude given only the `docs/` folder lists every question it would need answered before building. The target is zero questions; each question found is fixed in the documents. | R2–R6 and every build package |
| CTX-Q-6 | **Fact check.** Claims about external products (Zabbix, Prometheus Alertmanager, Jira, Microsoft Teams, AI model APIs, local model runtimes) are checked against current official documentation, with links. Anything that cannot be verified becomes an `ASSUMPTION` proved by an early build spike. | R3, R5, R6 |
| CTX-Q-7 | **Owner review.** Abdul reviews a short list (seven items or fewer) of the decisions that matter most in the round and replies "agree" or "change". | R0–R6 |
| CTX-Q-8 | **Build feedback.** During build, anything the code proves wrong is corrected in the documents and the decision log, so documents and code stay consistent. | Build |

Limits of these checks: the invented Jazz data cannot be verified against Jazz's real environment. It is checked for plausibility against public information about Jazz and against Abdul's and Muzaffar's telecom NOC experience. Whether Jazz buys can only be tested by the senior manager.

## 12. Explicitly out of scope for this document

Demo content (see `01-scope-lock`), module design (R1), data model (R2), technology choices (R5) and delivery dates (CTX-C-05).

## 13. Decision log

| ID | Date | Decision | Reason |
| --- | --- | --- | --- |
| CTX-D-001 | 8 Oct 2026 | Working name is **Vigil**, replacing "Continuum". | Continuum was never chosen by the owner and does not describe the product. |
| CTX-D-002 | 8 Oct 2026 | The demo is the product seed (CTX-C-03). | Avoid a rebuild after the sale; the owner intends to own the product. |
| CTX-D-003 | 8 Oct 2026 | First buyer is Jazz IT/service NOC. All demo data is invented and Jazz-flavoured. | The senior manager will sell to Jazz; no Jazz information is available. |
| CTX-D-004 | 8 Oct 2026 | Sole builder is Abdul using Claude Code. | Actual team. The "two people" in the demo document referred to Abdul and Muzaffar and was a planning assumption. |
| CTX-D-005 | 8 Oct 2026 | Scope lock adopted, including every difference from the demo document listed in `01-scope-lock` Section 9 (`SL-X-01` to `SL-X-16`). | Reduce build risk for one builder without weakening the sales story. |
| CTX-D-006 | 8 Oct 2026 | Approve and Acknowledge happen on a mobile web page inside Vigil, reached from links in messages, after login as a named demo user. Chat and email channels only deliver messages and links. | Approval, recheck and audit must live in Vigil; avoids bot integrations and Meta verification for the demo. |
| CTX-D-007 | 8 Oct 2026 | The 15-minute demo (`SL-A1`) is the primary demo. The deep dives (`SL-A2`) are optional. | Sellers rarely get 35 minutes; gives a natural build order. |
| CTX-D-008 | 8 Oct 2026 | The technology stack is not fixed. It is decided in Round 5. | CTX-C-04; the stack is reviewed for one builder using Claude Code. |
| CTX-D-009 | 8 Oct 2026 | The `docs/` folder in the repository is the master copy of all project documents. | Claude Code reads the repository; git keeps history. |
| CTX-D-010 | 8 Oct 2026 | The demo includes a full drag-and-drop workflow editor (`01-scope-lock` Section 5.6), replacing form-based edits. | Owner decision: the workflow editor is what a prospective customer sees and judges; accepted despite its build effort. |
| CTX-D-011 | 8 Oct 2026 | Similar-incident search (SL-T-3) uses a local embedding model by default in the demo; OpenAI remains an allowed alternative. | Best for the demo: identical results every run, keeps working when cloud AI is cut (SL-F-4), and supports the data-stays-in-house story. Not decided on cost or API availability. |
| CTX-D-012 | 9 Oct 2026 | Product map `10-product-map` v1.0 adopted: 24 built and 4 Later-only modules with working names, module rules PM-R-01 to PM-R-09, and decisions PM-D-01 to PM-D-14. The AI task list SL-T-1 to SL-T-3 is confirmed complete. | Round 1 output; owner agreed the seven decisions put to review. |
| CTX-D-013 | 9 Oct 2026 | The admin demo user may run "Re-run AI investigation" (clarification of SL-A3-07, PM-D-13). | SL-A2-07 has the admin switch providers and re-run; no user held the permission. |
| CTX-D-014 | 9 Oct 2026 | Domain model `20-domain-model` v1.0 adopted: 82 entities, 16 state machines, rules DM-R-01 to DM-R-69 and decisions DM-D-01 to DM-D-21; `10-product-map` amended to v1.1 (DM-A-01 to DM-A-28). | Round 2 output; owner agreed the seven review items. |
| CTX-D-015 | 9 Oct 2026 | SL-A1-07 clarified: "the synthetic USSD check reports success" means no alert of the check component is firing, and recovery conditions count only while every active monitoring source is healthy (DM-D-11). | Silence from a broken source is not recovery; no source queries needed. |
| CTX-D-016 | 9 Oct 2026 | SL-A2-05 clarified: AI fallback applies to SL-T-1 and SL-T-2; SL-T-3 has one provider and changes it only through re-embedding (DM-D-21). | Similarity scores from different providers are not comparable. |
| CTX-D-017 | 9 Oct 2026 | Jazz storyboard `30-jazz-storyboard` v1.0 and demo data set `31-demo-data` v1.0 adopted, with decisions SB-D-01 to SB-D-12. Amendments written: `20-domain-model` v1.1 (DM-E-16, DM-E-38) and `10-product-map` v1.2 (PM-DC-01, PM-DC-04). | Round 3 output; owner agreed the seven review items. |
| CTX-D-018 | 9 Oct 2026 | Demo setup (resolves CTX-O-03): Microsoft Teams meeting; the senior manager narrates and Abdul operates every screen; mobile pages in a phone-size browser window; attendees do not join a demo Teams channel (`30-jazz-storyboard` SB-D-08). | Owner answers in the Round 3 conversation. |
| CTX-D-019 | 9 Oct 2026 | Scope-lock clarifications from Round 3 (no item added to list A): SL-A3-01 — the SMS queue mock includes its dispatcher and a mock report export job exists only for SL-F-3; SL-A3-07 — teams without members are configuration data. `01-scope-lock` v1.7. | Found by the final consistency audit of Round 3; keeps the complete lists exact. Raised to Abdul at the Round 3 close for confirmation. |
| CTX-D-020 | 9 Oct 2026 | Demo cut `40-demo-cut` v1.0 adopted: lean cut (screens offer only what list A needs; modules keep their records and rules), screen-by-screen content of SL-A4-01 to SL-A4-12, product wording and outbound content, decisions DC-D-01 to DC-D-18. Amendments written: `01-scope-lock` v1.8, `10-product-map` v1.3 (Section 20), `20-domain-model` v1.2, `30-jazz-storyboard` v1.1, `31-demo-data` v1.1 (DC-A-01 to DC-A-10). | Round 4 output; owner agreed the seven review items (DC-D-02, DC-D-04, DC-D-05, DC-D-06, DC-D-07, DC-D-09, DC-D-11). |
| CTX-D-021 | 9 Oct 2026 | Scope-lock clarification (no item added to list A): in SL-A2-01, "connect" means setting the address of an existing monitoring source and testing it on screen; sources and credentials are created by seed import (`40-demo-cut` DC-D-06). `01-scope-lock` v1.8. | Credentials live in the deployment secret store; the demo shows the connection test, not source creation. Agreed by owner in the Round 4 review. |
| CTX-D-022 | 9 Oct 2026 | "Vigil" is shown as the product name on screens and in tickets and messages (`40-demo-cut` DC-D-04, DC-W-30); Section 3 amended. | The narration presents the product as Vigil (SB-S00); a product needs a visible name. Agreed by owner in the Round 4 review. |

## 14. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| CTX-O-01 | Target demo date | Abdul with the senior manager | Before R6 closes |
| CTX-O-02 | Hosting: cloud account, domain name, approval of running costs (server, domain, AI usage, Jira) | Muzaffar | Before R5 closes |
| CTX-O-03 | Closed by CTX-D-018, 9 Oct 2026 (was: meeting tool, presenter, who shares the phone screen, and whether attendees join a demo Teams channel to receive messages themselves). | Abdul | R3 |
| CTX-O-04 | Availability of a GPU server for the local AI model, and the acceptable local-model answer time | Abdul | R5 |
| CTX-O-05 | Confirmation from the senior manager that the Jazz name and logo may be shown in the demo | Abdul | Before the branding build package |

## 15. Quality-check results

| Check | Result |
| --- | --- |
| CTX-Q-4 Independent review | Done, 8 Oct 2026, in two passes. Pass 1: 31 findings across both R0 documents (9 High, 16 Medium, 6 Low). Pass 2 (verification of fixes): 29 resolved, 2 partly resolved, plus 6 new findings introduced by the fixes (2 High, 4 Medium) and 6 Low. All closed in version 1.2. Main fixes: document-prefixed IDs; MUST/MAY wording; reset compatible with the append-only rules; lab boundary and separate demo control panel application; demo users with teams; login on Acknowledge and Approval pages; minimum security items; defined terms (storm re-run, test replay, re-run AI investigation, environment label); root-cause rule; allowed providers per AI task (Claude has no embeddings API); source documents moved to `docs/sources/`. |
| CTX-Q-4 pass 3 | Review of the owner-review changes: 10 Medium findings, all resolved in `01-scope-lock` v1.4. |
| CTX-Q-7 Owner review | Done, 8 Oct 2026. Five of seven decisions agreed as proposed; two changed (CTX-D-010, CTX-D-011). |

## 16. Change history

| Version | Date | Change |
| --- | --- | --- |
| 1.0 | 8 Oct 2026 | Initial baseline (Round 0). |
| 1.1 | 8 Oct 2026 | Resolved independent review findings: ID prefixes, added inputs-read, out-of-scope and quality-check sections, corrected BSD section reference, Q-1 exception for R1/R2, repository naming, "Resolved in" column for open items. |
| 1.2 | 8 Oct 2026 | Resolved verification findings: source documents located in `docs/sources/`; BSD RULE and goal traces allowed in CTX-W-05; review results updated. |
| 1.3 | 8 Oct 2026 | Owner review recorded; decisions CTX-D-010 and CTX-D-011 added. |
| 1.4 | 8 Oct 2026 | Review pass 3 recorded; Round 0 closed. |
| 1.5 | 9 Oct 2026 | Round 1 closed; decisions CTX-D-012 and CTX-D-013 added. |
| 1.6 | 9 Oct 2026 | Round 2 closed; decisions CTX-D-014 to CTX-D-016 added. |
| 1.7 | 9 Oct 2026 | Round 3 closed; decisions CTX-D-017 to CTX-D-019 added; CTX-O-03 closed. |
| 1.8 | 9 Oct 2026 | Round 4 closed; decisions CTX-D-020 to CTX-D-022 added; Section 3 product name amended (CTX-D-022). |
