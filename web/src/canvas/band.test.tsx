import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import badges from "../badges/badges.module.css";
import { answered, stormRun } from "../test/fixtures";
import { textOf, texts } from "../test/render";
import { BandBar, expandedTop, RunBand, type BandBarProps } from "./RunCanvas";
import styles from "./canvas.module.css";

const bar = (over: Partial<BandBarProps> = {}) =>
  renderToStaticMarkup(
    <BandBar
      run={stormRun()}
      follow
      expanded={false}
      onFollow={() => {}}
      onZoomIn={() => {}}
      onZoomOut={() => {}}
      onFit={() => {}}
      onExpand={() => {}}
      {...over}
    />,
  );

it("DC-S03-06 the band's heading, run state, Run n of n and controls", () => {
  const html = bar({ run: stormRun({ run_number: 2, run_count: 2 }) });
  expect(textOf(html)).toBe("WorkflowFinishedRun 2 of 2Follow+−FitExpand");
  expect(html).toContain(`<span class="${badges.stage}">Finished</span>`);
  expect(textOf(bar())).toBe("WorkflowFinishedFollow+−FitExpand");
  expect(html).toContain(
    '<button type="button" aria-label="Zoom in">+</button>',
  );
  expect(html).toContain(
    '<button type="button" aria-label="Zoom out">−</button>',
  );
  expect(html).toContain(
    '<button type="button" aria-pressed="true">Follow</button>',
  );
  expect(bar({ follow: false })).toContain('aria-pressed="false">Follow');
  for (const [state, name] of [
    ["running", "Running"],
    ["waiting", "Waiting"],
    ["cancelled", "Cancelled"],
    ["failed", "Failed"],
  ] as const) {
    expect(textOf(bar({ run: stormRun({ state }) }))).toContain(
      `Workflow${name}`,
    );
  }
  const band = renderToStaticMarkup(
    <RunBand part={answered(stormRun())} withCanvas={false} />,
  );
  expect(band).toContain(`class="${styles.band}"`);
  expect(band).toContain(`<div class="${styles.canvas}"></div>`);
  expect(texts(band, "h2")).toEqual(["Workflow"]);
});

it("VD-C-18 the expanded band", () => {
  expect(texts(bar({ expanded: true }), "button")).toEqual([
    "Follow",
    "+",
    "−",
    "Fit",
    "Close",
  ]);
  expect(expandedTop(86, null)).toBe(86);
  expect(expandedTop(86, 120)).toBe(120);
});
