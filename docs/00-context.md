# Vigil — Project Context

| Field | Value |
| --- | --- |
| Document | 00-context (ID prefix `CTX-`) |
| Version | 1.13 |
| Date | 16 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed) |

## 1. Purpose of this file

Read this file first in every Claude conversation in the "Noc Automation" project and at the start of every Claude Code session. It records the situation, the roles, the decisions made so far, and the rules every project document follows. If another project document conflicts with this file, this file wins until it is updated through the decision log (Section 13).

## 2. Inputs read

- `docs/sources/Continuum — Business Solution Document.md`, version 0.2, 4 October 2026.
- `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md`, 7 October 2026.
- Owner direction given in the Claude project conversation of 8 October 2026.

## 3. Product name

- **Vigil** is the working name for the whole product idea. It is used only by Abdul Wadood, Muzaffar Hussain and Claude. It is not a brand.
- The two source documents call the product "Continuum". Continuum and Vigil are the same product. All new documents and all code use Vigil only.
- **The repository** means the git repository in the folder `noc automation` on Abdul's computer. Code packages and modules inside it use the lowercase prefix `vigil`. Round 1 assigns working module names; Round 5 made them final without change (CTX-D-025, `50-architecture` AR-D-12).

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

Each round is one conversation in the Claude project and produces the files listed. A round starts by reading all earlier files. The master copy of every file is in the `docs/` folder of the repository; the Claude project holds a mirror updated at the end of each round. If the two differ, the repository copy wins. Once the repository is pushed to its private GitHub repository (`60-build-plan` BP-OT-01), the `main` branch there is the master copy (CTX-D-030).

| Round | Topic | Output files | Status |
| --- | --- | --- | --- |
| R0 | Context and scope | `00-context.md`, `01-scope-lock.md` | Closed, 8 Oct 2026 |
| R1 | Product map: every module of the full product, its job, what it owns, what it talks to; working module names; AI task list confirmation | `10-product-map.md` | Closed, 9 Oct 2026 |
| R2 | Core domain model: entities, relationships, lifecycles and state machines | `20-domain-model.md` | Closed, 9 Oct 2026 |
| R3 | Jazz storyboard and demo data: scene-by-scene script with Real/Simplified/Staged tags; the invented Jazz data set; exact demo values | `30-jazz-storyboard.md`, `31-demo-data/` | Closed, 10 Oct 2026 (restarted from scratch on 10 Oct 2026; the first run, made without web access, is in `docs/_superseded/`) |
| R4 | Demo cut: per module, keep / simplify / stage / later, keeping product shape; screen-by-screen content | `40-demo-cut.md` | Closed, 10 Oct 2026 |
| R5 | Architecture: applications, stack, integrations, AI layer, hosting, final module names | `50-architecture.md` | Closed, 12 Oct 2026 |
| R6 | Build plan: packages sized for Claude Code, each with spec, done-check and tests; repository conventions | `60-build-plan.md`, `CLAUDE.md` | Closed, 12 Oct 2026 |
| Build | One package at a time, by the protocol of `60-build-plan` Section 6; checkpoints SL-CP-1 to SL-CP-3 reviewed in the Claude project (`60-build-plan` Section 8.3) | `docs/packages/P-##.md`, `docs/packages/P-##-report.md`, code | In progress: P-01 merged 14 Oct 2026, P-02 and P-03 merged 15 Oct 2026, P-04 merged 16 Oct 2026 |

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
| CTX-D-017 | 10 Oct 2026 | Round 3 restarted from scratch: the first Round 3 output, the Round 4 output and the interrupted Round 5 draft moved to `docs/_superseded/`; `00-context`, `01-scope-lock`, `10-product-map` and `20-domain-model` restored to their end-of-Round-2 versions (commit eded314). | The first Round 3 ran without web access, so its fact check (CTX-Q-6) was skipped; Round 4 was built on it. Owner decision. |
| CTX-D-018 | 10 Oct 2026 | Jazz storyboard `30-jazz-storyboard` v1.0 and demo data set `31-demo-data` v1.0 adopted, with decisions SB-D-01 to SB-D-13. Amendments written: `01-scope-lock` v1.7 (SB-A-02, SB-A-03, SB-A-04, SB-A-06), `20-domain-model` v1.1 (SB-A-01, SB-A-06, SB-A-07, SB-A-08). | Round 3 output; owner agreed the seven review items (SB-D-02, SB-D-03 with SB-D-04, SB-D-08, SB-D-09 with SB-D-12, SB-D-11, SB-D-13, and the key timing values). |
| CTX-D-019 | 10 Oct 2026 | Demo setup (resolves CTX-O-03): Microsoft Teams meeting; the senior manager narrates and Abdul operates every screen; mobile pages in a phone-size browser window; attendees do not join a demo Teams channel (`30-jazz-storyboard` SB-D-01). No public Jazz fact is used (SB-D-06). | Owner answers in the Round 3 conversation. |
| CTX-D-020 | 10 Oct 2026 | Scope-lock clarifications from Round 3 (no item added to list A): SL-A3-01 — the SMS queue mock includes its dispatcher, and a lab Reporting server runs a mock report export job only to produce SL-F-3; SL-A3-03 — triggering faults includes clearing them ("Clear all faults"), and a reset clears the lab in parallel with the Vigil reset; SL-A3-07 — teams without members are configuration data. `01-scope-lock` v1.7. | SL-F-3 needs a component unrelated to the storm (DM-R-14); a storm re-run needs every lab alert resolved (DM-R-24); routing needs owner teams. Agreed by owner in the Round 3 review. |
| CTX-D-021 | 10 Oct 2026 | Teams messages are posted through Microsoft Workflows webhooks, not Office 365 connectors (fact check, `30-jazz-storyboard` Section 13); how a delivery address holds the per-channel webhook address is decided in Round 5 (DD-O-04, PM-O-10). | Microsoft disabled Office 365 connectors in Teams between 18 and 22 May 2026. |
| CTX-D-022 | 10 Oct 2026 | Demo cut `40-demo-cut` v1.0 adopted: 23 modules Keep, `vigil-setup` Simplified, 4 Later-only modules Later, nothing Staged; screens DC-S01 to DC-S12, product wording DC-TX-00 to DC-TX-11, outbound content DC-OUT-00 to DC-OUT-07, decisions DC-D-01 to DC-D-17. Amendments DC-A-01 to DC-A-19 written: `01-scope-lock` v1.8, `10-product-map` v1.2, `20-domain-model` v1.2, `30-jazz-storyboard` v1.1, `31-demo-data` v1.1. | Round 4 output; owner agreed the seven review items (DC-D-01 with DC-D-03, DC-D-02, DC-D-04, DC-D-05, DC-D-07 with DC-D-08, DC-D-11, DC-D-13). |
| CTX-D-023 | 10 Oct 2026 | SL-A2-01 clarified: "connect Zabbix and Alertmanager" is shown by editing the address of, testing, and seeing the health of the monitoring sources loaded with the starting state (SB-D01); adding a source is Later (`40-demo-cut` DC-L-01). No item added to list A. | Adding a source needs a runtime secret-writing path for something no scene shows (DC-D-03). |
| CTX-D-024 | 10 Oct 2026 | The product display name shown to the audience, on screens and in outbound content, is "Techfinity", held as a tenant setting (`40-demo-cut` DC-U-02, DC-A-09). The working name Vigil stays in documents and code (CTX-D-001). | Owner direction that the demo is branded "Techfinity"; Vigil is not a brand (Section 3). |
| CTX-D-025 | 12 Oct 2026 | Architecture `50-architecture` v1.0 adopted: one Java 25 / Spring Boot 4.1 application as a modular monolith (Spring Modulith; 23 Java modules, each module that owns records with its own database schema) with a React and TypeScript web application and PostgreSQL 17 with pgvector; final module names equal the working names; reset by a new tenant per demo run behind a tenant alias, earlier runs dormant and unchanged; Teams through one Workflows webhook per channel and email through Amazon SES to shared mailboxes, both in Techfinity's Microsoft 365 tenant; AI defaults Claude Sonnet 5.5, then GPT-6.1 Sol, then the local model for SL-T-1 and SL-T-2, local embeddings for SL-T-3. Amendments AR-A-01 to AR-A-24 written: `01-scope-lock` v1.9, `10-product-map` v1.3, `20-domain-model` v1.3, `30-jazz-storyboard` v1.2, `31-demo-data` v1.2, `40-demo-cut` v1.1. | Round 5 output; owner answers of 10 and 12 Oct 2026; owner agreed review items 2 to 5 and changed items 1 and 6 (CTX-D-026, CTX-D-028). |
| CTX-D-026 | 12 Oct 2026 | Hosting is AWS only, in region `us-east-1`: an always-on main host, a GPU host started on demand, an Application Load Balancer with an AWS Certificate Manager certificate, and Amazon Route 53 for a subdomain of the owner's domain, delegated by `NS` records in the owner's existing DNS zone at Cloudflare (`50-architecture` AR-D-03, AR-H-01 to AR-H-13). | Owner direction: no AWS and Cloudflare combination; AWS's default region rather than Mumbai. `us-east-1` has every service needed (the Gulf regions lack SES SMTP endpoints); pages respond about 200 to 250 ms slower from Pakistan than from Mumbai. |
| CTX-D-027 | 12 Oct 2026 | The local model runs on a GPU host started on demand (`gemma4:12b` on Ollama, `g6.xlarge`); a local SL-T-1 answer within 45 s is acceptable; the local embedding model runs on the main host's CPU (`50-architecture` AR-D-05). Closes CTX-O-04. | Owner answer of 10 Oct 2026 and review item 2. |
| CTX-D-028 | 12 Oct 2026 | The running costs are approved. Recorded as Abdul's statement of 12 Oct 2026; the estimate for the chosen design is about 234 USD a month (`50-architecture` Section 17.1, AR-D-17). Closes CTX-O-02. | Owner answer to review item 6. |
| CTX-D-029 | 12 Oct 2026 | Build plan `60-build-plan` v1.0 and `CLAUDE.md` adopted: 40 build packages P-01 to P-40 in five phases, risk first (lab and storm timing, AWS edge and live updates, Jira, Teams, email and AI providers proved in Phase A); SL-CP-1 is the storm to a resolved incident and resolved Jira ticket on the demo installation with screens DC-S01 to DC-S05 and DC-S12 (closes SL-O-19); building in Claude Code cloud sessions (cloud environment `vigil`, Java 25 from its setup script) with GitHub Actions as the merge gate; one local installation with dev fakes on the owner's Mac, in CI and in cloud sessions; demo images built by GitHub Actions and pushed to Amazon ECR, deployed by the owner's scripts; the package protocol of `60-build-plan` Section 6. Amendments BP-A-01 to BP-A-16 written: `01-scope-lock` v1.10, `30-jazz-storyboard` v1.3, `31-demo-data` v1.3, `40-demo-cut` v1.2, `50-architecture` v1.1. | Round 6 output; owner agreed review items 1 to 5 and 7 and changed item 6 (CTX-D-030). |
| CTX-D-030 | 12 Oct 2026 | The repository is a private GitHub repository on GitHub Free with GitHub Actions. `main` is not protected; only Abdul merges, and only after the `ci.yml` job `gate` passes (`60-build-plan` BP-D-13, BP-R-07). Once pushed, the GitHub `main` branch is the master copy of the documents (refines CTX-D-009). | Owner answers of 12 Oct 2026: private GitHub with Actions; no paid plan for branch protection. |
| CTX-D-031 | 12 Oct 2026 | SL-B-14 clarified (no item added to list A or list B): "HTTPS only" applies to every installation reachable from outside its own host; a local installation reachable only from its own machine (`60-build-plan` Section 11) serves plain HTTP. `01-scope-lock` v1.10. | The owner runs the software on his Mac (owner answer of 12 Oct 2026); certificates for a machine-local name add work and no protection. |
| CTX-D-032 | 15 Oct 2026 | From the P-02 report: in a cloud session the gate command starts the Docker daemon when it is not running (`60-build-plan` v1.3 BP-R-12 step (0), `tools/start-docker.sh`, built by P-03), and `CLAUDE.md` says so; Docker Hub rate limits are handled by retried base-image pulls in `deploy/images.sh`, with pre-pulling in the setup script kept as open item BP-O-04. | The P-02 session found the daemon stopped and started it by hand; one Docker Hub request was refused once. Changing the setup script now would need an environment update for a single occurrence. |
| CTX-D-033 | 15 Oct 2026 | The GitHub repository is `awadoodatevam/vigil` (private, GitHub Free, Claude GitHub App installed, `main` the default branch); its `main` is the master copy of the documents (CTX-D-030). `awadood/vigil`, which holds P-01's pull request, is no longer pushed to. | The owner moved the work to the account connected to his current Claude account on 15 Oct 2026. |
| CTX-D-034 | 15 Oct 2026 | The lab image takes Zabbix agent 2 of the 7.0 LTS line from the official `zabbix/zabbix-agent2` image (the newest tag built on Ubuntu 24.04), pinned by tag and digest, instead of Ubuntu's archive (`60-build-plan` BP-A-17; `50-architecture` AR-X-01 v1.2). | Ubuntu 24.04's archive has no Zabbix package (found by the P-03 readiness test); `repo.zabbix.com` is not reachable from cloud sessions. Keeps a real agent on the lab hosts, on the same LTS line as the lab's Zabbix server (Zabbix supports an older agent patch with a newer server). Decided by owner, 15 Oct 2026. |
| CTX-D-035 | 15 Oct 2026 | From the P-03 report (BP-L-8) and the writing of the P-04 spec (BP-L-1), no behaviour of Vigil changed (complete list): (1) a lab API request makes every write of its endpoint and fails when any write fails, and the USSD gateway writes `ussd_sessions` also after a failed API call: this is the reading of DD-F-10 `api_instances` and `every_writer_rate` that P-03 built and the owner merged; the P-03 spec's "stops at the first write" is corrected; (2) the `df` used percentage after SL-F-1 depends on the lab size profile (100 % in `demo`, 99 % in `test`) and is compared exactly only in `demo` (`60-build-plan` BP-R-30, DD-F-10 `faults.SL-F-1`); (3) the build CA secret is mounted readable by apt's sandbox user (`60-build-plan` BP-R-04); (4) BP-ASM-02 and BP-ASM-06 are proved and BP-ASM-09 is proved for its P-03 part; (5) for P-04: the gate tests `spikes/` and `tools/trace.py` reads its test names (BP-R-12, BP-R-15), `deploy/images.sh --test` also pulls the third-party images of the local compose file with retries and builds the storm timing spike's image (BP-R-04), the local installation adds the webhook recorder and the spike (Section 11.1) and runs `lab-zabbix-provision` as the user who runs `deploy/local.sh` (BP-R-25), DD-F-10 gets `lab_db_server.development_values` for the values P-04 measures (amendment BP-A-18), the deployment waits for the Zabbix provisioning with `docker wait` (`50-architecture` AR-H-11, amendment BP-A-19), and assumptions BP-ASM-10 (`demo-faults` on the host network) and BP-ASM-11 (Zabbix API features) are added. | P-03 report, document feedback 1 to 4 and deviations 1, 2, 3, 5; the P-04 spec needs its spike tested in the gate and a place in DD-F-10 for development values that P-32 does not overwrite. |
| CTX-D-036 | 15 Oct 2026 | The gate runs the lab tests (marker `lab`: the lab, monitoring and the storm of P-03 and P-04) only when the branch changes a file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` (`60-build-plan` BP-R-12 step (3)). | From P-04 the lab tests start Zabbix, Prometheus and Alertmanager and run one storm, so a gate run takes about 20 minutes instead of about 5; running them on every pull request would add about 2,300 GitHub Actions minutes over the build (the free plan includes 2,000 a month). Product code cannot affect the lab (BP-R-06). Decided by owner, 15 Oct 2026. |
| CTX-D-037 | 16 Oct 2026 | From the P-04 report (BP-L-8), no behaviour of Vigil changed (complete list): (1) Zabbix's trim preprocessing characters are written `\s\t\r\n` in the API (P-04 spec 6.2.2 corrected; deviation 1); (2) Docker Hub refusals in cloud sessions (open item BP-O-04, closed): `tools/start-docker.sh` starts the Docker daemon with the registry mirror `https://mirror.gcr.io`, Docker Hub stays the fallback and the retries stay (`60-build-plan` BP-R-12 step (0), built by P-08); decided by owner, 16 Oct 2026; (3) DD-ASM-05's baseline percentage is refuted (`df` shows 47 % in the `demo` profile; its injection limits hold) and DD-ASM-03 is proved by the development run of SB-T-01 (`31-demo-data` v1.5); (4) assumptions of earlier documents proved by the development run (complete list): SL-ASM-01 (95 individual Alertmanager alerts within 32 s), DM-ASM-05 (each storm alert exactly once), DM-ASM-08 for its lab part (every lab alert resolved within 40 s of the clear; the reset itself is proved by SB-T-05 in P-31 and P-32), AR-ASM-07 (`{EVENT.TAGSJSON}` is an array of `{tag, value}`); `60-build-plan` BP-ASM-05 (P-04's images), BP-ASM-09, BP-ASM-10 and BP-ASM-11 are proved (`60-build-plan` v1.5). | P-04 report, deviations, document feedback 1, 2 and 4, Section 8.3. The locked and baselined documents that hold SL-ASM-01, DM-ASM-05, DM-ASM-08 and AR-ASM-07 keep their text; this entry records the results. |

## 14. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| CTX-O-01 | Target demo date | Abdul with the senior manager | When the senior manager sets a demo date; no build package depends on it (owner answer of 12 Oct 2026, `60-build-plan` BP-A-14) |
| CTX-O-02 | Closed by CTX-D-026 and CTX-D-028, 12 Oct 2026 (was: hosting: cloud account, domain name, approval of running costs). | Muzaffar | R5 |
| CTX-O-03 | Closed by CTX-D-019, 10 Oct 2026 (was: meeting tool, presenter, who shares the phone screen, and whether attendees join a demo Teams channel to receive messages themselves). | Abdul | R3 |
| CTX-O-04 | Closed by CTX-D-027, 12 Oct 2026 (was: availability of a GPU server for the local AI model, and the acceptable local-model answer time). | Abdul | R5 |
| CTX-O-05 | Confirmation from the senior manager that the Jazz name and logo may be shown in the demo | Abdul | Before P-32, the first deployment of the public demo installation (`60-build-plan` BP-D-11, BP-OT-11) |

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
| 1.7 | 10 Oct 2026 | Round 3 restarted (CTX-D-017) and closed; decisions CTX-D-017 to CTX-D-021 added; CTX-O-03 closed. |
| 1.8 | 10 Oct 2026 | Round 4 closed; decisions CTX-D-022 to CTX-D-024 added. |
| 1.9 | 12 Oct 2026 | Round 5 closed; decisions CTX-D-025 to CTX-D-028 added; CTX-O-02 and CTX-O-04 closed; module names final. |
| 1.10 | 12 Oct 2026 | Round 6 closed; decisions CTX-D-029 to CTX-D-031 added; GitHub `main` is the master copy once pushed; CTX-O-01 and CTX-O-05 "Resolved in" updated; Build row refers to the protocol of `60-build-plan` Section 6. |
| 1.11 | 15 Oct 2026 | Build status updated (P-01 and P-02 merged); decisions CTX-D-032 (Docker daemon in cloud sessions), CTX-D-033 (repository `awadoodatevam/vigil`) and CTX-D-034 (Zabbix agent 2 from the official image). |
| 1.12 | 15 Oct 2026 | Build status updated (P-03 merged); decisions CTX-D-035 (P-03 report resolved, document changes for P-04) and CTX-D-036 (lab tests in the gate only when the lab changes). |
| 1.13 | 16 Oct 2026 | Build status updated (P-04 merged); decision CTX-D-037 (P-04 report resolved, registry mirror for cloud sessions, assumption results). |
