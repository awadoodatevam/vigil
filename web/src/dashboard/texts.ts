// The texts of the dashboard (DC-S10, DC-TX-00, DC-TX-01; P-33 spec Section 6.4).
import type { Dashboard } from "../api/types";
import { grouped } from "../format/numbers";

export type Metric = Dashboard["metrics"][number];
export type MetricKey = Metric["key"];

/** The four tiles in DC-S10-02's order, each with its name and its definition of DC-TX-01 (complete list). */
export const METRICS: readonly {
  key: MetricKey;
  name: string;
  definition: string;
}[] = [
  {
    key: "time_to_acknowledge",
    name: "Time to acknowledge",
    definition: "Mean time from detection to first acknowledgement",
  },
  {
    key: "time_to_recover",
    name: "Time to recover",
    definition: "Mean time from detection to first verified recovery",
  },
  {
    key: "alerts_suppressed",
    name: "Alerts suppressed",
    definition: "Alerts received minus incidents",
  },
  {
    key: "incidents_merged",
    name: "Incidents merged",
    definition: "Incidents made from 2 or more alerts",
  },
];

export const NO_DATA = "no data";

/** A metric's value or baseline value in its unit: "{x.x} min" for minutes, a whole number for counts. */
export function metricText(value: number | null, unit: Metric["unit"]): string {
  if (value === null) {
    return NO_DATA;
  }
  return unit === "minutes" ? `${grouped(value, 1)} min` : grouped(value, 0);
}

/** "{n} {adjective} {incident or incidents}" (DC-TX-00 "Counts"). */
function incidents(n: number, adjective: string): string {
  return `${grouped(n, 0)} ${adjective} ${n === 1 ? "incident" : "incidents"}`;
}

/** The data line of DC-S10-03: the labels in the answer's order, each in single quotes; none is 'none'. */
export function dataLine(
  live: number,
  past: number,
  labels: readonly string[],
): string {
  const listed = (labels.length === 0 ? ["none"] : labels)
    .map((label) => `'${label}'`)
    .join(", ");
  return `Includes ${incidents(live, "live")} and ${incidents(past, "imported past")} labelled ${listed}.`;
}
