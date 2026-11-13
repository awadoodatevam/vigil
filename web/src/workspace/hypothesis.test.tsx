import { expect, it } from "vitest";
import tags from "../tags/tags.module.css";
import { header } from "../test/fixtures";
import { render, texts } from "../test/render";
import { HypothesisContent, hypothesisSentence } from "./Hypothesis";

const RULE =
  "Chosen by the service map rule: the affected component that most other affected components depend on.";

const lines = (over: Parameters<typeof header>[0] = {}) =>
  texts(render(<HypothesisContent incident={header(over)} />), "p");

it("DC-S03-03 the hypothesis and the impact lines", () => {
  expect(lines()).toEqual([
    "HypothesisSubscriber DB server (srv-db-01)",
    "Server, criticality Critical",
    "The other 6 affected components all depend on it.",
    RULE,
    "Customer impact:",
    "Balance Check (USSD) — criticality Critical — via Balance-check API, USSD gateway",
    "Bundle Purchase — criticality Critical — via Bundle service",
    "SMS Notifications — criticality High — via SMS queue",
  ]);
});

it("DC-TX-02 every case of the hypothesis sentence", () => {
  expect(hypothesisSentence(0, 0)).toBe("It is the only affected component.");
  expect(hypothesisSentence(1, 1)).toBe(
    "The other affected component depends on it.",
  );
  expect(hypothesisSentence(6, 6)).toBe(
    "The other 6 affected components all depend on it.",
  );
  expect(hypothesisSentence(6, 4)).toBe(
    "4 of the other 6 affected components depend on it.",
  );
  expect(hypothesisSentence(1, 0)).toBe(
    "0 of the other 1 affected components depend on it.",
  );
});

it("DC-S03-03 no hypothesis and no impact", () => {
  expect(lines({ hypothesis: null, affected_services: [] })).toEqual([
    "No hypothesis: no alert matched a known component.",
    "Customer impact: none identified.",
  ]);
  const base = header().hypothesis!;
  expect(lines({ hypothesis: { ...base, criticality: null } })[1]).toBe(
    "Server, criticality not set",
  );
});

it("DC-S03-04 the rule line and each tie break", () => {
  const base = header().hypothesis!;
  expect(lines()).toContain(RULE);
  expect(lines().filter((line) => line.startsWith("Tie broken"))).toEqual([]);
  expect(
    lines({ hypothesis: { ...base, tie_break: "criticality" } }),
  ).toContain("Tie broken by higher criticality.");
  expect(
    lines({ hypothesis: { ...base, tie_break: "component_key" } }),
  ).toContain("Tie broken by component ID.");
});

it("PM-M24-05 the Hypothesis tag", () => {
  const html = render(<HypothesisContent incident={header()} />);
  expect(html).toContain(
    `<span class="${tags.tag}" data-kind="hypothesis">Hypothesis</span>`,
  );
  expect(
    render(<HypothesisContent incident={header({ hypothesis: null })} />),
  ).not.toContain("data-kind");
});
