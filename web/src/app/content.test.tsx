// VD-R-01: the styling changes no text. The text content (tags removed) of what the P-28 components render, compared
// with the exact strings of 40-demo-cut and of P-28's tests (P-44 spec Section 7).
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { expect, it } from "vitest";
import type { TenantSettings } from "../api/types";
import { NOW, row, strip } from "../incidents/fixtures";
import { Rows } from "../incidents/Rows";
import { Strip } from "../incidents/Strip";
import { Banner } from "../layout/Banner";
import { Header, type HeaderProps } from "../layout/Header";
import {
  ActionFailed,
  Empty,
  LiveInterrupted,
  Loading,
  PageError,
  PartError,
} from "../states/states";
import { KindTag, type KindWord } from "../tags/KindTag";

const ENTITIES: Record<string, string> = {
  "&amp;": "&",
  "&lt;": "<",
  "&gt;": ">",
  "&quot;": '"',
  "&#x27;": "'",
};

/** The DOM text content of rendered markup. */
const text = (html: string) =>
  html
    .replace(/<[^>]+>/g, "")
    .replace(/&(amp|lt|gt|quot|#x27);/g, (entity) => ENTITIES[entity]);
const render = (element: ReactElement) =>
  text(
    renderToStaticMarkup(
      <MemoryRouter initialEntries={["/incidents"]}>{element}</MemoryRouter>,
    ),
  );

const SETTINGS: TenantSettings = {
  product_display_name: "Techfinity",
  customer_name: "Jazz",
  has_logo: false,
  environment_label: "Demo environment — fictional data",
};

const HEADER: HeaderProps = {
  settings: SETTINGS,
  displayName: "Demo Presenter",
  full: true,
  withNavigation: true,
  onSignOut: () => {},
  signingOut: false,
  signOutFailure: null,
  now: new Date("2026-10-10T09:00:00Z"),
  zone: "Asia/Karachi",
};

it("VD-R-01 the texts of the P-28 screens are unchanged", () => {
  // The banner and the header in both forms (DC-U-01, DC-U-03, DC-S01-05).
  expect(render(<Banner label="Demo environment — fictional data" />)).toBe(
    "Demo environment — fictional data",
  );
  expect(render(<Header {...HEADER} />)).toBe(
    "TechfinityJazzIncidentsTimes in UTC+05:00Demo PresenterSign out",
  );
  expect(
    render(
      <Header
        {...HEADER}
        displayName="Hira Baig (NOC L1)"
        full={false}
        withNavigation={false}
        signOutFailure="No connection."
      />,
    ),
  ).toBe(
    "TechfinityJazzHira Baig (NOC L1)Sign outCould not sign out. No connection.",
  );

  // The strip during the storm, with a source that is not healthy, and with no alert (DC-S02-02).
  const storm = renderToStaticMarkup(
    <Strip
      strip={strip({
        received_last_10_minutes: 127,
        forming: { incidents: 1, alerts: 126 },
        sources: {
          healthy: 1,
          total: 2,
          not_healthy: [
            { source_key: "lab-zabbix", name: "Lab Zabbix", health: "stale" },
          ],
        },
        latest: [
          {
            alert_id: "a-1",
            first_received_at: "2026-10-10T09:19:58.000Z",
            source_kind: "zabbix",
            alert_type_name: "Filesystem full",
            title: "Free disk space is less than 5% on volume /data",
          },
          {
            alert_id: "a-2",
            first_received_at: "2026-10-10T09:19:57.000Z",
            source_kind: "alertmanager",
            alert_type_name: "Balance-check API errors",
            title: "BalanceCheckErrorRateHigh",
          },
        ],
      })}
      now={NOW}
      zone="UTC"
    />,
  );
  const items = [...storm.matchAll(/<(p|li)\b[^>]*>(.*?)<\/\1>/g)].map(
    (match) => text(match[2]),
  );
  expect(items).toEqual([
    "Alerts received in the last 10 minutes: 127",
    "Grouping in progress: 126 alerts are being grouped",
    "Monitoring: 1 of 2 sources healthyLab Zabbix: Stale",
    "09:19:58ZabbixFilesystem fullFree disk space is less than 5% on volume /data",
    "09:19:57AlertmanagerBalance-check API errorsBalanceCheckErrorRateHigh",
  ]);
  expect(text(storm)).toBe(items.join(""));
  expect(render(<Strip strip={strip()} now={NOW} zone="UTC" />)).toBe(
    "Alerts received in the last 10 minutes: 0" +
      "Monitoring: 2 of 2 sources healthy" +
      "No alerts in the last 10 minutes.",
  );

  // The rows (DC-S02-04).
  const rows = renderToStaticMarkup(
    <Rows
      rows={[
        row(2, {
          title: "Subscriber DB server: Disk full — 3 services affected",
          severity: "P1",
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
          ticket: { key: "NOC-2", url: "https://jira.example/browse/NOC-2" },
        }),
        row(1, { severity: "P4", state: "resolved" }),
        row(3, { state: "closed" }),
      ]}
      onOpen={() => {}}
      now={NOW}
      zone="UTC"
    />,
  );
  const cells = [...rows.matchAll(/<(th|td)\b[^>]*>(.*?)<\/\1>/g)].map(
    (match) => text(match[2]),
  );
  expect(cells).toEqual([
    ...["Number", "Title", "Severity", "State", "Stage", "Owner"],
    ...["Acknowledged", "Alerts", "Detected", "Ticket"],
    ...["INC-0002", "Subscriber DB server: Disk full — 3 services affected"],
    ...["P1", "Open", "Response", "Database Operations"],
    ...["Hira Baig (NOC L1), 09:18:30", "126", "09:12:00", "NOC-2"],
    ...["INC-0001", "Incident 1", "P4", "Resolved", "", "", "No", "1"],
    ...["09:12:00", ""],
    ...["INC-0003", "Incident 3", "", "Closed", "", "", "No", "1"],
    ...["09:12:00", ""],
  ]);

  // The states (DC-U-09).
  expect(render(<Loading />)).toBe("Loading…");
  expect(
    render(
      <Empty text="No incidents yet. Techfinity is watching 2 monitoring sources." />,
    ),
  ).toBe("No incidents yet. Techfinity is watching 2 monitoring sources.");
  expect(render(<PageError reason="Not found." onTryAgain={() => {}} />)).toBe(
    "Could not load this page. Not found.Try again",
  );
  expect(
    render(<PartError reason="No connection." onTryAgain={() => {}} />),
  ).toBe("Could not load this part. No connection.Try again");
  expect(
    render(<ActionFailed action="show more" reason="No connection." />),
  ).toBe("Could not show more. No connection.");
  expect(render(<LiveInterrupted time="09:12:03" />)).toBe(
    "Live updates interrupted. Reconnecting… Last update 09:12:03.",
  );

  // The kind tags (DC-U-10).
  const words: KindWord[] = [
    "Hypothesis",
    "Observation",
    "AI analysis",
    "History match",
    "Proposed action",
    "Approval",
    "Execution result",
    "Verified outcome",
    "Not recovered",
    "Test",
    "Illustrative baseline",
  ];
  for (const word of words) {
    expect(render(<KindTag word={word} />)).toBe(word);
  }
  expect(render(<KindTag recordLabel="Fictional record" />)).toBe(
    "Fictional record",
  );
});
