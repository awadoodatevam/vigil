import { expect, it } from "vitest";
import { at, time } from "../test/fixtures";
import { acknowledgePage, approvalPage, HIRA, USMAN } from "../test/mobile";
import { acknowledgeRefusalText, decisionRefusalText } from "./refusals";

const refusal = (key: string, message: string) => ({ key, message });

it("DC-S04-05 the acknowledgement refusals", () => {
  expect(acknowledgeRefusalText(acknowledgePage(), time)).toBeNull();
  const resolved =
    "This incident is resolved. It does not need acknowledgement.";
  expect(
    acknowledgeRefusalText(
      acknowledgePage({
        state: "resolved",
        refusal: refusal("not_open", resolved),
      }),
      time,
    ),
  ).toBe(resolved);
  const notNotified =
    "You are not in a team that was notified about this incident.";
  expect(
    acknowledgeRefusalText(
      acknowledgePage({ refusal: refusal("not_notified", notNotified) }),
      time,
    ),
  ).toBe(notNotified);
  expect(
    acknowledgeRefusalText(
      acknowledgePage({
        acknowledged: { user: HIRA, at: at("10:07:30") },
        refusal: refusal(
          "already_acknowledged",
          "Already acknowledged by Hira Baig (NOC L1) at 2026-10-10 10:07:30 UTC.",
        ),
      }),
      (iso) => `viewer ${time(iso)}`,
    ),
  ).toBe("Already acknowledged by Hira Baig (NOC L1) at viewer 10:07:30.");
});

it("DC-S05-06 the decision refusals", () => {
  const given: [string, string][] = [
    [
      "cancelled",
      "This request was cancelled because the incident's workflow run ended.",
    ],
    ["not_pending", "Blocked by policy"],
    [
      "not_eligible",
      "You are not in a team that can approve this action (Database Operations).",
    ],
    [
      "excluded",
      "You approved an earlier step of this run. A different approver is needed.",
    ],
    [
      "snapshot_changed",
      "This request changed after you opened it. Reload the page to see the current request.",
    ],
  ];
  for (const [key, message] of given) {
    expect(
      decisionRefusalText(
        approvalPage({ refusal: refusal(key, message) }),
        time,
      ),
      key,
    ).toBe(message);
  }
  const decided = { user: USMAN, at: at("10:09:00") };
  for (const state of ["approved", "rejected"] as const) {
    expect(
      decisionRefusalText(
        approvalPage({
          state,
          decided,
          refusal: refusal("already_decided", "Already … UTC."),
        }),
        time,
      ),
    ).toBe(`Already ${state} by Usman Tariq (DB Ops) at 10:09:00.`);
  }
  expect(
    decisionRefusalText(
      approvalPage({
        expires_at: at("10:15:00"),
        refusal: refusal(
          "expired",
          "This request expired at 2026-10-10 10:15:00 UTC.",
        ),
      }),
      time,
    ),
  ).toBe("This request expired at 10:15:00.");
  expect(decisionRefusalText(approvalPage(), time)).toBeNull();
});

it("DC-S05-06 the snapshot_changed message", () => {
  const message =
    "This request changed after you opened it. Reload the page to see the current request.";
  expect(
    decisionRefusalText(
      approvalPage({ refusal: refusal("snapshot_changed", message) }),
      time,
    ),
  ).toBe(message);
});
