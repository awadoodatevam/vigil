// The answers of AR-I-80 for the dashboard's tests (P-33 spec Section 7): SB-V-38 (seeded), SB-S09 (after the storm)
// and edge variants built from them.
import type { Dashboard } from "../api/types";

type Values = [number | null, number | null, number | null, number | null];

const KEYS = [
  ["time_to_acknowledge", "minutes"],
  ["time_to_recover", "minutes"],
  ["alerts_suppressed", "count"],
  ["incidents_merged", "count"],
] as const;

function answer(
  values: Values,
  live: number,
  past = 50,
  labels: string[] = ["Fictional record"],
  baselines: Values = [11.0, 72.0, 0, 0],
): Dashboard {
  return {
    metrics: KEYS.map(([key, unit], i) => ({
      key,
      unit,
      value: values[i],
      baseline_value: baselines[i],
      baseline_label: baselines[i] === null ? null : "Illustrative baseline",
    })),
    live_incidents: live,
    past_incidents: past,
    record_labels: labels,
  };
}

/** SB-V-38: the seeded values, no live incident. */
export const SEEDED: Dashboard = answer([5.7, 41.1, 525, 46], 0);

/** SB-S09: the values after the storm driver run in mode script. */
export const AFTER_STORM: Dashboard = answer([5.7, 40.5, 650, 47], 2);

/** The edge variants (complete list). */
export const edge = {
  /** No incident acknowledged or recovered: the means have no value. */
  nullValue: answer([null, null, 0, 0], 0, 0, []),
  /** Time to recover without a baseline. */
  nullBaseline: answer(
    [5.7, 41.1, 525, 46],
    0,
    50,
    ["Fictional record"],
    [11.0, null, 0, 0],
  ),
  /** No record label. */
  noLabel: answer([5.7, 41.1, 525, 46], 0, 50, []),
  /** Two record labels, in AR-I-80's order. */
  twoLabels: answer([5.7, 41.1, 525, 46], 0, 50, ["A", "B"]),
  /** One live and one past incident. */
  one: answer([5.7, 41.1, 525, 46], 1, 1),
  /** 1,000 live incidents and large values. */
  thousand: answer([1234.5, 41, 1000, 1000], 1000, 1000),
  /** The answer without the metric incidents_merged. */
  missing: {
    ...SEEDED,
    metrics: SEEDED.metrics.filter(
      (metric) => metric.key !== "incidents_merged",
    ),
  },
};
