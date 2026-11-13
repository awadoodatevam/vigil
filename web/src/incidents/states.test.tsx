import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { NOW, row, strip } from "./fixtures";
import {
  IncidentsView,
  type IncidentsViewProps,
  type RowsView,
  type StripView,
} from "./IncidentsView";

const noop = () => {};

function render(stripView: StripView, rowsView: Partial<RowsView>) {
  const props: IncidentsViewProps = {
    product: "Techfinity",
    strip: stripView,
    rows: {
      rows: [],
      hasMore: false,
      failure: null,
      moreFailure: null,
      loadingMore: false,
      ...rowsView,
    },
    onTryAgainStrip: noop,
    onTryAgainRows: noop,
    onShowMore: noop,
    onOpen: noop,
    now: NOW,
    zone: "UTC",
  };
  return renderToStaticMarkup(<IncidentsView {...props} />);
}

it("DC-U-09 PageError only when both the strip and the rows fail", () => {
  const html = render(
    { data: undefined, failure: "No connection." },
    { rows: undefined, failure: "No connection." },
  );
  expect(html).toContain("Could not load this page. No connection.");
  expect(html).not.toContain("Could not load this part.");
  expect(html).not.toContain("<h1>Incidents</h1>");
});

it("DC-U-09 PartError in the strip when only the strip fails; the rows are shown", () => {
  const html = render(
    { data: undefined, failure: "Not found." },
    { rows: [row(2)] },
  );
  expect(html).toContain("<h1>Incidents</h1>");
  expect(html).toContain("Could not load this part. Not found.");
  expect(html).toContain("INC-0002");
});

it("DC-U-09 PartError in the table when only the rows fail; the strip is shown", () => {
  const html = render(
    { data: strip(), failure: null },
    { rows: undefined, failure: "No connection." },
  );
  expect(html.replace(/<[^>]+>/g, "")).toContain(
    "Alerts received in the last 10 minutes: 0",
  );
  expect(html).toContain("Could not load this part. No connection.");
  expect(html).not.toContain("Could not load this page.");
});

it("DC-U-09 loading in each part; a failed Show more under its button keeps the rows", () => {
  const loading = render(
    { data: undefined, failure: null },
    { rows: undefined },
  );
  expect(loading.match(/Loading…/g)).toHaveLength(2);
  const more = render(
    { data: strip(), failure: null },
    { rows: [row(3)], hasMore: true, moreFailure: "No connection." },
  );
  expect(more).toContain("INC-0003");
  expect(more).toContain("Show more");
  expect(more).toContain("Could not show more. No connection.");
});

it("DC-S02-05 the empty text with the strip's total", () => {
  const html = render({ data: strip(), failure: null }, { rows: [] });
  expect(html).toContain(
    "No incidents yet. Techfinity is watching 2 monitoring sources.",
  );
  const one = render(
    {
      data: strip({ sources: { healthy: 1, total: 1, not_healthy: [] } }),
      failure: null,
    },
    { rows: [] },
  );
  expect(one).toContain(
    "No incidents yet. Techfinity is watching 1 monitoring source.",
  );
});

it("DC-S02-05 the table shows Loading until the strip has answered, also while the strip shows PartError", () => {
  expect(
    render({ data: undefined, failure: null }, { rows: [] }),
  ).not.toContain("No incidents yet.");
  const failed = render(
    { data: strip(), failure: "No connection." },
    { rows: [] },
  );
  expect(failed).toContain("Could not load this part. No connection.");
  expect(failed).toContain("Loading…");
  expect(failed).not.toContain("No incidents yet.");
});
