# P-28 — Report: Web foundation, login and incident list

| Field | Value |
| --- | --- |
| Package | P-28 Web foundation, login and incident list (spec `docs/packages/P-28.md`) |
| Branch | `p-28-web-foundation-login-list` |
| Pin date (BP-R-08) | 13 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built. In this session (done-check item 3): `deploy/local.sh up` (exit 0), `deploy/local.sh e2e` (26 of 26 passed), `VIGIL_STORM_RESULTS=$HOME/storm-results tests/acceptance/storm/with-playwright.sh` (exit 0: the six `@storm` tests passed, the driver passed every check, AR-Q-07 passed) and `deploy/local.sh down --volumes` (exit 0) (Section 8); the gate command result is in Section 10. Open: the owner-run Mac step of spec Section 6.12 and the label `e2e` on the pull request. |

## 1. What was built

No change to `app/src/main/java`, the OpenAPI file or the database (spec Section 5).

| Part | Requirement IDs |
| --- | --- |
| **Build** (spec 6.2): `web/package.json` dependencies `react-router`, `@tanstack/react-query`, development dependency `openapi-typescript`, script `generate`; `web/src/api/generated/` in `.gitignore`, `web/.prettierignore` and ESLint's `ignores`; `app/pom.xml` execution `npm-run-generate` right after `npm-ci`. | BP-R-13 |
| **API client** `web/src/api/client.ts`: a typed `fetch` wrapper over the generated `paths` (`apiGet`, `apiPost`, `apiDelete`; the answer, query and body types come from the operation), same-origin `/api/v1` with `credentials: "same-origin"`, `X-Vigil-CSRF` from the cookie `vigil_csrf` on every method but GET, a 15 s abort, `ApiFailure {status, code, reason}` with the reasons of spec 6.4 (the default texts of BP-A-20, "A service is not available. Try again later." for `starting`, "No connection.", "Something went wrong. Try again."). `web/src/api/types.ts` names the generated schemas. | AR-W-02, BP-R-13, DC-U-09 |
| **Routes and session** (spec 6.3): `web/src/app/routes.tsx` (the route table: `/login`, `/no-access`, `/incidents`, `/` → `/incidents`, every other path the not-found state), `App.tsx` (AR-I-10 first; a failure shows only the DC-U-09 error state with an empty tab title on every route), `NotFoundPage.tsx`; `web/src/session/` (`validNext`, `loginAddress`, the queries of AR-I-10, AR-I-13, AR-I-15, the default page, the login messages); `web/src/layout/SignedInLayout.tsx` (AR-I-13 when a desktop page opens; the first 401 of any read of the page opens `/login?next=…` with "Your session ended. Sign in again." for `session_ended`; `view` checks for `/incidents`, `/no-access` and the not-found state; "Sign out" with 204 or 401 → `/login` with "You are signed out.", other failures "Could not sign out. {reason}"); `web/src/app/queryClient.ts` (`retry: false`, no re-read on focus or reconnection); `web/src/app/title.ts`. | AR-W-01, AR-B-10, DC-U-05, DC-U-13, DC-U-15, DC-S01-03, DC-S01-04, SL-A4-01 |
| **Layout** (spec 6.5): `Banner.tsx` (sticky at the top, a colour no other element uses, only for a non-empty label), `Header.tsx` (`{product}`, the logo or the customer name, the navigation with only "Incidents" and `aria-current="page"` plus a visible style on the current item, the zone note, the display name, "Sign out"; the reduced header of `/no-access`), `Customer.tsx`; the tenant topic on every desktop page. | DC-U-01, DC-U-02, DC-U-03, DC-U-04, PM-M24-02, SL-A3-06, SL-DR-03 |
| **Formats** `web/src/format/` (spec 6.6): `viewerTime`, `zoneNote` (through `Intl` with `hourCycle: "h23"`, fixed month abbreviations, the browser's zone or a zone passed in), `duration`, `incidentNumber`, the DC-TX-01 names of state, stage, severity and source kind, `count`, `isAre`. No relative age is shown. | DC-U-07, AR-W-06 |
| **States and tags** `web/src/states/states.tsx` (`Loading`, `Empty`, `PageError`, `PartError`, `ActionFailed`, `LiveInterrupted`), `web/src/tags/KindTag.tsx` (the eleven words of DC-U-10 as a TypeScript union, or a record label). | DC-U-09, DC-U-10, PM-M24-05 |
| **Live updates and polling** `web/src/live/` (spec 6.8): `LiveConnection` (one `EventSource` per screen on `/api/v1/live?topics=…`; per topic at most one invalidation per 500 ms and one more at the end of the window; every query of the screen invalidated on each `open` after the first; down from `error` to the next `open`; after an `error` with `readyState` `CLOSED` a new stream after 1 s, again each 1 s while it fails; the interrupted state after 5 s down, cleared on `open`), `useLiveTopics` (the topics' queries: `tenant` → AR-I-15, `incidents` → the rows, `alerts` and `sources` → the strip), `polling` (`refetchInterval` with `refetchIntervalInBackground: true`). | AR-W-02, DC-U-08, DC-U-09, AR-TM-09 |
| **Login and no-access** (spec 6.9): `web/src/login/LoginPage.tsx` (banner, `{product}`, customer, the message above the form, heading "Sign in", "Username" with `autocomplete="username"`, "Password" with `type="password"` and `autocomplete="current-password"`, "Sign in", the refusal or "Could not sign in. {reason}" under the button, the button disabled while the request runs, one column at most 360 px wide; a valid session goes to `next` or the default page; the message copied from the route state and the history entry replaced without state), `NoAccessPage.tsx`. | DC-S01-01, DC-S01-02, DC-S01-03, DC-S01-04, DC-S01-05, SL-A4-01, DC-U-15 |
| **Incident list** (spec 6.10): `web/src/incidents/` `IncidentsPage.tsx` (topics `incidents`, `alerts`, `sources`, `tenant`), `queries.ts` (the strip polled every 1 s; the rows as an infinite query: first page `limit=50`, "Show more" with `after={next_cursor}`, a re-read reads every page shown, in order, from the first), `Strip.tsx` (items (1) to (4); item (3) in the warning colour with the sources that are not healthy, no link), `Rows.tsx` (the ten columns and cells, the ticket link with `target="_blank"` and `rel="noopener noreferrer"`, a click elsewhere on the row opens `/incidents/{incident_id}`), `IncidentsView.tsx` (heading "Incidents"; `PageError` when both parts fail, `PartError` in the part that failed, `Loading` per part, the empty text with the strip's `total`, the table's `Loading` until the strip has answered and while it shows `PartError`, "Show more" and "Could not show more. {reason}"). The interrupted line under the header. | DC-S02-01, DC-S02-02, DC-S02-03, DC-S02-04, DC-S02-05, DC-U-06, DC-U-08, DC-U-12, SL-A1-01, SL-A1-02, SL-A4-02, PM-M24-01 |
| DC-S02-06: nothing of it is built (no filter, other sort, search or impact order). | DC-S02-06 (checked by review) |
| **`tests/e2e/` lint** (spec 6.11): `package.json` (development dependencies `eslint`, `@eslint/js`, `typescript-eslint`, `prettier`, `globals` at `web/`'s versions, `@types/node` 22.20.5, `typescript` 7.0.2 → 5.9.3; scripts `lint`, `format:check`, `typecheck`), `eslint.config.mjs`, `.prettierrc`, `.prettierignore`, `.dockerignore`, `package-lock.json` made again with `npm install --package-lock-only`; `app/pom.xml` executions `e2e-npm-ci`, `e2e-npm-run-lint`, `e2e-npm-run-format-check`, `e2e-npm-run-typecheck` after `npm-run-build`; `playwright.config.ts` and `smoke.spec.ts` reformatted by Prettier (no change of behaviour). | BP-R-09 (CTX-D-085 (10)) |
| **Local installation** (spec 6.11): `deploy/local/compose.yaml` `e2e` gets a `tmpfs` at `/e2e/tests/node_modules`; `deploy/local.sh` `playwright` runs `mkdir -p tests/e2e/node_modules` first, and `e2e` without a suite runs `--grep-invert "@ai-down|@storm"`; `tools/trace.py` `files_under` skips paths with a `node_modules` part. | BP-W-02, BP-R-15 |
| **`tests/acceptance/storm/with-playwright.sh`** (spec 6.11; bash 3.2, `shellcheck` clean) and `.github/workflows/e2e.yml` (`local installation`, `playwright suites`, `storm with the incident list`; the failure step unchanged). | BP-W-02 |
| `README.md` "Local installation" (the `@storm` tag, the order, `with-playwright.sh`, the `tmpfs`), `CLAUDE.md` "Commands" (the row for `with-playwright.sh`; "(runs `npm run generate` first)"), `tests/acceptance/storm/README.md` (files). | BP-DC-06 |

## 2. Tests added

Vitest (`web/src`, 60 tests in 15 files, Node environment, `renderToStaticMarkup`; behaviour over time on `LiveConnection` with fake timers and a test `EventSource`, and on `QueryObserver`/`InfiniteQueryObserver` with a `QueryClient`):

| Test | IDs |
| --- | --- |
| `format/time.test.ts`: today and other days, every month abbreviation, `00:05:00`, the day boundary in `Asia/Karachi`; the zone note for +05:00, +00:00, +05:30, -03:00. | DC-U-07, AR-W-06 |
| `format/duration.test.ts`: 0, 119, 120, 180, 181, 3600 s. | DC-U-07 |
| `format/names.test.ts`: numbers (`INC-0002`, `INC-12345`), states, stages, severities, source kinds; alert/alerts, is/are; monitoring source/sources. | DC-S02-04, DC-S02-02, DC-S02-05 |
| `api/client.test.ts`: the 15 s abort gives "No connection." (not at 14.999 s), a network failure, the AR-I-03 message, the default texts (including `starting`), answers not in the AR-I-03 format; the CSRF header on `DELETE` and none on GET; same-origin path and query. | AR-W-02, DC-U-09 |
| `session/next.test.ts`: accepted and refused `next` values; the encoded login address. | DC-U-13 |
| `live/live.test.ts`: one stream with the screen's topics; at most one invalidation per 500 ms per topic and one more at its end; every query on a reconnection, not on the first open; a new stream 1 s after a `CLOSED` error and again while it fails; the interrupted state after more than 5 s, removed on `open`; none for a shorter outage. | AR-W-02, DC-U-09 |
| `layout/tenant.test.tsx`: a `tenant` notice re-reads `/api/v1/tenant` and the header shows the new product. | DC-U-02 |
| `incidents/strip.test.tsx`: each item, the warning class and the source list, "No alerts in the last 10 minutes."; the 1 s interval; the strip with 127 alerts, the grouping text and 10 lines. | DC-S02-02, AR-TM-09, SL-A1-01 |
| `incidents/rows.test.tsx`: columns and every cell, empty cells, the ticket link's `target` and `rel`; names not keys; the rows are exactly the answer's, in its order. | DC-S02-04, DC-U-06, DC-S02-03 |
| `incidents/pages.test.ts`: "Show more" adds a page; a re-read reads every page from the first and keeps the number of pages. | DC-U-12 |
| `incidents/states.test.tsx`: `PageError` only when both parts fail, `PartError` in the failing part, loading per part, a failed "Show more"; the empty text; the table's `Loading` until the strip answered and while it shows `PartError`. | DC-U-09, DC-S02-05 |
| `states/states.test.tsx`: each state's text and the "Try again" buttons. | DC-U-09 |
| `tags/tags.test.tsx`: every word and a record label; one tag component for every kind. | DC-U-10, PM-M24-05 |
| `layout/header.test.tsx`: the order of the items, logo or name, the reduced header and the sign-out failure; the navigation only with `view`, the current item marked; no banner for a null or empty label. | DC-U-03, DC-U-04, DC-U-01 |
| `app/routes.test.ts`: the route table, every other path (the AR-W-01 paths not built yet included) to the not-found state; "BP-R-13 the API client uses the generated types" (type-level checks compiled by `tsc`, with two `@ts-expect-error` calls outside the contract). | AR-W-01, BP-R-13 |

Playwright (`tests/e2e/`, user `presenter` unless named): every test of spec Section 7 with the titles given there — `login.spec.ts` (18 tests, three `@smoke`), `incidents.spec.ts` (4 tests, one `@smoke`; the file runs in `Asia/Karachi`), `storm.spec.ts` (6 tests `@storm`, one serial file sharing one page, 20 minutes per test), with `helpers.ts`. pytest: `tests/acceptance/storm/test_with_playwright.py` (12 cases "BP-W-02 …": usage (4), the missing `values-file`, order and success, the default mode, marker never comes, suite ends first, driver fails, suite fails after the driver, relative and absolute `VIGIL_STORM_RESULTS`); `deploy/test_local_script.py` (the pattern `@ai-down|@storm` in two cases, "BP-W-02 the suite storm …", "BP-W-02 e2e makes tests/e2e/node_modules …"); `tools/test_trace.py` "BP-R-15 files under node_modules are not read"; `deploy/test_images.py` "BP-R-04 the Playwright base image's version …" changed (Section 3 item 13).

## 3. Deviations from the spec

None changes a behaviour the spec states. Readings and details the spec left open:

1. **Banner and header while AR-I-15 loads.** After sign-in they show AR-I-15's answer; until it has answered (or when it fails) they show AR-I-10's, which the application has already read (the same tenant settings), so the banner never disappears on a page change.
2. **The login screen while AR-I-13 runs** shows `Loading` in place of the form, so a user with a valid session goes on to `next` without seeing a form. Any failure of that read (401 or another) shows the form.
3. **Earlier reads are dropped on `/login`.** When `/login` opens, every cached read except AR-I-10 is removed, so after a reset and a new sign-in no row or strip of the earlier tenant is shown even for a moment; the login screen's own AR-I-13 read is never answered from the cache.
4. **During sign-out** a 401 of another read (the strip's polling after the session ended) does not open `/login?next=…`, so the message is "You are signed out.".
5. **The not-found state's "Try again"** reloads the page (`navigate(0)`).
6. **`PageError` of the incident list** shows the strip's reason; its "Try again" re-reads both parts.
7. **A failed re-read of the rows** (a notice, a reconnection or "Try again", not "Show more") shows `PartError` in the table in place of the earlier rows, as for a failed first read.
8. **"Last update {time}"** is the latest successful read of the screen's queries; if the screen has none yet, the time the line appeared.
9. **The live stream** is opened once AR-I-13 has answered and the user may see the page, not before (a stream before that would only answer 401).
10. **The login messages** are shown above the heading "Sign in" (above the form, spec 6.9).
11. **`web/src/incidents/fixtures.ts`** holds test data used only by the Vitest files; `tools/loc.sh` counts its 41 lines (its name does not end in `.test.ts`, which Vitest would run as a test file).
12. **The storm test DC-S02-03** keeps sampling after both rows exist until the three "seen at least once" items were seen or 600 s passed (in this session they were all seen before the second row).
13. **`deploy/test_images.py::test_e2e_image_version_equals_the_package`** (a P-26 test, not in spec Section 7) asserted that `tests/e2e/node_modules` does not exist on the host (CTX-D-081 (4)). The Maven build's lint of `tests/e2e/` now makes that folder (spec 6.11, CTX-D-085 (10)), so the gate failed on it. Its last assertion now checks the amended rule instead: `tests/e2e/.dockerignore` keeps `node_modules` out of the image's build context and the `e2e` service hides it with the `tmpfs` mount.

## 4. Document feedback (CTX-Q-8)

1. **Order of the two incidents.** As in P-27's runs, the report incident was `INC-0001` and the storm incident `INC-0002` in this session's storm run, so the storm row is the first row of the list. The `@storm` tests check "the higher number first" without assuming which.
2. **`vigil/e2e:local` now also installs the lint packages** (its `npm ci` installs every development dependency of `tests/e2e/package.json`); BP-R-04's text ("`npm ci` for `tests/e2e/`") still holds.
3. **Cloud session network.** Maven Central answered existing artifacts as absent several times in this session (the Spring Boot parent POM in the first `deploy/local.sh up`; Spotless's plugin dependencies and `archunit` 1.5.1 in two gate runs, after every Java test had passed), and Maven caches each miss (`*.lastUpdated`). Deleting the markers and resolving every dependency and plugin first (`./mvnw -B -q dependency:resolve dependency:resolve-plugins`) made the next runs pass. Not a document error; noted for later sessions.
4. **CTX-D-081 (4)'s P-26 test.** `deploy/test_images.py` still asserted "no `node_modules` on the host" (Section 3 item 13); the decision log's CTX-D-085 (10) amends that rule, but the spec's file list did not name the test. Changed here so the gate can pass.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 13 November 2026)

| Package | Version | Where | Note |
| --- | --- | --- | --- |
| `react-router` | 8.5.1 | `web/` dependency | Latest release (27 Oct 2026); needs Node.js ≥ 22.22.0 (the build uses 22.23.3). Brings `@remix-run/route-pattern` 0.22.1 and `cookie-es` 3.1.1. |
| `@tanstack/react-query` | 5.107.1 | `web/` dependency | Latest release (6 Nov 2026); brings `@tanstack/query-core` 5.107.1. |
| `openapi-typescript` | 7.13.0 | `web/` development dependency | Latest release. |
| `@types/node` | 22.20.5 | `tests/e2e/` development dependency | Latest 22.x. |
| `eslint` 10.12.0, `@eslint/js` 10.0.1, `typescript-eslint` 8.71.2, `prettier` 3.9.10, `globals` 17.13.0 | as `web/` | `tests/e2e/` development dependencies | `web/`'s pins. |
| `typescript` | 7.0.2 → 5.9.3 | `tests/e2e/` | The change named by the spec (CTX-D-085 (10)). |

## 7. Values recorded under `docs/`

None yet: `tests/acceptance/results/P-28-mac.txt` is written by the owner-run step (spec Section 9).

## 8. Owner-run results

Owner-run on the Mac (spec Section 6.12): not yet run; the owner runs `deploy/local.sh up --profile demo`, `deploy/local.sh e2e`, `tests/acceptance/storm/with-playwright.sh`, the look at the login and the list in a browser during a storm, and `deploy/local.sh down`, into `tests/acceptance/results/P-28-mac.txt`, and adds the label `e2e` to the pull request.

Done-check item 3, in this session (cloud, lab profile `test`):

- `deploy/images.sh --test` then `deploy/local.sh up`: exit 0, "Tenant … is ready at demo.vigil.test." (after the retry of Section 4 item 3).
- `deploy/local.sh e2e`: **26 passed** (12.3 s): `login.spec.ts` 18, `incidents.spec.ts` 4, `smoke.spec.ts` 4; the `@ai-down` listing found no test.
- `VIGIL_STORM_RESULTS=$HOME/storm-results tests/acceptance/storm/with-playwright.sh`: **exit 0**. The suite `storm`: **6 passed** (3.2 min): DC-S01-04 (15.2 s; the session ended by the reset, sign-in back on `/incidents`), DC-S02-05, DC-S02-03 (1.2 min), SL-A1-02 (1.7 min), DC-U-08, DC-S02-04. The driver (mode `immediate`): reset 12.3 s (branch A 12.2 s, branch B 1.1 s); storm `INC-0002` (ticket `NOC-2`), report `INC-0001` (ticket `NOC-1`); all 33 checks of mode `immediate` passed (`storm-immediate-20261113T070527Z.json`, `passed: true`); "AR-Q-07 passed: no secret of the installation in the log of this run."
- `deploy/local.sh down --volumes`: exit 0.

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

1,784 (BP-R-05 limit for M 2,000; spec estimate about 1,800): `web/src` 1,780 (including the 41 lines of `fixtures.ts`, Section 3 item 11), `deploy/local.sh` 4, `deploy/local/compose.yaml` 2 (YAML, not counted by the tool). CSS Modules, tests, scripts under `tests/` and generated types are not counted.

## 10. Gate command result

GATE_RESULT

## 11. Follow-up items for later packages

- P-29: `/incidents/{id}` is the not-found state until DC-S03 is built; `SignedInLayout` (`access`, `screen`, `topics`, `live`), `TOPIC_QUERIES` (add `incident:{id}` and `run:{id}`), `KindTag`, `viewerTime` and `duration` are ready; the `jira-fake` ticket link does not open in a browser (CTX-D-082 (6)).
- P-30: the mobile pages need their own layout without the desktop header (AR-W-07) and `polling(1000)`; DC-U-11's dialog.
- P-33, P-35 to P-39: add each built screen to `NAVIGATION` in `web/src/layout/Header.tsx` in DC-U-04's order (BP-R-22) and its route to `ROUTE_TABLE`.
- P-38: the link of DC-S02-02 item (3) to `/setup` (CTX-D-085 (7)) in `Strip.tsx`.
- P-32: the Playwright suites against the demo host (`VIGIL_BASE_URL`).
