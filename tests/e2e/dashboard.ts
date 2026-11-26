// Helpers of the dashboard's Playwright tests (P-33 spec Section 6.6 item 1): the tiles as the screen shows them, the
// values DM-R-53 gives from SB-S09's seeded sums and the live incidents' headers, and an AR-I-80 answer formatted as
// the screen formats it (Section 6.4).
import type { Page } from "@playwright/test";

/** The four tile names in DC-S10-02's order. */
export const METRICS = [
  "Time to acknowledge",
  "Time to recover",
  "Alerts suppressed",
  "Incidents merged",
];

export interface Tile {
  name: string;
  value: string;
  definition: string;
  /** The text of the baseline line, null when absent. */
  baseline: string | null;
}

/** The tiles of the dashboard shown, in order. */
export function tiles(page: Page): Promise<Tile[]> {
  return page.locator("main section").evaluateAll((sections) =>
    sections.map((section) => {
      const blocks = [...section.querySelectorAll("p")].map(
        (p) => p.textContent ?? "",
      );
      return {
        name: section.querySelector("h2")?.textContent ?? "",
        value: blocks[0] ?? "",
        definition: blocks[1] ?? "",
        baseline: blocks[2] ?? null,
      };
    }),
  );
}

/** The data line under the tiles. */
export function dataLineOf(page: Page) {
  return page.locator("main > p");
}

function grouped(n: number, decimals: number): string {
  return new Intl.NumberFormat("en-US", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
    useGrouping: true,
  }).format(n);
}

/** The fields of an AR-I-22 header that DM-R-53 reads. */
export interface Header {
  first_alert_fired_at: string;
  acknowledged: { at: string } | null;
  resolved_at: string | null;
  alert_count: number;
}

// SB-S09's seeded sums (complete list; the sums of docs/31-demo-data/09-past-incidents.csv).
const MINUTE_MS = 60_000n;
const SEEDED_ACKNOWLEDGED_MS = 284n * MINUTE_MS;
const SEEDED_ACKNOWLEDGED = 50n;
const SEEDED_RECOVERED_MS = 2056n * MINUTE_MS;
const SEEDED_RECOVERED = 50n;
const SEEDED_ALERTS = 575;
const SEEDED_MERGED = 46;
const SEEDED_INCIDENTS = 50;

/** total / (60,000 × count) minutes, rounded half-up to one decimal, as storm_checks.half_up_mean; in tenths. */
function halfUpTenths(totalMs: bigint, count: bigint): bigint {
  const negative = totalMs < 0n;
  const numerator = (negative ? -totalMs : totalMs) * 10n;
  const denominator = MINUTE_MS * count;
  let quotient = numerator / denominator;
  if (2n * (numerator % denominator) >= denominator) {
    quotient += 1n;
  }
  return negative ? -quotient : quotient;
}

const minutes = (tenths: bigint) => `${grouped(Number(tenths) / 10, 1)} min`;
const between = (start: string, end: string) =>
  BigInt(Date.parse(end) - Date.parse(start));

/** The four values DM-R-53 gives, as the screen shows them, from the seeded sums and the live incidents' headers. */
export function formulas(headers: readonly Header[]): string[] {
  let acknowledgedMs = SEEDED_ACKNOWLEDGED_MS;
  let acknowledged = SEEDED_ACKNOWLEDGED;
  let recoveredMs = SEEDED_RECOVERED_MS;
  let recovered = SEEDED_RECOVERED;
  let alerts = SEEDED_ALERTS;
  let merged = SEEDED_MERGED;
  for (const header of headers) {
    if (header.acknowledged !== null) {
      acknowledgedMs += between(
        header.first_alert_fired_at,
        header.acknowledged.at,
      );
      acknowledged += 1n;
    }
    if (header.resolved_at !== null) {
      recoveredMs += between(header.first_alert_fired_at, header.resolved_at);
      recovered += 1n;
    }
    alerts += header.alert_count;
    if (header.alert_count >= 2) {
      merged += 1;
    }
  }
  return [
    minutes(halfUpTenths(acknowledgedMs, acknowledged)),
    minutes(halfUpTenths(recoveredMs, recovered)),
    grouped(alerts - (SEEDED_INCIDENTS + headers.length), 0),
    grouped(merged, 0),
  ];
}

/** The fields of an AR-I-80 answer. */
export interface Answer {
  metrics: {
    key: string;
    unit: "minutes" | "count";
    value: number | null;
    baseline_value: number | null;
  }[];
  live_incidents: number;
  past_incidents: number;
  record_labels: string[];
}

const KEYS = [
  "time_to_acknowledge",
  "time_to_recover",
  "alerts_suppressed",
  "incidents_merged",
];

function metricText(value: number | null, unit: "minutes" | "count"): string {
  if (value === null) {
    return "no data";
  }
  return unit === "minutes" ? `${grouped(value, 1)} min` : grouped(value, 0);
}

const incidents = (n: number, adjective: string) =>
  `${grouped(n, 0)} ${adjective} ${n === 1 ? "incident" : "incidents"}`;

/** The four values, the baseline lines and the data line of an AR-I-80 answer, formatted as Section 6.4 says. */
export function shown(answer: Answer): {
  values: string[];
  baselines: (string | null)[];
  dataLine: string;
} {
  const metrics = KEYS.map((key) =>
    answer.metrics.find((metric) => metric.key === key),
  );
  const labels = (
    answer.record_labels.length === 0 ? ["none"] : answer.record_labels
  )
    .map((label) => `'${label}'`)
    .join(", ");
  return {
    values: metrics.map((metric) =>
      metric === undefined ? "no data" : metricText(metric.value, metric.unit),
    ),
    baselines: metrics.map((metric) =>
      metric === undefined || metric.baseline_value === null
        ? null
        : `${metricText(metric.baseline_value, metric.unit)} Illustrative baseline`,
    ),
    dataLine: `Includes ${incidents(answer.live_incidents, "live")} and ${incidents(answer.past_incidents, "imported past")} labelled ${labels}.`,
  };
}
