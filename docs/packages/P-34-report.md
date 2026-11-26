# P-34 — Report: Primary demo hardening

| Field | Value |
| --- | --- |
| Package | P-34 Primary demo hardening (spec `docs/packages/P-34.md`) |
| Branch | `p-34-primary-demo-hardening` |
| Pin date (BP-R-08) | 26 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | GATE_STATE |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| **Compression of the built files** (spec 6.2 item 1): `app/src/main/resources/application.properties` gains exactly `server.compression.enabled=true`, `server.compression.mime-types=text/html,text/css,text/javascript,application/javascript,image/svg+xml` and `server.compression.min-response-size=2KB`. Spring Boot 4.1's embedded Tomcat compresses the answers of `WebApplicationConfiguration`'s resource handlers with these three settings alone: no Java line and nothing else was needed (`WebAppServingIT`). `Cache-Control` is unchanged (`max-age=31536000, immutable` for `/assets/**`, `no-cache` for `index.html` and the fallback). The API answers (`application/json`, `application/problem+json`), the live stream (`text/event-stream`), the actuator answers and fonts are not in the list, so they are not compressed. `demo-control` has no compression setting. | AR-W-01 (BP-A-33), AR-I-04 |
| **`check.sh`'s `assets-gzip`** (spec 6.2 item 3, `tests/acceptance/demo-host/check.sh`): in the part `https`, after `https-demo`, the section `-- assets-gzip` fetches `https://demo.{d}/login`, takes the first `/assets/….js` path (printed as `asset {path}`), then fetches it with `curl -sS -o /dev/null -D - -w 'size %{size_download}\n'`, first with `-H 'Accept-Encoding: gzip'` (after the line `with gzip:`) and then without it (after `without:`); both sizes stay in the output. The check `assets-gzip` (after `https-demo` in the list of checks) passes when the first answer has `content-encoding: gzip` and `cache-control: max-age=31536000, immutable` (header names in any case) and its `size` is smaller than the second's. | AR-W-01 |
| **The control panel's look** (spec 6.3, `demo/control/src/main/resources/static/index.html` only): the `<style>` is replaced by the styles of VD-R-15, VD-C-29 and VD-S-09. The page has the two `@font-face` rules (the Latin `normal` rules of the two packages' `index.css`, `src: url("/fonts/{file}") format("woff2-variations")`) and a `:root` with exactly the 44 tokens the page uses, named and valued as in `web/src/app/tokens.css` (`--vd-text` keeps `var(--vd-gray-900)` and `--vd-gray-900` is declared, and the same for every token that names another one). The page then has: the body role on `--vd-canvas` over the full window height; the navy top line; the content 760px wide at most, centred, padding 24px (16px below 600px); the login card with the VD-C-10 fields, the full-width primary "Sign in" 20px below them and the error notice, hidden while `:empty`; the button card (the `ul#buttons`, items 14px 20px apart from each other with 1px `--vd-border-2`, secondary buttons, the description, the refusal reason in the warning colour, disabled at opacity 0.55); the state card (the fault lines with the first one bold, the status with its line above it, the storm clock in mono `--vd-accent-700` tabular, the link "Open {product}" at 15px weight 600); the dialog of VD-C-27 with "Cancel" secondary and "Reset" destructive side by side; the focus ring (VD-R-12) and the reduced-motion rule (VD-R-11). Markup (VD-R-01): one `<div class="card login-card">` around the login form, one `<div class="card state-card">` around `#faults`, `#status`, `#clock` and the link's paragraph, and one `<div class="confirm-buttons">` around the dialog's two buttons. The script, every text, control, id and the order of the elements are unchanged (the script's SHA-256 equals that of `4fd1c27`). There is still one `<script>` without `src`, and no `<link>`, `@import` or `http(s)://` address. No decorative element and no product mark. | DC-S12-01, AR-T-21, VD-R-01, VD-R-09, VD-R-12, VD-R-15, VD-C-29, VD-S-09 |
| **The panel's fonts** (spec 6.1, 6.3 item 3): `demo/control/src/main/resources/static/fonts/inter-latin-wght-normal.woff2` and `jetbrains-mono-latin-wght-normal.woff2`, byte-equal copies of the files of `@fontsource-variable/inter` and `@fontsource-variable/jetbrains-mono` 5.3.0 under `web/node_modules`; their `LICENSE` files (OFL-1.1) as `demo/control/src/main/resources/licenses/inter-LICENSE.txt` and `jetbrains-mono-LICENSE.txt`, outside `static/`, so they are not served. Spring Boot serves the fonts from `classpath:/static/fonts/` with `Content-Type: font/woff2` and without a session. Its type table already has `woff2`, so no mapping was added. | VD-R-09, AR-T-21 |
| **The storm driver's two values** (spec 6.4, `tests/acceptance/storm/storm.py`, `storm_checks.py`): `times` gains `approved` (the seconds after T0 of `d.decision`'s `at`, with `--acts driver` and `--acts pages`, `null` without a decision), with the keys in the order `SB-TL-01`, `SB-TL-02`, `SB-TL-03`, `SB-TL-04`, `SB-TL-06`, `SB-TL-07`, `approved`, `SB-TL-08`, `SB-TL-10`. `investigation` gains `summary_length` (Python `len` of the summary text that `SB-S04 investigation` reads, `null` when there is none). Neither value is checked; the checks, their count and their order are unchanged. `tests/acceptance/storm/README.md` names both values. | SB-T-04, AR-G-12 |
| **SB-T-02's series** (spec 6.5): `tests/acceptance/demo-host/series.sh {installation} [--runs N] [--measured FILE]` (Mac, bash 3.2) writes the manifest `tests/acceptance/results/P-34-series-{UTC start}.txt` (two `#` comment lines, then one line per run: `run {k} exit {code} result {file} checklist {file}`). It runs `storm.sh {installation} --mode script --with-playwright [--measured FILE]` N times, keeps each exit code, finds each run's new `storm-script-*.json` and `storm-checklist-*.md` (exactly one each, else `none`), and copies the suite's `screens/` and `results/` to `~/.vigil-demo/{installation}/series/{UTC start}/run-{k}/`. A failed run does not stop the series. The script prints `manifest {path}` and exits 0 only when every run exited 0. `tests/acceptance/demo-host/series.py {manifest}` (python3, standard library only) writes `P-34-SB-T-02.json` beside the manifest with the fields, run entries and criteria of spec 6.5 item 2 (`runs_5`, `settings_5`, `drivers_5`, `checklists_5`, `repeatability_5`, `similar_5`, `step_times_5` with 15, 60 and 15 s, `rb_db_01_4_of_5`), `passed`, `suite_storm_runs_passed` and `summary_lengths`. It prints `PASS`/`FAIL` per criterion and exits 0, 1, or 2 for wrong arguments or an unreadable manifest line. | SB-T-02, SL-A3-04, SB-T-03, SB-T-04, AR-G-12, SL-ASM-04, SB-ASM-02 |
| **The local model's SB-T-04 part** (spec 6.6): `spikes/providers/run.sh` takes `--package P-34` as it takes `--package P-20`: `VIGIL_SPIKE_PACKAGE` is the package and the recordings go to a temporary folder, removed at the end. The result is `spikes/results/P-34-{spike}.json`, so `local-model` writes `P-34-SB-T-04.json`. The usage line is exactly `Usage: spikes/providers/run.sh jira|claude|openai|similarity|local-model [--package P-20|P-34] | teams | email`. `common.py`'s docstrings, `spikes/providers/README.md` (the option and the row for `run.sh local-model --package P-34`) and `spikes/results/README.md` (the row `P-34-SB-T-04.json`) follow. The request and the output checks are those of P-20 (unchanged). | AR-G-12, AR-G-32, PM-M17-03, AR-ASM-12 |
| **Rehearsals** (spec 6.7): `tests/acceptance/demo-host/rehearsal.py {file}` (python3, standard library only) reads `P-34-rehearsal-{n}.txt` in the order of spec 6.7 item 1 (`#` lines are comments, blank lines are skipped) and writes `{file without .txt}.json` with `date`, `scenes` (`{id, start_s, duration_s, budget_s, within}`, budgets 60, 120, 90, 45, 60, 105, 105, 135, 30, 60, 30 s), `length_s`, `assumptions`, `criteria` (`scenes_within_30`, `length_900`, `assumptions_yes`) and `passed`. Exit 0 or 1. A file it cannot read stops it with `Line {n}: {reason}` and exit 2: an item out of order or missing (`expected {item}, found {item}`, `{item} missing`), a repeated item, a start earlier than the one before, an unreadable time or date, an answer other than yes or no, or an extra line. | SB-T-08, SB-ASM-01, DC-ASM-01, DC-ASM-03, DC-ASM-04 |
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
- `app/src/test/java/vigil/WebAppServingIT.java` (6 tests, 3 new):
  - "AR-W-01 built files are sent gzip-compressed": the first `/assets/….js` and `….css` of `index.html`, sent with `java.net.http.HttpClient`, which does not decompress. With `Accept-Encoding: gzip` each answers `Content-Encoding: gzip` and `Cache-Control: max-age=31536000, immutable`, and its body is smaller and unzips to the file under `classpath:/static/`. Without `Accept-Encoding` each answers without `Content-Encoding`, with the same `Cache-Control` and the file's bytes.
  - "AR-W-01 no other answer is compressed": `GET /api/v1/public/branding` with `Accept-Encoding: gzip` answers JSON without `Content-Encoding`.
  - "AR-I-04 the live stream is not compressed": a signed-in `GET /api/v1/live?topics=tenant` with `Accept-Encoding: gzip` answers `text/event-stream` without `Content-Encoding`. The test reads the head and closes the stream.
- `demo/control/src/test/java/democontrol/InterfaceIT.java` (7 tests, 3 new):
  - "VD-R-09 the panel serves its two fonts from its own origin": `GET /fonts/{file}` without a session answers 200 `font/woff2` without `Content-Encoding`, and the bytes equal `ROOT/web/node_modules/@fontsource-variable/…/files/{file}`. The page's two `@font-face` rules name only `/fonts/…`, their families are "Inter Variable" and "JetBrains Mono Variable", and their family, style, display, weight range and `unicode-range` equal the Latin `normal` rules of the packages' `index.css`.
  - "VD-R-15 the panel's tokens are those of tokens.css": every `--vd-*` the page declares has the name and value (white space collapsed) of `ROOT/web/src/app/tokens.css`. The page declares exactly the tokens it uses outside `:root`, plus those they name. Outside `:root` the only colour literals are those VD-R-04 allows.
  - "VD-R-01 the panel's texts and controls are unchanged": the page's texts, ids, the `form`, `label`, `input`, `button`, `a` and `dialog` tags with their attributes, and the order of the elements (the wrapping cards without an id left out) equal those of the page at `4fd1c27`, written into the test; the script's SHA-256 equals that of `4fd1c27`.

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

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 26 November 2026)

No new dependency and no pin changed. The two font files are copies of the pinned `@fontsource-variable/inter` 5.3.0 and `@fontsource-variable/jetbrains-mono` 5.3.0 (CTX-D-088 (2)).

## 7. Values recorded under `docs/`

None under `docs/` besides this report (spec Section 9: no DD value is measured). This session recorded the screenshots of spec Section 7 under `tests/acceptance/results/P-34/` (Section 8).

## 8. Owner-run results

CLOUD_RESULTS

**Owner-run steps (spec Section 6.8), all still to run by the owner:**
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

**0** (S; BP-R-05 limit 800; spec estimate about 10). The compression is three lines of `application.properties`, and the panel's page is HTML and CSS; neither is counted. Neither are the tooling under `tests/` and `spikes/`, the tests or the font files (BP-R-05). No Java line was needed (Section 1).

## 10. Gate command result

GATE_RESULT

## 11. Follow-up items for later packages

- The owner: the steps of spec Section 6.8 (Section 8 above), the label `e2e` on the pull request, and the decisions of the SL-CP-2 review (BP-CR-06, including SL-C-01).
- A cloud session "Record the owner-run results of P-34" after the series and the local-model step, and again after the rehearsals and the video. It also records the result of P-29's "DC-S03-07 countdowns and the observation window were counted" in each of the five runs (spec 6.9 item 4, CTX-D-098 (5)).
- P-40: the full demo's backup video, SB-T-09's other parts, SB-T-10 and the deep dives on the demo installation.

## 12. SL-CP-2 review (`60-build-plan` Section 8.3)

### BP-CR-02 (build session)

`tools/trace.py --check` passes: every row with proof `test` in the part 4 tables of the specs has a test that names its ID, P-34's rows included. `tools/trace.py` lists the IDs of the earlier documents that no test names and that no spec of a package with a report on `main` marks "checked by review". On this branch, 26 November 2026:

| Prefix | IDs listed |
| --- | --- |
TRACE_TABLE

<details>
<summary>The full list (<code>tools/trace.py</code> output)</summary>

```
TRACE_LIST
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

## 13. Pull request

PR_LINE
