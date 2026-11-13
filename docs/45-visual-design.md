# Vigil — Visual Design

| Field | Value |
| --- | --- |
| Document | 45-visual-design (ID prefix `VD-`) |
| Version | 1.0 |
| Date | 13 November 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner approved the look on 13 Nov 2026, CTX-D-087) |

## 1. Purpose

`40-demo-cut` defines what every screen shows and says, and leaves visual design (colours, fonts, icons, spacing) open except where a rule names it (`40-demo-cut` Sections 1 and 13). This document defines that visual design for every screen of `vigil-web`: the design tokens (Section 4), the typography (Section 5), the layout (Section 6), the components (Section 7), how the screens built by P-28 apply them (Section 8), and what the later screen packages use (Section 9). Its rules (Section 3) keep the design from changing any content: the design changes how a screen looks, never what it says or does.

The look was approved by the owner from the mockup of 13 Nov 2026 (the incident list during the storm, sign in, the starting state and the screen states, and a component sheet). The mockup is a picture of this document, not a source: where they differ, this document wins.

## 2. Inputs read

- `docs/00-context.md` v1.43.
- `docs/40-demo-cut.md`: Sections 1 and 13 (visual design left open), Section 3 terms (main window 1280 px × 720 px, mobile page 360 px), DC-U-01 to DC-U-16, DC-S01, DC-S02, DC-S03-07, DC-S04-02, DC-S07-02, DC-ASM-03.
- `docs/50-architecture.md`: AR-T-08 (CSS Modules), AR-W-01, AR-W-07.
- `docs/60-build-plan.md` v1.31: P-28 to P-39 entries, BP-R-05, BP-R-08, BP-DC-03.
- The P-28 code as merged (`5932cde`) (`web/src`: the components, their CSS Modules and the texts they render) and its report.
- The owner's request of 13 Nov 2026 (decide how the product looks and design its UI system, beautiful and professional) and his approval of the mockup.

## 3. Rules (complete list)

| ID | Rule | Trace |
| --- | --- | --- |
| VD-R-01 | **Content is not changed.** Applying this document MUST NOT add, remove, reorder or reword any text, control or item that `40-demo-cut` defines, and MUST NOT change a text's DOM text content. Markup MAY change only by wrapping existing text or elements in elements (Example: `<span>` around the number of DC-S02-02 item (1); a card `<div>` around a table), by adding class names and `data-` attributes for styling, and by adding the decorative elements of VD-R-02. | `40-demo-cut` DC-U-*, DC-S*; BP-DC-03 |
| VD-R-02 | **Decorative elements** are allowed only where this document names them (complete list): the product mark (VD-C-02), the user glyph (VD-C-02), the status dots (VD-C-05, VD-C-07), the external-link glyph (VD-C-08), the empty-state ring (VD-C-12), the spinner (VD-C-13), the banner stripes (VD-C-01) and the login background (VD-S-01). Each carries no text and is drawn with CSS (a pseudo-element with `content: ""`, or an empty element with `aria-hidden="true"`) or with an inline SVG that has `aria-hidden="true"`. | DC-U-03; BP-DC-03 |
| VD-R-03 | **No text transforms.** `text-transform` is not used, and no text is cut visually (no `text-overflow: ellipsis`, no `line-clamp`): a text that does not fit wraps. | VD-R-01; DC-U-15 |
| VD-R-04 | **One source of tokens.** Every colour, font, size, space, radius, shadow and duration that a CSS Module uses is one of the custom properties of Section 4, declared once in `web/src/app/tokens.css`. A literal value is allowed only (complete list): `0`; border, outline and stroke widths from 1px to 3px; the keywords `auto`, `none`, `transparent`, `currentColor`, `inherit`, `100%`, `50%`; the media queries `(max-width: 599px)` ("below 600px") and `(max-width: 899px)` ("below 900px"); offsets computed from stated values to place an element as stated (Example: the ring's position inside the mark); and the values Sections 4.6, 5, 6, 7 and 8 state (sizes, weights, line heights, letter spacing, gradients, timings, and the colours `#ffffff`, `#5aa2ff`, `#f5f9ff`, `#24406f`, `#1b3d73`, `rgba(0, 0, 0, 0.06)` and `rgba(11, 21, 38, 0.5)`). | AR-T-08 |
| VD-R-05 | **Contrast.** Every text meets WCAG 2.2 contrast 4.5:1 against its background, and every status dot, focus ring and border that carries meaning 3:1. The pairs of Section 4 are chosen so; a new pair is checked before it is used. | CTX-W-08 |
| VD-R-06 | **Colour is never the only signal.** Every state shown by colour also has its text (Example: the severity badge shows "P1"; a source not healthy shows "Stale"). | DC-S02-02, DC-S02-04 |
| VD-R-07 | **The banner colour is used nowhere else** (`--vd-banner`, DC-U-01). No other element, state or tag uses a yellow. | DC-U-01 |
| VD-R-08 | **Named colours of `40-demo-cut`.** "The warning colour" (DC-S02-02, DC-S07-02) is `--vd-warning` on text; "the accent colour" and "accent outline" (DC-S03-07) are `--vd-accent-500`; "grey" (DC-S03-07) is `--vd-gray-400` for lines and `--vd-text-3` for text. | DC-S02-02, DC-S03-07, DC-S07-02 |
| VD-R-09 | **Fonts are bundled.** Inter and JetBrains Mono are the npm packages `@fontsource-variable/inter` and `@fontsource-variable/jetbrains-mono` (OFL-1.1), imported once by `web/src/main.tsx`; Vite places their files in the application's build, served by `vigil-app` from the same origin. No page loads anything from another host (AR-T-08, AR-W-01). | AR-W-01; BP-R-08 |
| VD-R-10 | **Sizes.** Every desktop screen is usable at 1280 px × 720 px without horizontal scrolling (`40-demo-cut` main window); every screen MUST also be usable at 360 px wide where `40-demo-cut` asks for it (DC-S01-02 below 600 px; DC-S04, DC-S05). | `40-demo-cut` Section 3; DC-S01-02, DC-S04-02 |
| VD-R-11 | **Motion.** Only the transitions and animations of Section 4.6 are used; the `@keyframes` of an animation are declared in the CSS Module that uses it; under `prefers-reduced-motion: reduce` every animation and transition is off (a pulse or spin stays still). | CTX-W-08 |
| VD-R-12 | **Focus.** Every focusable element shows `--vd-focus-ring` on `:focus-visible`, over any shadow of its own (one global rule with `!important`); no focus outline is removed without it. | DC-U-05 |
| VD-R-13 | **Changes.** A screen package that needs a component or token this document does not define does not invent one: it raises it in its spec, and Claude adds it to this document through the decision log before the package starts (CTX-Q-8). | CTX-W-04 |

## 4. Tokens

All tokens are CSS custom properties on `:root`, in `web/src/app/tokens.css` (VD-R-04), named exactly as below.

### 4.1 Neutral and surface colours (complete list)

| Token | Value | Use |
| --- | --- | --- |
| `--vd-gray-0` | `#ffffff` | Cards, inputs, the table. |
| `--vd-gray-25` | `#f8fafc` | Table header row. |
| `--vd-gray-50` | `#f1f4f8` | Page background (canvas). |
| `--vd-gray-100` | `#e6eaf0` | Inner dividers; neutral badge, chip and source-kind backgrounds. |
| `--vd-gray-200` | `#d5dbe4` | Card and control borders. |
| `--vd-gray-300` | `#b8c1cd` | Borders of disabled controls (later screens). |
| `--vd-gray-400` | `#8b97a8` | Grey lines and dashed borders ("grey", VD-R-08). |
| `--vd-gray-500` | `#647083` | Third text; input borders (3:1 against the card, VD-R-05). |
| `--vd-gray-600` | `#4a5567` | Second text. |
| `--vd-gray-700` | `#353f4f` | Field labels; neutral badge text. |
| `--vd-gray-900` | `#141a23` | Main text. |
| `--vd-text` | `var(--vd-gray-900)` | Main text. |
| `--vd-text-2` | `var(--vd-gray-600)` | Labels, secondary values, times. |
| `--vd-text-3` | `var(--vd-gray-500)` | Least important text (loading text, empty feed line). |
| `--vd-canvas` | `var(--vd-gray-50)` | Page background. |
| `--vd-surface` | `var(--vd-gray-0)` | Card background. |
| `--vd-surface-2` | `var(--vd-gray-25)` | Table header background. |
| `--vd-border` | `var(--vd-gray-200)` | Card and control borders. |
| `--vd-border-2` | `var(--vd-gray-100)` | Dividers inside a card. |

### 4.2 Brand colours (complete list)

| Token | Value | Use |
| --- | --- | --- |
| `--vd-navy-900` | `#0b1526` | Header background; login page background. |
| `--vd-navy-800` | `#12213a` | Hover of a control on the header. |
| `--vd-navy-700` | `#1c2f50` | Customer chip background on the header. |
| `--vd-navy-600` | `#2a4068` | Borders on the header; user glyph background. |
| `--vd-on-navy` | `#ffffff` | `{product}` and the current navigation item on the header. |
| `--vd-on-navy-2` | `#cfdbf0` | Customer name, display name and "Sign out" on the header. |
| `--vd-on-navy-3` | `#aebbd1` | Navigation items that are not current. |
| `--vd-on-navy-4` | `#8fa0bb` | The time zone note. |
| `--vd-accent-50` | `#eaf2ff` | Row hover; info notice and "Open" chip background; grouping tile. |
| `--vd-accent-100` | `#d3e3ff` | P3 badge background; info notice border. |
| `--vd-accent-200` | `#b3cdfb` | P3 badge border. |
| `--vd-accent-500` | `#2f6fed` | The accent colour: primary button, current navigation underline, focus ring, mark, live dot, canvas highlight (VD-R-08). |
| `--vd-accent-600` | `#1f5ad6` | Links; primary button hover. |
| `--vd-accent-700` | `#1846ab` | Accent text on light accent backgrounds; incident numbers. |

### 4.3 Status colours (complete list)

| Token | Value | Use |
| --- | --- | --- |
| `--vd-red-50` | `#fdecec` | Error notice background; "Not recovered" tag background. |
| `--vd-red-200` | `#f6c7c3` | Error notice border. |
| `--vd-red-600` | `#d92d20` | P1 badge background; destructive button. |
| `--vd-red-700` | `#b42318` | Error text. |
| `--vd-orange-50` | `#fff1e6` | Warning notice and live-interrupted bar background. |
| `--vd-orange-100` | `#ffe2c7` | P2 badge background. |
| `--vd-orange-200` | `#f6b98a` | P2 badge border; warning notice border. |
| `--vd-orange-600` | `#e8590c` | Warning dot (not text). |
| `--vd-orange-700` | `#b54708` | Warning text. |
| `--vd-orange-800` | `#9a3d06` | P2 badge text. |
| `--vd-warning` | `var(--vd-orange-700)` | "The warning colour" (VD-R-08). |
| `--vd-green-50` | `#e8f7ee` | "Resolved" chip, "Verified outcome" tag and empty-state ring background. |
| `--vd-green-600` | `#13955a` | Healthy dot; empty-state ring (not text). |
| `--vd-green-700` | `#0e7448` | Success text. |
| `--vd-banner` | `#ffd23f` | The environment banner only (VD-R-07). |
| `--vd-banner-text` | `#1a1400` | Banner text. |

### 4.4 Kind tag colours (complete list; DC-U-10)

| Token pair (background, text) | Values | Word |
| --- | --- | --- |
| `--vd-tag-hypothesis-bg`, `--vd-tag-hypothesis` | `#f1ecfd`, `#6e44d4` | Hypothesis |
| `--vd-tag-observation-bg`, `--vd-tag-observation` | `#eaf0f7`, `#2d4e78` | Observation |
| `--vd-tag-ai-bg`, `--vd-tag-ai` | `#ecebff`, `#4338ca` | AI analysis |
| `--vd-tag-history-bg`, `--vd-tag-history` | `#e5f6f6`, `#0b6e70` | History match |
| `--vd-tag-proposed-bg`, `--vd-tag-proposed` | `#fff3e0`, `#9a4a00` | Proposed action |
| `--vd-tag-approval-bg`, `--vd-tag-approval` | `var(--vd-accent-50)`, `var(--vd-accent-700)` | Approval |
| `--vd-tag-execution-bg`, `--vd-tag-execution` | `#e6f6fb`, `#0b7896` | Execution result |
| `--vd-tag-verified-bg`, `--vd-tag-verified` | `var(--vd-green-50)`, `var(--vd-green-700)` | Verified outcome |
| `--vd-tag-not-recovered-bg`, `--vd-tag-not-recovered` | `var(--vd-red-50)`, `var(--vd-red-700)` | Not recovered |
| `--vd-tag-test-bg`, `--vd-tag-test` | `#ffffff`, `#c026a3` | Test (with a 1.5px dashed border in the text colour) |
| `--vd-tag-baseline-bg`, `--vd-tag-baseline` | `#ffffff`, `var(--vd-gray-600)` | Illustrative baseline (with a 1.5px dashed `--vd-gray-400` border) |
| `--vd-tag-record-bg`, `--vd-tag-record` | `var(--vd-gray-100)`, `var(--vd-gray-600)` | A record label (Example: "Fictional record") |

### 4.5 Type, space, radius and shadow (complete list)

| Token | Value |
| --- | --- |
| `--vd-font` | `"Inter Variable", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif` |
| `--vd-mono` | `"JetBrains Mono Variable", ui-monospace, "SF Mono", Menlo, Consolas, monospace` |
| `--vd-size-12`, `--vd-size-13`, `--vd-size-14`, `--vd-size-15`, `--vd-size-16`, `--vd-size-20`, `--vd-size-24`, `--vd-size-30` | `12px`, `13px`, `14px`, `15px`, `16px`, `20px`, `24px`, `30px` |
| `--vd-space-1` to `--vd-space-10` | `--vd-space-1` 4px, `-2` 8px, `-3` 12px, `-4` 16px, `-5` 20px, `-6` 24px, `-8` 32px, `-10` 40px (no `-7`, `-9`) |
| `--vd-radius-sm` | `6px` (controls, badges, tags, notices) |
| `--vd-radius` | `10px` (cards) |
| `--vd-radius-lg` | `14px` (the login card) |
| `--vd-radius-pill` | `999px` (chips, the user glyph) |
| `--vd-shadow-1` | `0 1px 2px rgba(16, 24, 40, 0.06), 0 1px 3px rgba(16, 24, 40, 0.08)` |
| `--vd-shadow-2` | `0 8px 24px rgba(16, 24, 40, 0.1), 0 2px 6px rgba(16, 24, 40, 0.06)` |
| `--vd-focus-ring` | `0 0 0 2px var(--vd-gray-0), 0 0 0 4px var(--vd-accent-500)` (a white ring inside an accent ring: 4.55:1 on white, 4.02:1 on the navy header) |

### 4.6 Motion (complete list)

| Token | Value | Use |
| --- | --- | --- |
| `--vd-fast` | `120ms ease-out` | Background and border colour changes on hover. |
| Pulse | `@keyframes` in the module that uses it; 1.6s, infinite: a `box-shadow` ring of `--vd-accent-500` growing from 0 to 9px and fading to transparent | The live dot of the grouping tile (VD-C-05). |
| Spin | `@keyframes` in the module that uses it; 0.8s linear, infinite, a full turn | The spinner (VD-C-13). |

## 5. Typography (complete list of text roles)

`font-family: var(--vd-font)` on `body`, with `-webkit-font-smoothing: antialiased`. Numbers that line up in columns or change live use `font-variant-numeric: tabular-nums`.

| Role | Font | Size / line height | Weight | Colour | Used for |
| --- | --- | --- | --- | --- | --- |
| Body | `--vd-font` | 15px / 1.45 | 400 | `--vd-text` | Default text. |
| Page heading | `--vd-font` | 24px / 32px, letter-spacing -0.015em | 650 | `--vd-text` | "Incidents", "No access", later screen headings (DC-U-15). |
| Login heading | `--vd-font` | 20px / 28px, letter-spacing -0.01em | 650 | `--vd-text` | "Sign in". |
| Product on header | `--vd-font` | 16px | 700, letter-spacing -0.01em | `--vd-on-navy` | `{product}` (DC-U-02). |
| Product on login | `--vd-font` | 20px | 700 | `--vd-navy-900` | `{product}` on DC-S01. |
| Stat value | `--vd-font` | 30px / 36px, letter-spacing -0.02em, tabular | 650 | `--vd-text`; grouping tile `--vd-accent-700` | The values of DC-S02-02 items (1) and (2). |
| Label | `--vd-font` | 13px | 500 | `--vd-text-2` | Tile labels; field labels use 600 and `--vd-gray-700`. |
| Table header | `--vd-font` | 13px | 600 | `--vd-text-2` | Column names, as written (VD-R-03). |
| Table cell | `--vd-font` | 14px | 400; title 600 | `--vd-text` | Cells. |
| Mono | `--vd-mono` | 13px (12px in the feed) | 400; numbers and keys 500 | `--vd-text-2`; numbers `--vd-accent-700`; ticket keys `--vd-accent-600` | Times, incident numbers, ticket keys. |
| Small | `--vd-font` | 13px | 400 | role colour | Action-failed lines, the zone note, header controls, the interrupted bar. |
| Notice | `--vd-font` | 14px | 400 | Section 7 | Notices (VD-C-11), the loading text, the empty feed line. |
| Badge | `--vd-font` | 12px, tabular | 700 | Section 7 | Severity badges. |
| Chip and tag | `--vd-font` | 12px (11px for the source kind) | 600 | Section 7 | State chips, stage, source kind, kind tags. |

## 6. Layout (complete list)

| ID | Rule |
| --- | --- |
| VD-L-01 | Desktop page: the banner (sticky, VD-C-01), the header (56px high, scrolls with the page), then, where the screen has it, the live-interrupted bar (VD-C-14, full width), then the content: `width: 100%`, `box-sizing: border-box`, `max-width: 1440px`, centred with `margin: 0 auto`, padding 24px (16px below 600px wide), background `--vd-canvas` over the whole window height. |
| VD-L-02 | Content order is the order of `40-demo-cut`; parts of a screen are cards (VD-C-04) 16px apart. The page heading has 16px below it. Where a complete list says "left to right" (DC-S02-02), the items keep that reading order; an item MAY start a new row (DC-S02-02 item (4), the latest alerts, is a full-width row under items (1) to (3), as P-28 drew it). |
| VD-L-03 | Widths: at 1280px the content is 1232px wide (VD-R-10); the incident table fits it with the Title, Owner and Acknowledged cells wrapping and every other cell on one line. |
| VD-L-04 | Below 600px (DC-S01-02): one column; the login page's padding is 24px 14px, so the login card fills the width less 14px on each side. The mobile pages of P-30 (DC-S04-02) use body text 16px and controls at least 44px high. |

## 7. Components (complete list)

| ID | Component | Specification |
| --- | --- | --- |
| VD-C-01 | Environment banner (DC-U-01) | Sticky at `top: 0`, `z-index: 20`, full width, `min-height: 30px` (a long label wraps), `box-sizing: border-box`, padding 6px 16px, text centred, 13px weight 600, `--vd-banner-text` on `--vd-banner` with stripes `repeating-linear-gradient(-45deg, rgba(0, 0, 0, 0.06) 0 10px, transparent 10px 20px)`; 12px below 600px wide. |
| VD-C-02 | Header (DC-U-03, DC-U-04) | `--vd-navy-900`, height 56px, padding 0 24px, items in DC-U-03's order with 24px gaps, the zone note, display name and "Sign out" pushed right (the first of them present gets `margin-left: auto`). **Product**: the mark, then `{product}` 10px after it. The mark is drawn by the product element's own `::before` and `::after` (no element of its own, so the product element's box starts at the mark): `::before` a 26px square, radius 7px, background `linear-gradient(135deg, var(--vd-accent-500), #5aa2ff)`; `::after` a 12px white open ring centred on it (border 2.5px solid `#ffffff` with the right border transparent, radius 50%, rotated -30deg). **Customer**: the logo at 24px high, or the name as a chip (12px, 600, `--vd-on-navy-2` on `--vd-navy-700`, border `--vd-navy-600`, pill, padding 3px 9px). **Navigation**: 8px after the customer; items full header height, padding 0 12px, 14px weight 500, `--vd-on-navy-3`; the current item `--vd-on-navy` with a 2px `--vd-accent-500` bottom border; hover `--vd-on-navy`, never underlined. **Zone note**: 13px `--vd-on-navy-4`. **Display name**: 13px weight 500 `--vd-on-navy-2`, after the user glyph (28px circle, `--vd-navy-600`, 8px before the name, a 14px person outline in `--vd-on-navy-2`). **Sign out**: the dark ghost button (VD-C-09), with its action-failed line under it (VD-C-15). The no-access header (DC-S01-05) is the same bar with its four items. |
| VD-C-03 | Page heading | The page heading role (Section 5), margin 0 0 16px. |
| VD-C-04 | Card | `--vd-surface`, 1px `--vd-border`, `--vd-radius`, `--vd-shadow-1`. Nothing inside is cut: the strip card clips only its tiles' backgrounds to the radius (`overflow: hidden` on the strip, whose content wraps), and the table card scrolls sideways (`overflow-x: auto`) only in a window narrower than VD-R-10 asks for. |
| VD-C-05 | Alerts strip (DC-S02-02) | One card. The card is `display: flex; flex-wrap: wrap`. Items (1) to (3) are tiles (`flex: 1 1 240px`; item (2)'s tile `flex-grow: 1.25`), each padding 16px 20px, a tile that follows a tile with a 1px `--vd-border-2` left border. In each tile the text before the value (Example: "Alerts received in the last 10 minutes: ") is the label role on its own line, and the value (Example: "127"; "126 alerts are being grouped") the stat value role. Item (2)'s tile has the background `linear-gradient(90deg, var(--vd-accent-50), #f5f9ff)`, its value in `--vd-accent-700`, and a pulsing 8px `--vd-accent-500` dot before its label. Item (3): label "Monitoring: ", then a 9px dot (`--vd-green-600`) and "{h} of {s} sources healthy" at 15px weight 600 `--vd-text`; when h < s the whole item is in the warning colour (DC-S02-02): label, value and each "{source name}: {health}" (on its own line, 13px) in `--vd-warning`, and the dot `--vd-orange-600`. Item (4) takes a row of its own below the tiles (`flex-basis: 100%`), padding 10px 20px 12px, divided from them by 1px `--vd-border-2`: the 10 lines flow newest first down the first column and on into the second (`column-count: 2`, gap 28px; one column below 900px), each line a grid of time (72px, mono 12px `--vd-text-2`), source kind (source-kind chip VD-C-07), alert type (13px weight 600) and title (13px `--vd-text-2`), wrapping when long, with a 1px dashed `--vd-border-2` line under it. "No alerts in the last 10 minutes." is 14px `--vd-text-3` in that place. |
| VD-C-06 | Table (DC-S02-04) | Inside a card, `border-collapse: separate`, width 100%. Header row `--vd-surface-2`, cells padding 10px 14px, table header role, 1px `--vd-border` under it. Body cells padding 13px 14px, 1px `--vd-border-2` between rows (none under the last), vertically centred. Row hover `--vd-accent-50` with `transition: background var(--vd-fast)`; `cursor: pointer`. Number: mono 13px weight 500 `--vd-accent-700`, no wrap. Title: weight 600. Alerts: right-aligned (header too), tabular, weight 600. Detected: mono 13px `--vd-text-2`, no wrap. Acknowledged "No": `--vd-warning` weight 600. "Show more" (DC-U-12): a secondary button (VD-C-09) centred in a footer of the card, padding 12px, divided by 1px `--vd-border-2`; its action-failed line under it. |
| VD-C-07 | Badges and chips | **Severity** (DC-S02-04): min-width 30px, height 22px, padding 0 7px, `--vd-radius-sm`, badge role. P1 `#ffffff` on `--vd-red-600`; P2 `--vd-orange-800` on `--vd-orange-100` with an inset 1px `--vd-orange-200` border; P3 `--vd-accent-700` on `--vd-accent-100` with an inset 1px `--vd-accent-200` border; P4 `--vd-gray-700` on `--vd-gray-100`; empty severity: nothing drawn. **State**: pill, height 22px, padding 0 9px, chip role, a 6px dot in the text colour before the text; Open `--vd-accent-700` on `--vd-accent-50`; Resolved `--vd-green-700` on `--vd-green-50`; Closed `--vd-gray-600` on `--vd-gray-100`. **Stage** (DC-TX-01): chip role, `--vd-text-2`, 1px `--vd-border`, `--vd-radius-sm`, padding 2px 8px; empty stage: nothing drawn. **Source kind**: chip role at 11px, `--vd-gray-700` on `--vd-gray-100`, pill, padding 1px 7px; one style for every kind. |
| VD-C-08 | Links | `--vd-accent-600`, no underline, underline on hover and focus. The ticket link (DC-S02-04): mono 13px weight 500, no wrap, followed by a 12px external-link glyph (inline SVG, stroke `currentColor`). |
| VD-C-09 | Buttons | Font 14px weight 600, padding 9px 16px, `--vd-radius-sm`, 1px border. **Primary** ("Sign in"): `#ffffff` on `--vd-accent-500`, hover `--vd-accent-600`. **Secondary** ("Show more", "Try again"): `--vd-text` on `--vd-surface`, border `--vd-border`, `--vd-shadow-1`, hover background `--vd-gray-25`. **Dark ghost** ("Sign out" on the header): 13px weight 500, padding 6px 12px, `--vd-on-navy-2`, transparent, border `--vd-navy-600`, hover `--vd-navy-800`. **Destructive** (later screens, DC-U-11 where the action ends or rejects): `#ffffff` on `--vd-red-600`. Disabled: opacity 0.55, `cursor: not-allowed`. Every button: the focus ring (VD-R-12). |
| VD-C-10 | Fields (DC-S01-02) | The label above its input, 6px apart, label 13px weight 600 `--vd-gray-700`; input 15px, padding 10px 12px, 1px `--vd-gray-500`, `--vd-radius-sm`, `--vd-surface`; focus border `--vd-accent-500` and the focus ring (VD-R-12); 16px between fields. |
| VD-C-11 | Notices | Padding 12px 14px, `--vd-radius-sm`, 14px, 1px border. **Info** (the login messages of DC-S01-04): `--vd-accent-700` on `--vd-accent-50`, border `--vd-accent-100`. **Error** (`PageError`, `PartError`, the login refusal): `--vd-red-700` on `--vd-red-50`, border `--vd-red-200`; "Try again" is a secondary button at 13px, padding 5px 12px, on the right of the message (below it under 600px). **Warning**: `--vd-orange-700` on `--vd-orange-50`, border `--vd-orange-200`. |
| VD-C-12 | Empty state (DC-U-09 empty) | Inside a card, text centred, padding 56px 24px: the ring (56px circle `--vd-green-50` holding a 20px circle with a 3px `--vd-green-600` border) 14px above the text, text 16px weight 500 `--vd-gray-700`. |
| VD-C-13 | Loading (DC-U-09 loading) | "Loading…" (notice role) in `--vd-text-3`, preceded by a 16px spinner (2px `--vd-gray-200` ring with a `--vd-accent-500` top), 10px apart, padding 16px; inside a card where the part it replaces is a card. |
| VD-C-14 | Live-interrupted bar (DC-U-09) | Full width under the header (outside the content's maximum width), padding 8px 24px, 13px weight 600, `--vd-orange-700` on `--vd-orange-50`, 1px `--vd-orange-200` under it. |
| VD-C-15 | Action failed (DC-U-09) | 13px `--vd-red-700`, 4px under its control. On the header ("Could not sign out. {reason}"), the line is an error notice (VD-C-11) with padding 6px 10px, placed under "Sign out" over the content (`position: absolute`, right-aligned with the button), so the header keeps its height. |
| VD-C-16 | Kind tag (DC-U-10) | Height 20px, padding 0 8px, `--vd-radius-sm`, chip role, no wrap, the colours of Section 4.4 for its word; a record label uses the record pair. |

## 8. Screens built by P-28 (complete list)

| ID | Screen | Application |
| --- | --- | --- |
| VD-S-01 | Login, DC-S01-01 to DC-S01-04 | Under the banner, the whole window is `--vd-navy-900` with two soft radial highlights (`radial-gradient(1200px 500px at 10% -10%, #24406f 0%, transparent 60%)`, `radial-gradient(900px 500px at 110% 110%, #1b3d73 0%, transparent 55%)`), content centred vertically and horizontally, padding 40px 20px (24px 14px below 600px, VD-L-04). One card (`--vd-surface`, `--vd-radius-lg`, `--vd-shadow-2`, `width: 100%`, `max-width: 424px`, `box-sizing: border-box`, so its content is at most 360px wide; padding 32px 32px 28px, 26px 22px below 600px): the mark and `{product}` (login role); the customer logo at 28px high or the name as a light chip (12px weight 600, `--vd-gray-600` on `--vd-gray-50`, 1px `--vd-border`, pill), 22px above the next item; the message as an info notice (VD-C-11); "Sign in" (login heading); the fields (VD-C-10); the button, primary and full width, padding 11px 16px; the refusal as an error notice and "Could not sign in. {reason}" as an action-failed line under the button; `Loading` (VD-C-13) in place of the form while it shows. |
| VD-S-02 | No access, DC-S01-05 | The desktop page (VD-L-01) with the no-access header, the page heading "No access", and the text in a card, padding 24px, 15px. |
| VD-S-03 | Not-found and error states (CTX-D-085 (1), (13); AR-B-10) | `PageError` as an error notice (VD-C-11) in the content area of the desktop page (also when the signed-in layout shows it without a header because the session read failed; its `Loading` too), or, where there is no header (the unknown host, a failed AR-I-10), in a column `width: 100%`, `box-sizing: border-box`, at most 480px wide, centred, with 40px above it and padding 0 16px. |
| VD-S-04 | Incident list, DC-S02-01 to DC-S02-05 | Page heading "Incidents"; the strip (VD-C-05); the table (VD-C-06) with its badges and chips (VD-C-07) and the ticket link (VD-C-08), or the empty state (VD-C-12); each part's `Loading` (VD-C-13) and `PartError` (VD-C-11) in place of the part; the live-interrupted bar (VD-C-14). |

## 9. Later screens (complete list of what the later packages use)

Each later screen package applies Sections 3 to 7 and adds nothing else without VD-R-13.

| Package | Screens | Uses |
| --- | --- | --- |
| P-29 | DC-S03 incident workspace and run canvas | Cards per section with a 16px weight 650 section heading; the kind tags (VD-C-16) on evidence and sections; badges and chips (VD-C-07); times in mono. Canvas nodes are cards (VD-C-04) at `--vd-radius-sm`; the step styles of DC-S03-07 by VD-R-08: not reached and cancelled with `--vd-gray-400` border and `--vd-text-3` text; running and waiting with a 2px `--vd-accent-500` outline and `--vd-focus-ring` as glow; taken connections `--vd-accent-500` 2px, others `--vd-gray-400` 1.5px; the output tag as a stage chip (VD-C-07). |
| P-30 | DC-S04, DC-S05 mobile pages | VD-L-04; the bar of DC-S04-02 as the header (VD-C-02) without navigation; buttons at least 44px high; Approve primary, Reject destructive; the confirmation dialog (DC-U-11): a card with `--vd-radius-lg` and `--vd-shadow-2` over `rgba(11, 21, 38, 0.5)`. |
| P-33 | DC-S10 dashboard | Stat tiles as VD-C-05 items (1) and (2) in cards; the baseline kind tag (VD-C-16). |
| P-37 | DC-S06 workflow editor | The P-29 node styles; palette and panels as cards; Publish primary; Roll back with DC-U-11. |
| P-38, P-39 | DC-S07, DC-S08, DC-S09, DC-S11 | Cards, the table (VD-C-06), fields (VD-C-10), notices (VD-C-11); "Saved." (DC-U-16) at 13px `--vd-green-700`; "Health" not healthy in `--vd-warning` (VD-R-08). |
| P-31 | DC-S12 demo control panel (`demo-control`, its own page) | MAY use the tokens; it is demo-only and not bound by this document. |

## 10. Explicitly out of scope for this document

Any content, wording, order or behaviour of a screen (`40-demo-cut`); the technology of the screens (`50-architecture`); a dark theme; localisation (PM-M24-L2); printing; the content of outbound messages (DC-OUT-00 to DC-OUT-07); the demo control panel's look (Section 9, P-31).

## 11. Decisions and assumptions

| ID | Decision | Reason | Status |
| --- | --- | --- | --- |
| VD-D-01 | Navy chrome, light work surfaces and one blue accent; colour is reserved for meaning (severity, state, health, kind tags, the banner). | A NOC screen is read for exceptions; a calm surface makes P1, a stale source and the live grouping stand out in a screen share. | Approved by owner, 13 Nov 2026 (CTX-D-087) |
| VD-D-02 | Inter for text and JetBrains Mono for times, numbers and keys, bundled with the application. | Legible at 1280px in a Teams share; tabular figures for live counts; no external host. | Approved by owner, 13 Nov 2026 |
| VD-D-03 | P2 is a tonal orange badge, not a filled one. | A filled orange with white text fails VD-R-05 (3.6:1), and a darker filled orange cannot be told from P1's red. | Proposed (the approved mockup showed a filled orange) |

| ID | Assumption | Verified by |
| --- | --- | --- |
| VD-ASM-01 | The two fonts' Latin files, bundled by Vite, are about 90 KB together (Inter 48 KB, JetBrains Mono 40 KB, version 5.3.0); the browser fetches a subset file only for characters in its `unicode-range`, so the screens of the demo, which use Latin text, load only the Latin files. | P-44 (the build's asset list) |

## 12. Open items

None.

## 13. Quality-check results

| Check | Result |
| --- | --- |
| CTX-Q-1 Coverage | Every DC-U item that names a visual property (DC-U-01, DC-S02-02 warning colour, DC-S03-07, DC-S07-02 warning colour) maps to a VD rule or component; every component maps to a `40-demo-cut` element; the decorative elements are listed (VD-R-02). |
| CTX-Q-2 Consistency | No `40-demo-cut` text, order or behaviour changes (VD-R-01); DC-U-01's unique banner colour holds (VD-R-07); 1280px and 360px widths (VD-R-10) checked on the mockup at 1280px. |
| VD-R-05 Contrast | Computed for every text pair of Sections 4 and 7 (WCAG relative luminance): lowest text pairs `--vd-text-3` on `--vd-canvas` 4.55:1, white on `--vd-accent-500` 4.55:1, `--vd-tag-execution` 4.57:1, white on `--vd-red-600` 4.83:1; status dots, input borders (`--vd-gray-500`, 5.0:1) and the focus ring (4.02:1 on navy, 4.55:1 on white) at least 3:1. |
| CTX-Q-7 Owner review | The owner approved the mockup on 13 Nov 2026. |

## 14. Change history

| Version | Date | Change |
| --- | --- | --- |
| 1.0 | 13 Nov 2026 | First version, from the mockup the owner approved (CTX-D-087). |
