# P-33 — Report: Dashboard screen

| Field | Value |
| --- | --- |
| Package | P-33 Dashboard screen (spec `docs/packages/P-33.md`) |
| Branch | `p-33-dashboard` |
| Pin date (BP-R-08) | 26 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built; the gate command passes (Section 10). Done-check item 3 in this session (lab profile `test`, mode `immediate`): `deploy/local.sh up` (exit 0), `deploy/local.sh e2e` (54 of 54 passed), STORM_STATE, `deploy/local.sh down --volumes` (exit 0); the four screenshots are committed under `tests/acceptance/results/P-33/` (Section 8). The owner-run Mac step (spec Section 6.7) and the label `e2e` are open. |

## 1. What was built

No change to `app/`, the OpenAPI file, the database, `deploy/` or the storm driver (spec Section 5).

| Part | Requirement IDs |
| --- | --- |
| **Route** (spec 6.2 item 1, `web/src/app/routes.tsx`): `Screen` gains `"dashboard"`; `ROUTE_TABLE` is `/login`, `/no-access`, `/incidents`, `/incidents/:incidentId`, `/m/acknowledge/:incidentId`, `/m/approve/:requestId`, `/dashboard`, `/`, `*`; the element is `<DashboardPage />`, not lazy (`LAZY_SCREENS` unchanged). | SL-A4-10, AR-W-01 |
| **Navigation** (spec 6.2 item 2, `web/src/layout/Header.tsx`): `NAVIGATION` is Incidents (`/incidents`), Dashboard (`/dashboard`); the existing `NavLink` with `end` marks "Dashboard" on `/dashboard` only. | DC-U-04, BP-R-22, VD-R-12 |
| **Page** (spec 6.2 item 3, `web/src/dashboard/DashboardPage.tsx`): `<SignedInLayout access="view" screen="Dashboard" topics={TOPICS} live={false}>` with `TOPICS = []` (`queries.ts`): a user without `view` goes to the no-access page; tab title "Dashboard — {product}"; the stream has the topic `tenant` only; no live-interrupted line. | DC-S10-01, DC-U-05, DC-U-15, AR-I-04 |
| **Read** (spec 6.3 item 1, `web/src/dashboard/queries.ts`): `dashboardQuery(fetch = read)`, key `["dashboard"]`, `apiGet("/api/v1/dashboard", { signal })`, `refetchOnMount: "always"`, `gcTime: 0`, no `refetchInterval`. `topicQueries` is unchanged; no topic key matches `["dashboard"]`. `api/types.ts` names `Dashboard`. | DC-S10-04, DC-U-08, AR-W-02 |
| **States and "Refresh"** (spec 6.3 items 2, 3, `web/src/dashboard/DashboardView.tsx`): while a read runs (`isFetching`), the heading row with "Refresh" disabled and `Loading` in a card in place of the tiles and the data line; when the latest read failed and none runs, `PageError` "Could not load this page. {reason}" with "Try again" in place of the whole content; otherwise the heading row, the tiles and the data line. "Refresh" and "Try again" call `refetch()`. A 401 opens the login through the layout's `useSessionEnd`. | DC-S10-04, DC-U-08, DC-U-09, DC-U-05 |
| **Content** (spec 6.4, `DashboardView.tsx`, `texts.ts`): the heading row (`<h1>Dashboard</h1>`, then "Refresh"); four tiles by key in DC-S10-02's order, each a `<section aria-labelledby>` with the name (`<h2>`), the value, the definition of DC-TX-01 and, when `baseline_value` is not `null`, the baseline line (the baseline value, a space, `<KindTag word="Illustrative baseline" />`); a key missing from the answer shows "no data" and no baseline line; then the data line `dataLine(live, past, labels)`: "Includes {k} live {incident or incidents} and {p} imported past {incident or incidents} labelled {labels}." with the labels in the answer's order, each in single quotes, joined by ", ", and `'none'` for no label. `metricText(value, unit)`: `null` → "no data", minutes → "{grouped(value, 1)} min", count → `grouped(value, 0)`. | SL-A1-09, SL-DR-03, DC-S10-01, DC-S10-02, DC-S10-03, DC-U-10, DC-TX-00, DC-TX-01, DM-R-53, VD-R-01, VD-C-16 |
| **Numbers** (spec 6.4 item 4, `web/src/format/numbers.ts`): `grouped(n, decimals)` with `Intl.NumberFormat("en-US", { minimumFractionDigits, maximumFractionDigits, useGrouping: true })`. | DC-S10-02 |
| **Styles** (spec 6.5, `web/src/dashboard/dashboard.module.css`): the heading row (`flex`, wrap, centred, `space-between`, gap 8px 16px, margin 0 0 16px; `.headingRow > h1 { margin: 0 }`); "Refresh" the secondary button of VD-C-09 (disabled opacity 0.55, `not-allowed`); `Loading`'s card; the tiles (VD-C-28: a wrapping row 16px apart, each a card `flex: 1 1 240px`, column, padding 16px 20px; name in the label role; value or "no data" in the stat value role, 4px below; definition 13px / 18px `--vd-text-2`, 6px below, 12px under it; the baseline line `margin-top: auto`, 1px `--vd-border-2` above, padding-top 12px, wrapping row gap 4px 8px, the value 14px weight 600 tabular); the data line 12px under the tiles, 14px `--vd-text-2`. Tokens only; `contrast.test.ts` unchanged. | VD-C-28, VD-S-08, VD-R-05, VD-R-10, VD-R-13 |
| **Playwright** (spec 6.6): `tests/e2e/dashboard.ts` (`METRICS`, `tiles`, `dataLineOf`, `formulas` with `BigInt` and the half-up rule of `storm_checks.half_up_mean`, `shown`); `tests/e2e/dashboard.spec.ts` (nine tests); `storm.spec.ts` "SB-S09 the dashboard after the run" as its last test; the four screenshots. | (Section 2) |

Checked by review (spec part 4): **DC-S10-05** — no time filter, trend, chart or report beyond DC-S10 is built; **VD-R-06** — no state of the dashboard is shown by colour alone ("no data" is a text, the baseline has its tag's word, the disabled "Refresh" sits beside "Loading…"); **VD-R-13** — no component or token beyond `45-visual-design` v1.3 (the module uses only Section 4 tokens and the literal values VD-C-09, VD-C-28 and VD-S-08 state: `9px`, `6px`, `18px`, `36px`, `-0.02em`, `0.55`, `240px`).

## 2. Tests added

Vitest (`web/src`, **223 tests in 57 files** after 192 in 51 on `main`: 31 new tests in 6 new files and two extended ones), named as spec Section 7: `app/routes.test.ts` ("SL-A4-10 /dashboard is the dashboard", "AR-W-01 /dashboard follows /m/approve/:requestId and is not lazy"); `layout/header.test.tsx` ("DC-U-04 the navigation lists Incidents and Dashboard in this order", "BP-R-22 only the built screens are navigation items", "DC-U-04 Dashboard is marked on /dashboard"); `format/numbers.test.ts` (1); `dashboard/texts.test.ts` (5); `dashboard/view.test.tsx` (13); `dashboard/queries.test.ts` (4); `dashboard/content.test.tsx` (1); `dashboard/styles.test.ts` (2). Fixtures: `web/src/test/dashboard.ts` (`SEEDED`, `AFTER_STORM`, `edge`: `nullValue`, `nullBaseline`, `noLabel`, `twoLabels`, `one`, `thousand`, `missing`). `app/contrast.test.ts` unchanged.

Playwright: `dashboard.spec.ts` "SB-V-38 the starting state of the dashboard", "DC-U-04 the Dashboard item opens the dashboard and is marked", "DC-U-15 the dashboard's tab title", "DC-S10-04 the values are read on opening and on Refresh only", "DC-U-09 a failed read shows the page error and Try again reads again", "DC-S10-01 a user without view is sent to the no-access page", "VD-R-10 the dashboard at 1280 × 720 has no horizontal scrolling", "VD-S-08 the heading row and the four tiles on one row", "VD-R-12 Tab reaches Refresh with the focus ring"; `storm.spec.ts` `@storm` "SB-S09 the dashboard after the run".

**Earlier test changes** (done-check item 4, complete list; every other test and expected text unchanged): `app/routes.test.ts` "AR-W-01 the route table of P-28" (the table with `/dashboard`), "AR-W-01 every other path, …" (`/dashboard` left its list), "AR-W-01 the mobile routes" (`/m/approve/:requestId` is right before `/dashboard`); `layout/header.test.tsx` "DC-U-03 the header items from left to right" and "DC-U-03 the customer logo …" (`|Incidents|Dashboard|`); `app/content.test.tsx` "VD-R-01 the texts of the P-28 screens are unchanged" ("IncidentsDashboard"); `tests/e2e/incidents.spec.ts` "DC-S02-01 …" ("IncidentsDashboard"); `storm.spec.ts` gets the new last test, its imports and its header comment names P-33.

## 3. Deviations from the spec

1. **The baseline tag's border in "VD-S-08 the heading row and the four tiles on one row".** The spec's check "the baseline tag's border 1.5px dashed `rgb(139, 151, 168)`" cannot pass in Chromium: its computed `border` is `1px dashed rgb(139, 151, 168)` at device scale factor 1, and still `1px` at scale factor 2 (both tried in this session's `deploy/local.sh e2e` runs). Chromium rounds the computed border width of this element down to whole CSS pixels. The stylesheet is as VD-C-16 and Section 4.4 say (`tags.module.css`, from P-44: `border: 1.5px dashed var(--vd-gray-400)`). The test therefore checks (a) that exactly one rule applies a border to the tag and it is `1.5px dashed var(--vd-gray-400)` (read from `document.styleSheets` and matched with `element.matches`), and (b) the computed `border-top-style` `dashed` and `border-top-color` `rgb(139, 151, 168)`. No product code changes. See Section 4 item 1.
2. **One callback for "Refresh" and "Try again".** `DashboardView` takes `onRefresh`, which both buttons call (both are `refetch()`, spec 6.3 items 2 and 3).
3. **A space between the baseline value and its tag.** The baseline line holds the value's `<span>`, a space and the tag, so its text content is "11.0 min Illustrative baseline" (the form the spec's Playwright tests read); in the flex row the space takes no room (the 8px gap separates them).
4. **`api/types.ts`** names `Dashboard` (the generated schema), as the earlier screen packages named theirs; the file is not in the spec's list of new files because it is not new.

## 4. Document feedback (CTX-Q-8)

1. **The 1.5px dashed borders render as 1px in Chromium.** `45-visual-design` Section 4.4 gives the "Illustrative baseline" and "Test" tags a 1.5px dashed border. Chromium computes and draws it as 1px (at device scale factor 1 and 2, Section 3 item 1), so on the presenter's Chrome the tag's border is 1px dashed `--vd-gray-400`. The look is unchanged otherwise; no document change is needed unless the owner wants the computed width to equal the document's (then 1px or 2px). A later spec that checks a 1.5px border on screen checks the rule, as this package does.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 26 November 2026)

No new dependency (spec 6.1) and no pin changed.

## 7. Values recorded under `docs/`

None under `docs/` besides this report. The files of spec Section 9: `tests/acceptance/results/P-33/` (this session's four screenshots); `tests/acceptance/results/P-33-mac.txt` and `tests/acceptance/results/P-33-mac/` are the owner's (Section 8).

## 8. Owner-run results

Owner-run on the Mac (spec Section 6.7): **not yet done**: `down --volumes`, `up --profile demo`, the wait for `/data`, `deploy/local.sh e2e`, `with-playwright.sh --mode script` (the dashboard test then checks SB-S09's exact values "5.7 min", "40.5 min", "650", "47" and the four baselines), the copy of `dashboard-*.png` into `tests/acceptance/results/P-33-mac/`, the look at `http://demo.vigil.test:8080/dashboard` with "Refresh", `down --volumes`, the commit, and the label `e2e` on the pull request.

Done-check item 3, in the build session (cloud, lab profile `test`, mode `immediate`):

- `deploy/local.sh up`: exit 0, "Tenant 01a216ef-d5fc-74ce-b043-83fc1b825403 is ready at demo.vigil.test."
- `deploy/local.sh e2e`: **54 passed** (28.2 s), the nine `dashboard.spec.ts` tests among them. Two earlier runs had one failure each, "VD-S-08 …", on the computed border width (Section 3 item 1: `1px` at scale factor 1, then `1px` at scale factor 2); each was followed by `deploy/local.sh reset` before the next run.
STORM_RESULTS
- Screenshots (spec 6.6 item 4), committed under `tests/acceptance/results/P-33/`: `dashboard-start-1280`, `dashboard-loading-1280`, `dashboard-error-1280` (from `deploy/local.sh e2e`), `dashboard-storm-1280` (from the storm run).
- `deploy/local.sh down --volumes`: DOWN_STATE

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

**236** (S; BP-R-05 limit 800; spec estimate about 300): `web/src` TypeScript only — `DashboardView.tsx` 97, `texts.ts` 61, `DashboardPage.tsx` 32, `queries.ts` 22, `numbers.ts` 10, `routes.tsx` 9, `Header.tsx` 4, `api/types.ts` 1 (lines added). CSS Modules, tests, fixtures under `web/src/test/` and `tests/` are not counted.

## 10. Gate command result

`tools/check-all.sh` passed in this session (exit 0, "== all checks passed"): (0) Docker, (1) setup version, (1a) test images, (2) `./mvnw -B verify` (web: lint, Prettier check, type check, Vitest **223 tests in 57 files**, build; `tests/e2e/`: lint, Prettier check, type check; `vigil-app` 197 unit tests and 780 integration tests, `demo-control` 13 and 34, 0 failures, 0 errors; Spotless; `BUILD SUCCESS`), (3) `ruff` and pytest: **570 passed**, 1 skipped, 42 deselected (lab tests skipped: no change to the lab), (4) shellcheck, (5) data checks, (6) compose validation, (7) traceability. A first run in this session was stopped by the session's 30-minute limit for a background command during the integration tests (no failure until then) and was run again in full. The change to `tests/e2e/dashboard.spec.ts` after the gate run (Section 3 item 1) was checked with that folder's lint, Prettier check and type check, and by `deploy/local.sh e2e`.

## 11. Follow-up items for later packages

- P-34: the dashboard on the demo installation, SB-T-02's five runs and the SL-CP-2 review.
- P-37 to P-39: their navigation items join `NAVIGATION` after "Dashboard" in DC-U-04's order (BP-R-22).
- The owner: the Mac step of spec Section 6.7 and the label `e2e` on the pull request; the choice in Section 4 item 1 (keep 1.5px or not).

## 12. Pull request

PR_LINE
