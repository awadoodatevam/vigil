# P-44 — Report: Visual design system

| Field | Value |
| --- | --- |
| Package | P-44 Visual design system (spec `docs/packages/P-44.md`) |
| Branch | `p-44-visual-design-system` |
| Pin date (BP-R-08) | 13 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built; the gate command passes (Section 10). In this session (done-check item 3): `deploy/local.sh up` (exit 0), `deploy/local.sh e2e` (38 of 38 passed: the 26 tests of P-28 and P-26 and the 12 of `look.spec.ts`), `VIGIL_STORM_RESULTS=$HOME/storm-results tests/acceptance/storm/with-playwright.sh` (exit 0: the eight `@storm` tests passed, the driver passed, AR-Q-07 passed) and `deploy/local.sh down --volumes` (exit 0); the eleven screenshots are committed under `tests/acceptance/results/P-44/` (Section 8). Owner-run steps (spec Section 6.10): not yet run (Section 8). |

## 1. What was built

No change to `app/`, the OpenAPI file, the database or any route, request, query, text or behaviour of P-28 (VD-R-01).

| Part | Requirement IDs |
| --- | --- |
| **`tools/trace.py`** (spec 6.8a): `ID_PATTERN` reads the prefix `VD-`. | BP-R-15 |
| **Fonts** (spec 6.2 items 1, 2): `web/package.json` dependencies `@fontsource-variable/inter` 5.3.0 and `@fontsource-variable/jetbrains-mono` 5.3.0, development dependency `@types/node` 22.20.5; `web/src/main.tsx` imports both packages, then `./app/tokens.css`, before `App`. Vite places the font files in `dist/assets/`, served by `vigil-app` from the page's origin (Section 9 of this report lists them). | VD-R-09 |
| **`web/src/app/tokens.css`** (spec 6.2 item 3), the only global stylesheet: (a) one `:root` rule with the 99 tokens of `45-visual-design` Sections 4.1 to 4.6, named and valued as there; (b) the base rules (`html, body`; `body` font, size, line height, colours, smoothing; `#root` as a flex column of the full window height; `main h1` as the page heading role with 16px below it; `a` in `--vd-accent-600`, underlined on hover and focus; the focus ring on `:is(a, button, input):focus-visible` with `!important`); (c) the reduced-motion rule. `app.module.css` lost its `:global` rules; its `.content` is VD-S-03's column. | VD-R-04, VD-R-09, VD-R-11, VD-R-12, VD-C-03, VD-C-08, VD-S-03 |
| **Banner, header, layout** (spec 6.3): `.banner` as VD-C-01 (sticky, `z-index: 20`, at least 30px, stripes, 13px weight 600, 12px below 600px; the only rule that uses `--vd-banner` and `--vd-banner-text`); the header bar (56px, navy, 24px gaps); the product's mark from its own `::before` and `::after`; the customer chip or 24px logo (`Customer` gets the prop `place`); the navigation 8px after the customer, items full height, the current item with the accent underline, never underlined; the zone note and the user span pushed right (`.zone + .user` resets the user's margin, so the no-access header also pushes the name right); the user glyph (a 28px circle with a 14px person outline, inline SVG, `aria-hidden`); "Sign out" as the dark ghost button, its action-failed line as an error notice placed under it (`position: absolute`); `main.content` as VD-L-01 (at most 1440px, centred, padding 24px, 16px below 600px), its parts 16px apart; the session's `Loading` wrapped in `styles.content` as its `PageError` already was. | VD-C-01, VD-C-02, VD-C-09, VD-C-15, VD-L-01, VD-L-02, VD-S-03, DC-U-01 |
| **States** (spec 6.4): `Loading` with its spinner (`spin` keyframes in `states.module.css`); `Empty` as a card with the ring and the text; `PageError` and `PartError` as the error notice with "Try again" as the small secondary button on the right (below the message under 600px); `ActionFailed` 13px `--vd-red-700` 4px under its control; `LiveInterrupted` as VD-C-14's bar, full width under the header. | VD-C-11, VD-C-12, VD-C-13, VD-C-14, VD-C-15 |
| **Login and no-access** (spec 6.5): the login content wrapped in one `styles.card` inside the navy `styles.page` with its two radial highlights; the card at most 424px, centred, padding 32px 32px 28px (26px 22px below 600px), its items in one column, the product and the customer starting at the card's left edge; the message as an info notice, the refusal as an error notice, "Sign in" primary and full width, the fields as VD-C-10. The no-access text in a card (`styles.noAccess`). | VD-S-01, VD-S-02, VD-C-04, VD-C-09, VD-C-10, VD-C-11, VD-L-04 |
| **Incident list** (spec 6.6): `Strip.tsx` items (1) to (3) as tiles with a label and a value (the grouping tile with its gradient and pulsing dot, `pulse` keyframes in `incidents.module.css`; item (3) with its 9px dot, the whole item in `--vd-warning` with the class `warning` when a source is not healthy), item (4) as the feed row (two columns, one below 900px) or `feedEmpty`; `Rows.tsx` with the severity badge (`data-severity`), the state chip (`data-state`, its dot from `::before`), the stage chip, "No" in the warning colour and the external-link glyph inside the ticket link, every column styled by position; `IncidentsView.tsx` with the table and "Show more" in `styles.tableCard` (the footer `styles.more`) and each part's `Loading` in `styles.partCard`. | VD-C-04, VD-C-05, VD-C-06, VD-C-07, VD-C-08, VD-L-03, VD-S-04, VD-R-08 |
| **Kind tags** (spec 6.7): `KindTag` keeps one class and gets `data-kind` (the eleven words and `record`); `tags.module.css` gives each its pair of Section 4.4 (the dashed borders of "Test" and "Illustrative baseline"). | VD-C-16 |
| **`CLAUDE.md`** (spec 6.8): the style line and the Maven note, worded as the spec gives them. | BP-DC-06 |
| **Playwright** (spec 6.9): `screenshot(page, name)` in `tests/e2e/helpers.ts`; `tests/e2e/look.spec.ts` (12 tests); `storm.spec.ts` takes `incidents-storm-grouping` and `incidents-storm-open` and has the two new tests "VD-L-03 …" and "VD-C-07 …" after "DC-U-08 …" (the suite `storm` has eight tests). | VD-R-07, VD-R-09 to VD-R-12, VD-C-01, VD-C-07, VD-L-03, VD-L-04, VD-S-01, DC-U-01 |
| VD-R-06: every coloured state keeps its text (P1 to P4, "Open", "Resolved", "Closed", the stage names, "No", "{h} of {s} sources healthy", "{source}: Stale"); VD-R-13: no component or token was added beyond `45-visual-design`. | VD-R-06, VD-R-13 (checked by review) |

## 2. Tests added

Vitest (`web/src`, 76 tests in 19 files; the CSS tests read the files with `node:fs` and start with `/// <reference types="node" />`):

| Test | IDs |
| --- | --- |
| `app/tokens.test.ts`: "VD-R-04 the tokens are those of 45-visual-design Section 4" (the 99 tokens copied from the document, in order), "VD-R-04 every var() in a CSS Module is a declared token" (and no module declares a custom property), "VD-R-04 literal colours only where 45-visual-design allows them", "VD-R-03 no text transform and no cut text", "VD-R-07 the banner colour is used only by the banner" (the tokens only in `.banner`; no yellow, hue 40° to 65° with saturation over 60 %, in any CSS file apart from the two token declarations), "VD-R-08 the warning item is in the warning colour", "VD-R-11 animations and transitions stop under reduced motion" (the rule; the two animations name keyframes of their own module), "VD-R-12 every link, button and input shows the focus ring". | VD-R-03, VD-R-04, VD-R-07, VD-R-08, VD-R-11, VD-R-12 |
| `app/contrast.test.ts` "VD-R-05 every listed pair meets its contrast": the 51 text pairs of spec Section 7 at least 4.5:1, the five graphic pairs at least 3:1, values read from `tokens.css`. | VD-R-05 |
| `app/content.test.tsx` "VD-R-01 the texts of the P-28 screens are unchanged": the banner; the header in both forms (with the sign-out failure); the strip during the storm (127, grouping, a stale source, two feed lines) item by item and with no alert; the column names and every cell of three rows of `fixtures.ts`; each state; each kind tag. | VD-R-01 |
| `app/decor.test.tsx` "VD-R-02 decorative elements carry no text and are hidden": the glyph, pulse, dot, ring and spinner (one each) and the two SVGs have `aria-hidden="true"` and no text. | VD-R-02 |
| `incidents/strip.test.tsx` "VD-C-05 the tiles, the grouping tile and the warning item". | VD-C-05 |
| `incidents/rows.test.tsx` "VD-C-07 each severity, state and stage has its badge or chip; an empty one draws nothing". | VD-C-07 |
| `tags/tags.test.tsx` "VD-C-16 each word has its data-kind and one class for every tag". | VD-C-16 |
| `states/states.test.tsx` "VD-C-12 the empty state has its ring and text", "VD-C-13 Loading has its spinner and the text Loading…". | VD-C-12, VD-C-13 |

Playwright (`tests/e2e/look.spec.ts`, viewport 1280 × 720, user `presenter` unless named): "DC-U-01 no element other than the banner uses its colour", "VD-C-01 the banner is sticky, at least 30 px high, yellow with dark text", "VD-R-09 the fonts are bundled and nothing loads from another host", "VD-R-10 no horizontal scrolling at 1280 × 720", "VD-L-04 the login at 360 px in one column", "VD-R-12 the focus ring on Tab", "VD-R-11 no animation under reduced motion", "VD-S-01 the login card is centred on the navy page", and the four screenshot tests "VD-S-01 the login screens", "VD-S-02 the no-access page", "VD-S-03 the error states", "VD-S-04 the empty incident list". `storm.spec.ts` `@storm`: "VD-L-03 the incident table fits 1280 px", "VD-C-07 the badge and chip colours".

pytest: `tools/test_trace.py` "BP-R-15 VD- IDs are read in part 4 and in test titles".

**P-28 tests changed** (spec Section 6.1 item 3; no expected text, order or checked attribute changed; complete list):

| Test | Change and why |
| --- | --- |
| `incidents/strip.test.tsx` (its reader, used by every test of the file) | Before its replacements, every `<span class="…">` with the class `source`, `latestTime`, `latestKind`, `latestType` or `latestTitle` becomes `|` and every other `<span…>` and `</span>` is removed, because the items are now wrapped in label and value spans (spec 6.6 item 1). |
| `incidents/strip.test.tsx` "DC-S02-02 the warning colour and one entry per source that is not healthy" | Reads the item `<p>` that holds "Monitoring:", checks that its classes include `warning` and compares its text through the reader, in place of the raw string `<p class="…">Monitoring:` (the item now has the classes `tile warning` and a label span). |
| `incidents/rows.test.tsx` "DC-S02-04 the ticket link opens in a new tab with noopener noreferrer" | Checks the link's `href`, `target` and `rel` and its text (tags removed) in place of its whole markup, since the glyph is inside the link. |
| `incidents/states.test.tsx` "DC-U-09 PartError in the table when only the rows fail; the strip is shown" | Compares the strip text with the tags removed (the number is in its own span). |
| `tags/tags.test.tsx` "DC-U-10 every word of the complete list and a record label are shown as a tag" | The expected markup includes `data-kind` (spec 6.7). |

Every other P-28 test (Vitest and Playwright) runs unchanged and passes.

## 3. Deviations from the spec

None changes a behaviour, text or value the spec states. Readings of points the spec and `45-visual-design` leave open:

1. **Parts 16px apart** (VD-L-02): `IncidentsView` gets no wrapper other than the two cards of spec 6.6 item 3, so `layout.module.css` gives every child of `main.content` after the first `margin-top: 16px` (`.content > :not(:first-child)`); with the heading's 16px margin below it, the margins collapse to 16px.
2. **Spaces the documents do not state** use the space tokens (VD-R-04): on the login card the product has 16px below it, the message and the heading 16px, the button 24px above it and the refusal 12px; the feed line's parts are 8px apart with 4px above and below; the state chip's dot and the ticket glyph are 4px from the text; the tile label's pulse is 8px from the text.
3. **The login customer chip's padding** is not stated in VD-S-01; it uses the header chip's 3px 9px (VD-C-02).
4. **The header's sign-out failure notice** has `width: max-content`, so its text is not wrapped to the width of the "Sign out" button it is aligned with.
5. **Severity and stage cells** render through two small functions (`Severity`, `Stage` in `Rows.tsx`) that return exactly the markup of spec 6.6 item 2 (`name && <span …>`).
6. **"VD-C-07 the badge and chip colours"** also waits for the chips' texts "Resolved" and "Open" before reading their colours.
7. **`--vd-fast` transitions** are used only for background and border colour on hover (Section 4.6): the row, the secondary buttons, the dark ghost button and the primary button; the navigation colour and the input's focus border change without a transition.
8. **The header is 56px high without wrapping** (VD-C-02). In a window narrower than VD-R-10 asks for (desktop screens at 1280px), its items shrink and their text wraps inside them.
9. **A tile that wraps to a new row** keeps its left border (VD-C-05 says "a tile that follows a tile"); this happens only below about 760px of content width.

## 4. Document feedback (CTX-Q-8)

1. **The Maven note of CTX-D-086 (4)** (written into `CLAUDE.md` as the spec words it) did not work as written in this session: after Maven Central answered existing artifacts as absent, `./mvnw -B -q dependency:resolve dependency:resolve-plugins` failed with "Could not find goal 'resolve' in plugin org.codehaus.mojo:dependency-maven-plugin:1.0", because the plugin prefix `dependency` was resolved from a group metadata file that had also been missed. The full coordinates worked: `./mvnw -B -q org.apache.maven.plugins:maven-dependency-plugin:3.10.0:resolve org.apache.maven.plugins:maven-dependency-plugin:3.10.0:resolve-plugins` (it failed once more on misses and passed on the second attempt). The first gate run then failed on `surefire-junit-platform` 3.5.6 (absent), which `resolve-plugins` does not fetch (Surefire loads its provider at run time); `…:3.10.0:get -Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.6` fetched it. Two later gate runs stopped on `junit-platform-launcher` 6.0.3 (fetched the same way) and on Spotless's own dependencies (fetched by `./mvnw -B -q -pl app,demo/control com.diffplug.spotless:spotless-maven-plugin:3.10.4:check`; without `-pl` Maven answers "Spotless plugin absent from the project"). Suggested wording for the note: delete the `*.lastUpdated` markers, run the two goals with the full coordinates (repeat when a miss remains), fetch with `:get` any artifact the next run still names as absent, then the gate again.
2. **While the storm groups, the table shows the empty state.** Before the storm incident becomes `open`, the strip shows "Grouping in progress: 1 alert is being grouped" and the table "No incidents yet. Techfinity is watching 2 monitoring sources." (`tests/acceptance/results/P-44/incidents-storm-grouping.png`). This is P-28's behaviour as DC-S02-03 and DC-S02-05 define it (a forming incident is never a row); it is now more visible with the empty-state card. No change made; for the owner's look.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 13 November 2026)

| Package | Version | Where | Note |
| --- | --- | --- | --- |
| `@fontsource-variable/inter` | 5.3.0 | `web/` dependency | Latest release (19 Jul 2026); OFL-1.1. |
| `@fontsource-variable/jetbrains-mono` | 5.3.0 | `web/` dependency | Latest release (19 Jul 2026); OFL-1.1. |
| `@types/node` | 22.20.5 | `web/` development dependency | The version `tests/e2e/` pins (spec 6.1 item 4). |

## 7. Values recorded under `docs/`

None under `docs/` besides this report. The files of spec Section 9: `tests/acceptance/results/P-44/` (the eleven screenshots of this session: `login-1280`, `login-360`, `login-refused`, `login-signed-out`, `no-access-1280`, `not-found-1280`, `unknown-host`, `incidents-empty-1280`, `incidents-storm-grouping`, `incidents-storm-open`, `incidents-storm-rows`); `tests/acceptance/results/P-44-mac.txt` and `P-44-mac/` are the owner's (Section 8).

## 8. Owner-run results

Not yet run. The owner runs spec Section 6.10 on the Mac (`deploy/local.sh up --profile demo`, `deploy/local.sh e2e`, `tests/acceptance/storm/with-playwright.sh`, the eleven screenshots into `tests/acceptance/results/P-44-mac/`, a look in the browser during a second storm, `deploy/local.sh down`), commits `P-44-mac.txt` and `P-44-mac/`, and adds the label `e2e` to the pull request.

Done-check item 3, in the build session (cloud, lab profile `test`):

- `deploy/images.sh --test` then `deploy/local.sh up`: exit 0, "Tenant … is ready at demo.vigil.test." (the first attempt stopped on Maven Central misses, Section 4 item 1).
- `deploy/local.sh e2e`: **38 passed** (21.9 s): `login.spec.ts` 18, `incidents.spec.ts` 4, `smoke.spec.ts` 4, `look.spec.ts` 12. "VD-R-09 …" printed the font files the page requested: `inter-latin-wght-normal-Dx4kXJAl.woff2` and `jetbrains-mono-latin-wght-normal-B9CIFXIH.woff2`, both from `http://demo.vigil.test:8080/assets/`.
- `VIGIL_STORM_RESULTS=$HOME/storm-results tests/acceptance/storm/with-playwright.sh`: **exit 0**. The suite `storm`: **8 passed** (3.1 min): DC-S01-04 (15.6 s), DC-S02-05, DC-S02-03 (1.2 min), SL-A1-02 (1.7 min), DC-U-08, VD-L-03, VD-C-07, DC-S02-04. The driver (mode `immediate`): reset 12.4 s (branch A 12.4 s, quiet 0.06 s, branch B 1.2 s); storm `INC-0002`, report `INC-0001`, ticket `NOC-2` Done; every check of mode `immediate` passed (`storm-immediate-20261113T113342Z.json`); "AR-Q-07 passed: no secret of the installation in the log of this run."
- `deploy/local.sh down --volumes`: exit 0.

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

**210** (BP-R-05 limit for S 800; spec estimate about 330): `web/src` TypeScript only. CSS (`tokens.css` 173 lines, the CSS Modules), tests and `tests/e2e/` are not counted.

Font files of `npm run build` (VD-ASM-01; `dist/assets/`, bytes): Inter `latin` 48,256, `latin-ext` 85,068, `cyrillic-ext` 25,960, `cyrillic` 18,748, `greek` 18,996, `greek-ext` 11,232, `vietnamese` 10,252; JetBrains Mono `latin` 40,404, `latin-ext` 15,196, `cyrillic` 12,108, `greek` 9,004, `vietnamese` 7,504. The two Latin files the screens load are 88,660 bytes together (VD-ASM-01: about 90 KB; Inter 48 KB, JetBrains Mono 40 KB): the assumption holds.

## 10. Gate command result

`tools/check-all.sh` passed in this session (exit 0, "all checks passed"): step (0) Docker, (1) setup version, (1a) test images, (2) `./mvnw -B verify` (web: `npm ci`, `npm run generate`, lint, Prettier check, type check, Vitest 76 tests in 19 files, build; `tests/e2e/`: `npm ci`, lint, Prettier check, type check; `vigil-app` 197 unit tests and 776 integration tests, `demo-control` 1 and 2 tests, 0 failures, 0 errors; Spotless; `BUILD SUCCESS`), (3) `ruff` and pytest: 508 passed, 1 skipped, 39 deselected ("lab tests skipped: no change to the lab", as the spec header says), (4) shellcheck, (5) data checks (49 checks, 0 failed), (6) compose validation, (7) traceability. Three earlier runs stopped on Maven Central misses, none on a test: Surefire's `surefire-junit-platform` 3.5.6, then `junit-platform-launcher` 6.0.3, then Spotless's dependencies (after every Java test had passed); each was fetched alone (Section 4 item 1) and the run reported here is the full run after that.

## 11. Follow-up items for later packages

- P-29: the section headings (16px weight 650) and the run canvas styles of `45-visual-design` Section 9; `KindTag` already carries its colours (`data-kind`); badges and chips come from `incidents.module.css` (`.severity`, `.state`, `.stage`), which P-29 MAY move to a shared module of its own when the workspace uses them.
- P-30: the destructive button, the confirmation dialog (`rgba(11, 21, 38, 0.5)` overlay) and the mobile layout (VD-L-04, controls at least 44px high).
- P-31: the control panel's look is not bound by `45-visual-design`.
- `CLAUDE.md`'s Maven note: Section 4 item 1.
