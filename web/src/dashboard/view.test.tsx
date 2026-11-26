import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import type { Dashboard } from "../api/types";
import { AFTER_STORM, edge, SEEDED } from "../test/dashboard";
import { buttons } from "../test/markup";
import { textOf } from "../test/render";
import styles from "./dashboard.module.css";
import { DashboardView, type DashboardViewProps } from "./DashboardView";

function render(overrides: Partial<DashboardViewProps> = {}): string {
  return renderToStaticMarkup(
    <DashboardView
      data={SEEDED}
      fetching={false}
      failure={null}
      onRefresh={() => {}}
      {...overrides}
    />,
  );
}

/** Each tile's blocks: the name, the value, the definition and the baseline line when shown. */
function tiles(html: string): string[][] {
  return [...html.matchAll(/<section\b[^>]*>([\s\S]*?)<\/section>/g)].map(
    (match) =>
      [...match[1].matchAll(/<(h2|p)\b[^>]*>([\s\S]*?)<\/\1>/g)].map((block) =>
        textOf(block[2]),
      ),
  );
}

const of = (data: Dashboard) => tiles(render({ data }));

it("SL-A1-09 the four metrics, each compared with a baseline labelled Illustrative baseline", () => {
  expect(of(SEEDED)).toEqual([
    [
      "Time to acknowledge",
      "5.7 min",
      "Mean time from detection to first acknowledgement",
      "11.0 min Illustrative baseline",
    ],
    [
      "Time to recover",
      "41.1 min",
      "Mean time from detection to first verified recovery",
      "72.0 min Illustrative baseline",
    ],
    [
      "Alerts suppressed",
      "525",
      "Alerts received minus incidents",
      "0 Illustrative baseline",
    ],
    [
      "Incidents merged",
      "46",
      "Incidents made from 2 or more alerts",
      "0 Illustrative baseline",
    ],
  ]);
});

it("DC-S10-01 the heading Dashboard and the button Refresh in one row", () => {
  const html = render();
  const row = html.match(
    new RegExp(`<div class="${styles.headingRow}">([\\s\\S]*?)</div>`),
  );
  expect(row).not.toBeNull();
  expect(row![1]).toBe(
    `<h1>Dashboard</h1><button type="button" class="${styles.refresh}">Refresh</button>`,
  );
  expect(html.startsWith(`<div class="${styles.headingRow}">`)).toBe(true);
});

it("DC-S10-02 the four tiles in order with name, value, definition and baseline", () => {
  expect(of(AFTER_STORM)).toEqual([
    [
      "Time to acknowledge",
      "5.7 min",
      "Mean time from detection to first acknowledgement",
      "11.0 min Illustrative baseline",
    ],
    [
      "Time to recover",
      "40.5 min",
      "Mean time from detection to first verified recovery",
      "72.0 min Illustrative baseline",
    ],
    [
      "Alerts suppressed",
      "650",
      "Alerts received minus incidents",
      "0 Illustrative baseline",
    ],
    [
      "Incidents merged",
      "47",
      "Incidents made from 2 or more alerts",
      "0 Illustrative baseline",
    ],
  ]);
  const html = render({ data: AFTER_STORM });
  for (const key of [
    "time_to_acknowledge",
    "time_to_recover",
    "alerts_suppressed",
    "incidents_merged",
  ]) {
    expect(html).toContain(
      `<section class="${styles.tile}" aria-labelledby="metric-${key}"><h2 id="metric-${key}"`,
    );
  }
  expect(of(edge.thousand).map((tile) => tile[1])).toEqual([
    "1,234.5 min",
    "41.0 min",
    "1,000",
    "1,000",
  ]);
  expect(of(edge.nullValue).map((tile) => tile[1])).toEqual([
    "no data",
    "no data",
    "0",
    "0",
  ]);
  expect(render({ data: edge.nullValue })).toContain(
    `<p class="${styles.noData}">no data</p>`,
  );
});

it("SB-V-39 every baseline is shown with its tag", () => {
  expect(of(SEEDED).map((tile) => tile[3])).toEqual([
    "11.0 min Illustrative baseline",
    "72.0 min Illustrative baseline",
    "0 Illustrative baseline",
    "0 Illustrative baseline",
  ]);
  expect(render().match(/>Illustrative baseline</g)).toHaveLength(4);
});

it("DC-U-10 the baseline tag is the Illustrative baseline kind tag", () => {
  const tags = [
    ...render().matchAll(/<span class="[^"]*" data-kind="([^"]*)">([^<]*)</g),
  ];
  expect(tags.map((tag) => [tag[1], tag[2]])).toEqual(
    Array(4).fill(["illustrative-baseline", "Illustrative baseline"]),
  );
});

it("DC-S10-02 a metric without a baseline shows no baseline line and no tag", () => {
  const shown = of(edge.nullBaseline);
  expect(shown[1]).toEqual([
    "Time to recover",
    "41.1 min",
    "Mean time from detection to first verified recovery",
  ]);
  expect(shown.map((tile) => tile.length)).toEqual([4, 3, 4, 4]);
  expect(
    render({ data: edge.nullBaseline }).match(/Illustrative baseline/g),
  ).toHaveLength(3);
});

it("DC-S10-02 a metric missing from the answer shows no data", () => {
  expect(of(edge.missing)[3]).toEqual([
    "Incidents merged",
    "no data",
    "Incidents made from 2 or more alerts",
  ]);
});

it("DC-S10-03 the data line under the tiles", () => {
  const html = render();
  const line = `<p class="${styles.dataLine}">Includes 0 live incidents and 50 imported past incidents labelled &#x27;Fictional record&#x27;.</p>`;
  expect(html.endsWith(`</section></div>${line}`)).toBe(true);
  expect(textOf(render({ data: AFTER_STORM }))).toContain(
    "Includes 2 live incidents and 50 imported past incidents labelled 'Fictional record'.",
  );
  expect(textOf(render({ data: edge.noLabel }))).toContain("labelled 'none'.");
  expect(textOf(render({ data: edge.twoLabels }))).toContain(
    "labelled 'A', 'B'.",
  );
  expect(textOf(render({ data: edge.one }))).toContain(
    "Includes 1 live incident and 1 imported past incident labelled 'Fictional record'.",
  );
});

it("SL-DR-03 the record label is in the data line", () => {
  const line = render().match(
    new RegExp(`<p class="${styles.dataLine}">([^<]*)</p>`),
  );
  expect(textOf(line![1])).toContain("labelled 'Fictional record'.");
});

it("DC-U-09 Loading in place of the tiles and the data line while a read runs, Refresh disabled", () => {
  for (const data of [undefined, SEEDED]) {
    const html = render({ data, fetching: true });
    expect(html).toContain("Loading…");
    expect(html).toContain(`<div class="${styles.loadingCard}"><p`);
    expect(html).not.toContain("<section");
    expect(html).not.toContain(styles.dataLine);
    expect(html).toContain(
      `<button type="button" class="${styles.refresh}" disabled="">Refresh</button>`,
    );
    expect(html).toContain("<h1>Dashboard</h1>");
  }
  // A failure is not shown while "Try again" reads again.
  expect(
    render({ data: undefined, fetching: true, failure: "No connection." }),
  ).not.toContain("Could not load this page.");
});

it("DC-U-09 Could not load this page. {reason} with Try again in place of the content", () => {
  for (const data of [undefined, SEEDED]) {
    const html = render({ data, failure: "No connection." });
    expect(textOf(html)).toBe(
      "Could not load this page. No connection.Try again",
    );
    expect(buttons(html)).toEqual(["Try again"]);
    expect(html).not.toContain("<h1>");
  }
  let pressed = 0;
  const element = DashboardView({
    data: undefined,
    fetching: false,
    failure: "No connection.",
    onRefresh: () => pressed++,
  }) as { props: { onTryAgain: () => void } };
  element.props.onTryAgain();
  expect(pressed).toBe(1);
});

it("DC-U-05 the dashboard has no control besides Refresh", () => {
  const html = render({ data: AFTER_STORM });
  expect(buttons(html)).toEqual(["Refresh"]);
  for (const tag of ["<a ", "<input", "<select", "<textarea", "<form"]) {
    expect(html).not.toContain(tag);
  }
});

it("VD-C-16 the tag follows the baseline value", () => {
  const html = render();
  expect(html).toContain(
    `<p class="${styles.baseline}"><span class="${styles.baselineValue}">11.0 min</span> <span class=`,
  );
});
