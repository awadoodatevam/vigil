# P-34 — Report: Primary demo hardening

| Field | Value |
| --- | --- |
| Package | P-34 Primary demo hardening (spec `docs/packages/P-34.md`) |
| Branch | `p-34-primary-demo-hardening` |
| Pin date (BP-R-08) | 26 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Fix round 1 built (Section 12); the gate command passes (Section 10). The owner's next step is `p34-2` and spec Section 6.8 steps 2 to 6 again. Built in the first session; the gate command passed there too. Done-check item 3 in the first session (26 Nov 2026; lab profile `test`): `deploy/local.sh up`, `deploy/local.sh e2e`, `deploy/local.sh e2e control`, `with-playwright.sh` and `deploy/local.sh down --volumes` all passed (Section 8); the four screenshots are committed under `tests/acceptance/results/P-34/`. Owner-run on `p34-1` (27 Nov 2026): steps 2, 4, 5 and 6 (Section 8); steps 7, 9 and 10 not yet. |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| **Compression of the built files and the JSON answers** (spec 6.2 item 1; `application/json` from fix round 1, Section 12): `app/src/main/resources/application.properties` gains exactly `server.compression.enabled=true`, `server.compression.mime-types=text/html,text/css,text/javascript,application/javascript,image/svg+xml,application/json` and `server.compression.min-response-size=2KB`. Spring Boot 4.1's embedded Tomcat compresses the answers of `WebApplicationConfiguration`'s resource handlers with these three settings alone: no Java line and nothing else was needed (`WebAppServingIT`). `Cache-Control` is unchanged (`max-age=31536000, immutable` for `/assets/**`, `no-cache` for `index.html` and the fallback). JSON answers of `vigil-api` of 2 KB or more are compressed (fix round 1). The error answers (`application/problem+json`), the live stream (`text/event-stream`), the actuator answers and fonts are not in the list, so they are not compressed. `demo-control` has no compression setting. | AR-W-01 (BP-A-33), AR-I-04 |
| **`check.sh`'s `assets-gzip`** (spec 6.2 item 3, `tests/acceptance/demo-host/check.sh`): in the part `https`, after `https-demo`, the section `-- assets-gzip` fetches `https://demo.{d}/login`, takes the first `/assets/….js` path (printed as `asset {path}`), then fetches it with `curl -sS -o /dev/null -D - -w 'size %{size_download}\n'`, first with `-H 'Accept-Encoding: gzip'` (after the line `with gzip:`) and then without it (after `without:`); both sizes stay in the output. The check `assets-gzip` (after `https-demo` in the list of checks) passes when the first answer has `content-encoding: gzip` and `cache-control: max-age=31536000, immutable` (header names in any case) and its `size` is smaller than the second's. | AR-W-01 |
| **The control panel's look** (spec 6.3, `demo/control/src/main/resources/static/index.html` only): the `<style>` is replaced by the styles of VD-R-15, VD-C-29 and VD-S-09. The page has the two `@font-face` rules (the Latin `normal` rules of the two packages' `index.css`, `src: url("/fonts/{file}") format("woff2-variations")`) and a `:root` with exactly the 44 tokens the page uses, named and valued as in `web/src/app/tokens.css` (`--vd-text` keeps `var(--vd-gray-900)` and `--vd-gray-900` is declared, and the same for every token that names another one). The page then has: the body role on `--vd-canvas` over the full window height; the navy top line; the content 760px wide at most, centred, padding 24px (16px below 600px); the login card with the VD-C-10 fields, the full-width primary "Sign in" 20px below them and the error notice, hidden while `:empty`; the button card (the `ul#buttons`, items 14px 20px apart from each other with 1px `--vd-border-2`, secondary buttons, the description, the refusal reason in the warning colour, disabled at opacity 0.55); the state card (the fault lines with the first one bold, the status with its line above it, the storm clock in mono `--vd-accent-700` tabular, the link "Open {product}" at 15px weight 600); the dialog of VD-C-27 with "Cancel" secondary and "Reset" destructive side by side; the focus ring (VD-R-12) and the reduced-motion rule (VD-R-11). Markup (VD-R-01): one `<div class="card login-card">` around the login form, one `<div class="card state-card">` around `#faults`, `#status`, `#clock` and the link's paragraph, and one `<div class="confirm-buttons">` around the dialog's two buttons. The script, every text, control, id and the order of the elements are unchanged (the script's SHA-256 equals that of `4fd1c27`). There is still one `<script>` without `src`, and no `<link>`, `@import` or `http(s)://` address. No decorative element and no product mark. | DC-S12-01, AR-T-21, VD-R-01, VD-R-09, VD-R-12, VD-R-15, VD-C-29, VD-S-09 |
| **The panel's fonts** (spec 6.1, 6.3 item 3): `demo/control/src/main/resources/static/fonts/inter-latin-wght-normal.woff2` and `jetbrains-mono-latin-wght-normal.woff2`, byte-equal copies of the files of `@fontsource-variable/inter` and `@fontsource-variable/jetbrains-mono` 5.3.0 under `web/node_modules`; their `LICENSE` files (OFL-1.1) as `demo/control/src/main/resources/licenses/inter-LICENSE.txt` and `jetbrains-mono-LICENSE.txt`, outside `static/`, so they are not served. Spring Boot serves the fonts from `classpath:/static/fonts/` with `Content-Type: font/woff2` and without a session. Its type table already has `woff2`, so no mapping was added. | VD-R-09, AR-T-21 |
| **The storm driver's two values** (spec 6.4, `tests/acceptance/storm/storm.py`, `storm_checks.py`): `times` gains `approved` (the seconds after T0 of `d.decision`'s `at`, with `--acts driver` and `--acts pages`, `null` without a decision), with the keys in the order `SB-TL-01`, `SB-TL-02`, `SB-TL-03`, `SB-TL-04`, `SB-TL-06`, `SB-TL-07`, `approved`, `SB-TL-08`, `SB-TL-10`. `investigation` gains `summary_length` (Python `len` of the summary text that `SB-S04 investigation` reads, `null` when there is none). Neither value is checked; the checks, their count and their order are unchanged. `tests/acceptance/storm/README.md` names both values. | SB-T-04, AR-G-12 |
| **SB-T-02's series** (spec 6.5): `tests/acceptance/demo-host/series.sh {installation} [--runs N] [--measured FILE]` (Mac, bash 3.2) writes the manifest `tests/acceptance/results/P-34-series-{UTC start}.txt` (two `#` comment lines, then one line per run: `run {k} exit {code} result {file} checklist {file}`). It runs `storm.sh {installation} --mode script --with-playwright [--measured FILE]` N times, keeps each exit code, finds each run's new `storm-script-*.json` and `storm-checklist-*.md` (exactly one each, else `none`), and copies the suite's `screens/` and `results/` to `~/.vigil-demo/{installation}/series/{UTC start}/run-{k}/`. A failed run does not stop the series. The script prints `manifest {path}` and exits 0 only when every run exited 0. `tests/acceptance/demo-host/series.py {manifest}` (python3, standard library only) writes `P-34-SB-T-02.json` beside the manifest with the fields, run entries and criteria of spec 6.5 item 2 (`runs_5`, `settings_5`, `drivers_5`, `checklists_5`, `repeatability_5`, `similar_5`, `step_times_5` with 15, 60 and 15 s, `rb_db_01_4_of_5`), `passed`, `suite_storm_runs_passed` and `summary_lengths`. It prints `PASS`/`FAIL` per criterion and exits 0, 1, or 2 for wrong arguments or an unreadable manifest line. | SB-T-02, SL-A3-04, SB-T-03, SB-T-04, AR-G-12, SL-ASM-04, SB-ASM-02 |
| **The local model's SB-T-04 part** (spec 6.6): `spikes/providers/run.sh` takes `--package P-34` as it takes `--package P-20`: `VIGIL_SPIKE_PACKAGE` is the package and the recordings go to a temporary folder, removed at the end. The result is `spikes/results/P-34-{spike}.json`, so `local-model` writes `P-34-SB-T-04.json`. The usage line is exactly `Usage: spikes/providers/run.sh jira|claude|openai|similarity|local-model [--package P-20|P-34] | teams | email`. `common.py`'s docstrings, `spikes/providers/README.md` (the option and the row for `run.sh local-model --package P-34`) and `spikes/results/README.md` (the row `P-34-SB-T-04.json`) follow. The request and the output checks are those of P-20 (unchanged). | AR-G-12, AR-G-32, PM-M17-03, AR-ASM-12 |
| **Rehearsals** (spec 6.7): `tests/acceptance/demo-host/rehearsal.py {file}` (python3, standard library only) reads `P-34-rehearsal-{n}.txt` in the order of spec 6.7 item 1 (`#` lines are comments, blank lines are skipped) and writes `{file without .txt}.json` with `date`, `scenes` (`{id, start_s, duration_s, budget_s, within}`, budgets 60, 120, 90, 45, 60, 105, 105, 135, 30, 60, 30 s), `length_s`, `assumptions`, `criteria` (`scenes_within_30`, `length_900`, `assumptions_yes`) and `passed`. Exit 0 or 1. A file it cannot read stops it with `Line {n}: {reason}` and exit 2: an item out of order or missing (`expected {item}, found {item}`, `{item} missing`), a repeated item, a start earlier than the one before, an unreadable time or date, an answer other than yes or no, or an extra line. | SB-T-08, SB-ASM-01, DC-ASM-01, DC-ASM-03, DC-ASM-04 |
| **Live re-reads a notice does not cancel** (fix round 1, Fix A, spec 6.10 item 3, `web/src/live/useLiveTopics.ts`): `liveHandlers`' `invalidate` and `invalidateAll` re-read each cached query that matches a topic's keys with `invalidateQueries({ queryKey, exact: true }, { cancelRefetch: false })`, so a running read is never cancelled. A query whose read runs when a notice arrives is noted once (a set of query hashes per stream); when that read ends, the query is read once more, so the screen ends on a read that started after the last notice, with at most one extra read per query however many notices arrive meanwhile. AR-TM-04's at most once per 500 ms per topic (`live.ts`) is unchanged. | AR-W-02, DC-S03-07 |
| **Commands** (done-check item 1): `CLAUDE.md`'s rows "Provider spikes" (`[--package P-20|P-34]`, "`--package P-34` from P-34") and "Runs against the demo installation" (`series.sh`, `series.py`, `rehearsal.py`, `docs/packages/P-34.md` Section 6.8); `README.md`'s `tests/acceptance/demo-host/` and `spikes/providers/run.sh` lines. | BP-DC-06 |

Checked by review (spec part 4):
- **VD-R-05**: the panel's text pairs are those of `45-visual-design` Section 13 "v1.4 contrast", and each is already in `web/src/app/contrast.test.ts`, which is unchanged:
  - `--vd-on-navy` on `--vd-navy-900`;
  - `--vd-text-2` and `--vd-gray-700` on white;
  - the warning colour on white;
  - `--vd-accent-700` and `--vd-accent-600` on white;
  - `--vd-red-700` on `--vd-red-50`;
  - white on `--vd-accent-500`;
  - white on `--vd-red-600`;
  - `--vd-text` on white.
- **VD-R-06**: no state of the panel is shown by colour alone. A refused button shows its reason as text, a disabled button keeps its label beside that reason, the login refusal is its sentence, and the faults and the status are text lines.

The rows checked by review through owner-run result files (SL-A3-04, SL-A3-08, SL-CP-2, SB-T-02, SB-T-03, SB-T-04, SB-T-08, AR-G-12, AR-G-32, PM-M17-03, SB-ASM-01, SB-ASM-02, DC-ASM-01, DC-ASM-03, DC-ASM-04, AR-ASM-12, SL-ASM-04) wait for the owner-run steps (Section 8).

## 2. Tests added

**Java:**
- `app/src/test/java/vigil/WebAppServingIT.java` (6 tests, 3 new; the second changed by fix round 1):
  - "AR-W-01 built files are sent gzip-compressed": the first `/assets/….js` and `….css` of `index.html`, sent with `java.net.http.HttpClient`, which does not decompress. With `Accept-Encoding: gzip` each answers `Content-Encoding: gzip` and `Cache-Control: max-age=31536000, immutable`, and its body is smaller and unzips to the file under `classpath:/static/`. Without `Accept-Encoding` each answers without `Content-Encoding`, with the same `Cache-Control` and the file's bytes.
  - "AR-W-01 JSON answers of 2 KB or more are compressed, smaller ones are not" (fix round 1; it replaces "AR-W-01 no other answer is compressed"): with `ApiFixture`'s storm (127 recorded alerts), `GET /api/v1/incidents/{storm incident}/alerts` without `Accept-Encoding` answers at least 2,048 bytes without `Content-Encoding`; with `Accept-Encoding: gzip` it answers `application/json` with `Content-Encoding: gzip`, a smaller body, and unzips to the same JSON. `GET /api/v1/public/branding` (under 2,048 bytes) with `Accept-Encoding: gzip` answers without `Content-Encoding`.
  - "AR-I-04 the live stream is not compressed": a signed-in `GET /api/v1/live?topics=tenant` with `Accept-Encoding: gzip` answers `text/event-stream` without `Content-Encoding`. The test reads the head and closes the stream.
- `demo/control/src/test/java/democontrol/InterfaceIT.java` (7 tests, 3 new):
  - "VD-R-09 the panel serves its two fonts from its own origin": `GET /fonts/{file}` without a session answers 200 `font/woff2` without `Content-Encoding`, and the bytes equal `ROOT/web/node_modules/@fontsource-variable/…/files/{file}`. The page's two `@font-face` rules name only `/fonts/…`, their families are "Inter Variable" and "JetBrains Mono Variable", and their family, style, display, weight range and `unicode-range` equal the Latin `normal` rules of the packages' `index.css`.
  - "VD-R-15 the panel's tokens are those of tokens.css": every `--vd-*` the page declares has the name and value (white space collapsed) of `ROOT/web/src/app/tokens.css`. The page declares exactly the tokens it uses outside `:root`, plus those they name. Outside `:root` the only colour literals are those VD-R-04 allows.
  - "VD-R-01 the panel's texts and controls are unchanged": the page's texts, ids, the `form`, `label`, `input`, `button`, `a` and `dialog` tags with their attributes, and the order of the elements (the wrapping cards without an id left out) equal those of the page at `4fd1c27`, written into the test; the script's SHA-256 equals that of `4fd1c27`.

**Vitest** (fix round 1, `web/src/live/reread.test.ts`, a `QueryObserver` on `["incident", id]` whose reads the test answers):
- "AR-W-02 a notice during a running read does not cancel it";
- "AR-W-02 a notice during a running read leads to exactly one more read after it" (three notices and a reconnection during the read: one more read, then none; a notice during that read again leads to one more);
- "AR-W-02 a notice with no read running reads at once".

The first two fail with the `liveHandlers` of `p34-1` (the running read is cancelled) and pass with Fix A.

**Python:**
- `tests/acceptance/storm/test_storm.py`: "SB-T-04 the result file records the approve time and the summary length". It is parametrized over `--acts driver` and `--acts pages` and checks the `times` keys in order, `approved` as the decision's time after T0 and not later than `SB-TL-08`, and `summary_length` as the fake summary's length.
- `tests/acceptance/demo-host/test_demo_host.py`:
  - "AR-W-01 check.sh's assets-gzip check on fake outputs";
  - "SB-T-02 series.sh runs storm.sh five times in mode script with the suite storm, keeps going after a failed run, and writes the manifest" (a fake `storm.sh`);
  - "SB-T-02 series.py passes five good runs and fails a run whose checklist has an unticked line";
  - "SL-A3-04 series.py needs the repeatability result in every run";
  - "SB-T-03 series.py needs PI-1001's similar incident check in every run";
  - "SB-T-04 series.py's step times and their limits 15, 60 and 15 s";
  - "AR-G-12 RB-DB-01 cited in 4 of 5 runs passes and in 3 of 5 fails";
  - "SB-T-02 a failed suite storm with a passed driver is recorded, not a failed criterion";
  - "SB-T-08 rehearsal.py's budgets ±30 s and 15:00";
  - "SB-T-08 rehearsal.py refuses an item out of order";
  - "SB-ASM-01 rehearsal.py needs the four assumptions answered yes".
- `spikes/providers/test_providers_run.py`: "AR-G-12 run.sh local-model --package P-34 writes P-34-SB-T-04.json, no P-06 result file and no recording under dev/fakes"; the usage line with `[--package P-20|P-34]`; `teams --package P-34` prints the usage line.

**Playwright** (`tests/e2e/control.spec.ts`, tag `@control`, after "DC-S12-02 the buttons, descriptions and link"):
- "VD-S-09 the control panel in Vigil's look";
- "VD-C-29 the Reset question in the confirmation dialog's look";
- "VD-R-12 Tab reaches Sign in and the buttons with the focus ring".

They take the screenshots `control-login-1280`, `control-panel-1280`, `control-panel-640` and `control-confirm-1280`.

**Earlier test changes** (done-check item 4, complete list; every other test and expected text is unchanged):
- `InterfaceIT` "AR-T-21 the page is one static HTML file …": the static folder's file list.
- `test_storm.py` `test_immediate_run_passes`: the expected `times` keys with `approved` and the `investigation` keys with `summary_length`.
- `test_providers_run.py`: `USAGE` with `[--package P-20|P-34]` (the wrong-value cases are unchanged; `P-34` was not among them).
- `test_demo_host.py` `test_check_verdicts`: the expected check list with `assets-gzip` (16 checks), and the fake table `check_table` with the answers of the three `assets-gzip` calls (Section 3 item 2).
- Fix round 1 (the test the fix names, spec 6.9 item 1): `WebAppServingIT` "AR-W-01 no other answer is compressed" became "AR-W-01 JSON answers of 2 KB or more are compressed, smaller ones are not".

## 3. Deviations from the spec

1. **`series.sh` empties the suite's `screens/` folder before each run.** `~/.vigil-demo/{installation}/e2e/` is shared by every suite `e2e.sh` runs (the default suites and `control` of steps 4 and 5 write their screenshots there too), and screenshot files are only overwritten by name. Without emptying the folder, each run's copy would also hold the screenshots of earlier suites and runs, and step 6's `cp …/run-{k}/screens/*.png` would copy them. Playwright already empties `results/` (its `outputDir`) at the start of each run.
2. **`test_demo_host.py`'s `check_table` gains three fake curl answers** for `assets-gzip`: the login page, and the script with and without gzip. Without them the unchanged expectation "every check passes" of `test_check_verdicts` cannot hold with the new check. The test's assertions change only by the check list (done-check item 4).
3. **The tests check slightly more than spec Section 7 lists, to cover spec 6.3:**
   - "VD-R-01" also compares the script's SHA-256 with `4fd1c27` and the order of the elements ("its script … and the order of the elements … do not change");
   - "VD-R-15" also requires that the page declares no token it does not use ("exactly the custom properties … that the page uses");
   - "VD-R-09" also compares `unicode-range` and the other font-face properties with `index.css`, and checks that the fonts are not compressed (spec Section 5).
4. **The dialog's buttons are 16px** (with `min-height: 48px`), as `web/src/dialog/dialog.module.css` draws VD-C-27's dialog in Vigil (P-30). VD-C-29 asks for "the dialog of VD-C-27", so the panel copies that dialog rather than VD-C-09's 14px. The buttons of the button card are VD-C-09's 14px.
5. **A `[hidden] { display: none !important; }` rule.** The script shows and hides `#login`, `#panel`, `#clock` and each `.reason` with the `hidden` attribute. The rule keeps that working when a style also gives these elements a `display` value. It shows nothing and changes no behaviour.
6. **"VD-S-09 the control panel in Vigil's look" uses a browser context of its own.** A context with an empty cache is needed so the font requests are seen. The test takes `control-login-1280` there before signing in (the spec's "a new context without a session shows the login card"). At idle only the Inter file is requested: the storm clock, the only mono text, is hidden. So the test requires the Inter request and checks every font request made (origin and 200), and checks that no request left the control panel's origin.
7. **Fix round 1's large answer (Fix B's test) comes from `ApiFixture`.** The spec lets the session choose an answer of at least 2,048 bytes. `WebAppServingIT`'s own tenant has no incident, so the test starts `ApiFixture` (as `WorkspaceIT` does) and reads the storm incident's alerts section, the answer that was 77 KB in the failed runs.
8. **Fix A re-reads each matching query on its own** (`exact: true`) instead of one `invalidateQueries` per key prefix. This is so a query whose read runs can be told from the others and read once more when its read ends. The effect on queries without a running read is the same as before: inactive ones are marked stale, active ones are read at once.

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 26 November 2026)

No new dependency and no pin changed. The two font files are copies of the pinned `@fontsource-variable/inter` 5.3.0 and `@fontsource-variable/jetbrains-mono` 5.3.0 (CTX-D-088 (2)).

## 7. Values recorded under `docs/`

None under `docs/` besides this report (spec Section 9: no DD value is measured). This session recorded the screenshots of spec Section 7 under `tests/acceptance/results/P-34/` (Section 8).

## 8. Owner-run results

**Cloud session, 26 November 2026** (done-check item 3; lab profile `test`; whole runs in this session's logs):

- `deploy/local.sh up --profile test`: exit 0 ("Tenant … is ready at demo.vigil.test.").
- `deploy/local.sh e2e`: **54 passed**, exit 0.
- `deploy/local.sh e2e control`: **8 passed**, exit 0:
  - "DC-S12-01 the login and its refusal";
  - "DC-S12-02 the buttons, descriptions and link";
  - "VD-S-09 the control panel in Vigil's look";
  - "VD-C-29 the Reset question in the confirmation dialog's look";
  - "VD-R-12 Tab reaches Sign in and the buttons with the focus ring";
  - "SB-T-05 Clear all faults after a storm" (2.8 min);
  - "SB-T-05 five resets in a row, the first after SL-F-1 with SL-F-2" (3.6 min);
  - "AR-B-02 a reset gives Vigil a new tenant".
- `VIGIL_STORM_RESULTS=$HOME/storm-results tests/acceptance/storm/with-playwright.sh` (mode `immediate`), exit 0:
  - the suite `storm` **33 passed**, "DC-S03-07 countdowns and the observation window were counted" among them;
  - the driver passed **33 of 33** checks (`storm-immediate-20261126T131919Z.json`, kept outside the repository; the local results are unchanged);
  - AR-Q-07 passed.

  The new values in that file:
  - `times`: `SB-TL-01` 30.373, `SB-TL-02` 70.504, `SB-TL-03` 71.581, `SB-TL-04` 71.771, `SB-TL-06` 73.917, `SB-TL-07` 74.268, `approved` 79.516, `SB-TL-08` 80.386, `SB-TL-10` 166.081;
  - `investigation.runbooks_cited` `["RB-DB-01"]`, `summary_length` 297.
- `deploy/local.sh down --volumes`: exit 0.
- Screenshots committed: `tests/acceptance/results/P-34/control-login-1280.png`, `control-panel-1280.png`, `control-panel-640.png` and `control-confirm-1280.png`. In `control-confirm-1280.png` the dialog's backdrop ends at the 800px window, because the page is taller than the window and the screenshot is a full-page capture.

**Cloud session, 27 November 2026, after fix round 1** (lab profile `test`):
- `deploy/local.sh up --profile test`: exit 0.
- `deploy/local.sh e2e`: **54 passed**.
- `VIGIL_STORM_RESULTS=$HOME/storm-results2 tests/acceptance/storm/with-playwright.sh` (mode `immediate`), exit 0:
  - the suite `storm` **33 passed**, SL-A1-08 and DC-S03-07 included;
  - the driver passed **33 of 33** (`storm-immediate-20261127T103350Z.json`, outside the repository);
  - AR-Q-07 passed.

  Highlight delays (ms): `s-investigate` 59, `s-summarise` 460, `s-ticket` 422, `s-notify` 395, `s-wait-ack` 233, `s-similar` 242, `s-approve` 544, `s-action` 69, `s-recover` 182, `s-resolve` 508. This is the local installation, without the owner's link, so it shows the fix causes no regression but does not prove it on the demo host; the series on `p34-2` does that.
- `deploy/local.sh down --volumes`: exit 0.

**Owner-run, release `p34-1`, 27 November 2026** (commit `5e1b58a`; `tests/acceptance/results/P-34-aws.txt`, `P-34-demo-host.txt`, `P-34-demo-host/`):

- Step 2, `check.sh demo`: **16 of 16 PASS**, `assets-gzip` included. The script `/assets/index-BKe-iJAI.js` was 101,516 bytes with gzip against 324,023 bytes without (`content-encoding: gzip`, `cache-control: max-age=31536000, immutable`).
- Step 4, the default suites against the demo host: **48 passed**, "SB-V-38 the starting state of the dashboard" included.
- Step 5, the suite `control`: **8 passed**, the three look tests included.
- Step 6, SB-T-02's first series (`P-34-series-20261127T052633Z.txt`, `P-34-SB-T-02.json`): the five driver runs passed **33 of 33** checks each, and `series.py` gave:
  - PASS: `runs_5`, `settings_5`, `drivers_5`, `repeatability_5`, `similar_5`, `step_times_5`, `rb_db_01_4_of_5`;
  - FAIL: `checklists_5`, by design, because the owner did not tick the checklists of this series (fix round 1 repeats it; spec 6.10 item 6).

  The suite `storm` passed completely in run 1 and stopped in runs 2 to 5 at "SL-A1-08 the highlight followed every step change within 2 s". Fix round 1 (Section 12) has the cause and the fix. One more `series.py` call in the log printed its usage line (a wrong argument); the next call wrote the verdict.
- Steps 7, 9 and 10 (the local model, the rehearsals, the video) have not run.

**Owner-run steps (spec Section 6.8; after fix round 1, steps 2 to 6 again on `p34-2`):**
1. `deploy/build.sh p34-1` and `deploy/images.sh --local` on the Mac.
2. `deploy/deploy.sh demo p34-1`, `deploy/status.sh demo` and `check.sh demo` into `P-34-aws.txt`, with `assets-gzip` and both sizes.
3. `secrets.sh demo`, then a look at the panel and a Reset.
4. The default suites.
5. The suite `control`.
6. SB-T-02's series and `series.py`: `P-34-SB-T-02.json`, the manifest, the five result files and ticked checklists, and the screenshots of one run under `P-34-demo-host/`. The report then records, per run, the result of P-29's "DC-S03-07 countdowns and the observation window were counted" (spec 6.9 item 4).
7. The local model: `spikes/results/P-34-SB-T-04.json`, with `chat-1` to `chat-5` for AR-G-32.
8. The label `e2e` on the pull request.
9. Two rehearsals: `P-34-rehearsal-{1,2}.txt` and `.json`.
10. The backup video: `P-34-backup-video.txt`.
11. `secrets.sh demo --remove`.
12. After the merge: `release.yml` and the deployment.

A cloud session with "Record the owner-run results of P-34" copies their values here. A failed result goes to "Fix the failed owner-run results of P-34" (spec 6.9).

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

**30** (S; BP-R-05 limit 800; spec estimate about 10, with fix rounds added): fix round 1's Fix A, `web/src/live/useLiveTopics.ts` 30 lines added (2 changed lines removed). Before fix round 1 the count was 0. The compression is lines of `application.properties`, and the panel's page is HTML and CSS; neither is counted. Neither are the tooling under `tests/` and `spikes/`, the tests or the font files (BP-R-05). No Java line was needed (Section 1).

## 10. Gate command result

**After fix round 1** (27 November 2026, at `fc567fd` with the report change): `tools/check-all.sh` passed (exit 0, "== all checks passed"):

- (0) Docker, (1) setup version, (1a) test images.
- (2) `./mvnw -B verify`, `BUILD SUCCESS`, 0 failures and 0 errors:
  - web: Vitest **226 tests in 58 files** (the three tests of `reread.test.ts` new), lint, Prettier check, type check, build;
  - `vigil-app`: 197 unit tests and **783 integration tests**;
  - `demo-control`: 13 unit tests and 37 integration tests;
  - Spotless.
- (3) `ruff` and pytest: **620 passed**, 1 skipped, 6 deselected (lab tests included).
- (4) shellcheck, (5) data checks, (6) compose validation, (7) traceability.

**First session** (26 November 2026): `tools/check-all.sh` passed with the same counts except Vitest **223 tests in 57 files**.

## 11. Follow-up items for later packages

- The owner: the steps of spec Section 6.8 (Section 8 above), the label `e2e` on the pull request, and the decisions of the SL-CP-2 review (BP-CR-06, including SL-C-01).
- A cloud session "Record the owner-run results of P-34" after the series and the local-model step, and again after the rehearsals and the video. It also records the result of P-29's "DC-S03-07 countdowns and the observation window were counted" in each of the five runs (spec 6.9 item 4, CTX-D-098 (5)).
- P-40: the full demo's backup video, SB-T-09's other parts, SB-T-10 and the deep dives on the demo installation.

## 12. Fix round 1 (27 November 2026; spec Section 6.10)

**What failed.** The first series on `p34-1`: the suite `storm` stopped in runs 2 to 5 at "SL-A1-08 the highlight followed every step change within 2 s" (DC-S03-07's 2 s). The five drivers passed 33 of 33. Per run, the driver's checks, the suite `storm`'s result and the highlight delays printed by SL-A1-08 (milliseconds from a step's start to its highlight on the canvas; from `P-34-demo-host.txt`):

| Run | Driver | Suite `storm` | Highlight delays (ms) |
| --- | --- | --- | --- |
| 1 (`storm-script-20261127T052640Z.json`) | 33 of 33 | 33 passed | `s-summarise` 406, `s-ticket` 434, `s-notify` 454, `s-wait-ack` 471, `s-similar` 900, `s-approve` 705, `s-action` 462, `s-recover` 420, `s-resolve` 973 |
| 2 (`…T053722Z.json`) | 33 of 33 | 10 passed, SL-A1-08 failed, 22 did not run | `s-ticket` **4891**, `s-notify` **4141**, `s-wait-ack` **2067**, `s-similar` 653, `s-approve` 442, `s-action` 416, `s-recover` 1298, `s-resolve` 414 |
| 3 (`…T054746Z.json`) | 33 of 33 | 10 passed, SL-A1-08 failed, 22 did not run | `s-ticket` 1720, `s-notify` **6642**, `s-wait-ack` **4967**, `s-similar` **7269**, `s-approve` **7076**, `s-action` **4312**, `s-recover` **3795**, `s-resolve` 449 |
| 4 (`…T055804Z.json`) | 33 of 33 | 10 passed, SL-A1-08 failed, 22 did not run | `s-ticket` **4640**, `s-notify` **3855**, `s-wait-ack` **2211**, `s-similar` 608, `s-approve` 410, `s-action` 405, `s-recover` 374, `s-resolve` 404 |
| 5 (`…T060830Z.json`) | 33 of 33 | 10 passed, SL-A1-08 failed, 22 did not run | `s-ticket` **4499**, `s-notify` **3203**, `s-wait-ack` 1595, `s-similar` 925, `s-approve` 732, `s-action` 439, `s-recover` 417, `s-resolve` 491 |

The step times of SB-T-04 were within their limits in every run:
- `diagnostics_s` 1.0, 0.7, 0.7, 0.7, 0.7;
- `ai_and_messages_s` 9.1, 9.7, 10.3, 8.4, 10.1;
- `action_s` 0.6, 0.5, 0.5, 0.5, 0.5.

RB-DB-01 was cited in 5 of 5 runs. The summary lengths were 518, 439, 507, 498 and 529. P-29's "DC-S03-07 countdowns and the observation window were counted" passed in run 1 and did not run in runs 2 to 5 (the suite stops at its first failure).

**Cause** (spec 6.10 item 2, the Claude project's reading of run 2's trace): each notice of `run:{id}` and `incident:{id}` re-read the workspace's queries with `invalidateQueries`, which cancels a read still running. Over the owner's link the largest answers (alerts section about 77 KB, timeline about 42 KB, uncompressed JSON) took 1 to 2 s, so during the burst of step changes every re-read was cancelled by the next notice, and the canvas was redrawn only when the notices paused.

**Fixes** (Section 1; both changes are in `fc567fd`):
- **Fix A** (`web/src/live/useLiveTopics.ts`, AR-W-02): a notice never cancels a running read; one more read follows it.
  - Tests: the three Vitest tests of Section 2 (`web/src/live/reread.test.ts`). Two of them fail on `p34-1`'s code.
- **Fix B** (AR-W-01 as amended, BP-A-33): `application/json` in `server.compression.mime-types`, so JSON answers of 2 KB or more are gzip-compressed.
  - Test: `WebAppServingIT` "AR-W-01 JSON answers of 2 KB or more are compressed, smaller ones are not".
  - The live stream, error answers, fonts and the actuator stay uncompressed: "AR-I-04 the live stream is not compressed" still passes.

**`tools/loc.sh`**: 30 lines, all Fix A (Section 9).

**Then** (spec 6.10 item 6): the owner builds `p34-2`, deploys it and runs spec Section 6.8 steps 2 to 6 again, a new series of five whose checklists are ticked. Step 7 (the local model) runs once on any release, since the SL-T-1 system text is unchanged. The first series' files stay in the repository as this round's record.

## 13. SL-CP-2 review (`60-build-plan` Section 8.3)

### BP-CR-02 (build session)

`tools/trace.py --check` passes: every row with proof `test` in the part 4 tables of the specs has a test that names its ID, P-34's rows included. `tools/trace.py` lists the IDs of the earlier documents that no test names and that no spec of a package with a report on `main` marks "checked by review". On this branch, 26 November 2026:

| Prefix | IDs listed |
| --- | --- |
| CTX | 110 |
| SL | 81 |
| PM | 203 |
| DM | 152 |
| DC | 142 |
| AR | 84 |
| SB | 106 |
| VD | 8 |
| DD | 30 |

<details>
<summary>The full list (<code>tools/trace.py</code> output)</summary>

```
CTX: 110 without a test or a review mark
  CTX-Q-1
  CTX-Q-2
  CTX-Q-3
  CTX-Q-4
  CTX-Q-5
  CTX-Q-6
  CTX-Q-7
  CTX-Q-8
  CTX-D-001
  CTX-D-002
  CTX-D-003
  CTX-D-004
  CTX-D-005
  CTX-D-006
  CTX-D-007
  CTX-D-008
  CTX-D-009
  CTX-D-010
  CTX-D-011
  CTX-D-012
  CTX-D-013
  CTX-D-014
  CTX-D-015
  CTX-D-016
  CTX-D-017
  CTX-D-018
  CTX-D-019
  CTX-D-020
  CTX-D-021
  CTX-D-022
  CTX-D-023
  CTX-D-024
  CTX-D-025
  CTX-D-026
  CTX-D-027
  CTX-D-028
  CTX-D-029
  CTX-D-030
  CTX-D-031
  CTX-D-032
  CTX-D-033
  CTX-D-034
  CTX-D-035
  CTX-D-036
  CTX-D-037
  CTX-D-038
  CTX-D-039
  CTX-D-040
  CTX-D-041
  CTX-D-042
  CTX-D-043
  CTX-D-044
  CTX-D-045
  CTX-D-046
  CTX-D-047
  CTX-D-048
  CTX-D-049
  CTX-D-050
  CTX-D-051
  CTX-D-052
  CTX-D-053
  CTX-D-054
  CTX-D-055
  CTX-D-056
  CTX-D-057
  CTX-D-058
  CTX-D-059
  CTX-D-060
  CTX-D-061
  CTX-D-062
  CTX-D-063
  CTX-D-064
  CTX-D-065
  CTX-D-066
  CTX-D-068
  CTX-D-069
  CTX-D-070
  CTX-D-071
  CTX-D-072
  CTX-D-073
  CTX-D-074
  CTX-D-075
  CTX-D-076
  CTX-D-077
  CTX-D-078
  CTX-D-079
  CTX-D-080
  CTX-D-081
  CTX-D-082
  CTX-D-083
  CTX-D-085
  CTX-D-086
  CTX-D-087
  CTX-D-088
  CTX-D-089
  CTX-D-090
  CTX-D-091
  CTX-D-092
  CTX-D-093
  CTX-D-094
  CTX-D-095
  CTX-D-096
  CTX-D-097
  CTX-D-098
  CTX-D-099
  CTX-O-01
  CTX-O-02
  CTX-O-03
  CTX-O-04
  CTX-O-05
SL: 81 without a test or a review mark
  SL-A2-01
  SL-A2-02
  SL-A2-06
  SL-A2-08
  SL-A3-08
  SL-A4-06
  SL-A4-07
  SL-A4-08
  SL-A4-09
  SL-A4-11
  SL-WE-01
  SL-WE-02
  SL-WE-03
  SL-WE-04
  SL-WE-05
  SL-WE-06
  SL-WE-07
  SL-WE-08
  SL-WE-09
  SL-X-01
  SL-X-02
  SL-X-03
  SL-X-04
  SL-X-05
  SL-X-06
  SL-X-07
  SL-X-08
  SL-X-09
  SL-X-10
  SL-X-11
  SL-X-12
  SL-X-13
  SL-X-14
  SL-X-15
  SL-X-16
  SL-C-01
  SL-C-02
  SL-C-03
  SL-C-04
  SL-C-05
  SL-C-06
  SL-C-07
  SL-C-08
  SL-C-09
  SL-C-10
  SL-C-11
  SL-C-12
  SL-C-13
  SL-C-14
  SL-C-15
  SL-C-16
  SL-C-17
  SL-C-18
  SL-C-19
  SL-ASM-01
  SL-ASM-03
  SL-O-01
  SL-O-02
  SL-O-03
  SL-O-04
  SL-O-05
  SL-O-06
  SL-O-07
  SL-O-08
  SL-O-09
  SL-O-10
  SL-O-11
  SL-O-12
  SL-O-13
  SL-O-14
  SL-O-15
  SL-O-16
  SL-O-17
  SL-O-18
  SL-O-19
  SL-O-20
  SL-O-21
  SL-O-22
  SL-O-23
  SL-F-4
  SL-CR-01
PM: 203 without a test or a review mark
  PM-M01
  PM-M02
  PM-M03
  PM-M04
  PM-M05
  PM-M06
  PM-M07
  PM-M08
  PM-M09
  PM-M10
  PM-M11
  PM-M12
  PM-M13
  PM-M14
  PM-M15
  PM-M16
  PM-M17
  PM-M18
  PM-M19
  PM-M20
  PM-M21
  PM-M22
  PM-M23
  PM-M24
  PM-M25
  PM-M26
  PM-M27
  PM-M28
  PM-M01-L1
  PM-M01-L2
  PM-M02-L1
  PM-M02-L2
  PM-M03-L1
  PM-M04-L1
  PM-M04-L2
  PM-M04-L3
  PM-M05-L1
  PM-M05-L2
  PM-M06-L1
  PM-M06-L2
  PM-M07-L1
  PM-M08-L1
  PM-M08-L2
  PM-M08-L3
  PM-M09-10
  PM-M09-L1
  PM-M09-L2
  PM-M10-L1
  PM-M10-L2
  PM-M10-L3
  PM-M11-03
  PM-M11-04
  PM-M11-05
  PM-M11-06
  PM-M11-08
  PM-M11-L1
  PM-M11-L2
  PM-M11-L3
  PM-M11-L4
  PM-M12-09
  PM-M12-L1
  PM-M12-L2
  PM-M13-L1
  PM-M13-L2
  PM-M14-L1
  PM-M14-L2
  PM-M14-L3
  PM-M15-L1
  PM-M16-L1
  PM-M16-L2
  PM-M17-L1
  PM-M18-L1
  PM-M18-L2
  PM-M19-L1
  PM-M19-L2
  PM-M20-L1
  PM-M20-L2
  PM-M21-L1
  PM-M21-L2
  PM-M22-01
  PM-M22-02
  PM-M22-L1
  PM-M22-L2
  PM-M23-L1
  PM-M24-L1
  PM-M24-L2
  PM-K-11
  PM-K-12
  PM-K-13
  PM-K-14
  PM-WV-01
  PM-WV-02
  PM-WV-03
  PM-WV-04
  PM-I-01
  PM-I-02
  PM-I-03
  PM-I-04
  PM-I-05
  PM-I-08
  PM-I-09
  PM-I-10
  PM-I-11
  PM-I-12
  PM-I-13
  PM-I-14
  PM-I-15
  PM-I-16
  PM-I-17
  PM-I-18
  PM-I-19
  PM-I-20
  PM-I-21
  PM-I-22
  PM-I-23
  PM-I-24
  PM-I-25
  PM-I-26
  PM-I-27
  PM-I-28
  PM-I-29
  PM-I-30
  PM-I-31
  PM-I-32
  PM-I-33
  PM-I-34
  PM-I-35
  PM-I-36
  PM-I-37
  PM-I-38
  PM-I-39
  PM-I-40
  PM-I-41
  PM-I-42
  PM-I-43
  PM-EV-01
  PM-EV-02
  PM-EV-03
  PM-EV-04
  PM-EV-05
  PM-EV-06
  PM-EV-07
  PM-EV-08
  PM-EV-09
  PM-EV-10
  PM-EV-11
  PM-EV-12
  PM-EV-13
  PM-EV-14
  PM-T-L1
  PM-T-L2
  PM-T-L3
  PM-T-L4
  PM-T-L5
  PM-DC-01
  PM-DC-02
  PM-DC-03
  PM-DC-04
  PM-DC-05
  PM-DC-06
  PM-EX-01
  PM-EX-02
  PM-EX-03
  PM-EX-04
  PM-EX-05
  PM-EX-06
  PM-EX-07
  PM-EX-08
  PM-EX-09
  PM-EX-10
  PM-D-01
  PM-D-02
  PM-D-03
  PM-D-04
  PM-D-05
  PM-D-06
  PM-D-07
  PM-D-09
  PM-D-10
  PM-D-11
  PM-D-12
  PM-D-13
  PM-D-14
  PM-ASM-01
  PM-ASM-02
  PM-ASM-04
  PM-O-01
  PM-O-02
  PM-O-03
  PM-O-04
  PM-O-05
  PM-O-06
  PM-O-07
  PM-O-08
  PM-O-09
  PM-O-10
  PM-O-11
  PM-O-12
  PM-O-13
  PM-O-14
  PM-O-15
  PM-O-16
  PM-O-17
DM: 152 without a test or a review mark
  DM-A-01
  DM-A-02
  DM-A-03
  DM-A-04
  DM-A-05
  DM-A-06
  DM-A-07
  DM-A-08
  DM-A-09
  DM-A-10
  DM-A-11
  DM-A-12
  DM-A-13
  DM-A-14
  DM-A-15
  DM-A-16
  DM-A-17
  DM-A-18
  DM-A-19
  DM-A-20
  DM-A-21
  DM-A-22
  DM-A-23
  DM-A-24
  DM-A-25
  DM-A-26
  DM-A-27
  DM-A-28
  DM-C-09
  DM-V-04
  DM-V-05
  DM-E-46
  DM-E-48
  DM-E-49
  DM-E-81
  DM-R-46
  DM-R-47
  DM-R-48
  DM-R-49
  DM-R-68
  DM-R-59
  DM-SM01-T1
  DM-SM01-T2
  DM-SM01-T3
  DM-SM02-T1
  DM-SM02-T2
  DM-SM02-T3
  DM-SM03-T1
  DM-SM03-T2
  DM-SM03-T3
  DM-SM03-T4
  DM-SM04-T1
  DM-SM04-T2
  DM-SM05-T1
  DM-SM05-T2
  DM-SM05-T3
  DM-SM05-T4
  DM-SM05-T5
  DM-SM05-T6
  DM-SM06-T1
  DM-SM06-T2
  DM-SM06-T3
  DM-SM06-T4
  DM-SM07-T1
  DM-SM07-T2
  DM-SM07-T3
  DM-SM07-T4
  DM-SM07-T5
  DM-SM07-T6
  DM-SM08-T1
  DM-SM08-T2
  DM-SM08-T3
  DM-SM08-T4
  DM-SM08-T5
  DM-SM09-T1
  DM-SM09-T2
  DM-SM09-T3
  DM-SM10-T1
  DM-SM10-T2
  DM-SM10-T3
  DM-SM10-T4
  DM-SM10-T5
  DM-SM10-T6
  DM-SM11-T1
  DM-SM11-T2
  DM-SM11-T3
  DM-SM11-T4
  DM-SM11-T5
  DM-SM11-T6
  DM-SM11-T7
  DM-SM11-T8
  DM-SM12-T1
  DM-SM12-T2
  DM-SM12-T3
  DM-SM12-T4
  DM-SM12-T5
  DM-SM12-T6
  DM-SM12-T7
  DM-SM12-T8
  DM-SM12-T9
  DM-SM12-T10
  DM-SM13-T1
  DM-SM13-T2
  DM-SM13-T3
  DM-SM13-T4
  DM-SM15-T1
  DM-SM15-T2
  DM-SM15-T3
  DM-D-01
  DM-D-02
  DM-D-03
  DM-D-04
  DM-D-05
  DM-D-06
  DM-D-07
  DM-D-08
  DM-D-10
  DM-D-11
  DM-D-12
  DM-D-13
  DM-D-14
  DM-D-15
  DM-D-16
  DM-D-17
  DM-D-18
  DM-D-19
  DM-D-20
  DM-D-21
  DM-ASM-01
  DM-ASM-02
  DM-ASM-03
  DM-ASM-04
  DM-ASM-05
  DM-ASM-06
  DM-ASM-08
  DM-L-01
  DM-L-02
  DM-L-03
  DM-O-01
  DM-O-02
  DM-O-03
  DM-O-04
  DM-O-05
  DM-O-06
  DM-O-07
  DM-O-08
  DM-O-09
  DM-O-10
  DM-O-11
  DM-O-12
  DM-O-13
  DM-O-14
DC: 142 without a test or a review mark
  DC-A-01
  DC-A-02
  DC-A-08
  DC-A-09
  DC-A-10
  DC-A-11
  DC-A-12
  DC-A-18
  DC-R-01
  DC-R-02
  DC-R-03
  DC-R-04
  DC-R-05
  DC-R-07
  DC-M-01
  DC-M-02
  DC-M-03
  DC-M-04
  DC-M-05
  DC-M-06
  DC-M-07
  DC-M-08
  DC-M-09
  DC-M-10
  DC-M-11
  DC-M-12
  DC-M-13
  DC-M-14
  DC-M-15
  DC-M-16
  DC-M-17
  DC-M-18
  DC-M-19
  DC-M-20
  DC-M-21
  DC-M-22
  DC-M-23
  DC-M-24
  DC-M-25
  DC-M-26
  DC-M-27
  DC-M-28
  DC-M-29
  DC-M-30
  DC-M-31
  DC-M-32
  DC-M-33
  DC-M-34
  DC-M-35
  DC-M-36
  DC-M-37
  DC-M-38
  DC-M-39
  DC-M-40
  DC-M-41
  DC-M-42
  DC-M-43
  DC-U-14
  DC-U-16
  DC-S06-01
  DC-S06-02
  DC-S06-03
  DC-S06-04
  DC-S06-07
  DC-S06-08
  DC-S06-09
  DC-S06-10
  DC-S06-11
  DC-S06-12
  DC-S06-13
  DC-S06-14
  DC-S06-15
  DC-S06-16
  DC-S07-01
  DC-S07-02
  DC-S07-03
  DC-S07-04
  DC-S07-05
  DC-S08-01
  DC-S08-02
  DC-S08-03
  DC-S08-04
  DC-S08-05
  DC-S09-01
  DC-S09-02
  DC-S09-03
  DC-S09-04
  DC-S09-05
  DC-S11-01
  DC-S11-02
  DC-S11-03
  DC-S11-04
  DC-L-01
  DC-L-02
  DC-L-03
  DC-L-04
  DC-L-05
  DC-L-06
  DC-L-07
  DC-L-08
  DC-L-09
  DC-L-10
  DC-L-11
  DC-L-12
  DC-L-13
  DC-L-14
  DC-D-01
  DC-D-02
  DC-D-03
  DC-D-04
  DC-D-05
  DC-D-06
  DC-D-07
  DC-D-08
  DC-D-09
  DC-D-10
  DC-D-11
  DC-D-12
  DC-D-13
  DC-D-14
  DC-D-15
  DC-D-16
  DC-D-17
  DC-A-03
  DC-A-04
  DC-A-05
  DC-A-06
  DC-A-07
  DC-A-13
  DC-A-14
  DC-A-15
  DC-A-16
  DC-A-17
  DC-A-19
  DC-ASM-01
  DC-ASM-02
  DC-ASM-03
  DC-ASM-04
  DC-O-01
  DC-O-02
  DC-O-03
  DC-O-04
AR: 84 without a test or a review mark
  AR-A-01
  AR-A-02
  AR-A-03
  AR-A-04
  AR-A-05
  AR-A-06
  AR-A-07
  AR-A-08
  AR-A-21
  AR-A-22
  AR-A-24
  AR-U-05
  AR-M-25
  AR-M-26
  AR-M-27
  AR-M-28
  AR-E-12
  AR-I-26
  AR-I-40
  AR-I-41
  AR-I-42
  AR-I-43
  AR-I-44
  AR-I-45
  AR-I-46
  AR-I-47
  AR-I-48
  AR-I-49
  AR-I-50
  AR-I-55
  AR-I-56
  AR-I-60
  AR-I-61
  AR-I-62
  AR-I-63
  AR-I-64
  AR-I-70
  AR-I-71
  AR-I-72
  AR-I-73
  AR-I-74
  AR-I-75
  AR-I-81
  AR-I-82
  AR-W-04
  AR-W-05
  AR-S-10
  AR-D-01
  AR-D-02
  AR-D-03
  AR-D-04
  AR-D-05
  AR-D-06
  AR-D-07
  AR-D-08
  AR-D-09
  AR-D-10
  AR-D-11
  AR-D-12
  AR-D-13
  AR-D-14
  AR-D-15
  AR-D-16
  AR-D-18
  AR-D-17
  AR-A-09
  AR-A-10
  AR-A-11
  AR-A-12
  AR-A-13
  AR-A-14
  AR-A-15
  AR-A-16
  AR-A-17
  AR-A-18
  AR-A-19
  AR-A-20
  AR-A-23
  AR-ASM-07
  AR-ASM-09
  AR-L-01
  AR-L-02
  AR-L-03
  AR-O-01
SB: 106 without a test or a review mark
  SB-SET-01
  SB-SET-02
  SB-SET-03
  SB-SET-04
  SB-SET-05
  SB-SET-06
  SB-SET-07
  SB-SET-08
  SB-S00
  SB-S01
  SB-S02
  SB-S03
  SB-S04
  SB-S05
  SB-S06
  SB-S07
  SB-S10
  SB-TL-01
  SB-TL-02
  SB-TL-03
  SB-TL-04
  SB-TL-05
  SB-TL-06
  SB-TL-07
  SB-TL-08
  SB-TL-09
  SB-TL-10
  SB-R-01
  SB-R-02
  SB-R-03
  SB-R-04
  SB-R-05
  SB-R-06
  SB-V-01
  SB-V-02
  SB-V-03
  SB-V-04
  SB-V-05
  SB-V-06
  SB-V-07
  SB-V-08
  SB-V-09
  SB-V-10
  SB-V-11
  SB-V-12
  SB-V-13
  SB-V-14
  SB-V-15
  SB-V-16
  SB-V-17
  SB-V-18
  SB-V-19
  SB-V-20
  SB-V-21
  SB-V-22
  SB-V-23
  SB-V-24
  SB-V-25
  SB-V-26
  SB-V-27
  SB-V-28
  SB-V-29
  SB-V-30
  SB-V-31
  SB-V-32
  SB-V-33
  SB-V-34
  SB-V-35
  SB-V-36
  SB-V-37
  SB-V-40
  SB-V-41
  SB-V-42
  SB-T-10
  SB-D-01
  SB-D-02
  SB-D-03
  SB-D-04
  SB-D-05
  SB-D-06
  SB-D-07
  SB-D-08
  SB-D-09
  SB-D-10
  SB-D-11
  SB-D-12
  SB-D-13
  SB-A-01
  SB-A-02
  SB-A-03
  SB-A-04
  SB-A-05
  SB-A-06
  SB-A-08
  SB-A-07
  SB-ASM-02
  SB-O-01
  SB-O-02
  SB-O-03
  SB-D01
  SB-D02
  SB-D03
  SB-D04
  SB-D05
  SB-D06
  SB-D07
VD: 8 without a test or a review mark
  VD-D-01
  VD-D-02
  VD-D-03
  VD-D-04
  VD-D-05
  VD-D-06
  VD-D-07
  VD-ASM-01
DD: 30 without a test or a review mark
  DD-O-01
  DD-O-02
  DD-F-00
  DD-F-11
  DD-F-12
  DD-R-01
  DD-R-02
  DD-R-03
  DD-R-04
  DD-R-05
  DD-R-06
  DD-R-08
  DD-R-09
  DD-R-11
  DD-K-01
  DD-K-02
  DD-K-07
  DD-K-08
  DD-K-09
  DD-K-10
  DD-K-12
  DD-K-13
  DD-K-14
  DD-K-15
  DD-K-16
  DD-ASM-01
  DD-ASM-03
  DD-ASM-05
  DD-O-03
  DD-O-04
```

</details>

The review of this list is BP-CR-02's second half, made by the Claude project.

### BP-CR-05 (build session)

P-34 builds only what its spec lists:
- the compression of the built files, three settings, with no other answer compressed (AR-W-01 as amended by BP-A-33);
- the control panel's look, styles, two font files and their licences, with no text, control, order or behaviour changed (`45-visual-design` v1.4);
- the acceptance tooling under `tests/` and `spikes/`.

It adds no screen element, operation, field, setting or behaviour to Vigil (`web/`, the OpenAPI file and the database are unchanged; `app/` changes only by `application.properties`) or to the control panel, and no item to list A or B of `01-scope-lock`. The deviations of Section 3 concern tests and tooling, plus two style details of the panel that change nothing it shows or does.

### BP-CR-01, BP-CR-03, BP-CR-04, BP-CR-06, BP-CR-07, BP-CR-08 and BP-CR-02's review (Claude project)

To be written by the Claude project after the last recording session (spec Section 9).

## 14. Pull request

Opened with the GitHub tool: [awadoodatevam/vigil#39](https://github.com/awadoodatevam/vigil/pull/39), "P-34: Primary demo hardening", base `main`.
