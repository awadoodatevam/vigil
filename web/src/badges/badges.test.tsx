import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { SeverityBadge, StageChip, StateChip } from "./Badges";
import styles from "./badges.module.css";

it("VD-C-07 the shared badges render P-44's markup", () => {
  for (const severity of ["P1", "P2", "P3", "P4"]) {
    expect(renderToStaticMarkup(<SeverityBadge severity={severity} />)).toBe(
      `<span class="${styles.severity}" data-severity="${severity}">${severity}</span>`,
    );
  }
  expect(renderToStaticMarkup(<SeverityBadge severity={null} />)).toBe("");
  for (const [state, name] of [
    ["open", "Open"],
    ["resolved", "Resolved"],
    ["closed", "Closed"],
  ]) {
    expect(renderToStaticMarkup(<StateChip state={state} />)).toBe(
      `<span class="${styles.state}" data-state="${state}">${name}</span>`,
    );
  }
  for (const [stage, name] of [
    ["investigation", "Investigation"],
    ["response", "Response"],
    ["recovery", "Recovery"],
  ]) {
    expect(renderToStaticMarkup(<StageChip stage={stage} />)).toBe(
      `<span class="${styles.stage}">${name}</span>`,
    );
  }
  expect(renderToStaticMarkup(<StageChip stage={null} />)).toBe("");
  expect(renderToStaticMarkup(<StageChip text="not recovered" />)).toBe(
    `<span class="${styles.stage}">not recovered</span>`,
  );
});
