import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { NOW, row } from "./fixtures";
import { COLUMNS, Rows } from "./Rows";

const cells = (html: string) =>
  [...html.matchAll(/<td>(.*?)<\/td>/g)].map((match) =>
    match[1].replace(/<[^>]+>/g, ""),
  );

const storm = row(1, {
  title: "Subscriber DB server: Disk full — 3 services affected",
  severity: "P1",
  state: "open",
  stage: "response",
  owner: { team_key: "db-ops", name: "Database Operations" },
  acknowledged: {
    user: {
      user_id: "00000000-0000-4000-8000-0000000000aa",
      display_name: "Hira Baig (NOC L1)",
    },
    at: "2026-10-10T09:18:30.000Z",
  },
  alert_count: 126,
  first_alert_fired_at: "2026-10-09T23:59:00.000Z",
  ticket: { key: "NOC-1", url: "https://jira.example/browse/NOC-1" },
});

it("DC-S02-04 the columns in order and every cell from an IncidentRow", () => {
  const html = renderToStaticMarkup(
    <Rows rows={[storm]} onOpen={() => {}} now={NOW} zone="UTC" />,
  );
  expect(
    [...html.matchAll(/<th scope="col">(.*?)<\/th>/g)].map((match) => match[1]),
  ).toEqual([...COLUMNS]);
  expect(cells(html)).toEqual([
    "INC-0001",
    "Subscriber DB server: Disk full — 3 services affected",
    "P1",
    "Open",
    "Response",
    "Database Operations",
    "Hira Baig (NOC L1), 09:18:30",
    "126",
    "9 Oct 2026, 23:59",
    "NOC-1",
  ]);
});

it("DC-S02-04 empty cells for null values, No when not acknowledged", () => {
  const html = renderToStaticMarkup(
    <Rows
      rows={[row(12345, { state: "resolved" })]}
      onOpen={() => {}}
      now={NOW}
      zone="UTC"
    />,
  );
  expect(cells(html)).toEqual([
    "INC-12345",
    "Incident 12345",
    "",
    "Resolved",
    "",
    "",
    "No",
    "1",
    "09:12:00",
    "",
  ]);
});

it("DC-S02-04 the ticket link opens in a new tab with noopener noreferrer", () => {
  const html = renderToStaticMarkup(
    <Rows rows={[storm]} onOpen={() => {}} now={NOW} zone="UTC" />,
  );
  expect(html).toContain(
    '<a href="https://jira.example/browse/NOC-1" target="_blank" rel="noopener noreferrer">NOC-1</a>',
  );
});

it("DC-U-06 the owner and the acknowledging user are shown by name", () => {
  const html = renderToStaticMarkup(
    <Rows rows={[storm]} onOpen={() => {}} now={NOW} zone="UTC" />,
  );
  expect(html).toContain("Database Operations");
  expect(html).toContain("Hira Baig (NOC L1)");
  expect(html).not.toContain("db-ops");
  expect(html).not.toContain("0000000000aa");
});

it("DC-S02-03 the rows are exactly the answer's, in its order", () => {
  const rows = [row(7), row(5), row(2)];
  const html = renderToStaticMarkup(
    <Rows rows={rows} onOpen={() => {}} now={NOW} zone="UTC" />,
  );
  expect(cells(html).filter((cell) => cell.startsWith("INC-"))).toEqual([
    "INC-0007",
    "INC-0005",
    "INC-0002",
  ]);
  expect(html.match(/<tr class=/g)).toHaveLength(3);
});
