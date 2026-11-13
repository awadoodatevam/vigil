import { expect, it } from "vitest";
import { at, verification } from "../test/fixtures";
import { render, texts } from "../test/render";
import { RecoveryContent } from "./Recovery";

const lines = (
  over: Parameters<typeof verification>[0],
  now = Date.parse(at("10:20:00")),
) =>
  texts(
    render(
      <RecoveryContent
        section={{ verifications: [verification(over)] }}
        now={now}
      />,
    ),
    "p",
  );

it("DC-S03-11 holding for counts from window_started_at", () => {
  expect(lines({ window_started_at: at("10:20:00") })).toEqual([
    "Check: Subscriber DB services recovered",
    "Every alert of the incident is resolved at its source: Holding",
    "No alert of Synthetic USSD sessions is firing: Holding",
    "Monitoring sources: all healthy",
    "Holding for 0 s of 60 s",
  ]);
  expect(lines({})[4]).toBe("Holding for 17 s of 60 s");
  expect(lines({}, Date.parse(at("10:20:01")))[4]).toBe(
    "Holding for 18 s of 60 s",
  );
  expect(lines({ observation_window_seconds: 180 })[4]).toBe(
    "Holding for 17 s of 3 min",
  );
});

it("DC-S03-11 waiting for the conditions, not holding, sources not healthy, recovered, not recovered", () => {
  const notHolding = {
    window_started_at: null,
    conditions: [
      { detail: "3 alerts of the incident are still firing", held: false },
      { detail: "No alert of Synthetic USSD sessions is firing", held: true },
    ],
    sources_healthy: false,
    unhealthy_sources: ["Lab Zabbix", "Lab Prometheus"],
  };
  expect(lines(notHolding)).toEqual([
    "Check: Subscriber DB services recovered",
    "3 alerts of the incident are still firing: Not holding",
    "No alert of Synthetic USSD sessions is firing: Holding",
    "Monitoring sources: Lab Prometheus, Lab Zabbix not healthy",
    "Waiting for the conditions (gives up at 10:14:06)",
  ]);
  expect(lines({ sources_healthy: null, window_started_at: null })).toEqual([
    "Check: Subscriber DB services recovered",
    "Every alert of the incident is resolved at its source: Holding",
    "No alert of Synthetic USSD sessions is firing: Holding",
    "Waiting for the conditions (gives up at 10:14:06)",
  ]);
  expect(
    lines({ state: "recovered", decided_at: at("10:11:00") }).slice(3),
  ).toEqual([
    "Monitoring sources: all healthy",
    "Verified outcomeRecovered at 10:11:00",
  ]);
  expect(
    lines({
      ...notHolding,
      state: "not_recovered",
      decided_at: at("10:14:06"),
    }).slice(4),
  ).toEqual(["Not recoveredNot recovered by 10:14:06"]);
  expect(lines({ state: "cancelled" })).toHaveLength(4);
});
