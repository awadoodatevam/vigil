import { expect, it } from "vitest";
import { CONTENT } from "../test/content";
import { DB_OPS, NOC_L1 } from "../test/fixtures";
import { cardSummary, teamName, type ContentStep } from "./summaries";

const TEAMS = [DB_OPS, NOC_L1];
const summary = (stepId: string, teams = TEAMS) =>
  cardSummary(
    CONTENT.steps.find((step) => step.step_id === stepId)!,
    teams,
  );
const step = (
  type: ContentStep["type"],
  parameters: Record<string, unknown>,
): ContentStep => ({
  step_id: "s-x",
  type,
  label: "x",
  position: { x: 0, y: 0 },
  parameters,
});

it("DC-TX-05 every card summary", () => {
  expect(summary("s-trigger")).toBe("On incident created or reopened");
  expect(summary("s-severity")).toBe("1 branch and otherwise");
  expect(
    cardSummary(
      step("condition", { branches: [{ label: "a" }, { label: "b" }] }),
      TEAMS,
    ),
  ).toBe("2 branches and otherwise");
  expect(summary("s-diagnose")).toBe(
    "3 checks on the hypothesis component's server",
  );
  expect(
    cardSummary(step("diagnostic", { diagnostics: ["disk_usage"] }), TEAMS),
  ).toBe("1 check on the hypothesis component's server");
  expect(summary("s-investigate")).toBe("Incident investigation");
  expect(summary("s-summarise")).toBe("Incident summary");
  expect(summary("s-similar")).toBe("Similar-incident search");
  expect(summary("s-approve")).toBe(
    "Eligible: Database Operations; expires after 900 s",
  );
  expect(summary("s-action")).toBe("Runs the approved action");
  expect(summary("s-recover")).toBe("Gives up after 300 s");
  expect(summary("s-wait-ack")).toBe(
    "Escalates every 600 s until acknowledged",
  );
  expect(summary("s-escalate")).toBe("Escalates one level now");
  expect(summary("s-notify")).toBe("To Owner team, Informed teams, NOC L1");
  expect(summary("s-notify-2")).toBe("To Owner team");
  expect(summary("s-ticket")).toBe("Create ticket");
  expect(summary("s-resolve")).toBe("Resolve ticket");
  expect(
    cardSummary(step("wait", { mode: "duration", duration: 60 }), TEAMS),
  ).toBe("Wait 60 s");
  expect(
    cardSummary(
      step("wait", {
        mode: "until_event",
        event: "incident_acknowledged",
        timeout: 600,
      }),
      TEAMS,
    ),
  ).toBe("Wait for acknowledgement, up to 600 s");
  expect(
    cardSummary(
      step("notification", {
        recipients: ["named_teams"],
        named_teams: ["noc-l1", "db-ops"],
      }),
      TEAMS,
    ),
  ).toBe("To Database Operations, NOC L1");
  expect(summary("s-approve", [])).toBe(
    "Eligible: db-ops; expires after 900 s",
  );
  expect(teamName("unknown-team", TEAMS)).toBe("unknown-team");
});
