import { expect, it } from "vitest";
import { dataLine, METRICS, metricText, NO_DATA } from "./texts";

it("DC-S10-02 the values and baselines in the metric's unit", () => {
  expect(metricText(5.7, "minutes")).toBe("5.7 min");
  expect(metricText(41, "minutes")).toBe("41.0 min");
  expect(metricText(11, "minutes")).toBe("11.0 min");
  expect(metricText(1234.5, "minutes")).toBe("1,234.5 min");
  expect(metricText(525, "count")).toBe("525");
  expect(metricText(0, "count")).toBe("0");
  expect(metricText(1000, "count")).toBe("1,000");
  expect(metricText(null, "minutes")).toBe("no data");
  expect(metricText(null, "count")).toBe("no data");
});

it("DC-S10-03 the data line", () => {
  expect(dataLine(0, 50, ["Fictional record"])).toBe(
    "Includes 0 live incidents and 50 imported past incidents labelled 'Fictional record'.",
  );
  expect(dataLine(1, 1, ["Fictional record"])).toBe(
    "Includes 1 live incident and 1 imported past incident labelled 'Fictional record'.",
  );
  expect(dataLine(2, 50, ["A", "B"])).toBe(
    "Includes 2 live incidents and 50 imported past incidents labelled 'A', 'B'.",
  );
  expect(dataLine(0, 0, [])).toBe(
    "Includes 0 live incidents and 0 imported past incidents labelled 'none'.",
  );
  expect(dataLine(1000, 1000, ["Fictional record"])).toBe(
    "Includes 1,000 live incidents and 1,000 imported past incidents labelled 'Fictional record'.",
  );
});

it("DC-TX-00 one incident is singular", () => {
  expect(dataLine(1, 2, ["X"])).toMatch(/^Includes 1 live incident and 2 /);
  expect(dataLine(2, 1, ["X"])).toMatch(
    /and 1 imported past incident labelled/,
  );
  expect(dataLine(0, 0, ["X"])).toMatch(
    /^Includes 0 live incidents and 0 imported past incidents /,
  );
});

it("DC-TX-01 the four definitions", () => {
  expect(
    METRICS.map((metric) => [metric.key, metric.name, metric.definition]),
  ).toEqual([
    [
      "time_to_acknowledge",
      "Time to acknowledge",
      "Mean time from detection to first acknowledgement",
    ],
    [
      "time_to_recover",
      "Time to recover",
      "Mean time from detection to first verified recovery",
    ],
    [
      "alerts_suppressed",
      "Alerts suppressed",
      "Alerts received minus incidents",
    ],
    [
      "incidents_merged",
      "Incidents merged",
      "Incidents made from 2 or more alerts",
    ],
  ]);
});

it("DM-R-53 a mean without value is no data", () => {
  expect(NO_DATA).toBe("no data");
  expect(metricText(null, "minutes")).toBe(NO_DATA);
  expect(metricText(0, "minutes")).toBe("0.0 min");
});
