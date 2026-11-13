# P-29 — Report: Incident workspace and run canvas

| Field | Value |
| --- | --- |
| Package | P-29 Incident workspace and run canvas (spec `docs/packages/P-29.md`) |
| Branch | `p-29-incident-workspace-run-canvas` |
| Pin date (BP-R-08) | 13 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built; the gate command passes (Section 10). In this session (done-check item 3): `deploy/local.sh up` (exit 0), `deploy/local.sh e2e` (39 of 39 passed: the 38 tests of P-26, P-28 and P-44 and `workspace.spec.ts`), `VIGIL_STORM_RESULTS=$HOME/storm-results tests/acceptance/storm/with-playwright.sh` (exit 0: the 26 `@storm` tests passed, the driver passed 33 of 33 checks, AR-Q-07 passed) and `deploy/local.sh down --volumes` (exit 0); the four screenshots are committed under `tests/acceptance/results/P-29/` (Section 8). The owner-run Mac step (spec Section 6.12) and the label `e2e` are open. |

## 1. What was built

No change to `app/`, the OpenAPI file, the database or `deploy/` (spec Section 5).

| Part | Requirement IDs |
| --- | --- |
| **Dependency** (spec 6.1): `@xyflow/react` 12.12.1 in `web/package.json` (exact) and the lock file (Section 6). | AR-T-09 |
| **API client** (spec 6.2 item 1): `RequestOptions.params`; `send` replaces each `{name}` of the path with `encodeURIComponent(params[name])` before the query is added. `api/types.ts` names the workspace's schemas. | AR-W-02 |
| **Route** (spec 6.2 item 2): `ROUTE_TABLE` adds `{ path: "/incidents/:incidentId", screen: "workspace" }` before `*`; the screen renders `WorkspacePage` (keyed by the incident id, so a new address starts a new page state). | AR-W-01, AR-I-100, PM-M24-01, DC-U-13 |
| **Reads** (`workspace/queries.ts`, spec 6.2 item 3): the nine query keys of the spec's table; the recovery query's `refetchInterval` is 1000 ms while any verification of its last answer is `observing`, else off, with `refetchIntervalInBackground: true`. | AR-I-05, AR-W-02 |
| **Live topics** (spec 6.2 item 4): `topicQueries(topic)` replaces `TOPIC_QUERIES` with the spec's complete list; invalidation by prefix; `queriesOf` keeps its meaning. `LiveConnection` takes `rereadOnFirstOpen`; `useLiveTopics` passes it for every stream after the first of a page, so a stream opened because the topics changed re-reads on its first open. The interrupted line's "Last update" is `lastUpdate(client, keys)`, the latest `dataUpdatedAt` of `getQueryCache().findAll({ queryKey })` over the keys. `SignedInLayout` gives its pages `InterruptedContext` (whether the line is shown). | AR-I-04, AR-W-02, DC-U-08, DC-U-09 |
| **Page** (`WorkspacePage.tsx`, spec 6.2 item 5, 6.3): `SignedInLayout` with `access="view"`, `live`, the topics `incident:{incident_id}` (from the answer), `run:{run_id}` (from AR-I-29) and `alerts`; tab title "{number} {title} — {product}" once AR-I-22 answered an incident, `{product}` alone before and on the error state; a `MergedIncident` answer navigates to `/incidents/{merged_into_incident_id}` with `replace`; `PageBody` shows `Loading` in a card, `PageError` (also for a failed re-read after an answer) or the workspace. | DC-S03-01, DC-U-09, DC-U-15, SL-A4-03 |
| **Parts and their states** (`Section.tsx`, `WorkspaceView.tsx`, spec 6.3): `partContent` gives `Loading` until the first answer, `PartError` when the latest read failed, else the content; the run band is shown while AR-I-29 first loads, with a run or failed; Response when `similar` is not `null` or failed; Recovery when a verification exists or failed; an anchor exactly when its section is shown. | DC-U-09, DC-S03-06, DC-S03-10, DC-S03-11 |
| **Header card** (`Header.tsx`, spec 6.4): the page heading and the labelled values in the spec's order and conditions; the state chip, stage chip, severity badge; "no" in the warning colour; the ticket link with the external-link glyph; empty values for `null`. | DC-S03-02, SL-A1-04, SL-A2-03, DC-U-06, DC-U-07, VD-C-17 |
| **Main column** (spec 6.5): anchors (`nav`); "Hypothesis and customer impact" (`Hypothesis.tsx`, the DC-TX-02 sentence, the rule and tie-break lines, the impact lines); "Alerts ({alert_count})" (`Alerts.tsx`, the counts, one disclosure per group, the alert lines, "Why grouped" with the reason of `reasons.ts`); "Evidence ({n})" / "Evidence" (`Evidence.tsx`, the kind tags with "Not recovered" by the summary, the diagnostic disclosure with its output values); "AI investigation" (`Investigation.tsx`); "Response" (`Response.tsx`: similar, proposal, approvals, executions); "Recovery" (`Recovery.tsx`, "Holding for {s} s of …" counted by `useNow`). Open groups, reasons, diagnostic entries and timeline entries are kept per key in `WorkspaceView` state, so they stay open across re-reads and are closed when the page opens. | SL-A1-02, SL-A1-03, SL-A1-04, SL-A1-06, SL-A1-07, PM-M24-05, SL-DR-03, DC-S03-03 to DC-S03-05, DC-S03-08 to DC-S03-11, DC-TX-00 to DC-TX-03, DC-U-10, VD-C-20, VD-C-21, VD-C-22, VD-C-23 |
| **Run band and canvas** (`web/src/canvas/`, spec 6.6): `graph.ts` (the stored content's shape, nodes with their step states, outputs, taken outputs, countdown due time, `current`, `followTarget`; edges `{from}--{output}` with `ariaLabel: null` and taken), `summaries.ts` (DC-TX-05 card summaries and DM Section 9 named outputs), `follow.ts` (follow target, zoom steps, move durations), `StepCard.tsx` (card, handles with `isConnectable={false}`, state row, countdown), `nodes.tsx` (eleven node types), `RunCanvas.tsx` (`RunBand`, `BandBar`, `canvasProps`, `expandedTop`; follow by `setCenter` at zoom 0.8 on the first render, on each target change and on a size change of the canvas or the target card; Follow, zoom in and out, Fit, Expand and Close; `onMove` with an event turns Follow off; the expanded card's `top` from the header or the interrupted line, measured again on the line's change and on resize; `overflow: hidden` on the root element while expanded). `base.css` is imported before `canvas.module.css`. | SL-WE-10, SL-A1-08, PM-M24-04, AR-W-03, AR-T-09, DC-S03-06, DC-S03-07, DC-S06-05, DC-S06-06, DC-TX-05, DC-U-07, VD-C-18, VD-C-19, VD-R-11, VD-R-14 |
| **Side column** (`Side.tsx`, `timeline.ts`, spec 6.7): run steps; the timeline with both collapsing rules as disclosures; notified teams ordered by time, then name. | DC-S03-12, DC-S03-13, DC-S03-14, DC-TX-04, DC-U-06 |
| **Names and formats** (spec 6.8): `names.ts` adds the complete list of the spec (component kind, criticality with "not set", provider kind and display name, AI unavailable reason, approval request state, policy check result, action execution state, recheck name, notified purpose, run state, step type, AI task, output display text) and `listText` (DC-TX-00 Lists); `time.ts` adds `countdown` and `elapsedSeconds`; `workspace/useNow.ts` ticks every second only while active. | DC-TX-01, DC-TX-00, DC-U-07 |
| **Shared badges** (spec 6.9): `web/src/badges/Badges.tsx` (`SeverityBadge`, `StateChip`, `StageChip`) and `badges.module.css` with P-44's rules moved unchanged from `incidents.module.css`; `Rows.tsx`, the header, the band and the cards use them. | VD-C-07 |
| **Styles** (spec 6.10): `workspace.module.css` and `canvas.module.css` with the tokens only; no keyframes; the canvas module overrides the connection stroke, the handles and the pane background with selectors more specific than `base.css`. | VD-S-05, VD-L-05, VD-C-17 to VD-C-23, VD-R-01 to VD-R-05, VD-R-07, VD-R-08, VD-R-12, VD-R-14 |
| **Storm script** (spec 6.11 item 5): `with-playwright.sh` writes the mode into `{base}/{project}/e2e/storm-mode` (creating the folder) before it starts the suite; `README.md` names the file. | BP-W-02 |
| **Playwright** (spec 6.11): `workspace.spec.ts`; `recorder.ts` (the init script, the profile and mode readers, the minimums, the highlight measure); `storm.spec.ts` with the workspace context, the navigation started from "DC-S02-03 …", and the tests of spec Section 7 in the order of spec 6.11 item 1. | SL-A1-08, DC-U-08, VD-S-05 and the IDs of spec Section 7 |
| VD-R-06 (checked by review): every coloured state keeps its text: the chips and badges (P1 to P4, Open, Resolved, Closed, stage and run state names), the kind tags, the outcome words ("Holding", "Not holding", "passed", "failed", "Firing", "Resolved"), "Waiting", "Cancelled", the output tags and "no". VD-R-13 (checked by review): no component or token beyond `45-visual-design` v1.1. | VD-R-06, VD-R-13 |

## 2. Tests added

Vitest (`web/src`, 146 tests in 42 files after P-44's 76 in 19: 70 new tests, in 23 new files and five extended ones (`api/client.test.ts`, `app/routes.test.ts`, `format/names.test.ts`, `format/time.test.ts`, `app/contrast.test.ts`)): every test of spec Section 7, named as there, in the files the spec names (`api/client.test.ts`, `live/reopen.test.ts`, `live/topics.test.ts`, `app/routes.test.ts`, `format/names.test.ts`, `format/time.test.ts`, `workspace/queries.test.ts`, `workspace/page.test.tsx`, `workspace/header.test.tsx`, `workspace/hypothesis.test.tsx`, `workspace/reasons.test.ts`, `workspace/alerts.test.tsx`, `workspace/evidence.test.tsx`, `workspace/investigation.test.tsx`, `workspace/response.test.tsx`, `workspace/recovery.test.tsx`, `workspace/timeline.test.ts`, `workspace/side.test.tsx`, `workspace/sections.test.tsx`, `canvas/graph.test.ts`, `canvas/summaries.test.ts`, `canvas/follow.test.ts`, `canvas/card.test.tsx`, `canvas/band.test.tsx`, `badges/badges.test.tsx`, `app/contrast.test.ts`, `workspace/styles.test.ts`, `workspace/texts.test.tsx`). Fixtures typed by the generated types are in `web/src/test/` (`fixtures.ts`, `content.ts`: the seeded workflow version 1 with the stored parameter names, generated from `06-workflow.yaml`; `render.tsx`).

Playwright: `workspace.spec.ts` "DC-S03-01 an unknown incident shows Could not load this page. Not found."; `storm.spec.ts` `@storm`: "SL-A1-08 the storm workspace opens and records the run", "SL-A1-08 the highlight followed every step change within 2 s", "DC-S03-06 follow mode keeps the current step in view", "DC-S03-07 countdowns and the observation window were counted", "SL-A1-02 the storm workspace header, hypothesis, impact and alerts", "SL-A1-03 the evidence lines", "SL-A1-04 the AI investigation section", "SL-A1-06 the Response section", "SL-A1-07 the Recovery section", "SB-S08 the finished run on the canvas and in the run steps", "VD-C-18 the band and the expanded canvas", "DC-S03-13 the timeline and notified teams", "DC-U-08 the workspace changed without a reload", "DC-U-15 the workspace tab title", "VD-S-05 the workspace fits 1280 px with its two columns", "VD-C-22 the section cards and their headings", "VD-C-23 the outcome words in their colours", "DC-S03-01 the report incident's workspace".

pytest: `tests/acceptance/storm/test_with_playwright.py` "BP-W-02 the mode is written to storm-mode before the suite starts".

**P-28 and P-44 tests changed** (spec 6.9, complete list; no expected text, colour or attribute changed otherwise): `incidents/rows.test.tsx` imports the badge classes from `badges/badges.module.css`; `app/routes.test.ts` "AR-W-01 the route table of P-28" expects `/incidents/:incidentId`, and "AR-W-01 every other path…" no longer lists `/incidents/{uuid}`; `app/contrast.test.ts` adds `--vd-green-700` on `--vd-surface` and `--vd-surface-2`, `--vd-accent-700` on `--vd-surface` (54 text pairs) and the graphic pair `--vd-accent-500` on `--vd-surface-2`; `storm.spec.ts` "DC-S01-04 …" creates the workspace context after its second sign-in and "DC-S02-03 …" starts the workspace's navigation.

## 3. Deviations from the spec

None changes a text, value or behaviour the spec states. Readings of points the spec leaves open:

1. **The run's last step in the highlight test** (a guard; not needed in this session's run, where a change to `s-resolve` was recorded 641 ms after its start). The test measures every step execution that started after the first recorded current card (spec Section 7). The storm run's last execution (`s-resolve`, a Ticket step) has no later step, and when it runs and completes between two re-reads of the page, no card ever carries `data-current` for it (the run is then finished, `current_step_execution_id` is `null`). `highlightDelays` (in `tests/e2e/recorder.ts`) therefore measures the run's last execution only when a change to it was recorded; every other execution is measured as the spec says, and a missing change fails the test.
2. **`ariaLabel: null`** on the edges is cast through `unknown`, because the library's `Edge` type does not list `null`, which its code reads as "no `aria-label`" (CTX-D-089 (3)).
3. **Handles** keep the library's classes `connectablestart` and `connectableend` (its defaults for `isConnectableStart`/`isConnectableEnd`); they have no effect while `isConnectable={false}`, and the class `connectable` is absent (the Playwright test checks that class).
4. **The countdown** rounds the remaining time up to whole seconds, so it reads "0:00" exactly from `timer_due_at` on.
5. **The expanded card's `top`** is measured from the `header` element and, when the interrupted line is shown, from the element just above `main` (the line); `expandedTop` chooses between them.
6. **Spaces the documents do not state** use the space tokens (for example, the blocks of a section 12px apart, the lines inside a block 4px apart).
7. **`useNow`** sets the clock with a `setTimeout(…, 0)` when it becomes active (the lint rule `react-hooks/set-state-in-effect` refuses a synchronous update), then every second.

## 4. Document feedback (CTX-Q-8)

1. **The Maven note of CTX-D-088 (4)** worked as written in this session, for a miss it does not name as an example: the first gate run stopped at step (2) because Maven Central answered `org.springframework.boot:spring-boot-starter-parent:pom:4.1.1` as absent (the parent POM of `app` and `demo/control`, so no project could be read; a direct request answered 200 a minute later). Deleting the `*.lastUpdated` markers and running the two `resolve` goals with full coordinates fetched it; `surefire-junit-platform` 3.5.6, `junit-platform-launcher` 6.0.3 and Spotless's `google-java-format` 1.36.1 (absent at its first request, fetched by the Spotless `check` run of the note on a second attempt) were fetched before the second gate run, which passed. No change to the note is suggested.
2. **The canvas library's types** do not list `null` for `Edge.ariaLabel`, which its code reads as "no accessible name" (CTX-D-089 (3), spec 6.6 item 9); the code casts it (Section 3 item 2). This works in 12.12.1; P-37 keeps it in mind when the library is upgraded.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 13 November 2026)

| Package | Version | Where | Licence | Note |
| --- | --- | --- | --- | --- |
| `@xyflow/react` | 12.12.1 | `web/` dependency (exact) | MIT | The latest 12.x release on the pin date (published 2 Nov 2026); AR-T-09. |
| `@xyflow/system` | 0.0.84 | lock file (of `@xyflow/react`) | MIT | |
| `classcat` | 5.0.5 | lock file | MIT | |
| `zustand` | 4.5.7 | lock file | MIT | |
| `use-sync-external-store` | 1.7.0 | lock file (of `zustand`) | MIT | |
| `d3-color` 3.1.1, `d3-dispatch` 3.0.2, `d3-drag` 3.0.1, `d3-interpolate` 3.0.2, `d3-selection` 3.0.1, `d3-timer` 3.0.2, `d3-transition` 3.0.2, `d3-zoom` 3.0.1 | as listed | lock file (of `@xyflow/system`) | ISC | |
| `d3-ease` | 3.0.2 | lock file | BSD-3-Clause | |
| `@types/d3-color` 3.1.3, `@types/d3-drag` 3.0.7, `@types/d3-interpolate` 3.0.4, `@types/d3-selection` 3.0.12, `@types/d3-transition` 3.0.9, `@types/d3-zoom` 3.0.9 | as listed | lock file (of `@xyflow/system`) | MIT | |

## 7. Values recorded under `docs/`

None under `docs/` besides this report. The files of spec Section 9: `tests/acceptance/results/P-29/` (the cloud session's `workspace-storm`, `workspace-canvas-fit`, `workspace-canvas-expanded` and `workspace-report`); `tests/acceptance/results/P-29-mac.txt` and `tests/acceptance/results/P-29-mac/` are the owner's (Section 8).

## 8. Owner-run results

Owner-run on the Mac (spec Section 6.12): **not yet done** — `up --profile demo`, the suites, `with-playwright.sh --mode script` with the `demo` profile's exact values, the copy of the screenshots into `tests/acceptance/results/P-29-mac/`, the look at the workspace in a browser during a storm, `down`, and the label `e2e` on the pull request. The test reads the profile from `/run/state/lab-profile` and the mode from `/e2e/run/storm-mode`, so the Mac run checks DD-F-10's development values and the minimums of mode `script` (3 step executions, 3 follow distances, a "Waiting" sample of each of `s-wait-ack` and `s-approve`).

Done-check item 3, in the build session (cloud, lab profile `test`, mode `immediate`):

- `deploy/local.sh up`: exit 0, "Tenant 01a1d65e-3e78-7247-9aa1-2de56ca55384 is ready at demo.vigil.test."
- `deploy/local.sh e2e`: **39 passed** (17.5 s): `login.spec.ts` 18, `look.spec.ts` 12, `incidents.spec.ts` 4, `smoke.spec.ts` 4, `workspace.spec.ts` 1 ("DC-S03-01 an unknown incident shows Could not load this page. Not found.").
- `VIGIL_STORM_RESULTS=$HOME/storm-results tests/acceptance/storm/with-playwright.sh`: **exit 0**. The suite `storm`: **26 passed** (3.4 min), in the order of spec Section 6.11 item 1: DC-S01-04 (14.1 s), DC-S02-05, DC-S02-03 (1.2 min), SL-A1-08 opens, SL-A1-02 rows (1.8 min), DC-U-08 list, VD-L-03, VD-C-07, SL-A1-08 highlight, DC-S03-06, DC-S03-07, SL-A1-02 workspace, SL-A1-03, SL-A1-04, SL-A1-06, SL-A1-07, SB-S08 (2.3 s), VD-C-18, DC-S03-13, DC-U-08 workspace, DC-U-15, VD-S-05, VD-C-22, VD-C-23, DC-S03-01 report (1.3 s), DC-S02-04.
  - Highlight delays (ms, from each step's `started_at` to the recorded highlight): `s-investigate` 114, `s-summarise` 223, `s-ticket` 197, `s-notify` 167, `s-wait-ack` 333, `s-similar` 296, `s-approve` 249, `s-action` 147, `s-recover` 408, `s-resolve` 641 (10 measured; limit 2,000; minimum 1 in mode `immediate`). The steps before `s-investigate` started before the workspace first showed the run.
  - Follow distances 500 ms after each change (px, at zoom 0.8): `s-diagnose` 0.00, `s-notify` 0, `s-approve` 0.16, `s-action` 0, `s-recover` 0.16, `s-resolve` 0.16 (6 taken; limit 2).
  - The driver (mode `immediate`): reset 12.2 s (branch A 12.2 s, quiet 0.04 s, branch B 1.0 s); storm `INC-0002`, report `INC-0001`; **33 of 33** checks passed, 4 under `not_run` (those of mode `script`), `passed: true` (`storm-immediate-20261113T192501Z.json`, kept outside the repository as in P-44); "AR-Q-07 passed: no secret of the installation in the log of this run."
- Screenshots (spec Section 6.11 item 4), committed under `tests/acceptance/results/P-29/`: `workspace-storm.png` (the storm workspace at the end, in follow mode), `workspace-canvas-fit.png`, `workspace-canvas-expanded.png` (window only) and `workspace-report.png`. In the full-page shots the sticky banner appears where the page was scrolled to when the shot was taken, as in P-44's full-page shots.
- `deploy/local.sh down --volumes`: exit 0.

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

**2,543** (BP-R-05 limit for L 3,500; spec estimate about 2,500): `web/src` TypeScript only. CSS Modules, tests, the fixtures under `web/src/test/` and `tests/e2e/` are not counted.

## 10. Gate command result

`tools/check-all.sh` passed in this session (exit 0, "all checks passed"): step (0) Docker, (1) setup version, (1a) test images, (2) `./mvnw -B verify` (web: `npm ci`, `npm run generate`, lint, Prettier check, type check, Vitest **146 tests in 42 files**, build; `tests/e2e/`: `npm ci`, lint, Prettier check, type check; `vigil-app` 197 unit tests and 776 integration tests, `demo-control` 1 and 2 tests, 0 failures, 0 errors; Spotless; `BUILD SUCCESS`), (3) `ruff` and pytest: 509 passed, 1 skipped, 39 deselected ("lab tests skipped: no change to the lab", as the spec header says), (4) shellcheck, (5) data checks (49 checks, 0 failed), (6) compose validation, (7) traceability. The first run stopped at step (2) on a Maven Central miss before any project was read (Section 4 item 1); the run reported here is the full run after the note's steps.

## 11. Follow-up items for later packages

- P-30: uses the API client's `params` (CTX-D-089 (13)).
- P-37: the editor reuses `StepCard`, `nodes.tsx`, `summaries.ts`, `graph.ts` and the canvas module's rules (VD-C-19, VD-R-14); it adds selection, connecting, the palette, the parameter panel, the wheel zoom of DC-S06-10 and `elkjs`.
- P-39: DC-S03-09's "Re-run AI investigation" with its confirmation and "Re-running…", and the link "AI usage of the latest investigation" (`ai_last_request_group_id` is read by AR-I-25 but not shown).
- The owner: the Mac step of spec Section 6.12 and the label `e2e` on the pull request.
