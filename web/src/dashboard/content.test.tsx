// VD-R-01: the dashboard's texts are those of 40-demo-cut (DC-S10, DC-TX-01, DC-U-10) and CTX-D-097: the whole text
// content of what the view renders, compared with the exact strings.
import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { edge, SEEDED } from "../test/dashboard";
import { textOf } from "../test/render";
import { DashboardView } from "./DashboardView";

it("VD-R-01 the dashboard's fixed texts are those of 40-demo-cut", () => {
  const seeded = renderToStaticMarkup(
    <DashboardView
      data={SEEDED}
      fetching={false}
      failure={null}
      onRefresh={() => {}}
    />,
  );
  expect(textOf(seeded)).toBe(
    "Dashboard" +
      "Refresh" +
      "Time to acknowledge" +
      "5.7 min" +
      "Mean time from detection to first acknowledgement" +
      "11.0 min Illustrative baseline" +
      "Time to recover" +
      "41.1 min" +
      "Mean time from detection to first verified recovery" +
      "72.0 min Illustrative baseline" +
      "Alerts suppressed" +
      "525" +
      "Alerts received minus incidents" +
      "0 Illustrative baseline" +
      "Incidents merged" +
      "46" +
      "Incidents made from 2 or more alerts" +
      "0 Illustrative baseline" +
      "Includes 0 live incidents and 50 imported past incidents labelled 'Fictional record'.",
  );
  const empty = renderToStaticMarkup(
    <DashboardView
      data={edge.nullValue}
      fetching={false}
      failure={null}
      onRefresh={() => {}}
    />,
  );
  expect(textOf(empty)).toBe(
    "Dashboard" +
      "Refresh" +
      "Time to acknowledge" +
      "no data" +
      "Mean time from detection to first acknowledgement" +
      "11.0 min Illustrative baseline" +
      "Time to recover" +
      "no data" +
      "Mean time from detection to first verified recovery" +
      "72.0 min Illustrative baseline" +
      "Alerts suppressed" +
      "0" +
      "Alerts received minus incidents" +
      "0 Illustrative baseline" +
      "Incidents merged" +
      "0" +
      "Incidents made from 2 or more alerts" +
      "0 Illustrative baseline" +
      "Includes 0 live incidents and 0 imported past incidents labelled 'none'.",
  );
  const loading = renderToStaticMarkup(
    <DashboardView
      data={undefined}
      fetching
      failure={null}
      onRefresh={() => {}}
    />,
  );
  expect(textOf(loading)).toBe("DashboardRefreshLoading…");
});
