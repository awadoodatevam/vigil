import { expect, it } from "vitest";
import {
  count,
  incidentNumber,
  isAre,
  severityName,
  sourceKindName,
  stageName,
  stateName,
} from "./names";

it("DC-S02-04 incident numbers are INC- and at least 4 digits", () => {
  expect(incidentNumber(2)).toBe("INC-0002");
  expect(incidentNumber(1234)).toBe("INC-1234");
  expect(incidentNumber(12345)).toBe("INC-12345");
});

it("DC-S02-04 state, stage, severity and source kind names", () => {
  expect(
    ["forming", "open", "resolved", "closed", "merged"].map(stateName),
  ).toEqual(["Forming", "Open", "Resolved", "Closed", "Merged"]);
  expect(
    ["investigation", "response", "recovery", null].map(stageName),
  ).toEqual(["Investigation", "Response", "Recovery", ""]);
  expect(["P1", "P2", "P3", "P4", null].map(severityName)).toEqual([
    "P1",
    "P2",
    "P3",
    "P4",
    "",
  ]);
  expect(sourceKindName("zabbix")).toBe("Zabbix");
  expect(sourceKindName("alertmanager")).toBe("Alertmanager");
});

it("DC-S02-02 alert or alerts, is or are", () => {
  expect(`${count(1, "alert", "alerts")} ${isAre(1)}`).toBe("1 alert is");
  expect(`${count(126, "alert", "alerts")} ${isAre(126)}`).toBe(
    "126 alerts are",
  );
  expect(`${count(0, "alert", "alerts")} ${isAre(0)}`).toBe("0 alerts are");
});

it("DC-S02-05 monitoring source or monitoring sources", () => {
  expect(count(1, "monitoring source", "monitoring sources")).toBe(
    "1 monitoring source",
  );
  expect(count(2, "monitoring source", "monitoring sources")).toBe(
    "2 monitoring sources",
  );
});
