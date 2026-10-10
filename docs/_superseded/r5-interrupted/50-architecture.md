# Vigil — Architecture

| Field | Value |
| --- | --- |
| Document | 50-architecture (ID prefix `AR-`) |
| Version | 0.1 |
| Date | 9 October 2026 |
| Owner | Abdul Wadood |
| Status | Draft for review (Round 5) |

## 1. Purpose

This document decides how Vigil is built and run. It fixes the applications and what each contains, the technology stack, the process layout and the mechanism for requests, events and live screen updates, the storage rules, the security mechanisms, the connector contracts with Zabbix, Alertmanager, Jira Cloud, Microsoft Teams, email and the AI providers, the AI layer, the reset mechanism, the public interface of `vigil-api` with its paths and link formats, the hosting layout, and the final module names. It resolves every open item that earlier documents assigned to Round 5 (Section 20.1).

It does not add anything to `01-scope-lock` list A or list B. Where a mechanism needs a new record or responsibility (the tenant binding of the reset mechanism, the monitoring source key of the webhook address), it is recorded as an amendment to the earlier document (Section 19).

## 2. Inputs read

- `docs/00-context.md` v1.8.
- `docs/01-scope-lock.md` v1.8.
- `docs/10-product-map.md` v1.3.
- `docs/20-domain-model.md` v1.2.
- `docs/30-jazz-storyboard.md` v1.1.
- `docs/31-demo-data/` v1.1 (all files).
- `docs/40-demo-cut.md` v1.0.
- `docs/sources/Techfinity NOC Demo — What Will Be Delivered.md` (technology table, for the baseline stack) and `docs/sources/Continuum — Business Solution Document.md` v0.2 (BSD §5.2, §6.2, §10.3, §14).
- Owner direction in the Round 5 conversation, 9 October 2026: the round plan was approved ("go"). The owner did not answer the plan's questions on stack preference, cloud account, GPU server and budget; this document therefore proposes answers and puts them to the owner review (Section 23, CTX-Q-7).

## 3. Terms (complete list)

Terms defined in earlier documents keep their meaning. The terms below are added.

| Term | Meaning |
| --- | --- |
| Application | A separately started program: `vigil-server`, `vigil-web` (static files) or a demo-only program (Section 4). |
| Host | A virtual machine of the hosting layout (Section 15). |
| Installation | One deployment of `vigil-server` with its database. An installation holds any number of tenants. |
| Tenant binding | A record that names the tenant served under one HTTPS host name (Section 12, DM-E-83). |
| Bound tenant | The tenant named by the current binding of a host name. |
| Dormant tenant | A tenant in state `ready` that is not bound to any host name (Section 12). |
| Installation token | The secret that authorises installation operations (creating tenants, importing a seed bundle into a new tenant, changing a binding). It belongs to no tenant and no user (AR-SEC-07). |
| Secret store | Where secret values are kept on the host, outside the repository (AR-SEC-05). |
| Locator | The text in a credential reference (DM-E-11) that names one secret in the secret store. |
| Change notice | A short message pushed by `vigil-api` to an open browser over the stream, telling it which data to read again (AR-PL-10). |
| Spike | An early build test that proves an assumption against running software (Section 21). |
| Deployment values | The non-secret values of one installation (domain, Jira site, transition identifiers) used when the seed bundle is built (AR-SB-06). |

## 4. Applications (complete list)

| ID | Application | Contains | Runs on | Trace |
| --- | --- | --- | --- | --- |
| AR-APP-01 | `vigil-server` | Modules PM-M01 to PM-M23 (every built module except `vigil-web`), in one process (AR-PL-01). | `vigil-app-01` (AR-H-01) | PM-D-01, PM-O-01 |
| AR-APP-02 | `vigil-web` | Module PM-M24: the single-page application with screens SL-A4-01 to SL-A4-11, built to static files and served by the edge proxy of `vigil-app-01`. It talks only to `vigil-api` (PM-R-07). | Browser; files on `vigil-app-01` | PM-M24, PM-R-07 |
| AR-APP-03 | `demo-control` | PM-DC-04 (demo control panel, screen SL-A4-12) and PM-DC-03 (`demo-faults`, the fault scripts it runs). Demo-only. | `lab-01` (AR-H-02); the SL-F-4 script on `vigil-app-01` (AR-DEMO-04) | SL-A3-02, SL-A3-03 |
| AR-APP-04 | `demo-lab` | PM-DC-01, the mock Jazz services. Demo-only. | `lab-01` and `lab-db-01` (AR-H-03) | SL-A3-01 |
| AR-APP-05 | `demo-monitoring` | PM-DC-02: the real Zabbix, Prometheus and Alertmanager, with their lab configuration. Demo-only configuration of external products. | `lab-01`; Zabbix agent on `lab-db-01` | SL-A3-01, SL-A1-01 |
| AR-APP-06 | `demo-data` | PM-DC-05: the seed bundle built from `docs/31-demo-data/` (Section 13). Content, not a program. | Read by `demo-control` at reset | SL-DR-04 |

External runtimes used by these applications (complete list): PostgreSQL with pgvector (AR-STK-05); Ollama, twice — the local embedding model on `vigil-app-01` and the local chat model on `gpu-01` (AR-AI-08, AR-AI-09); Caddy as the edge proxy on `vigil-app-01`, `lab-01` and `gpu-01` (AR-SEC-01); Docker Engine with Compose on `lab-01` (AR-H-02).

## 5. Final module names

| ID | Rule | Trace |
| --- | --- | --- |
| AR-N-01 | The working module names of `10-product-map` Section 4.1 (PM-M01 `vigil-tenancy` to PM-M28 `vigil-discovery`) are final. No module is renamed. | CTX §3; PM-D-01 |
| AR-N-02 | In code, module `vigil-<name>` is the Java package `vigil.<name with hyphens removed>` (Example: `vigil-ai-gateway` → `vigil.aigateway`; `vigil-servicemap` → `vigil.servicemap`). `vigil-web` is the folder of the web application. | CTX §3 (lowercase `vigil` prefix) |
| AR-N-03 | In the database, module `vigil-<name>` owns the schema `vigil_<name with hyphens replaced by underscores>` (Example: `vigil_ai_gateway`). | AR-DS-01 |
| AR-N-04 | The `module` text of an Actor (DM-V-01) is the working module name, Example: `vigil-engine`. | DM-V-01 |
| AR-N-05 | Demo-only names are final: `demo-lab`, `demo-monitoring`, `demo-faults`, `demo-control`, `demo-data`. No demo-only code uses the `vigil` prefix (PM Section 3). | PM-DC-01 to PM-DC-05 |
| AR-N-06 | Product name shown on screens and in messages stays "Vigil" (DC-W-30). | CTX-D-022 |

The repository layout (folders, build files, conventions) is decided in Round 6 (CTX §9). It MUST keep the demo-only components in a separate top-level folder from `vigil-server` and `vigil-web` (PM Section 11.1).

## 6. Technology stack

### 6.1 Choice (complete list)

Versions are the latest generally available release of the named line at the start of the build; Round 6 pins exact versions in the build files (AR-ASM-40).

| ID | Part | Choice | Reason | Trace |
| --- | --- | --- | --- | --- |
| AR-STK-01 | Server language and runtime | Java 25 (LTS), one JVM process. Virtual threads enabled for request handling and scheduled work. | Long-term support through the pilot; the team's strongest skill in the baseline (demo document, Technology); strong typing for 82 entities and 16 state machines. | CTX-G-2; SL-X-12 |
| AR-STK-02 | Server framework | Spring Boot 4 (Spring Framework 7) with Spring Web MVC, Spring Security (authentication filter and password encoder only), Spring JDBC (`JdbcClient`), Flyway for schema migrations, Jackson for JSON and YAML. | Standard, documented, well known to Claude Code; nothing exotic for one builder. | CTX-C-01 |
| AR-STK-03 | Module structure and durable events | Spring Modulith: one application module per Vigil module (AR-N-02), boundary verification in a test, and the JDBC event publication registry for durable events (AR-PL-03). | Gives PM-R-02 and PM-R-03 an automated check and gives DM-R-66 a tested transactional-outbox mechanism instead of a hand-made one. | PM-R-02, PM-R-03, DM-R-66 |
| AR-STK-04 | Outbound calls | `java.net.http.HttpClient` for every HTTP connector; Angus Mail (Jakarta Mail) for SMTP; Apache MINA SSHD client for the runner (ed25519 keys, pinned host key). | One HTTP client for all HTTP connectors; SSH is the BSD's direct-diagnostics path. | SL-B-07, PM-K-03 |
| AR-STK-05 | Database | PostgreSQL 18 with the pgvector extension (0.8 line), one database per installation. | One store for records, events, timers and similarity search (baseline); pgvector exact search keeps the SL-T-3 result exact (AR-AI-10). | SL-X-12; SL-T-3 |
| AR-STK-06 | Web application | TypeScript, React 19, Vite (build to static files), React Router, TanStack Query (reads and re-reads), `@xyflow/react` 12 (React Flow) for the workflow canvas, generated TypeScript types from the OpenAPI document of `vigil-api` (AR-API-03). | React Flow is a maintained, MIT-licensed canvas with handles, connections, selection, zoom, pan and fit (SL-WE-01 to SL-WE-06); generated types keep screens and interface in step. Resolves the library part of DC-O-05; the automatic layout method stays with Round 6. | SL-A4-06; DC-O-05 |
| AR-STK-07 | Tests | JUnit 5, Testcontainers (PostgreSQL), ArchUnit and the Spring Modulith verification test on the server; Vitest and Playwright (Chromium) on the web application. | Automated proof of module rules, state machines and the primary demo path (Round 6 sizes the suites). | CTX-Q-5, CTX-Q-8 |
| AR-STK-08 | Edge proxy and TLS | Caddy 2: automatic certificates from a public certificate authority, HTTP redirected to HTTPS, reverse proxy to `vigil-server`, static files of `vigil-web`. | Automatic HTTPS with no certificate handling by the builder; streams responses of type `text/event-stream` without buffering (AR-ASM-44). | SL-A3-05, SL-B-14 |
| AR-STK-09 | Local AI runtime | Ollama, for the local chat model (on `gpu-01`) and the local embedding model (on `vigil-app-01`). | One runtime and one API style for both local providers; runs on CPU and on NVIDIA GPUs (AR-ASM-33). | PM-K-09, PM-K-10; CTX-D-011 |
| AR-STK-10 | Demo-only components | Python 3.12 or later: `demo-control` as a small web application with server-rendered pages, the lab mock services, the fault scripts and the seed bundle builder. Lab and monitoring run in Docker Compose on `lab-01`. | The data tools of Round 3 are already Python (DD-F-11, DD-F-12); demo-only code stays outside the product code base and language (CTX-C-03). | PM Section 11.1 |
| AR-STK-11 | Operating system | Ubuntu Server 24.04 LTS on every host, clock in UTC synchronised by NTP (chrony). | Long-term support; UTC is required by DD-F-10 `clock`. | DD-F-10 |

### 6.2 Alternatives considered

| Option | Why not chosen |
| --- | --- |
| TypeScript on Node.js for the server, with the same web stack | One language for the whole product and shared types without generation. Not chosen because it leaves durable events, module boundary checks and exact decimal arithmetic to hand-made code, and the Techfinity team that may take the product forward works in Java (demo document, Technology). This is the main alternative put to the owner (Section 23, item 1). |
| Python (FastAPI) for the server | Less code for small services; weaker typing and no standard module-boundary or outbox mechanism for 24 modules with a strict request order. |
| Next.js for the web application | Adds a second server process for rendering that no screen needs; every screen is behind a login and reads data from `vigil-api`. |
| A message broker (Kafka, RabbitMQ) for events | A second stateful system to run and back up, for an event volume of a few thousand events per demo run. The PostgreSQL event publication registry gives at-least-once delivery within one process (AR-PL-03). |
| An external workflow engine | Excluded by SL-B-03. |

## 7. Process layout and communication (resolves PM-O-01, DC-O-02)

| ID | Rule | Trace |
| --- | --- | --- |
| AR-PL-01 | **One process.** An installation runs exactly one `vigil-server` process. Every built module except `vigil-web` runs inside it as a Spring Modulith application module whose base package is `vigil.<name>` (AR-N-02). High availability and several processes are Later (SL-C-07). | PM-O-01; SL-C-07 |
| AR-PL-02 | **Requests.** A request of `10-product-map` Section 9.1 is a Java method call on a type in the target module's exposed API package. A module MUST NOT reference types in another module's internal packages. Each module declares as allowed dependencies exactly the modules earlier than it in the PM-R-03 order (`@ApplicationModule(allowedDependencies = …)`). An automated test MUST fail the build when a module has a cycle, uses another module's internal type, or depends on a module that is not earlier in the PM-R-03 order. | PM-R-02, PM-R-03 |
| AR-PL-03 | **Events.** An event of `10-product-map` Section 9.2 is a Java record published inside the database transaction of the change it announces, through the Spring Modulith event publication registry backed by PostgreSQL (schema `vigil_events`). Each reacting module listens with an asynchronous, transactional listener that runs after the publishing transaction commits, in a new transaction (`@ApplicationModuleListener`). A publication is marked complete only when its listener's transaction commits. Outstanding publications MUST be published again at start-up (`spring.modulith.events.republish-outstanding-events-on-restart=true`) and every 30 s for publications incomplete for more than 30 s. Every listener MUST give the same result when it receives the same event twice (DM-R-66). Completed publications are kept, not deleted (completion mode `update`, DM-C-04). | DM-R-66; PM-O-01 |
| AR-PL-04 | **Event content.** An event carries the tenant identifier, the identifiers of the changed records and the fields named in its definition, never record content of another module. A reacting module that needs content requests it from the owner (PM-R-02). Every event published by an earlier module and reacted to by a later one follows the PM-R-03 order; no listener depends on a later module. | PM-R-02, PM-R-05 |
| AR-PL-05 | **Transactions.** Each request handler of a module runs in its own database transaction and commits before it returns; read-only requests MAY run without a transaction. A module MUST NOT send a request to another module or call an external system while it holds an open database transaction, with one exception: an audit event (PM-I-01) is written inside the transaction of the change it reports, so a change and its audit event commit together. | PM-R-01, PM-R-06; DM-E76-R2 |
| AR-PL-06 | **Serialised work.** One server process (AR-PL-01) allows in-process locks: (a) the per-tenant sequence of DM-R-62 is a lock per tenant held by `vigil-correlation` for each alert decision, stability transition and reopen decision; (b) DM-R-67 is a lock per workflow run held by `vigil-engine` for each step evaluation, with the step execution's state checked again inside the lock. Atomic decisions (DM-R-63) are a conditional update of the request or acknowledgement record inside one transaction, so of two attempts at most one changes a row. | DM-R-62, DM-R-63, DM-R-67 |
| AR-PL-07 | **Step dispatch.** For each step evaluation `vigil-engine` (1) stores the step execution with its increased `evaluation_count` and commits, publishing "step changed"; (2) calls the implementing module (PM-I-18) without a transaction; (3) stores the named output or the "waiting" answer and the run checkpoint and commits, publishing "step changed". When the call in (2) throws instead of answering, the engine repeats it after 5 s, 15 s and 45 s (DM-O-06); after the fourth failure the run ends as `failed` with `end_reason = step_failed_repeatedly`. | PM-S-12 to PM-S-14; DM-O-06 |
| AR-PL-08 | **Durable timers.** `vigil-engine` reads due `pending` timers (DM-E-52) every 1 s and handles each as PM-S-13 states. A timer that became due while the process was stopped fires at the first read after start-up (DM-E52-R1). After start-up the engine evaluates every `waiting` step execution once (DM-R-66). | PM-M12-05, DM-E52-R1 |
| AR-PL-09 | **Scheduled work (complete list).** Each job runs only for bound tenants (AR-RS-04). (1) Timer read, every 1 s (`vigil-engine`). (2) Stability check, every `stability_check_interval` (`vigil-correlation`, DM-R-15). (3) Closure check, every `closure_check_interval` (`vigil-incidents`, DM-R-23). (4) Source connection test and health evaluation, every `health_check_interval` per source (`vigil-intake`, DM-R-39, DM-R-40). (5) Re-embedding retry, every 30 s while `embedding_state = reembedding` (`vigil-knowledge`, DM-SM15-T3). (6) Resubmission of incomplete event publications, every 30 s (AR-PL-03). Session expiry is checked on each request and needs no job (DM-SM02-T3). | DM-R-15, DM-R-23, DM-R-39, DM-SM15-T3 |
| AR-PL-10 | **Live updates to screens.** `vigil-api` keeps one Server-Sent Events stream per open browser (AR-OP-05). It reacts to the events "incident changed" (PM-EV-09), "run started, step changed, run finished" (PM-EV-11), "workflow version published" (PM-EV-13), "tenant settings changed" (PM-EV-14) and "tenant binding changed" (PM-EV-15) and pushes change notices (AR-API-08) to the streams of the tenant concerned. A notice carries identifiers only; the browser reads the data again through the normal read operations. Step changes with `mode = test` are never pushed (DM-R-59). The stream sends a comment line every 15 s to keep the connection open. The time from a commit to the pushed notice MUST be at most 1 s, and from the commit to the updated screen at most 2 s (SL-A1-08, DC-UI-09; spike AR-SPK-09). | PM-M23-04; SL-A1-08 |
| AR-PL-11 | **Alert states and recovery evaluations (resolves DC-O-02).** While the incident workspace is open, `vigil-web` reads the alerts region (DC-S03-R3, AR-OP-13) and the recovery region (DC-S03-R7, AR-OP-16) again every 2 s, in addition to the reads caused by change notices. This meets the 5-second rule of DC-UI-09 without adding alert events to the stream. | DC-UI-09, DC-D-14; DC-O-02 |

## 8. Data storage (resolves DM-O-08)

| ID | Rule | Trace |
| --- | --- | --- |
| AR-DS-01 | **One database, one schema per module.** Each installation has one PostgreSQL database. Each module except `vigil-api` and `vigil-web` stores its records only in its own schema (AR-N-03). No SQL statement of a module refers to another module's schema; an automated test MUST check this. There are no foreign keys between schemas (DM-C-06). | PM-R-01, PM-R-02; DM-C-06 |
| AR-DS-02 | **Migrations.** Each module has its own Flyway migration folder and history table in its schema. Migrations only add; a migration that drops or rewrites data needs a decision log entry. | DM-C-04; CTX-Q-8 |
| AR-DS-03 | **Physical types (complete list).** `Id` — `uuid`, version 7 (time-ordered), generated by `vigil-server` before insert, written in text as lowercase canonical form. `Key` — `varchar(64)` with a check on the DM-C-01 character set. `Text(n)` — `varchar(n)` (PostgreSQL counts characters). `Integer` — `bigint`. `Decimal(p,s)` — `numeric(p,s)`. `Boolean` — `boolean`. `Timestamp` — `timestamptz`, written with millisecond precision. `Duration` — `bigint` seconds with a check of 0 or more. `Enum{…}` — `varchar` with a check listing the values. `List<T>`, `Set<T>` (stored sorted, DM-C-01) and `Json` — `jsonb`. `Ref<E>` — `uuid`. `Bytes` — `bytea`. `Vector` — pgvector `vector` without a fixed dimension, so embeddings of different providers can be stored (DM-SM-15). | DM-C-01; DM-O-08 |
| AR-DS-04 | **Hash.** Every hash of DM-O-08 (`snapshot_hash`, DM-V-12) is SHA-256 over the UTF-8 bytes of the canonical JSON form of the value as defined by RFC 8785 (JSON Canonicalization Scheme), written as 64 lowercase hexadecimal characters. Token hashes (DM-E-07) are SHA-256 of the token bytes, same notation. | DM-V-12, DM-E-07; DM-O-08 |
| AR-DS-05 | **Tenant column.** Every table except the tenant table has a `tenant_id uuid not null` column, and every unique index includes it (DM-C-10). | SL-B-01; DM-C-10 |
| AR-DS-06 | **No deletion, enforced.** The database role used by `vigil-server` MUST NOT hold the DELETE or TRUNCATE privilege on any `vigil_*` schema, and MUST NOT hold UPDATE on the audit event table. Migrations run under a separate owner role. | DM-C-04; SL-B-02, SL-B-06 |
| AR-DS-07 | **Similarity search.** Past-incident embeddings are stored in `vigil_knowledge` as `vector`. A search compares the query vector with every active past-incident vector of the search's provider by cosine similarity without an approximate index (pgvector `<=>` with no index), so the result is the exact nearest neighbour. The score is 1 − cosine distance, compared and shown rounded half-up to 2 decimal places (`01-scope-lock` Section 5.5). | SL-T-3, SL-A1-06 |
| AR-DS-08 | **Backups.** Backup and restore procedures are Later (SL-C-07, PM-M27-L1). | SL-C-07 |
| AR-DS-09 | **Event and infrastructure tables.** The event publication registry (AR-PL-03) is the only table set outside the module schemas; it lives in schema `vigil_events`, belongs to no module and holds no record content (AR-PL-04). | AR-PL-03 |

## 9. Security and identity (resolves DM-O-05, PM-O-11, PM-O-17)

| ID | Rule | Trace |
| --- | --- | --- |
| AR-SEC-01 | **HTTPS only.** On every host, Caddy is the only process listening on public ports: 443 (HTTPS) and 80 (which only redirects to HTTPS). `vigil-server` listens on 127.0.0.1 only. Responses carry `Strict-Transport-Security: max-age=31536000`. Certificates are obtained and renewed automatically by Caddy. | SL-A3-05, SL-B-14; PM-M23-05 |
| AR-SEC-02 | **Passwords (DM-O-05).** `password_hash` (DM-E-03) is an Argon2id hash in the encoded form of Spring Security's `Argon2PasswordEncoder` with its Spring Security 5.8 defaults (16-byte salt, 32-byte hash, 16 MiB memory, 2 iterations, parallelism 1). The identity import reads each seeded user's password from the locator `file:user-password-<username>` (AR-SEC-05). The deployment procedure generates each password at random, at least 20 characters, different per installation (PM-M02-02). | SL-B-14; PM-M02-02 |
| AR-SEC-03 | **Sessions (DM-O-05).** Login creates a session token of 32 random bytes, sent to the browser in the cookie `vigil_session` with `HttpOnly`, `Secure`, `SameSite=Lax`, `Path=/`; only its SHA-256 hash is stored (DM-E-07). Session lifetime (`expires_at`): 12 hours after login. Idle limit: 4 hours since `last_activity_at`. `last_activity_at` is updated at most once per minute. | DM-E-07, DM-SM-02; DM-O-05 |
| AR-SEC-04 | **Request forgery.** `vigil-api` MUST refuse (HTTP 403) every state-changing user request (POST, PUT, DELETE) whose `Origin` header is missing or is not the origin of the request's host (`https://{host}`). | SL-B-13 |
| AR-SEC-05 | **Secret store (resolves PM-O-11).** A locator has the form `file:<name>`, where `<name>` is 1 to 64 characters from `a–z 0–9 -`. The secret value is the whole content of the file `/etc/vigil/secrets/<name>` on `vigil-app-01`, owned by the operating-system user that runs `vigil-server`, mode 0400, in a directory of mode 0700. Secret files are created by the deployment procedure and are never in the repository. Values are read when needed and are never written to logs, records or responses. Other schemes (Example: a cloud key vault) MAY be added later as new locator prefixes without changing any record. | PM-M04-03, SL-B-14; PM-O-11 |
| AR-SEC-06 | **Secret contents by purpose (complete list).** `webhook_token`: 43 characters, base64url of 32 random bytes. `external_api`: the provider's API key or token as issued (Jira: `email:api_token`; Alertmanager: `username:password` for basic authentication; Teams: the full Workflows URL, which contains its signature; local chat model: the bearer token of `gpu-01`). `target_diagnostic`, `target_action`: a JSON object `{"username": "…", "private_key": "<OpenSSH ed25519 private key>"}`. `smtp`: a JSON object `{"username": "…", "password": "…"}`. User passwords: the password text. Installation token: 43 characters, base64url of 32 random bytes. | DM-E-11 |
| AR-SEC-07 | **Installation token (resolves PM-O-17).** Installation operations (AR-OP-60 to AR-OP-64) require the header `Authorization: Bearer <installation token>`; the value comes from locator `file:installation-token`. It grants no tenant user operation, and user sessions grant no installation operation. The demo control panel holds the same value in its own secret file on `lab-01`. Changes made by an installation operation record the actor `{kind: module, module: "vigil-api"}`. | SL-A3-03; PM-O-17 |
| AR-SEC-08 | **Webhook authentication.** Each monitoring source sends `Authorization: Bearer <token>`; `vigil-intake` compares it with the source's token in constant time (DM-E18-R1). A call without a matching token is answered 401 and not stored. | SL-B-14; DM-E18-R1 |
| AR-SEC-09 | **Network access (complete list of inbound rules).** `vigil-app-01`: 443 and 80 from anywhere; 22 from the operator's addresses and, for the SL-F-4 script only, from `lab-01` (AR-DEMO-04). `lab-01`: 443 and 80 from the operator's addresses and from `vigil-app-01`; 22 from the operator's addresses. `lab-db-01`: 22 from `vigil-app-01` (runner) and `lab-01` (fault scripts); 10050 (Zabbix agent) from `lab-01`; the mock database port from `lab-01`. `gpu-01`: 443 from `vigil-app-01`; 22 from the operator's addresses. Every other inbound port is closed. | SL-C-07 (no hardening beyond SL-B-14) |
| AR-SEC-10 | **Target access.** On `lab-db-01` the runner uses two operating-system users, `vigil-diag` (member of group `vigil-diag`, read access as DD-F-10 `permissions`) and `vigil-action` (owner of `/data/txlog_archive`), each with its own ed25519 key (DM-E13-R1). Neither user has `sudo`. The runner pins the host key given in the connector settings (AR-CN-03) and refuses any other. | SL-B-05; PM-M05-02 |

## 10. Connectors (resolves DM-O-04, DM-O-12, PM-O-09, PM-O-10)

### 10.1 Rules for every connector

| ID | Rule | Trace |
| --- | --- | --- |
| AR-CN-00a | Every connector kind of DM-E-09 is one class in `vigil-connectors` implementing its connector type interface (PM-M04-01). Its `settings_schema` is the settings table in Section 10.2; `settings` MUST NOT contain secret values, and MAY contain credential reference identifiers. | SL-B-07; DM-O-12 |
| AR-CN-00b | HTTP connectors use HTTPS with certificate verification always on. The only plain-HTTP address allowed is a loopback address (`127.0.0.1`) on the same host. | SL-B-14 |
| AR-CN-00c | A connector call that cannot connect, times out, or fails on name resolution, TLS or a proxy is reported as `unreachable`; an error answer from the external system is reported as `provider_error` (AI) or `failed` with the error text (other kinds). | DM-R-69 |

### 10.2 Connector contracts (complete list)

| ID | Kind | Protocol and calls | `settings` (complete list) | Credential |
| --- | --- | --- | --- | --- |
| AR-CN-01 | `zabbix` (PM-K-01) | **Inbound:** webhooks as AR-CN-01a. **Connection test (PM-M04-04):** JSON-RPC 2.0 `POST {address}{api_path}`: first `apiinfo.version` without authentication, then `host.get` with `{"output": ["hostid"], "limit": 1}` and header `Authorization: Bearer <API token>`; passes when both return a `result`. Fail reasons: "unreachable", "TLS error", "authentication failed", "unexpected response". | `api_path` (Text, default `/api_jsonrpc.php`); `request_timeout_s` (Duration, 10). | `external_api`: Zabbix API token. |
| AR-CN-02 | `alertmanager` (PM-K-02) | **Inbound:** webhooks as AR-CN-02a. **Connection test:** `GET {address}/api/v2/status` with basic authentication; passes on HTTP 200 with a JSON body. Fail reasons as AR-CN-01. | `request_timeout_s` (10). | `external_api`: `username:password` (basic authentication applied by Caddy on `lab-01` in front of Alertmanager). |
| AR-CN-03 | `linux_server` (PM-K-03, implemented by `vigil-runner`) | SSH exec channel to `{address}:{port}`; the command is `bash --noprofile --norc -c '<command>'` with the filled command template quoted for POSIX shells; every parameter value is inserted as one single-quoted word. stdout is the command output (DM-E-14 `output_text`, first 65,536 characters, DM-C-11); stderr is discarded except its first 1,000 characters in `error` on failure. The operation `timeout` closes the channel and gives `unknown` (action) or `failed` (diagnostic) (DM-E-12). | `port` (Integer, 22); `host_key` (Text: the server's public host key line, Example: `ssh-ed25519 AAAA…`); `connect_timeout_s` (10). | Per target access grant (DM-E-13): `target_diagnostic` or `target_action` (AR-SEC-06). |
| AR-CN-04 | `jira_cloud` (PM-K-04) | REST API v3 on `{address}` (`https://<site>.atlassian.net`), basic authentication. Create: `POST /rest/api/3/issue` with `fields`: `project {key}`, `issuetype {name}`, `summary` (DC-OUT-01 title, cut to 255 characters with "…"), `description` (ADF, AR-CN-04a), `labels` [the incident label]. Comment: `POST /rest/api/3/issue/{key}/comment` with `body` in ADF. Transition: `POST /rest/api/3/issue/{key}/transitions` with `{"transition": {"id": "<id>"}}`. Status read: `GET /rest/api/3/issue/{key}?fields=status`. Find by label after a restart (DM-E76-R2): `POST /rest/api/3/search/jql` with `jql` `project = "<project_key>" AND labels = "<label>"`, `fields` `["status"]`, `maxResults` 2. Incident label (DM-E76-R2, DC-OUT-01): `vigil-incident-<incident id>` (the `Id` in lowercase canonical text). | `project_key` (Text); `issue_type` (Text, Example "Task"); `resolve_transition_id` (Text); `reopen_transition_id` (Text); `resolved_status_name` (Text); `open_status_name` (Text); `request_timeout_s` (15). | `external_api`: `email:api_token` of the Vigil service account. |
| AR-CN-05 | `ms_teams` (PM-K-05) | One Microsoft Teams Workflows webhook per destination channel (Workflows template that posts to a channel when a webhook request is received). Send: `POST <Workflows URL>` with `Content-Type: application/json` and the message of AR-CN-05a. Delivered when the answer is HTTP 2xx. | `destinations`: list of `{address: Text(500), credential_ref_id: Ref<Credential reference>}`, one per Teams delivery address (DM-E-77); `request_timeout_s` (15). The instance `address` is `https://teams.microsoft.com` (informational). | Per destination: `external_api` holding that channel's Workflows URL (AR-SEC-06). The instance's own `credential_ref_id` is empty. |
| AR-CN-06 | `email` (PM-K-06) | SMTP submission with STARTTLS and SMTP AUTH. One message per recipient address: `From` = `from_name <from_address>`, `To` = the delivery address, `Subject` = line 1 of the DC-OUT item, body `text/plain; charset=UTF-8` = every line of the item joined by CRLF. No HTML part (DC-OUT-00: no text read as markup). | `port` (Integer, 587); `from_address` (Text); `from_name` (Text, "Vigil"); `connect_timeout_s` (10). The instance `address` is the SMTP host name. | `smtp` (AR-SEC-06). |
| AR-CN-07 | `claude` (PM-K-07) | Messages API: `POST https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version: 2023-06-01`; the output constrained to the task's JSON schema by the API's structured-output feature, or, where a model does not offer it, by one forced tool call. Units from `usage.input_tokens` and `usage.output_tokens`. | `connect_timeout_s` (5); `request_timeout_s` per task (AR-AI-06); `max_output_tokens` per task (AR-AI-06). Model name is in the provider configuration (DM-E-70). | `external_api`: API key. |
| AR-CN-08 | `openai` (PM-K-08) | Responses API: `POST https://api.openai.com/v1/responses` with `text.format` of type `json_schema` and `strict: true`; units from `usage.input_tokens` and `usage.output_tokens`. Embeddings (SL-T-3 alternative): `POST https://api.openai.com/v1/embeddings`, one input per request. | As AR-CN-07. | `external_api`: API key. |
| AR-CN-09 | `local_model` (PM-K-09) | Ollama native API on `gpu-01`: `POST {address}/api/chat` with `stream: false`, `format` = the task's JSON schema, `options.temperature` 0, `keep_alive` "-1"; units from `prompt_eval_count` and `eval_count` (DM-ASM-06 holds). `{address}` is `https://llm.<demo-domain>`; Caddy on `gpu-01` checks the bearer token and passes the request to Ollama on its loopback address. | `connect_timeout_s` (5); `request_timeout_s` and `max_output_tokens` per task (AR-AI-06). | `external_api`: bearer token of `gpu-01` (AR-A-10). |
| AR-CN-10 | `local_embedding` (PM-K-10) | Ollama native API on `vigil-app-01`: `POST http://127.0.0.1:11434/api/embed` with `model` and one input string per request; the vector is `embeddings[0]`. Units from `prompt_eval_count`, output units 0. | `connect_timeout_s` (5); `request_timeout_s` (10). | None. |

### 10.3 Monitoring webhook contracts and normalisation (resolves DM-O-04, PM-O-09)

| ID | Item | Content | Trace |
| --- | --- | --- | --- |
| AR-CN-01a | Zabbix webhook body | Sent by the Vigil media type for Zabbix (a webhook media type whose script is shipped with the Zabbix connector and installed in the customer's Zabbix; in the demo by `demo-monitoring`). One call per problem event and one per recovery event. `POST https://{host}/api/v1/webhooks/{source_key}`, header `Authorization: Bearer {token}`, body a JSON object with exactly these fields, each filled from the named macro: `event_id` {EVENT.ID}; `event_value` {EVENT.VALUE} ("1" problem, "0" resolved); `host` {HOST.HOST}; `trigger_name` {TRIGGER.NAME}; `event_name` {EVENT.NAME}; `severity` {EVENT.SEVERITY}; `tags` {EVENT.TAGSJSON}; `event_time` "{EVENT.DATE} {EVENT.TIME}"; `recovery_time` "{EVENT.RECOVERY.DATE} {EVENT.RECOVERY.TIME}" (empty in a problem message). The Zabbix server runs in UTC, so both times are UTC in the form `yyyy.MM.dd HH:mm:ss` (AR-ASM-11). | PM-K-01; DM-ASM-01, DM-ASM-02 |
| AR-CN-01b | Zabbix normalisation | One alert entry per call (`entry_index` 0). `alert_key` = `event_id` (in a recovery message {EVENT.ID} is the original problem event, AR-ASM-10). `reported_status` = `firing` when `event_value` is "1", `resolved` when "0"; any other value fails normalisation. Identity pairs, in this fixed order (SB-V-26): `tag.component` (the value of the first tag named `component`, omitted when there is none), then `host`. `title` = `event_name`, or `trigger_name` when `event_name` is empty. `source_time` = `event_time` (firing) or `recovery_time` (resolved). `source_severity` = `severity`. Fields usable in alert type mappings (DM-E-17 `field`, complete list): `trigger_name`, `event_name`, `host`, `severity`, `tag.<tag name>`. | DM-R-04, DM-E-17; DM-O-04 |
| AR-CN-02a | Alertmanager webhook body | Alertmanager's standard webhook payload (version "4"): `POST https://{host}/api/v1/webhooks/{source_key}` with header `Authorization: Bearer {token}` (Alertmanager `http_config.authorization`, type Bearer, credentials from a file). Each element of `alerts` is one alert entry; `entry_index` is its position in the array. | PM-K-02; SL-ASM-01 |
| AR-CN-02b | Alertmanager normalisation | `alert_key` = the entry's `fingerprint` (the same for firing and resolved notifications of one label set, AR-ASM-02). `reported_status` = the entry's `status` (`firing` or `resolved`). Identity pairs, in this fixed order (SB-V-26): `label.component`, then `label.instance` (each omitted when the label is absent). `title` = `annotations.summary`, or `labels.alertname` when there is no summary. `source_time` = `startsAt` (firing) or `endsAt` (resolved), RFC 3339. `source_severity` = `labels.severity`. Fields usable in alert type mappings (complete list): `label.<label name>`, `annotation.<annotation name>`. | DM-R-04, DM-E-17; DM-O-04 |
| AR-CN-09a | Resolve reading (closes PM-O-09) | Both monitoring connectors read resolution only from resolve notifications (Zabbix recovery operation; Alertmanager `send_resolved: true`). No connector queries a source for alert states (DM-D-11). | PM-O-09; DM-R-35, PM-ASM-01 |
| AR-CN-09b | Webhook answers (complete list) | 202 when the call is stored (also when `parse_outcome = failed`, DM-E-18); 401 when the token does not match (not stored); 404 when no active source of the bound tenant has the `source_key`, or the host has no binding; 413 when the body is larger than 5 MiB (not stored); 415 when the content type is not `application/json`. The answer is sent after the webhook call, its raw alerts, normalised alerts and alert changes are committed (DM-R-03); grouping runs afterwards from the "alert received" event. | DM-E-18, DM-R-03 |

### 10.4 Outbound formats (resolves DC-ASM-01 format choice, DC-ASM-02 format choice)

| ID | Item | Content | Trace |
| --- | --- | --- | --- |
| AR-CN-04a | Jira description and comments (ADF) | The DC-OUT lines become one ADF document `{"version": 1, "type": "doc", "content": [...]}`: each run of consecutive lines starting with "- " becomes one `bulletList` with one `listItem` per line, each holding one `paragraph` with the text after "- "; every other line becomes one `paragraph`. Text is placed in `text` nodes exactly as composed; no text is parsed as markup. The workspace link line "Incident in Vigil: {link}" is a paragraph whose link part is a `text` node with a `link` mark whose `href` is the link. An empty line is left out (ADF text nodes cannot be empty). | DC-OUT-01, DC-OUT-02; DC-ASM-01 |
| AR-CN-05a | Teams message | `{"type": "message", "attachments": [{"contentType": "application/vnd.microsoft.card.adaptive", "contentUrl": null, "content": {"type": "AdaptiveCard", "$schema": "http://adaptivecards.io/schemas/adaptive-card.json", "version": "1.4", "body": [...]}}]}`. Each DC-OUT line becomes one `RichTextBlock` containing one `TextRun` (plain text; TextRun does not read Markdown); line 1 is a TextRun with `weight: "Bolder"`; a "- " line becomes a TextRun with the text "• " followed by the text after "- "; the link line ("Acknowledge: {link}" or "Review and decide: {link}") is a TextRun of the label followed by a TextRun whose text is the full link and whose `selectAction` is `Action.OpenUrl` with that link. Whether the operator can copy the link from this card (SB-SET-04) is proved by spike AR-SPK-03; if it fails, the link line becomes a `TextBlock` with `wrap: true` whose text is the link alone, every Markdown character in it escaped. | DC-OUT-03 to DC-OUT-05; DC-ASM-02, SB-SET-04 |
| AR-CN-06a | Email body | As AR-CN-06; links stay as plain text addresses on their own line, which mail clients show as links. | DC-OUT-03 to DC-OUT-05 |

### 10.5 Teams delivery addresses (resolves PM-O-10)

The Teams delivery address (DM-E-77 `address`) is the display name of one channel destination in the Teams connector instance's `destinations` setting, written "{Team name} / {channel name}" (Example: "Vigil Demo / NOC L1", DD-F-07). Each destination has its own Workflows webhook URL, kept as a secret (AR-CN-05). A delivery address whose `address` is not in `destinations` makes the send `failed` with the reason "unknown Teams destination". Posting to a private channel is not used (AR-ASM-21).

## 11. AI layer (resolves SL-O-20; closes SB-O-02; CTX-O-04 proposal)

| ID | Rule | Trace |
| --- | --- | --- |
| AR-AI-01 | **Gateway requests (complete list).** `vigil-ai-gateway` offers two requests. (1) Run task — inputs: task (SL-T-1 or SL-T-2), tenant, incident, `request_group_id`, the instruction text, the request parts each with its data category (DM-R-43), the output JSON schema; output: the outcome, the provider and model used, the raw output, and the usage records written. (2) Embed — inputs: provider key (named by `vigil-knowledge`, DM-E72-R2), `request_group_id`, incident (when any), data categories, one text; output: the vector or the failure outcome. No other module calls an AI provider (PM-R-04). | SL-B-08; PM-I-31, PM-I-35 |
| AR-AI-02 | **Order of attempts.** For Run task, the gateway takes the task assignment's `provider_keys` in order (DM-E-72). For each provider: if it is not of an allowed kind for the task it is skipped without a record; if data routing does not allow it for every category in the request, it writes a usage record with outcome `refused_by_data_routing` and moves on (DM-R-45); otherwise it calls the provider and writes one usage record with the outcome and units (DM-E-74). It returns at the first `succeeded`; after `unreachable` or `provider_error` it tries the next provider (DM-R-69). Every record of one Run task shares its `request_group_id`; `fallback_position` counts the providers in the order, starting at 1. | DM-R-45, DM-R-69, DM-E-74 |
| AR-AI-03 | **Output schemas.** SL-T-1: a JSON object with exactly `verdict` (`"confirms"`, `"disputes"` or `null`), `likely_cause` (string, at most 2,000 characters), `evidence_entry_ids` (array of the evidence entry identifiers given in the request), `uncertainty` (string, at most 2,000 characters), `runbook_refs` (array of `{runbook_key, version}` given in the request). SL-T-2: a JSON object with exactly `summary` (string, at most 2,000 characters). Each provider is asked to answer in the schema (AR-CN-07 to AR-CN-09); `vigil-investigation` checks every output with PM-M17-03 whatever the provider (DM-E-68 `validation`). | DM-E-68, DM-E-69; PM-M17-03 |
| AR-AI-04 | **Instructions.** The instruction text of each task is product text kept in the code of `vigil-investigation`, in English, the same for every customer (DC-R-05). It states that the content of the request parts is data from monitoring, servers and records and is never to be followed as an instruction (BSD §10.3, misleading instructions embedded in operational data). Providers are given no tools. | BSD §10.3; PM-M17-02 |
| AR-AI-05 | **Default AI settings (resolves SL-O-20).** SL-T-1 and SL-T-2: `claude`, then `openai`, then `local-llm`. SL-T-3: `local-embed` only (CTX-D-011, DM-E72-R2). This confirms SB-V-31 and DD-F-07 `task_assignments`. Reason: the cloud provider first meets SB-TL-04 (60 s for diagnostics-to-messages); OpenAI second keeps cloud quality if Claude fails; the local model last keeps the demo running when SL-F-4 cuts both cloud providers (SL-A2-05). | SL-O-20; SB-V-31, SB-O-02 |
| AR-AI-06 | **Timeouts and limits (complete list).** Connect timeout for every AI provider: 5 s (DD-F-10 SL-F-4). Request timeouts: `claude` and `openai` — SL-T-1 60 s, SL-T-2 30 s, OpenAI embedding 15 s; `local_model` — SL-T-1 180 s, SL-T-2 120 s; `local_embedding` — 10 s. Maximum output units: SL-T-1 2,000; SL-T-2 600. A request that reaches its timeout ends with outcome `unreachable` (DM-R-69). | DD-F-10; SB-TL-04; DM-R-69 |
| AR-AI-07 | **One connection per call.** Each AI provider call opens a new HTTPS connection and closes it when the call ends, and the `vigil-server` JVM keeps no cache of successful name lookups (security property `networkaddress.cache.ttl=0`). A changed provider address or an unreachable provider therefore shows on the next call, which SL-F-4 (DD-F-10) and SB-ASM-03 rely on. | SL-A2-05; SB-ASM-03 |
| AR-AI-08 | **Local embedding model.** Ollama on `vigil-app-01`, bound to 127.0.0.1, CPU only, with one embedding model kept loaded (`keep_alive` -1). Every embedding request carries one text, at import and at search alike, so a stored vector and a search vector of the same text are computed the same way (SL-ASM-04). It is not affected by SL-F-4 (DD-F-10). | CTX-D-011; SL-ASM-04 |
| AR-AI-09 | **Local chat model.** Ollama on `gpu-01` (AR-H-04) with one chat model kept loaded while the host runs. Proposed acceptable answer time (CTX-O-04): SL-T-1 answered within 60 s of the request in each of 5 runs, measured by SB-T-04; the request timeout of 180 s (AR-AI-06) is the hard limit. `gpu-01` is started before each rehearsal and presentation and stopped afterwards (AR-H-04). When it is stopped, the local model is unreachable; this affects only SB-D05 and SB-D06. | CTX-O-04; SL-X-09; SB-D05, SB-D06 |
| AR-AI-10 | **Similarity search.** `vigil-knowledge` embeds the search text (DM-R-51) through Embed and compares it with the stored past-incident vectors as AR-DS-07 states. Past-incident vectors are computed during the knowledge part of the seed import (AR-SB-04). | SL-T-3; DM-R-51, DM-R-56 |
| AR-AI-11 | **Model choice.** The model names and prices of the provider configurations (DM-E-70) are open item AR-O-01. Requirements for the choice (complete list): `claude` — a current Claude model of the balanced speed and quality class that supports JSON-schema output; `openai` — a current OpenAI model of comparable class that supports strict JSON-schema output; `local-llm` — an open-weight model under a licence that allows commercial use, that fits with its context in the GPU memory of `gpu-01`, and that meets AR-AI-09 and the SB-S04 acceptance criteria in AR-SPK-06; `local-embed` — an open-weight embedding model under a licence that allows commercial use that passes SB-T-03 and AR-SPK-07. Prices are taken from the providers' official price pages on the day the values are written (USD per million units); `local-llm` and `local-embed` cost 0 (DD-F-07). | DM-O-13; CTX-O-02 |
| AR-AI-12 | **Cost.** The cost of a usage record is `(input_units × price_input_per_million + output_units × price_output_per_million) ÷ 1,000,000`, rounded half-up to 6 decimal places (DM-E-74 `Decimal(12,6)`). | DM-E-74; SL-A2-02 |

## 12. Reset and tenant binding (resolves SL-O-17, DC-O-03, DM-R-56 (8))

The reset mechanism recommended by SL-O-17 is adopted: every reset creates a new tenant, seeded from the starting state. A tenant binding decides which tenant a host name serves, so webhooks and logins reach the new tenant without changing Zabbix, Alertmanager or any address.

| ID | Rule | Trace |
| --- | --- | --- |
| AR-RS-01 | **Tenant binding.** `vigil-tenancy` keeps tenant bindings (DM-E-83, AR-A-03): each binding names one host name and one tenant and has a revision number per host; the current binding of a host is its highest revision. A new binding is a new record; no binding record is changed. | SL-A3-03; PM-M01-03 |
| AR-RS-02 | **Host decides the tenant.** `vigil-api` handles every user request and every webhook call in the tenant of the current binding of the request's host name (the HTTP `Host` header, as passed by Caddy). A host with no binding is answered 404. Installation operations (AR-OP-60 to AR-OP-64) do not depend on the host. | PM-R-05; SL-B-01 |
| AR-RS-03 | **Sessions follow the binding.** A request whose session belongs to a user of a tenant other than the host's bound tenant is refused with 401 and error code `session_ended` (AR-API-04); `vigil-web` then shows the login screen with "Your session has ended. Log in again." (DC-UI-08). | SL-B-13; DC-UI-08 |
| AR-RS-04 | **Dormant tenants.** A tenant in state `ready` that is not the tenant of any current binding is dormant. For a dormant tenant (complete list): no scheduled job of AR-PL-09 runs, so its timers, escalations, stability checks, closure checks and health checks stop; an event of the tenant is completed by each listener without effect; no request reaches it, because no host routes to it (AR-RS-02). Its records stay unchanged. In the build a tenant that has been bound and replaced is never bound again (AR-RS-06); viewing or reactivating it is Later (AR-L-01). | SL-A3-03; SL-B-10 |
| AR-RS-05 | **Binding change.** When a binding is created, `vigil-tenancy` publishes "tenant binding changed" (PM-EV-15, AR-A-02) with the host, the earlier tenant and the new tenant. `vigil-api` reacts by sending the change notice `session_ended` to every stream of the earlier tenant and closing them; `vigil-intake` reacts by running the connection test of every active monitoring source of the new tenant at once (DM-R-39). | DM-R-39; DC-O-03 |
| AR-RS-06 | **Binding guard.** A binding MUST be refused unless the tenant is in state `ready` and has never been the tenant of a binding before. | AR-RS-04 |
| AR-RS-07 | **Reset sequence (complete list of steps, run by `demo-control` through installation operations only).** (1) Start "Clear all faults" (DD-F-10 `clear_completion`). (2) At the same time: create a tenant named `jazz-demo-{yyyyMMdd-HHmmss}` in UTC (AR-OP-60); upload the seed bundle into it (AR-OP-61); read the import state every 1 s until it is `succeeded` or `failed` (AR-OP-62). (3) When both step 1 and step 2 have completed: bind host `vigil.<demo-domain>` to the new tenant (AR-OP-63). (4) Read the tenant status every 1 s (AR-OP-64) until every active monitoring source is `healthy`. The reset is complete after step 4; it is reported as failed when any step fails or 60 s have passed since step 1 started (DC-W-29). | SL-A3-03, SL-O-17; DM-R-24 (d) |
| AR-RS-08 | **Time budget (targets, proved by SB-T-05 and AR-SPK-10).** Step 1: at most 55 s (SB-T-05). Step 2: at most 20 s, of which embedding the 50 past incidents is at most 5 s (AR-AI-08). Step 3: at most 1 s. Step 4: at most 3 s. Steps 1 and 2 run in parallel, so the reset completes within 59 s in the worst case and within about 20 s when no fault was active. | SB-T-05 |
| AR-RS-09 | **Webhooks during a reset.** Resolve notifications caused by step 1 reach the earlier tenant, which is still bound until step 3; the new tenant receives no lab alert before it is bound, so it starts with no open incident (starting state). | DM-R-24 (d) |
| AR-RS-10 | **Earlier runs are kept (SL-A3-03).** A reset creates records only in the new tenant and one new binding record. It changes and deletes no record of an earlier tenant. | SL-A3-03; DM-C-04 |
| AR-RS-11 | **Windows after a reset (resolves DC-O-03).** Every Vigil window, the main window and the phone window, shows the login screen at its next request with "Your session has ended. Log in again."; an open main window receives `session_ended` within 1 s of step 3 and goes to the login screen. Before login, the login screen shows the banner, customer name and logo of the host's bound tenant (AR-OP-03). The operator logs in again, as SB-SET-07 already requires. | DC-O-03; SB-SET-07 |
| AR-RS-12 | **First tenant.** A new installation has no binding; its first reset creates and binds the first tenant. | AR-RS-02 |
| AR-RS-13 | **Seed embeddings (resolves DM-R-56 (8)).** The past-incident embeddings are computed during the import of the knowledge part, not supplied in the bundle (AR-SB-04). | DM-R-56 |

## 13. Seed bundle and file formats (resolves SL-O-11 format, DD-O-01)

| ID | Rule | Trace |
| --- | --- | --- |
| AR-SB-01 | **Bundle format.** A seed bundle is one ZIP file of at most 10 MiB containing `manifest.yaml` and the part files. `manifest.yaml` holds `bundle_name`, `bundle_version` and `parts`, a list in the order of DM-R-56 of `{module: <working module name>, files: [<file names>]}`. Part files are YAML 1.2 in UTF-8, using the field names of the domain model with the conventions of DD-R-07; the `vigil-knowledge` part is `knowledge.yaml` (knowledge settings), `runbooks.md` (DD-R-09 convention) and `past-incidents.csv` (RFC 4180, UTF-8, the columns of DD-F-09). | DM-R-56, DD-R-07, DD-R-09; DD-R-03 |
| AR-SB-02 | **Service map file format (resolves SL-O-11).** A service map file is YAML 1.2 in UTF-8, at most 5 MiB, whose top level is a mapping with exactly the keys `services`, `components`, `identity_keys` and `dependencies` (DM-R-54). Each record is either a mapping with the DM-R-54 field names or a sequence of the DM-R-54 fields in their listed order (as DD-F-02 writes `identity_keys` and `dependencies`). The same format is used for seed part 5 and for the import on DC-S07-A3. A file that is not valid YAML of this shape is refused with "The file could not be read: {reason}" (DC-W-21). | SL-O-11, SL-A2-01; DM-R-54, DM-R-55 |
| AR-SB-03 | **Relative times (resolves DD-O-01).** The `vigil-knowledge` import sets, for each past incident, `opened_at` = import start time − `opened_minutes_before_import` minutes, `acknowledged_at` = `opened_at` + `acknowledged_after_minutes` minutes and `recovered_at` = `opened_at` + `recovered_after_minutes` minutes. The import start time is the `created_at` of the seed import record (DM-E-82). | DD-R-04; DD-O-01 |
| AR-SB-04 | **Embeddings at import.** During the knowledge part, `vigil-knowledge` embeds the search text (DM-R-51) of every active past incident through the gateway (AR-AI-01, Embed) with `embedding_provider_key`, one request per past incident, data category `incident_history`. If any request fails, the part fails, and the import and the tenant become `failed` (DM-E82-R1). | DM-R-56 (8), DM-E82-R1 |
| AR-SB-05 | **Secrets in a bundle.** A bundle holds locators only (AR-SEC-05). The identity part holds no passwords; they are read from `file:user-password-<username>` (AR-SEC-02). | SL-B-14; PM-M02-02 |
| AR-SB-06 | **Bundle builder.** A `demo-data` script builds the ZIP from the content of record in `docs/31-demo-data/` and a deployment values file (non-secret: demo domain, Jira site address and transition identifiers, SMTP host, the SSH host key of `lab-db-01`). It MUST run `tools/check_data.py` first and stop when a check fails (DD-R-06). It copies the content unchanged except for replacing `<demo-domain>` and deployment values, and leaves out every demo-only section (DD-F-03 `lab_alert_catalogue`, DD-F-10). The ZIP is built at deployment and is not committed. | DD-R-03, DD-R-06; PM-DC-05 |
| AR-SB-07 | **Upload.** The bundle is uploaded with AR-OP-61 as the request body (`Content-Type: application/zip`). `vigil-setup` imports it part by part through each module's import interface (PM-D-09). | PM-M22-03 |

## 14. Public interface of `vigil-api` (resolves DC-O-04; defines the operations named in PM-I-04)

### 14.1 General rules

| ID | Rule | Trace |
| --- | --- | --- |
| AR-API-01 | **Addresses and format.** Every operation is served at `https://{host}/api/v1/…` and exchanges JSON in UTF-8, except the file uploads and the logo named below. Field names are the domain-model field names (lowercase with underscores). Timestamps are RFC 3339 in UTC with milliseconds (Example: `2026-10-09T14:05:32.120Z`); identifiers are lowercase canonical UUID text. | PM-M23 |
| AR-API-02 | **Callers (complete list).** Users, by the `vigil_session` cookie (AR-SEC-03); monitoring sources, by their bearer token (AR-SEC-08); `demo-control`, by the installation token (AR-SEC-07). `vigil-api` authenticates every user call through `vigil-identity` and checks the operation's permission before passing it on (PM-M23-02, PM-R-09). | PM-M23-01 to PM-M23-03 |
| AR-API-03 | **Interface definition.** The public interface definition owned by `vigil-api` (PM-M23) is an OpenAPI 3.1 document generated from the server code at build time and kept in the repository; the TypeScript types of `vigil-web` are generated from it (AR-STK-06). It lists exactly the operations of Section 14.2. A documented interface for customers and partners stays Later (PM-M23-L1). | PM-M23; PM-M23-L1 |
| AR-API-04 | **Errors.** An error answer has the body `{"error": {"code": "<code>", "message": "<English text>", "details": <object or null>}}`. Status and codes (complete list): 400 `invalid_request` (malformed body or parameter); 401 `not_logged_in`, `session_ended`, `login_failed`, `invalid_token`; 403 `forbidden` (permission missing), `origin_refused` (AR-SEC-04); 404 `not_found`; 409 `refused`, with `details.reason` holding the refusal reason key of the owning module (the reasons behind DC-W-13, DC-W-14 and the refusals listed in DC-W-31, and `draft_changed_elsewhere`), or `validation_failed`, with `details.violations` holding the DM-E48-R1 violations (rule, step, branch, parameter); 413 `too_large`; 415 `unsupported_media_type`. `vigil-web` composes the displayed texts from the reason keys (DC-W-13, DC-W-14, DC-W-18, DC-W-31). | DC-UI-08; DC-W-13, DC-W-14 |
| AR-API-05 | **Paging.** Operations that a screen reads "50 at a time" or "200 at a time" take `limit` and `after` (the cursor returned with the previous page) and return `items` and `next` (empty on the last page). Other lists are returned whole. | DC-S09-R5, DC-S11-R2 |
| AR-API-06 | **Composition.** Read operations that serve one screen region MAY combine data from several modules; `vigil-api` requests each part from its owning module (PM-I-04) and returns the fields the region shows (Section 6 of `40-demo-cut`). | PM-I-04; DC-R-01 |
| AR-API-07 | **Long work.** Operations that start long work answer 202 at once with the identifier to read its state: re-run AI investigation, test replay, seed import. | DC-S03-A1, DC-S06-A9 |

### 14.2 Operations (complete list)

"Module" names the owning module(s) whose requests serve the operation. "Serves" names the screen part or caller. Request and response fields are the fields of the named regions and entities; Round 6 writes them into the OpenAPI document (AR-API-03).

| ID | Method and path | Permission | Module | Serves |
| --- | --- | --- | --- | --- |
| AR-OP-01 | `POST /api/v1/session` — body `{username, password}`; sets the session cookie; 204 | None | `vigil-identity` | DC-S01-A1 |
| AR-OP-02 | `DELETE /api/v1/session` — ends the session; 204 | Logged in | `vigil-identity` | DC-S04-A2, DC-S05-A3, main-screen "Log out" |
| AR-OP-03 | `GET /api/v1/frame` — tenant settings of the bound tenant (`customer_name`, `environment_label`, `has_logo`) and, when logged in, the user (`username`, `display_name`, permissions, team keys) | None | `vigil-tenancy`, `vigil-identity` | DC-UI-02, DC-UI-04, DC-S01 |
| AR-OP-04 | `GET /api/v1/frame/logo` — the logo bytes with their media type, or 404 | None | `vigil-tenancy` | DC-UI-02, DC-UI-04 |
| AR-OP-05 | `GET /api/v1/stream` — Server-Sent Events stream of change notices (AR-API-08) | `view` | `vigil-api` | DC-UI-09, DC-UI-08 |
| AR-OP-10 | `GET /api/v1/incidents` — forming rows and incident rows | `view` | `vigil-incidents`, `vigil-engine` (stage) | DC-S02-R1, R2 |
| AR-OP-11 | `GET /api/v1/past-incidents` | `view` | `vigil-knowledge`, `vigil-servicemap`, `vigil-actions` (names) | DC-S02-R3 |
| AR-OP-12 | `GET /api/v1/incidents/{incident_id}` — forming or merged text, header, impact and assignment, summary, evidence, timeline | `view` | `vigil-incidents`, `vigil-routing`, `vigil-investigation`, `vigil-ticketing`, `vigil-servicemap`, `vigil-engine` (stage) | DC-S03-R0, R1, R2, R4, R8 |
| AR-OP-13 | `GET /api/v1/incidents/{incident_id}/alerts` | `view` | `vigil-incidents`, `vigil-intake`, `vigil-correlation`, `vigil-servicemap` | DC-S03-R3 |
| AR-OP-14 | `GET /api/v1/incidents/{incident_id}/ai` — latest accepted result, AI status, providers tried | `view` | `vigil-investigation`, `vigil-ai-gateway`, `vigil-knowledge` | DC-S03-R5 |
| AR-OP-15 | `GET /api/v1/incidents/{incident_id}/response` — similarity result, proposal, approvals, executions | `view` | `vigil-knowledge`, `vigil-actions` | DC-S03-R6 |
| AR-OP-16 | `GET /api/v1/incidents/{incident_id}/recovery` — verifications of the latest run, current source health | `view` | `vigil-recovery`, `vigil-intake` | DC-S03-R7 |
| AR-OP-17 | `GET /api/v1/incidents/{incident_id}/run` — latest live run: content, version number, state or end reason, step executions | `view` | `vigil-engine` | DC-S03-R9 |
| AR-OP-18 | `POST /api/v1/incidents/{incident_id}/ai-reruns` — 202 | `rerun_ai_investigation` | `vigil-investigation` | DC-S03-A1 |
| AR-OP-19 | `GET /api/v1/incidents/{incident_id}/acknowledge-page` | `acknowledge` or `view` | `vigil-incidents`, `vigil-investigation`, `vigil-servicemap` | DC-S04-R2, R3 |
| AR-OP-20 | `POST /api/v1/incidents/{incident_id}/acknowledgements` — 201, or 409 with the DM-E35-R1 reason | `acknowledge` | `vigil-incidents` | DC-S04-A1 |
| AR-OP-21 | `GET /api/v1/approval-requests/{approval_request_id}` — the stored snapshot, state, decisions, incident number and title | `approve_action` or `view` | `vigil-actions`, `vigil-incidents` | DC-S05-R2 to R6 |
| AR-OP-22 | `POST /api/v1/approval-requests/{approval_request_id}/decisions` — body `{decision: "approve" or "reject", snapshot_hash}`; 201, or 409 with the DM-E59-R1 reason | `approve_action` | `vigil-actions` | DC-S05-A1, A2 |
| AR-OP-30 | `GET /api/v1/workflow` — workflow name, active version (number and content), open draft (revision, base version, content, save time) | `view` | `vigil-workflows` | DC-S06-R1, R3 |
| AR-OP-31 | `GET /api/v1/workflow/step-types` — the step type catalogue (DM-E-44) | `view` | `vigil-workflows` | DC-S06-R4, R5 |
| AR-OP-32 | `GET /api/v1/workflow/versions` | `view` | `vigil-workflows` | DC-S06-R7 |
| AR-OP-33 | `GET /api/v1/workflow/choices` — active teams, active diagnostic definitions, services and components of the current service map, alert type definitions | `view` | `vigil-identity`, `vigil-diagnostics`, `vigil-servicemap`, `vigil-intake` | DC-S06-R5, DC-W-16 |
| AR-OP-34 | `POST /api/v1/workflow/draft` — start or continue editing; returns the draft | `edit_workflow` | `vigil-workflows` | DC-S06-A1 |
| AR-OP-35 | `PUT /api/v1/workflow/draft` — body `{base_revision, content}`; saves the next revision; 409 `draft_changed_elsewhere` when `base_revision` is not the latest | `edit_workflow` | `vigil-workflows` | DC-S06-R9 |
| AR-OP-36 | `POST /api/v1/workflow/draft/publish` — body `{revision}`; the new version, or 409 `validation_failed` | `publish_workflow` | `vigil-workflows` | DC-S06-A8 |
| AR-OP-37 | `POST /api/v1/workflow/versions/{version}/rollback` | `publish_workflow` | `vigil-workflows` | DC-S06-A10 |
| AR-OP-38 | `GET /api/v1/test-replays/sources` — incidents that qualify (DM-R-68) | `run_test_replay` | `vigil-workflows` | DC-S06-A9 |
| AR-OP-39 | `POST /api/v1/test-replays` — body `{source_incident_id, draft_revision}` or `{source_incident_id, version}`; 202 with the test replay identifier, or 409 `validation_failed` | `run_test_replay` | `vigil-workflows` | DC-S06-A9 |
| AR-OP-40 | `GET /api/v1/test-replays/{test_replay_id}` | `run_test_replay` | `vigil-workflows` | DC-S06-R6 |
| AR-OP-45 | `GET /api/v1/setup/sources` — with each source's webhook address (AR-API-09) | `view` | `vigil-setup` | DC-S07-R1 |
| AR-OP-46 | `POST /api/v1/setup/sources/{source_id}/connection-tests` | `manage_setup` | `vigil-setup` | DC-S07-A1 |
| AR-OP-47 | `PUT /api/v1/setup/sources/{source_id}/address` — body `{address}`; then runs the connection test | `manage_setup` | `vigil-setup` | DC-S07-A2 |
| AR-OP-48 | `GET /api/v1/setup/service-map` | `view` | `vigil-servicemap` | DC-S07-R2 |
| AR-OP-49 | `POST /api/v1/setup/service-map/imports` — body: the file (`Content-Type: application/yaml`), header `X-File-Name`; the DC-W-21 result | `manage_setup` | `vigil-setup`, `vigil-servicemap` | DC-S07-A3 |
| AR-OP-50 | `GET /api/v1/setup/readiness-gaps` | `view` | `vigil-setup` | DC-S07-R3 |
| AR-OP-52 | `GET /api/v1/routing/rule-set` — current revision, rules, active teams | `view` | `vigil-routing`, `vigil-identity` | DC-S08-R1, R2, A1 |
| AR-OP-53 | `PUT /api/v1/routing/rule-set` — body `{base_revision, rules}`; the new revision, or 409 with the DM-E41-R1 reason | `manage_routing` | `vigil-routing` | DC-S08-A2 |
| AR-OP-55 | `GET /api/v1/ai/settings` — task assignments, embedding state, allowed providers per task, active providers, data routing rules | `view` | `vigil-ai-gateway`, `vigil-knowledge` | DC-S09-R2, R3 |
| AR-OP-56 | `PUT /api/v1/ai/task-assignments/{task}` — body `{provider_keys}`; or 409 with the DM-E-72 reason | `manage_ai_settings` | `vigil-ai-gateway` | DC-S09-A1 |
| AR-OP-57 | `PUT /api/v1/ai/data-routing` — body `{rules: [{data_category, allowed_provider_keys}]}` | `manage_ai_settings` | `vigil-ai-gateway` | DC-S09-A2 |
| AR-OP-58 | `GET /api/v1/ai/usage-summary` — the two tables | `view` | `vigil-ai-gateway` | DC-S09-R4 |
| AR-OP-59 | `GET /api/v1/ai/usage-records` — filters `incident_id`, `request_group_id`; paged (AR-API-05) | `view` | `vigil-ai-gateway`, `vigil-incidents` (numbers) | DC-S09-R5 |
| AR-OP-60 | `POST /api/v1/installation/tenants` — body `{name}`; the tenant identifier and state | Installation token | `vigil-tenancy` | AR-RS-07 (2) |
| AR-OP-61 | `POST /api/v1/installation/tenants/{tenant_id}/seed-imports` — body: the bundle ZIP; 202 with the seed import identifier | Installation token | `vigil-setup` | AR-RS-07 (2) |
| AR-OP-62 | `GET /api/v1/installation/tenants/{tenant_id}/seed-imports/{seed_import_id}` — state and parts (DM-E-82) | Installation token | `vigil-setup` | AR-RS-07 (2) |
| AR-OP-63 | `PUT /api/v1/installation/bindings/{host}` — body `{tenant_id}`; the new binding, or 409 with reason `tenant_not_ready` or `tenant_bound_before` (AR-RS-06) | Installation token | `vigil-tenancy` | AR-RS-07 (3) |
| AR-OP-64 | `GET /api/v1/installation/tenants/{tenant_id}/status` — state, whether bound, and each active monitoring source's name and health | Installation token | `vigil-tenancy`, `vigil-intake` | AR-RS-07 (4) |
| AR-OP-65 | `GET /api/v1/dashboard` | `view` | `vigil-reporting` | DC-S10 |
| AR-OP-66 | `GET /api/v1/audit-events` — filters `incident_id`, `event_type` (repeatable); paged | `view` | `vigil-audit`, `vigil-incidents` (numbers) | DC-S11-R2 |
| AR-OP-67 | `GET /api/v1/audit-events/incident-choices` — numbered incidents for the filter | `view` | `vigil-incidents` | DC-S11-R1 |
| AR-OP-70 | `POST /api/v1/webhooks/{source_key}` — monitoring webhook (AR-CN-09b) | Source token | `vigil-intake` | SL-A1-01 |

### 14.3 Change notices on the stream (complete list)

| ID | Notice | Sent when | The browser reads again |
| --- | --- | --- | --- |
| AR-API-08a | `incident_changed {incident_id}` | "incident changed" (PM-EV-09) | AR-OP-10 when the list is open; AR-OP-12 to AR-OP-16 when that incident's workspace is open |
| AR-API-08b | `run_changed {incident_id, run_id}` | "run started, step changed, run finished" (PM-EV-11), live runs only | AR-OP-10 (stage) and AR-OP-17 when that workspace is open |
| AR-API-08c | `workflow_published {}` | "workflow version published" (PM-EV-13) | AR-OP-30 and AR-OP-32 when the editor is open |
| AR-API-08d | `tenant_settings_changed {}` | "tenant settings changed" (PM-EV-14) | AR-OP-03 |
| AR-API-08e | `session_ended {}` | "tenant binding changed" (PM-EV-15) for the earlier tenant, and logout of that session | Nothing; the browser goes to the login screen (AR-RS-11) |

When the stream breaks, `vigil-web` shows "Live updates paused — reconnecting…", reconnects every 2 s, and on success reads every open region again and removes the banner (DC-UI-08).

### 14.4 Page paths and link formats (resolves DC-O-04)

| ID | Page | Path |
| --- | --- | --- |
| AR-API-09a | Login (SL-A4-01) | `/login`, with `?next={path}` when redirected (DC-UI-08) |
| AR-API-09b | Incident list (SL-A4-02) | `/incidents`; past incidents tab `/incidents?tab=past` |
| AR-API-09c | Incident workspace (SL-A4-03) | `/incidents/{incident_id}`; Workflow tab `/incidents/{incident_id}/workflow` |
| AR-API-09d | Acknowledge page (SL-A4-04) | `/m/incidents/{incident_id}/acknowledge` |
| AR-API-09e | Approval page (SL-A4-05) | `/m/approvals/{approval_request_id}` |
| AR-API-09f | Workflow editor (SL-A4-06) | `/workflow` |
| AR-API-09g | Setup and sources (SL-A4-07) | `/setup` |
| AR-API-09h | Routing rules (SL-A4-08) | `/routing` |
| AR-API-09i | AI settings (SL-A4-09) | `/ai`; with the investigation filter `/ai?investigation={request_group_id}` (DC-S03-A2) |
| AR-API-09j | Dashboard (SL-A4-10) | `/dashboard` |
| AR-API-09k | Audit trail (SL-A4-11) | `/audit` |
| AR-API-09l | Start | `/` goes to `/incidents` for a user holding `view`, otherwise as DC-S01-R3 |
| AR-API-09m | Webhook address shown in DC-S07-R1 | `https://{host}/api/v1/webhooks/{source_key}` |

Links in tickets and messages (DC-OUT-01 to DC-OUT-05) are absolute: `https://{host}{path}`, where `{host}` is the host of the tenant's current binding, read from `vigil-tenancy` when the text is composed, and `{incident_id}` and `{approval_request_id}` are record identifiers (Example: `https://vigil.<demo-domain>/m/incidents/0192a4c0-7b1e-7cc3-9a51-3f0d2b6e8a41/acknowledge`). A link from an earlier demo run therefore never opens a record of the current run: after a reset it answers "not found".

### 14.5 `vigil-web`

| ID | Rule | Trace |
| --- | --- | --- |
| AR-WEB-01 | `vigil-web` is one single-page application for all screens SL-A4-01 to SL-A4-11, including the mobile pages. Caddy serves its files from `vigil-app-01`; every path that is not under `/api/` and is not a file returns `index.html`. | PM-M24-01 |
| AR-WEB-02 | Times are formatted in the browser time zone (DC-UI-05); every other DC-UI-05 format is applied by one formatter in `vigil-web` and, for stored texts and outbound content, by one formatter in `vigil-server`; both formatters MUST pass the same table of test cases (Round 6). | DC-UI-05, DC-UI-11 |
| AR-WEB-03 | The workflow canvas (editor and read-only run view, SL-WE-10) is built with `@xyflow/react`; undo and redo history is held in the browser only (PM-M24). The automatic layout method is chosen in Round 6 (DC-O-05). | SL-WE-01 to SL-WE-06, SL-WE-10 |
| AR-WEB-04 | Hiding a control is not the permission check (DC-R-06); every action goes through an operation of Section 14.2. | DC-R-06 |

## 15. Hosting (SL-A3-05; proposal for CTX-O-02 and CTX-O-04)

### 15.1 Hosts (complete list)

Sizes are minimums. The cloud provider and region are chosen with CTX-O-02 (Muzaffar); the layout does not depend on the provider.

| ID | Host | Size | Runs (complete list) | Always on | Trace |
| --- | --- | --- | --- | --- | --- |
| AR-H-01 | `vigil-app-01` | 2 vCPU, 8 GiB memory, 60 GiB disk | Caddy (HTTPS for `vigil.<demo-domain>`, files of `vigil-web`); `vigil-server` as a systemd service (JVM heap at most 3 GiB); PostgreSQL 18 with pgvector, listening on 127.0.0.1 only; Ollama with the local embedding model, on 127.0.0.1 only; the secret store (AR-SEC-05); the SL-F-4 script of `demo-faults` (AR-DEMO-04). | Yes | SL-A3-05 |
| AR-H-02 | `lab-01` | 2 vCPU, 8 GiB memory, 60 GiB disk | Docker Compose with: Caddy (HTTPS for `zabbix.`, `alertmanager.` and `control.<demo-domain>`, with basic authentication in front of Alertmanager); Zabbix 7.0 LTS server, web front end and its PostgreSQL database; Prometheus and Alertmanager; `demo-control` (with `demo-faults`); the lab hosts `lab-api-01`, `lab-vas-01`, `lab-rpt-01` and `lab-probe-01` as containers with those host names (DD-F-10 `lab_hosts`). | During rehearsals and presentations; MAY be stopped otherwise | SL-A3-01 to SL-A3-03 |
| AR-H-03 | `lab-db-01` | 2 vCPU, 2 GiB memory, 20 GiB system disk plus a separate 20 GiB data disk formatted XFS and mounted at `/data` (DD-F-10) | The mock subscriber database; OpenSSH server for the runner users (AR-SEC-10) and the `demo-faults` user; sysstat sampling every 10 s with `-S XDISK` (DD-F-10); Zabbix agent 2. | As `lab-01` | SL-A1-03, SL-A1-06; DD-F-10 |
| AR-H-04 | `gpu-01` | One NVIDIA GPU with 24 GB memory (L4 class or better), 4 vCPU, 16 GiB memory, 100 GiB disk | Caddy (HTTPS for `llm.<demo-domain>`, bearer token check); Ollama with the local chat model, on 127.0.0.1 only. | No: started at least 10 minutes before each rehearsal or presentation, stopped afterwards | SL-X-09; CTX-O-04 |

DD-F-10 allows one host per lab VM "unless Round 5 merges them": `lab-api-01`, `lab-vas-01`, `lab-rpt-01` and `lab-probe-01` are merged into containers on `lab-01` (AR-D-04). `lab-db-01` stays a separate host because it is the runner's target: the diagnostics and the action run over real SSH on a real XFS filesystem with real sysstat samples (SB-S03, SB-S06).

### 15.2 Names and accounts (complete list)

| ID | Item | Value | Trace |
| --- | --- | --- | --- |
| AR-H-10 | Host names under the demo domain (CTX-O-02) | `vigil.<demo-domain>` → `vigil-app-01`; `zabbix.<demo-domain>`, `alertmanager.<demo-domain>`, `control.<demo-domain>` → `lab-01`; `lab-db-01.<demo-domain>` → `lab-db-01`; `llm.<demo-domain>` → `gpu-01`. The domain's mail (MX) points to Microsoft 365 (AR-H-12). | DD-F-07 addresses |
| AR-H-11 | Cloud account | One account holding the four hosts, in one region chosen for latency from Pakistan and for GPU availability; `gpu-01` MAY be in another region of the same provider when the first region has no 24 GB GPU host. | CTX-O-02 |
| AR-H-12 | Microsoft 365 tenant with the demo domain | One licensed user (the operator; Business Basic or higher) who owns the Workflows and is a member of the Team "Vigil Demo" with the 9 channels of SB-SET-06; one shared mailbox per email address of DD-F-07 `delivery_addresses` (shared mailboxes need no licence, AR-ASM-24), opened by the operator in Outlook on the web. | SB-SET-03, SB-SET-06 |
| AR-H-13 | Email sending service | The SMTP submission service of the chosen cloud provider (Amazon SES or Azure Communication Services Email), with the demo domain verified and SPF and DKIM records published, sending as `vigil@<demo-domain>` to the shared mailboxes. | PM-K-06; SL-A1-05 |
| AR-H-14 | Jira Cloud | One Jira Cloud site on the Free plan with one company-managed project (key `VIG`, issue type Task) whose workflow has the statuses "Open" and "Resolved" with the transitions "Resolve" (Open → Resolved) and "Reopen" (Resolved → Open); one service account for Vigil with an API token. | SL-A1-05, SL-A1-07; DM-O-12 |
| AR-H-15 | AI provider accounts | One Claude API account and one OpenAI API account, each with a monthly spending limit set in the provider console. | PM-K-07, PM-K-08 |

### 15.3 Running the installation

| ID | Rule | Trace |
| --- | --- | --- |
| AR-H-20 | `vigil-server` runs as an operating-system user `vigil` with JVM options that include the security property `networkaddress.cache.ttl=0` (AR-AI-07). It writes its log as JSON lines to the system journal; logs MUST NOT contain secret values or session tokens. | AR-AI-07; SL-B-14 |
| AR-H-21 | Deployment is done by scripts kept in the repository (Round 6): build the server and web artefacts, copy them to `vigil-app-01`, write secret files from the operator's password manager (AR-SEC-05), restart the service, then run a reset (AR-RS-12). | CTX-C-01 |
| AR-H-22 | Before each presentation (SB-SET-07): start `lab-01`, `lab-db-01` and `gpu-01`; wait until Zabbix, Alertmanager and Ollama answer; run Reset from the control panel. | SB-SET-07 |

### 15.4 Running cost (Example, for CTX-O-02)

The figures below are estimates from list prices known to Claude, not read from the providers' price pages in this round (AR-ASM-50); Muzaffar confirms the real figures for the chosen provider and region (CTX-O-02). Example, per month, USD: `vigil-app-01` 60 to 80; `lab-01` 60 to 80 when always on, about half when stopped outside rehearsals; `lab-db-01` 15 to 25; disks 10 to 20; public IPv4 addresses 15 to 20; `gpu-01` about 1 per running hour, so 20 to 40 for 20 to 40 hours of rehearsals and presentations; Microsoft 365 for one user 6 to 8; domain about 1 (15 per year); AI usage 10 to 30 (each primary demo run uses a few thousand tokens per provider call); email below 1; Jira Free 0. Total: about 200 to 300 per month.

## 16. Demo-only components (PM Section 11.1)

| ID | Rule | Trace |
| --- | --- | --- |
| AR-DEMO-01 | **`demo-control`.** A Python web application with server-rendered pages at `https://control.<demo-domain>` (screen DC-S12), in a container on `lab-01`. Its login uses one account whose password hash is kept in its own secret file on `lab-01` (DC-S12-R0, SL-B-14). It runs one operation at a time and applies the refusal rules of DC-W-29 and DD-F-10 `control_panel`. | SL-A3-03; DC-S12 |
| AR-DEMO-02 | **Faults on `lab-db-01`** (SL-F-1, SL-F-2). Run over SSH as the operating-system user `demo-faults`, which is not one of the runner users (AR-SEC-10). SL-F-1 creates and removes the files of DD-F-10; SL-F-2 sets and clears the mock database's read-only flag through the mock's control endpoint. | SL-A3-02; DD-F-10 |
| AR-DEMO-03 | **Faults on `lab-01`** (SL-F-3 and the storm re-run). Run through the control endpoints of the mock containers (the report export job, DD-F-10 SL-F-3). | SL-A3-02; DD-F-10 |
| AR-DEMO-04 | **SL-F-4 on `vigil-app-01`.** `demo-control` connects by SSH to `vigil-app-01` as user `demo-faults`, whose key is restricted to one command: `/opt/demo-faults/sl-f-4 on|off|status`, allowed through `sudo` for that script only. The script performs the three injection steps and the clearing steps of DD-F-10 SL-F-4 (host name override in `/etc/hosts`, a firewall rule that rejects with TCP reset, dropping established connections). With AR-AI-07, every later Claude or OpenAI call fails within the 5 s connect timeout (SB-ASM-03). The script is demo-only, lives in the demo-only folder of the repository and is not called by Vigil (SL-B-10). | SL-F-4; DD-F-10; SL-B-10 |
| AR-DEMO-05 | **Clear completion.** `demo-control` reads the Zabbix problem API (`problem.get`) and the Alertmanager alerts API (`GET /api/v2/alerts`) with read-only credentials of its own, as DD-F-10 `clear_completion` and SB-A-02 state. | PM-DC-04 |
| AR-DEMO-06 | **Reset.** `demo-control` runs AR-RS-07 with the installation token and the bundle built by AR-SB-06. | SL-A3-03 |
| AR-DEMO-07 | **`demo-lab`.** The mock services are Python programs. `lab-api-01` (4 balance-check and 4 bundle-purchase API instances), `lab-vas-01` (USSD gateway nodes, SMS queue with 20 routes, SMS dispatcher), `lab-rpt-01` (report export job) and `lab-probe-01` (synthetic USSD probes, 6 cities × 4 journeys every 15 s) run as containers on `lab-01`; the mock subscriber database runs on `lab-db-01` and is reached by the API mocks over the private network. Each mock exposes the metrics that `demo-monitoring` needs (DD-F-03 `lab_alert_catalogue`, DD-F-10). | SL-A3-01; DD-F-10 |
| AR-DEMO-08 | **`demo-monitoring`.** Zabbix 7.0 LTS with the Vigil media type (AR-CN-01a) and one action limited to problems tagged `vigil=lab`; Prometheus and Alertmanager with the rules, route and receiver of DD-F-10 `monitoring_configuration` (`group_by: ['...']`, `send_resolved: true`, bearer authorisation from a credentials file). The Zabbix hosts are named `lab-db-01`, `lab-api-01`, `lab-vas-01` and `lab-rpt-01` (DD-F-02 identity keys). How each item is collected (agent or HTTP item) is decided in the lab build (Round 6). | SL-A3-01, SL-A1-01; DD-F-10 |
| AR-DEMO-09 | **Vigil media type for Zabbix.** The media type script of AR-CN-01a is product content of the Zabbix connector (PM-K-01), kept with the connector code; `demo-monitoring` installs it into the lab Zabbix. | PM-K-01; SL-B-07 |

## 17. Values fixed in this round

### 17.1 Open items and placeholders resolved (complete list)

| ID | Open item or placeholder | Value | Where |
| --- | --- | --- | --- |
| AR-V-01 | SL-O-17 Reset mechanism | New tenant per demo run, tenant binding by host name, dormant earlier tenants | Section 12 |
| AR-V-02 | SL-O-20 Default AI settings (and SB-O-02, SB-V-31) | SL-T-1 and SL-T-2: Claude → OpenAI → Local model; SL-T-3: Local embedding model | AR-AI-05 |
| AR-V-03 | SL-O-11 Service map file format | YAML 1.2 as AR-SB-02 | AR-SB-02 |
| AR-V-04 | PM-O-01 Process layout and mechanism | One process; Java calls; PostgreSQL-backed events; Server-Sent Events | Section 7 |
| AR-V-05 | PM-O-09 Connector capability | Resolve notifications only | AR-CN-09a |
| AR-V-06 | PM-O-10 Teams delivery address form | Destination name; one Workflows URL per channel | Section 10.5 |
| AR-V-07 | PM-O-11 Secret storage | Files under `/etc/vigil/secrets`, locator `file:<name>` | AR-SEC-05 |
| AR-V-08 | PM-O-17 Control panel identity | Installation token | AR-SEC-07 |
| AR-V-09 | DM-O-04 Identity key names, order, mapping fields | Zabbix [`tag.component`, `host`]; Alertmanager [`label.component`, `label.instance`]; mapping fields of AR-CN-01b and AR-CN-02b | AR-CN-01b, AR-CN-02b |
| AR-V-10 | DM-O-05 Password hash, session lifetime and idle limit | Argon2id; 12 h; 4 h | AR-SEC-02, AR-SEC-03 |
| AR-V-11 | DM-O-06 Retry limits and intervals | Engine requests to step modules: 3 repeats after 5 s, 15 s and 45 s, then `step_failed_repeatedly`. Reconciliation state checks: at most 5 checks, 10 s apart, then `unresolved`. Re-embedding retry: every 30 s. Event resubmission: every 30 s. | AR-PL-07, AR-PL-09, AR-PL-03 |
| AR-V-12 | DM-O-08 Identifier, hash, physical types | UUID version 7; SHA-256 over RFC 8785 JSON; AR-DS-03 | AR-DS-03, AR-DS-04 |
| AR-V-13 | DM-O-12 Settings schema per connector kind | Section 10.2 | Section 10.2 |
| AR-V-14 | DM-O-13 Model names and prices | Partly: local models cost 0 (unchanged); external names and prices open (AR-O-01) | AR-AI-11 |
| AR-V-15 | DM-R-56 (8) Seed embeddings | Computed during import | AR-SB-04 |
| AR-V-16 | DC-O-02 Alert states and recovery in the workspace | Read again every 2 s while open | AR-PL-11 |
| AR-V-17 | DC-O-03 Windows after a reset; login screen tenant | Log in again; bound tenant shown | AR-RS-11 |
| AR-V-18 | DC-O-04 Page paths, links, webhook address | Section 14.4 | Section 14.4 |
| AR-V-19 | DC-O-05 Canvas library (library part) | `@xyflow/react` | AR-STK-06 |
| AR-V-20 | DD-O-01 Relative times | AR-SB-03 | AR-SB-03 |
| AR-V-21 | DD-O-02 `R5` values in DD-F-07 | Section 17.2, except model names and prices (AR-O-01) and deployment values (AR-O-05) | Section 17.2 |
| AR-V-22 | DD-F-10 SL-F-4 "gateway connect timeout 5 s, Round 5" | 5 s | AR-AI-06 |
| AR-V-23 | DD-F-10 `lab_hosts` "unless Round 5 merges them" | Four lab hosts merged into containers on `lab-01`; `lab-db-01` separate | Section 15.1 |
| AR-V-24 | DC-OUT-00 lines to Jira and Teams formats (DC-ASM-01, DC-ASM-02 format choice) | ADF and Adaptive Card rules | Section 10.4 |
| AR-V-25 | DC-OUT-01 and DM-E76-R2 incident label format | `vigil-incident-<incident id>` | AR-CN-04 |
| AR-V-26 | CTX §3 final module names | Working names are final | Section 5 |
| AR-V-27 | CTX-O-04 GPU server and acceptable local-model answer time | Proposal: on-demand 24 GB GPU host; 60 s for SL-T-1 (owner review, Section 23) | AR-AI-09, AR-H-04 |
| AR-V-28 | PM-M01-03 "if Round 5 chooses another mechanism" | Kept: the chosen mechanism creates a tenant per reset | AR-RS-07 |

### 17.2 Values for DD-F-07 placeholders marked `R5` (complete list)

Credential locators (`credential_references.locator`): `cred-webhook-zabbix` → `file:webhook-zabbix-lab`; `cred-webhook-alertmanager` → `file:webhook-alertmanager-lab`; `cred-zabbix-api` → `file:zabbix-api-token`; `cred-alertmanager-api` → `file:alertmanager-basic-auth`; `cred-lab-db-01-diag` → `file:lab-db-01-diag-ssh`; `cred-lab-db-01-action` → `file:lab-db-01-action-ssh`; `cred-jira` → `file:jira-api`; `cred-smtp` → `file:smtp`; `cred-claude` → `file:claude-api-key`; `cred-openai` → `file:openai-api-key`. `cred-teams` is replaced by nine credential references of purpose `external_api`, one per Teams destination (AR-A-09): `cred-teams-noc-l1`, `cred-teams-db-ops`, `cred-teams-vas-service-owners`, `cred-teams-noc-management`, `cred-teams-it-ops-head`, `cred-teams-it-infra`, `cred-teams-customer-care`, `cred-teams-approvals-bilal`, `cred-teams-approvals-sana`, each with the locator `file:` followed by its handle without the `cred-` prefix (Example: `file:teams-noc-l1`). A new credential reference `cred-local-llm` (purpose `external_api`, locator `file:local-llm-token`) is added for the local chat model (AR-A-10).

Connector instances (`address`; `settings`):

| Handle | `address` | `settings` |
| --- | --- | --- |
| `zabbix-lab` | `https://zabbix.<demo-domain>` (unchanged) | `{api_path: "/api_jsonrpc.php", request_timeout_s: 10}` |
| `alertmanager-lab` | `https://alertmanager.<demo-domain>` (unchanged) | `{request_timeout_s: 10}` |
| `linux-lab-db-01` | `lab-db-01.<demo-domain>` (unchanged) | `{port: 22, host_key: <deployment value>, connect_timeout_s: 10}` |
| `jira-demo` | `https://<jira-site>.atlassian.net` (deployment value) | `{project_key: "VIG", issue_type: "Task", resolve_transition_id: <deployment value>, reopen_transition_id: <deployment value>, resolved_status_name: "Resolved", open_status_name: "Open", request_timeout_s: 15}` |
| `teams-demo` | `https://teams.microsoft.com` | `{destinations: [one entry per Teams address of DD-F-07 `delivery_addresses`, each with its `cred-teams-…` handle], request_timeout_s: 15}`; `credential_ref` becomes `null` |
| `email-demo` | the SMTP host of the sending service (deployment value) | `{port: 587, from_address: "vigil@<demo-domain>", from_name: "Vigil", connect_timeout_s: 10}` |
| `claude-api` | `https://api.anthropic.com` | `{connect_timeout_s: 5}`; request timeouts and output limits per AR-AI-06 |
| `openai-api` | `https://api.openai.com` | `{connect_timeout_s: 5}`; as above |
| `local-llm` | `https://llm.<demo-domain>` | `{connect_timeout_s: 5}`; as above; `credential_ref` becomes `cred-local-llm` |
| `local-embed` | `http://127.0.0.1:11434` | `{connect_timeout_s: 5, request_timeout_s: 10}` |

Monitoring sources get `source_key` values (AR-A-07): "Zabbix — Jazz lab" → `zabbix-lab`; "Alertmanager — Jazz lab" → `alertmanager-lab`.


## 18. Scenario walkthrough of the primary demo (CTX-Q-3)

| Scene | Applications and hosts | Mechanism | Data | Outcome and time |
| --- | --- | --- | --- | --- |
| SL-A1-01 Alert storm | `demo-control` → `lab-db-01` (SL-F-1) and `lab-rpt-01` (SL-F-3); Zabbix and Alertmanager on `lab-01` → Caddy → `vigil-server` | Webhooks AR-OP-70 with bearer tokens; `vigil-intake` stores call, raw alerts, normalised alerts and alerts in one transaction, answers 202 (AR-CN-09b), publishes "alert received" | Payloads AR-CN-01a, AR-CN-02a; identity pairs AR-CN-01b, AR-CN-02b; alert type mappings (DD-F-03) | 138 alerts within 120 s (SB-T-01); forming rows appear through `incident_changed` notices within 2 s of each change (AR-PL-10) |
| SL-A1-02 One incident | `vigil-server` (`vigil-correlation`, `vigil-servicemap`, `vigil-incidents`, `vigil-routing`) | "alert received" listener; per-tenant lock (AR-PL-06); stability check every 5 s (AR-PL-09) | Service map generation 1; correlation settings | One storm incident and one SL-F-3 incident leave `forming` at most 50 s after the last storm alert (SB-TL-02) |
| SL-A1-03 Diagnostics | `vigil-engine` → `vigil-diagnostics` → `vigil-runner` → SSH to `lab-db-01` as `vigil-diag` | Step dispatch AR-PL-07; AR-CN-03 | Operations `fs_usage`, `largest_dirs`, `fs_growth` (DD-F-04) | Three evidence entries within 15 s of SB-TL-02 (SB-TL-03, SB-T-04) |
| SL-A1-04 AI investigation and routing | `vigil-investigation` → `vigil-ai-gateway` → Claude API (AR-CN-07) | AR-AI-01 to AR-AI-03; routing already applied before the run (PM-D-07) | Request parts with data categories (DM-R-43); runbooks (DD-K-08) | Accepted result; P1, Database Operations, VAS Service Owners |
| SL-A1-05 Ticket and notification | `vigil-ticketing` → Jira Cloud (AR-CN-04); `vigil-notifications` → Teams Workflows (AR-CN-05) and SMTP (AR-CN-06); acknowledgement from the phone window via AR-OP-19 and AR-OP-20 | ADF (AR-CN-04a); Adaptive Card (AR-CN-05a); plain-text email; links per Section 14.4 | DC-OUT-01, DC-OUT-03 | Ticket, two Teams messages and two emails within 60 s of SB-TL-03 (SB-TL-04); acknowledgement recorded with user and time |
| SL-A1-06 Approved fix | `vigil-knowledge` → `vigil-ai-gateway` → Ollama on `vigil-app-01` (AR-CN-10); `vigil-actions` → `vigil-notifications`; approval via AR-OP-21 and AR-OP-22; `vigil-runner` → SSH as `vigil-action` | Exact cosine search (AR-DS-07); snapshot hash (AR-DS-04); rechecks; runner | Past-incident vectors from the import (AR-SB-04) | PI-0342 at 1.00 (SB-T-03); approval request within 5 s of the acknowledgement (SB-TL-07); action within 10 s of approval (SB-TL-08) |
| SL-A1-07 Recovery check | Zabbix recovery operations and Alertmanager resolved notifications → `vigil-intake`; `vigil-recovery`; `vigil-ticketing` → Jira | Resolve notifications only (AR-CN-09a); "alert state changed" wakes the step; workspace reads recovery every 2 s (AR-PL-11) | Recovery check `rc-subscriber-db` | All 138 resolved within 60 s of the action (SB-TL-09); incident resolved 60 s later (SB-TL-10); Jira transition to "Resolved" |
| SL-A1-08 Workflow glimpse | `vigil-engine` → "step changed" → `vigil-api` stream → `vigil-web` | AR-PL-10; `run_changed` notice; AR-OP-17 | Run content and step executions | Highlight moves within 2 s of each step change (AR-SPK-09) |
| SL-A1-09 Dashboard | `vigil-reporting` | AR-OP-65 | Live incidents of the bound tenant and the 50 past incidents with times set at import (AR-SB-03) | SB-V-40 values |

Result: every scene has its applications, mechanism, data and outcome. The reset before the demo (SB-SET-07) is Section 12.

## 19. Changes to earlier documents (complete list)

Status "To write at close" means the change is written to the named document, with a new version and change history entry, when this round closes after the owner review (CTX-Q-7).

| ID | Document and item | Change | Status |
| --- | --- | --- | --- |
| AR-A-01 | `00-context` | Round 5 status set to Closed; decision CTX-D-023 records the adoption of this document and the owner-review outcome; Section 3 says the module names were made final by Round 5 (AR-N-01); CTX-O-04 closed when AR-D-05 is agreed; CTX-O-02 updated with the owner's answer (AR-O-02). Written as v1.9. | To write at close |
| AR-A-02 | `10-product-map` | New Section 21 "Amendments from Round 5": PM-M01 Owns adds "tenant binding"; new responsibility PM-M01-04 — MUST keep tenant bindings by host name, answer the bound tenant of a host and whether a tenant is bound, and refuse a binding to a tenant that is not `ready` or was bound before (AR-RS-01, AR-RS-06); new event PM-EV-15 "Tenant binding changed", published by `vigil-tenancy`, reacted to by `vigil-api` (ends the earlier tenant's streams) and `vigil-intake` (connection tests of the new tenant's sources); new responsibilities PM-M23-06 — MUST offer installation operations (AR-OP-60 to AR-OP-64) only to a caller presenting the installation token — and PM-M23-07 — MUST handle every user request and webhook call in the host's bound tenant and refuse a session of another tenant (AR-RS-02, AR-RS-03); new request PM-I-46 — modules with scheduled work (`vigil-engine`, `vigil-correlation`, `vigil-incidents`, `vigil-intake`, `vigil-knowledge`) and the modules that compose links (`vigil-ticketing`, `vigil-notifications`, `vigil-actions`) ask `vigil-tenancy` which tenants are bound and the host of a tenant; PM-M06-01 — the webhook address names the source key (AR-API-09m); PM-K-05 — one Workflows webhook per channel (Section 10.5); PM-K-09 — the local chat model has a credential (AR-A-10); PM-O-01, PM-O-09, PM-O-10, PM-O-11 and PM-O-17 marked closed by AR-V-04 to AR-V-08. Written as v1.4. | To write at close |
| AR-A-03 | `20-domain-model` | New entity DM-E-83 Tenant binding (owner `vigil-tenancy`, Revisioned): `host` Text(253) Y (the HTTPS host name, lowercase); `tenant_id` (common field) is the bound tenant; `revision` Integer Y (per host, from 1); `bound_by` Actor Y. Rules: DM-E83-R1 the current binding of a host is its highest revision; DM-E83-R2 a binding is refused unless its tenant is `ready` and has never been bound (AR-RS-06). New rule DM-R-70 Dormant tenants (AR-RS-04). DM-E01-R1 adds "or not bound". DM-E-15 adds `source_key` Key Y, unique per tenant, used in the webhook address (AR-A-07). DM-C-01 `Id` = UUID version 7 and DM-V-12 hash = AR-DS-04 (DM-O-08). DM-E-03 `password_hash` = Argon2id (AR-SEC-02); DM-E-07 lifetime 12 h and idle limit 4 h (DM-O-05). DM-E-09 `settings_schema` = Section 10.2 of this document (DM-O-12). DM-E-11 `locator` format = AR-SEC-05. DM-E-17 `field` and DM-R-04 lists = AR-CN-01b, AR-CN-02b (DM-O-04). DM-R-56 (8): embeddings computed during import (AR-SB-04). Section 16: DM-O-04, DM-O-05, DM-O-06, DM-O-08 and DM-O-12 marked closed; DM-O-13 points to AR-O-01. Written as v1.3. | To write at close |
| AR-A-04 | `30-jazz-storyboard` | SB-O-02 marked closed by AR-V-02; SB-V-31 marked "confirmed in Round 5 (AR-AI-05)". Written as v1.2. | To write at close |
| AR-A-05 | `31-demo-data` | DD-F-07: the `R5` values of Section 17.2, including AR-A-09 and AR-A-10; model names and prices stay `R5` until AR-O-01; deployment values written as `<deployment value>` placeholders filled by the bundle builder (AR-SB-06). DD-F-03: `source_key` per monitoring source (AR-A-07). README: DD-O-01 and DD-O-02 marked closed except the items of AR-O-01 and AR-O-05; DD-ASM-01 points to AR-CN-01b, AR-CN-02b and AR-SPK-01. `tools/check_data.py` updated for the new handles and the Teams destinations, and run with all checks passing (DD-R-06). Written as v1.2. | To write at close |
| AR-A-06 | `40-demo-cut` | DC-O-02, DC-O-03, DC-O-04 and DC-O-05 (library part) marked closed by AR-V-16 to AR-V-19; DC-ASM-01 and DC-ASM-02 point to Section 10.4 and spikes AR-SPK-02 and AR-SPK-03. Written as v1.1. | To write at close |
| AR-A-07 | Monitoring source key (in AR-A-03 and AR-A-05) | `source_key` on DM-E-15, values `zabbix-lab` and `alertmanager-lab`. Reason: a reset creates new source identifiers in every new tenant, so the webhook address configured in Zabbix and Alertmanager MUST name a stable key (DM-C-06). | To write at close |
| AR-A-08 | Tenant binding and dormant tenants (in AR-A-02 and AR-A-03) | As AR-A-02 and AR-A-03. Reason: SL-O-17 requires webhooks and logged-in users to reach the current run, and earlier runs to stay unchanged. | To write at close |
| AR-A-09 | Teams credentials (in AR-A-05) | `cred-teams` is withdrawn and replaced by nine per-destination credential references (Section 17.2). Reason: each Teams channel has its own Workflows URL, which is a secret (Section 10.5). | To write at close |
| AR-A-10 | Local chat model credential (in AR-A-05) | `cred-local-llm` added; `local-llm.credential_ref` set to it. Reason: `gpu-01` is reached over the network and checks a bearer token (AR-CN-09). | To write at close |

No change is made to `01-scope-lock`: no item of list A or list B changes. The open items SL-O-11, SL-O-17 and SL-O-20 are resolved here (Section 17.1).

## 20. Traceability

### 20.1 Items assigned to Round 5 by earlier documents (complete list)

| Item | Resolved by |
| --- | --- |
| CTX §3 final module names | Section 5 (AR-V-26) |
| CTX-D-008, SL-X-12 stack | Section 6 |
| CTX-O-02 hosting, domain, costs | Section 15 (proposal); open as AR-O-02 |
| CTX-O-04, SL-X-09 GPU server and local-model answer time | AR-AI-09, AR-H-04 (proposal); open as AR-O-03 |
| CTX-Q-6 fact check | Section 22 (assumptions with check addresses); AR-O-04 |
| SL-ASM-01 Alertmanager timing | AR-ASM-03; AR-SPK-01 with SB-T-01 |
| SL-ASM-02 Teams without a bot | Section 10.5, AR-ASM-20; AR-SPK-03 |
| SL-ASM-03 no Anthropic embeddings | AR-ASM-28 |
| SL-ASM-04 embedding determinism | AR-AI-08; AR-SPK-07 |
| SL-O-11 service map file format | AR-SB-02 |
| SL-O-17 reset mechanism | Section 12 |
| SL-O-20 default AI settings | AR-AI-05 |
| PM-M01-03 tenant creation | AR-V-28 |
| PM-M04-03 secret storage | AR-SEC-05 |
| PM-M23-05 hosting | Section 15; AR-SEC-01 |
| PM-O-01 process layout and mechanism | Section 7 |
| PM-O-09 connector capability | AR-CN-09a |
| PM-O-10 Teams address form | Section 10.5 |
| PM-O-11 secret storage | AR-SEC-05 |
| PM-O-17 control panel identity | AR-SEC-07 |
| PM-I-04 operations | Section 14.2 |
| PM-ASM-01 resolve notifications | AR-ASM-06, AR-ASM-12; AR-SPK-01 |
| PM-ASM-02 connection test interfaces | AR-CN-01, AR-CN-02; AR-ASM-05, AR-ASM-13 |
| PM-ASM-03, DM-ASM-07 Jira transitions | AR-CN-04, AR-H-14; AR-ASM-19; AR-SPK-02 |
| PM Section 7 connector facts | Section 10; Section 22 |
| DM-C-01 identifier format, DM-V-12 hash, DM-O-08 | AR-DS-03, AR-DS-04 |
| DM-E-03 hash algorithm, DM-E-07 lifetimes, DM-O-05 | AR-SEC-02, AR-SEC-03 |
| DM-E-09 settings schema, DM-O-12 | Section 10.2 |
| DM-E-11 locator, DM-E-17 field, DM-R-04, DM-O-04 | AR-SEC-05; AR-CN-01b, AR-CN-02b |
| DM-R-56 (8) seed embeddings | AR-SB-04 |
| DM-R-66 durable event mechanism | AR-PL-03 |
| DM-O-06 retry values | AR-V-11 |
| DM-O-13 model names and prices | AR-AI-11; open as AR-O-01 |
| DM-ASM-01, DM-ASM-02, DM-ASM-04, DM-ASM-05 | AR-CN-01b, AR-CN-02b; AR-ASM-01 to AR-ASM-03, AR-ASM-10, AR-ASM-11; AR-SPK-01 |
| DM-ASM-03 connection test load | AR-CN-01, AR-CN-02 (one small call per minute per source); AR-SPK-01 |
| DM-ASM-06 token units reported | AR-CN-07 to AR-CN-10; AR-ASM-27, AR-ASM-29, AR-ASM-31 |
| DM-ASM-09 Jira label search | AR-CN-04; AR-ASM-18; AR-SPK-02 |
| DM §14 public interface operations | Section 14.2 |
| SB-V-26 identity key order | AR-CN-01b, AR-CN-02b (order kept) |
| SB-V-31, SB-O-02 provider order | AR-AI-05 |
| SB-ASM-03 cloud AI cut | AR-AI-06, AR-AI-07, AR-DEMO-04; AR-SPK-11 |
| SB-T-05 reset "whatever mechanism Round 5 chooses" | AR-RS-07, AR-RS-08; AR-SPK-10 |
| DD-R-03 bundle serialisation and service map format | AR-SB-01, AR-SB-02 |
| DD-O-01 relative times | AR-SB-03 |
| DD-O-02 `R5` values | Section 17.2; AR-O-01, AR-O-05 |
| DD-ASM-01 connector field names | AR-CN-01b, AR-CN-02b |
| DD-ASM-02 sysstat | AR-ASM-14 |
| DD-ASM-04 similarity with the chosen model | AR-AI-11; AR-SPK-07 |
| DD-ASM-05 one notification per alert | AR-ASM-03, AR-ASM-12; AR-SPK-01 |
| DD-F-10 lab host merge, gateway connect timeout | AR-V-23, AR-V-22 |
| DC-ASM-01, DC-ASM-02, DC-OUT-00 formats | Section 10.4; AR-SPK-02, AR-SPK-03 |
| DC-OUT-01 label format | AR-CN-04 |
| DC-O-02, DC-O-03, DC-O-04, DC-O-05 (library) | AR-PL-11, AR-RS-11, Section 14.4, AR-STK-06 |
| DC-S07-R1 webhook address format | AR-API-09m |
| DC-S12-A6 windows after a reset | AR-RS-11 |

### 20.2 Scope lock items to this document (complete list)

| Scope lock item | Covered by |
| --- | --- |
| SL-A1-01 to SL-A1-09 | Section 18 (each scene) |
| SL-A2-01 | AR-CN-01, AR-CN-02 (connection tests); AR-SB-02; AR-OP-45 to AR-OP-50 |
| SL-A2-02 | Section 11; AR-OP-55 to AR-OP-59 |
| SL-A2-03, SL-A2-04 | AR-PL-07, AR-PL-08 (durable timers); AR-CN-04 (escalation comment) |
| SL-A2-05 | AR-AI-02, AR-AI-06, AR-AI-07 |
| SL-A2-06 | AR-OP-30 to AR-OP-40; AR-STK-06 |
| SL-A2-07 | AR-OP-18, AR-OP-59; AR-AI-02 |
| SL-A2-08 | AR-OP-66, AR-OP-67; AR-PL-05 (audit with the change) |
| SL-A3-01, SL-A3-02 | Section 16; Section 15.1 |
| SL-A3-03 | Section 12; AR-DEMO-01, AR-DEMO-06; AR-SEC-07 |
| SL-A3-04 | AR-AI-08, AR-DS-07 (same match and score every run); AR-RS-07 (same starting state) |
| SL-A3-05 | Section 15; AR-SEC-01 |
| SL-A3-06 | AR-OP-03 (frame) |
| SL-A3-07 | AR-SEC-02; AR-SB-05 |
| SL-A3-08 | Not software (PM-DC-06) |
| SL-A4-01 to SL-A4-11 | Section 14.2 (operations per screen), Section 14.4 (paths) |
| SL-A4-12 | AR-DEMO-01 |
| SL-T-1 to SL-T-3 | Section 11 |
| SL-WE-01 to SL-WE-10 | AR-STK-06, AR-WEB-03; AR-OP-30 to AR-OP-40 |
| SL-B-01 | AR-DS-05; AR-RS-02 |
| SL-B-02 | AR-CN-09b; AR-DS-06 |
| SL-B-03, SL-B-11 | AR-PL-03, AR-PL-07, AR-PL-08 |
| SL-B-04 | Unchanged in the engine and `vigil-actions`; AR-PL-06 (atomic decisions) |
| SL-B-05 | AR-SEC-10; AR-CN-03 |
| SL-B-06 | AR-PL-05; AR-DS-06 |
| SL-B-07 | Section 10 |
| SL-B-08 | AR-AI-01, AR-AI-02 |
| SL-B-09 | Unchanged (configuration data in the module schemas) |
| SL-B-10 | AR-RS-04; AR-DEMO-04, AR-DEMO-09 |
| SL-B-12 | Unchanged (state machines of `20-domain-model`) |
| SL-B-13 | AR-SEC-03, AR-SEC-04; AR-API-02 |
| SL-B-14 | Section 9 |
| SL-DR-01 to SL-DR-04 | AR-SB-06 (bundle built from the content of record, unchanged) |
| SL-CR-01 to SL-CR-03, SL-CP-1 to SL-CP-3 | No item added to list A; checkpoints are Round 6 |
| SL-X-09, SL-X-12, SL-X-15 | AR-AI-09; Section 6; Section 15.4 |
| SL-C-01 to SL-C-19 | Not built. SL-C-07 bounds AR-PL-01 and AR-DS-08; SL-C-14 bounds AR-CN-04 |
| SL-ASM-01 to SL-ASM-04 | Section 20.1 |
| SL-O-11, SL-O-17, SL-O-20 | Section 17.1 |

## 21. Spikes (complete list)

Each spike is an early build package of Round 6. It proves the assumptions named against running software; a failed spike is corrected in this document through the decision log (CTX-Q-8). Where a spike is the same test as one of `30-jazz-storyboard` Section 11, it is run together with it.

| ID | Spike | Pass condition | Proves |
| --- | --- | --- | --- |
| AR-SPK-01 | Monitoring webhooks (with SB-T-01) | The lab Zabbix 7.0 and Alertmanager, configured as AR-DEMO-08, send to a test endpoint: one call per Zabbix problem and one per recovery with the AR-CN-01a fields, the same `event_id` in both, UTC times in the stated form; one Alertmanager call per alert with the same `fingerprint` in firing and resolved entries; bearer headers present; the 138 storm alerts within 120 s and no repeat. Connection tests of AR-CN-01 and AR-CN-02 pass and fail as stated. | AR-ASM-01 to AR-ASM-06, AR-ASM-10 to AR-ASM-14 |
| AR-SPK-02 | Jira (with SB-T-07) | On the AR-H-14 project: create with ADF description and label; the DC-OUT-01 lines show as paragraphs and list items; comment; resolve and reopen transitions; status read; label search finds the issue. | AR-ASM-16 to AR-ASM-19 |
| AR-SPK-03 | Teams (with SB-T-06) | A message of AR-CN-05a arrives in each of the 9 channels through its Workflows URL; every line shows on its own; the "- " lines show as list items; the operator can copy the link (SB-SET-04). If the link cannot be copied, the fallback form of AR-CN-05a is tested and adopted. | AR-ASM-20 to AR-ASM-23; AR-O-06 |
| AR-SPK-04 | Email | A message of AR-CN-06 sent through the chosen service arrives in the `noc-l1` shared mailbox inbox (not junk) and shows in Outlook on the web with a working link. | AR-ASM-24 to AR-ASM-26 |
| AR-SPK-05 | Cloud AI (with SB-T-04) | With the AR-O-01 candidate models, SL-T-1 and SL-T-2 requests with the AR-AI-03 schemas return valid JSON, report units, and finish within the SB-TL-04 budget in 5 of 5 runs; the SB-S04 acceptance criteria hold. | AR-ASM-27 to AR-ASM-30 |
| AR-SPK-06 | Local chat model (with SB-T-04) | On `gpu-01` with the candidate model: SL-T-1 returns valid JSON meeting the SB-S04 criteria and answers within 60 s in 5 of 5 runs; units are reported. | AR-ASM-31, AR-ASM-33, AR-ASM-34; CTX-O-04 |
| AR-SPK-07 | Local embedding model (with SB-T-03) | With the candidate model on `vigil-app-01`: the storm search text and PI-0342 score 1.00; the SL-F-3 incident's best score is below 0.95; no other past incident rounds to 1.00; the same text embedded 100 times gives vectors whose cosine similarity with the first rounds to 1.00 each time; importing 50 past incidents takes at most 5 s. | SL-ASM-04, AR-ASM-32; DD-ASM-04 |
| AR-SPK-08 | Durable events and restart | The `vigil-server` process is killed (`SIGKILL`) during a storm and again while steps wait; after restart every event is delivered, no alert gets two grouping decisions, every waiting step is evaluated again, and the run finishes as in an uninterrupted run. | AR-ASM-41; SL-B-11, DM-R-66 |
| AR-SPK-09 | Live update latency | Over 50 step changes and 50 incident changes, the time from commit to the change on screen is at most 2 s each, through Caddy. | AR-ASM-44; SL-A1-08 |
| AR-SPK-10 | Reset (with SB-T-05) | Reset as AR-RS-07 completes within 60 s 5 times in a row, including after SB-D07; the earlier tenant's records are unchanged (row counts and a checksum per table before and after); open windows go to the login screen. | AR-RS-07, AR-RS-08, AR-RS-10, AR-RS-11 |
| AR-SPK-11 | Cloud AI cut (with SB-T-09) | With SL-F-4 on, every Claude and OpenAI call fails within 5 s and the re-run falls back to the local model; Jira, Teams and email still work; with SL-F-4 off, cloud calls succeed again at the next call. | AR-ASM-45; SB-ASM-03 |
| AR-SPK-12 | Stack skeleton | A skeleton of `vigil-server` with two modules builds with the chosen versions; the boundary test fails on a forbidden dependency; an event survives a restart; an SSE stream works through Caddy; Argon2id hashing works; the SSH client rejects an unknown host key. | AR-ASM-40 to AR-ASM-47 |

## 22. Assumptions (complete list)

The fact check of this round could not read the official pages (Section 27, CTX-Q-6). Every claim about an external product is therefore an assumption, with the official page to check (AR-O-04) and the spike that proves it. Assumptions SL-ASM-01 to SL-ASM-04, PM-ASM-01 to PM-ASM-04, DM-ASM-01 to DM-ASM-09, DD-ASM-01 to DD-ASM-06, SB-ASM-01 to SB-ASM-03 and DC-ASM-01 to DC-ASM-02 stay in force and are mapped in Section 20.1.

| ID | Assumption | Check against | Spike |
| --- | --- | --- | --- |
| AR-ASM-01 | Alertmanager's webhook payload (version "4") has `status`, `alerts[]` with `status`, `labels`, `annotations`, `startsAt`, `endsAt`, `fingerprint`. | prometheus.io/docs/alerting/latest/configuration/ (webhook_config) | AR-SPK-01 |
| AR-ASM-02 | An alert's `fingerprint` depends only on its label set and is the same in its firing and resolved notifications. | prometheus.io/docs/alerting/latest/ ; github.com/prometheus/alertmanager | AR-SPK-01 |
| AR-ASM-03 | `group_by: ['...']` makes each alert its own group; with `group_wait` 5 s and `group_interval` 10 s the 138 storm alerts reach Vigil within 120 s; `send_resolved: true` sends resolved notifications. | prometheus.io/docs/alerting/latest/configuration/ (route) | AR-SPK-01 |
| AR-ASM-04 | `webhook_config.http_config.authorization` with `type: Bearer` and `credentials_file` sends `Authorization: Bearer <token>`. | prometheus.io/docs/alerting/latest/configuration/ (http_config) | AR-SPK-01 |
| AR-ASM-05 | Alertmanager offers `GET /api/v2/status` and `GET /api/v2/alerts`. | github.com/prometheus/alertmanager/blob/main/api/v2/openapi.yaml | AR-SPK-01 |
| AR-ASM-06 | Prometheus sends resolved alerts with `endsAt` to Alertmanager when a rule stops firing; `keep_firing_for: 0` adds no delay. | prometheus.io/docs/prometheus/latest/configuration/alerting_rules/ | AR-SPK-01 |
| AR-ASM-10 | A Zabbix 7.0 webhook media type script can build a JSON body and set an `Authorization` header (JavaScript `HttpRequest`); in a recovery operation message {EVENT.ID} is the original problem event ID. | zabbix.com/documentation/7.0/en/manual/config/notifications/media/webhook ; …/manual/appendix/macros/supported_by_location | AR-SPK-01 |
| AR-ASM-11 | {EVENT.DATE} and {EVENT.TIME} give `yyyy.MM.dd` and `HH:mm:ss` in the Zabbix server's time zone; {EVENT.TAGSJSON} gives the tags as a JSON array of `{tag, value}`; {EVENT.RECOVERY.DATE} and {EVENT.RECOVERY.TIME} exist. | zabbix.com/documentation/7.0/en/manual/appendix/macros/supported_by_location | AR-SPK-01 |
| AR-ASM-12 | A Zabbix action limited by a tag condition, with one operation step and recovery operations, sends exactly one webhook per problem and one per recovery. | zabbix.com/documentation/7.0/en/manual/config/notifications/action | AR-SPK-01 |
| AR-ASM-13 | The Zabbix API answers `apiinfo.version` without authentication, accepts an API token in `Authorization: Bearer`, and offers `host.get` and `problem.get`. | zabbix.com/documentation/7.0/en/manual/api | AR-SPK-01 |
| AR-ASM-14 | `sar -F MOUNT` prints `%ufsused` per mount point and `sadc -S XDISK` can sample every 10 s (DD-ASM-02). | sysstat.github.io ; man sar, man sadc | AR-SPK-01 |
| AR-ASM-16 | Jira REST API v3 creates issues with an ADF `description`, `labels` and a `summary` of at most 255 characters. | developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/ | AR-SPK-02 |
| AR-ASM-17 | ADF has `doc`, `paragraph`, `bulletList`, `listItem`, `text` and the `link` mark; a text node cannot be empty; text is not parsed as markup. | developer.atlassian.com/cloud/jira/platform/apis/document/structure/ | AR-SPK-02 |
| AR-ASM-18 | `POST /rest/api/3/search/jql` with `labels = "<label>"` finds issues by label; the older search endpoint is not used. | developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/ | AR-SPK-02 |
| AR-ASM-19 | Transitions are listed and run through `/rest/api/3/issue/{key}/transitions`; a company-managed project on the Free plan can have the AR-H-14 workflow; basic authentication with account email and API token works (tokens have an expiry date to renew). | developer.atlassian.com/cloud/jira/platform/basic-auth-for-rest-apis/ ; atlassian.com/software/jira/pricing | AR-SPK-02 |
| AR-ASM-20 | A Teams Workflows flow created from the channel template for webhook alerts gives a POST URL that accepts a message with an Adaptive Card attachment, is a secret, and needs no premium licence. | learn.microsoft.com/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook ; learn.microsoft.com/power-automate | AR-SPK-03 |
| AR-ASM-21 | The Workflows bot cannot post into private channels, so the 9 demo channels are standard channels. | learn.microsoft.com/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook | AR-SPK-03 |
| AR-ASM-22 | Teams renders Adaptive Card version 1.4 `RichTextBlock` and `TextRun` (plain text, no Markdown) and `Action.OpenUrl` as `selectAction`. | learn.microsoft.com/microsoftteams/platform/task-modules-and-cards/cards/cards-format ; adaptivecards.io | AR-SPK-03 |
| AR-ASM-23 | Microsoft Graph cannot post channel messages with application permissions outside migration, so no bot-free Graph route exists; the Office 365 connector webhooks are not used. | learn.microsoft.com/graph/api/channel-post-messages | AR-SPK-03 |
| AR-ASM-24 | Exchange Online shared mailboxes need no licence and open in Outlook on the web for a licensed user with Full Access. | learn.microsoft.com/microsoft-365/admin/email/about-shared-mailboxes | AR-SPK-04 |
| AR-ASM-25 | Amazon SES and Azure Communication Services Email each accept SMTP submission on port 587 with STARTTLS and SMTP AUTH after domain verification. | docs.aws.amazon.com/ses/latest/dg/send-email-smtp.html ; learn.microsoft.com/azure/communication-services/quickstarts/email/send-email-smtp/ | AR-SPK-04 |
| AR-ASM-26 | Mail from that service with SPF and DKIM aligned to the demo domain is delivered to the Microsoft 365 inboxes of the same domain, not to junk. | Microsoft 365 message trace | AR-SPK-04 |
| AR-ASM-27 | The Claude Messages API uses `x-api-key` and `anthropic-version`, reports `usage.input_tokens` and `usage.output_tokens`, and offers JSON-schema structured output or forced tool use. | docs.claude.com/en/api/messages ; docs.claude.com/en/docs/build-with-claude/structured-outputs | AR-SPK-05 |
| AR-ASM-28 | Anthropic offers no embeddings model of its own (SL-ASM-03). | docs.claude.com/en/docs/build-with-claude/embeddings | — |
| AR-ASM-29 | The OpenAI Responses API offers strict JSON-schema output and reports `usage.input_tokens` and `usage.output_tokens`; `POST /v1/embeddings` serves `text-embedding-3-small`. | platform.openai.com/docs/api-reference/responses ; platform.openai.com/docs/guides/structured-outputs ; platform.openai.com/docs/api-reference/embeddings | AR-SPK-05 |
| AR-ASM-30 | The chosen Claude model answers SL-T-1 and SL-T-2 within SB-TL-04 (SB-ASM-02). | — | AR-SPK-05 |
| AR-ASM-31 | Ollama's `POST /api/chat` accepts a JSON schema in `format` and reports `prompt_eval_count` and `eval_count`. | github.com/ollama/ollama/blob/main/docs/api.md | AR-SPK-06 |
| AR-ASM-32 | Ollama's `POST /api/embed` returns `embeddings` for an input string. | github.com/ollama/ollama/blob/main/docs/api.md | AR-SPK-07 |
| AR-ASM-33 | Ollama runs on CPU only and on NVIDIA GPUs, and keeps a model loaded with `keep_alive` -1. | github.com/ollama/ollama/blob/main/docs/faq.md ; …/docs/gpu.md | AR-SPK-06 |
| AR-ASM-34 | An open-weight model under a commercial-use licence, run on a 24 GB GPU, meets AR-AI-09 and the SB-S04 criteria. | Model cards of the candidates | AR-SPK-06 |
| AR-ASM-40 | The versions of Section 6.1 are generally available at the start of the build: Java 25 LTS; Spring Boot 4 with a compatible Spring Modulith; PostgreSQL 18 with pgvector 0.8; React 19 with `@xyflow/react` 12 (MIT); Caddy 2; Zabbix 7.0 LTS; Ubuntu 24.04 LTS. | openjdk.org ; spring.io/projects/spring-boot ; spring.io/projects/spring-modulith ; postgresql.org/support/versioning ; github.com/pgvector/pgvector ; reactflow.dev ; zabbix.com/life_cycle_and_release_policy | AR-SPK-12 |
| AR-ASM-41 | The Spring Modulith JDBC event publication registry stores a publication per transactional listener inside the publishing transaction, completes it when the listener succeeds, republishes outstanding publications on restart with `spring.modulith.events.republish-outstanding-events-on-restart`, and offers resubmission of incomplete publications by age. | docs.spring.io/spring-modulith/reference/events.html | AR-SPK-08 |
| AR-ASM-42 | `ApplicationModules.verify()` rejects cycles, access to other modules' internal types and dependencies outside `allowedDependencies`. | docs.spring.io/spring-modulith/reference/verification.html | AR-SPK-12 |
| AR-ASM-43 | Spring Security's `Argon2PasswordEncoder` offers the Spring Security 5.8 defaults (and needs Bouncy Castle on the class path). | docs.spring.io/spring-security/reference/features/authentication/password-storage.html | AR-SPK-12 |
| AR-ASM-44 | Caddy obtains certificates automatically and passes `text/event-stream` responses through without buffering. | caddyserver.com/docs/automatic-https ; caddyserver.com/docs/caddyfile/directives/reverse_proxy | AR-SPK-09 |
| AR-ASM-45 | The Java security property `networkaddress.cache.ttl=0` stops caching of successful name lookups; `java.net.http.HttpClient` can be closed so its connections end (Java 21 and later). | docs.oracle.com/en/java/javase/25/ (networking properties; HttpClient) | AR-SPK-11 |
| AR-ASM-46 | Apache MINA SSHD supports ed25519 keys and verification against a known host key (its default accepts any host key and MUST be replaced). | github.com/apache/mina-sshd | AR-SPK-12 |
| AR-ASM-47 | pgvector without an index returns exact nearest neighbours with `<=>` (cosine distance); PostgreSQL `varchar(n)` limits characters, not bytes. | github.com/pgvector/pgvector ; postgresql.org/docs/current/datatype-character.html | AR-SPK-07, AR-SPK-12 |
| AR-ASM-50 | The cost figures of Section 15.4 are within 30 % of the chosen provider's list prices. | The provider's price pages | — (checked by Muzaffar, CTX-O-02) |
| AR-ASM-51 | The chosen provider offers an on-demand host with a 24 GB NVIDIA GPU in a region usable for the demo. | The provider's GPU host pages | AR-SPK-06 |

## 23. Decisions made in this round (complete list)

Status "Proposed" means Claude made the decision within the scope lock and earlier documents; Abdul MAY change any of them through the `00-context` decision log. Items marked "Owner review" are the seven put to Abdul under CTX-Q-7 (Section 27).

| ID | Decision | Reason | Status | Trace |
| --- | --- | --- | --- | --- |
| AR-D-01 | Stack of Section 6: Java 25 with Spring Boot 4 and Spring Modulith on the server; TypeScript, React 19, Vite and React Flow on the web; PostgreSQL 18 with pgvector; Caddy; Ollama for local models; Python for demo-only components. | Continuity with the Techfinity team's skills and the baseline stack; module boundaries and durable events from a maintained framework instead of hand-made code; one builder (CTX-C-01). The main alternative, TypeScript on the server, is in Section 6.2. | Proposed — Owner review 1 | CTX-D-008, SL-X-12 |
| AR-D-02 | One `vigil-server` process with in-process requests, events through the PostgreSQL event publication registry, durable timers in the database and Server-Sent Events to browsers; no message broker. | Meets DM-R-66, SL-B-11 and the 2-second highlight with one stateful system to run. Several processes stay Later (SL-C-07, AR-L-03). | Proposed — Owner review 2 | PM-O-01 |
| AR-D-03 | Reset creates a new tenant per demo run and switches the host's tenant binding to it; earlier tenants become dormant and stay unchanged; every open window logs in again after a reset. | The mechanism SL-O-17 recommends; no address in Zabbix, Alertmanager or messages changes; nothing of an earlier run is modified (SL-A3-03); dormant tenants send no late escalation into the next presentation. | Proposed — Owner review 3 | SL-O-17, DC-O-03 |
| AR-D-04 | Hosting of Section 15: `vigil-app-01`, `lab-01` and `lab-db-01` on one cloud provider, with the four other lab hosts merged into containers on `lab-01`; `gpu-01` started only for rehearsals and presentations; provider and region chosen by Muzaffar; running cost in the order of USD 200 to 300 per month (Example, Section 15.4). | Lowest number of hosts that keeps the runner's target a real server; the GPU costs only while used. | Proposed — Owner review 4 | CTX-O-02, SL-A3-05 |
| AR-D-05 | AI defaults Claude → OpenAI → Local model for SL-T-1 and SL-T-2 and the local embedding model for SL-T-3; the embedding model runs on CPU on `vigil-app-01`; the local chat model runs on an on-demand 24 GB GPU host; acceptable local answer time for SL-T-1: 60 s (closes CTX-O-04 when agreed). | Cloud quality and speed for the primary demo; local fallback when the cloud is cut; similarity search never depends on the GPU host. | Proposed — Owner review 5 | SL-O-20, CTX-O-04 |
| AR-D-06 | Teams messages through one Workflows webhook per channel; email by SMTP submission through the cloud provider's email service into Microsoft 365 shared mailboxes. This needs a Microsoft 365 tenant on the demo domain with one licensed user. | The only bot-free Teams route (AR-ASM-23); shared mailboxes need no licence; SMTP is the generic product email connector. | Proposed — Owner review 6 | SL-ASM-02, PM-O-10 |
| AR-D-07 | The working module names are final; Java packages `vigil.<name>`; schemas `vigil_<name>`. | No reason to rename was found; names already appear in every document. | Proposed — Owner review 7 | CTX §3 |
| AR-D-08 | Secrets are files under `/etc/vigil/secrets` addressed by `file:` locators. | Works on any provider; other schemes can be added without changing records. | Proposed | PM-O-11 |
| AR-D-09 | The workspace reads alerts and recovery every 2 s instead of pushing alert changes. | Meets the 5-second rule with no new event path (DC-D-14). | Proposed | DC-O-02 |
| AR-D-10 | The webhook address names a stable monitoring source key (AR-A-07). | Source identifiers change with every reset tenant. | Proposed | DC-O-04, SL-O-17 |
| AR-D-11 | A webhook is answered after its records are committed; grouping runs from the event. | Nothing is lost when Vigil stops after answering; webhook answers stay short during the storm. | Proposed | SL-B-02, DM-R-66 |
| AR-D-12 | Each AI provider call uses a new connection, and the JVM does not cache name lookups. | SL-F-4 and provider address changes take effect on the next call (SB-ASM-03). | Proposed | SL-A2-05 |
| AR-D-13 | Session lifetime 12 h and idle limit 4 h. | A presenter logs in before the presentation (SB-SET-07) and is not logged out during it. | Proposed | DM-O-05 |
| AR-D-14 | Claims about external products are recorded as assumptions with check addresses and spikes, because the official pages could not be read in this round. | CTX-Q-6 allows this; values are never invented (CTX-W-04). | Proposed | CTX-Q-6 |
| AR-D-15 | Model names and prices of the external providers are not fixed in this round (AR-O-01). | Model line-ups and prices change often and could not be read from the official pages; CTX-W-04 forbids invented values. | Proposed | DM-O-13 |

## 24. Later (not built) items added by this document (complete list)

| ID | Item | Trace |
| --- | --- | --- |
| AR-L-01 | Viewing or reactivating a dormant tenant (an earlier demo run or an earlier customer tenant). | BSD §12.2; PM-M01-L2 |
| AR-L-02 | Secret store schemes other than files (Example: a cloud key vault). | BSD §6.2 Enterprise controls |
| AR-L-03 | Several `vigil-server` processes and a message broker. | SL-C-07; BSD §14 |
| AR-L-04 | Pushing alert state and recovery changes on the stream instead of polling. | BSD §14 Responsiveness |

## 25. Explicitly out of scope for this document

Build packages, their order and sizes, the repository layout and conventions, `CLAUDE.md`, the automatic layout method of the canvas, the exact OpenAPI fields, and the lab build details (Round 6). Visual design of screens (`40-demo-cut` Section 12). Delivery dates (CTX-C-05). Real secret values and deployment values (deployment, AR-O-05).

## 26. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| AR-O-01 | Model names and prices of `claude` and `openai`, and the choice of the local chat model and the local embedding model (DM-O-13, AR-AI-11), read from the official pages and proved by AR-SPK-05 to AR-SPK-07. | Claude, reviewed by Abdul; costs approved by Muzaffar | Before the AI gateway build package (Round 6 plan) |
| AR-O-02 | CTX-O-02 carried: cloud provider, region, demo domain, approval of running costs (Section 15.4). | Muzaffar | Before R5 closes (CTX-O-02), unless the owner moves it to the hosting build package |
| AR-O-03 | CTX-O-04 carried: owner agreement to the GPU proposal and the 60 s answer time (AR-D-05). | Abdul | R5 owner review |
| AR-O-04 | Fact check of AR-ASM-01 to AR-ASM-51 against the official pages listed, with links recorded in Section 27; any claim found wrong is corrected through the decision log. | Claude (needs web page access approved in the session) | Before R6 closes |
| AR-O-05 | Deployment values: Jira site and transition identifiers, SMTP host, `lab-db-01` host key, and the secret values of Section 17.2. | Abdul | Deployment build package |
| AR-O-06 | Final form of the Teams link line (AR-CN-05a), decided by AR-SPK-03. | Claude | AR-SPK-03 |

Open items carried from earlier documents and still open after this round: CTX-O-01, CTX-O-02 (AR-O-02), CTX-O-04 (AR-O-03), CTX-O-05; SL-O-19 (Round 6); DM-O-13 (AR-O-01); DD-O-03; SB-O-03.

## 27. Quality-check results

| Check | Result |
| --- | --- |
| AR-Q-N1 Input versions | The repository held the versions of Section 2 at the start of Round 5, with a clean git working tree (last commit "demo cut defined"). The project mirror held the same versions of `00-context` (v1.8) and `40-demo-cut` (v1.0, content compared). |
| CTX-Q-1 Coverage both ways | Section 20.1 maps every item that earlier documents assigned to Round 5; Section 20.2 maps every scope-lock item. Every rule of this document traces to an earlier ID or a BSD section (trace columns). New records and responsibilities (tenant binding, monitoring source key, installation operations) trace to SL-O-17, PM-O-17 and DC-O-04 and are recorded as amendments (Section 19). |
| CTX-Q-2 Consistency | Compared with every earlier document. Conflicts resolved by amendment, not silently: webhook address needs a stable key (AR-A-07); Teams needs one secret per channel (AR-A-09); the local chat model needs a credential (AR-A-10); tenant routing for the reset (AR-A-08). |
| CTX-Q-3 Scenario walkthrough | Section 18. |
| CTX-Q-4 Independent review | Pending. |
| CTX-Q-5 Claude Code readiness test | Pending. |
| CTX-Q-6 Fact check | Not completed in this round. Web search is disabled for the organisation, and page fetches need the owner's approval, which timed out while the owner was away. Helper agents fetched some upstream sources through the shell; that route is not approved, so those results were discarded and are not used as evidence. Every external claim is recorded as an assumption with the official page to check and a spike (Section 22); the check itself is AR-O-04. |
| CTX-Q-7 Owner review | Pending: seven items (AR-D-01 to AR-D-07). |

## 28. Change history

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 9 Oct 2026 | First draft (Round 5). |
