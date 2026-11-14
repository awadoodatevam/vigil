// The refusal and outcome texts of the mobile pages (P-30 spec Sections 6.3 item 3, 6.4 item 2, 6.5 item 5;
// CTX-D-079 (1), CTX-D-091 (4)): the three refusals whose text holds a time are composed with the viewer time from the
// answer's fields; every other refusal is the answer's message (DC-S04-05, DC-S05-06).
import type { AcknowledgePage, ApprovalPage } from "../api/types";

/** Formats a time of an answer as a viewer time (DC-U-07). */
export type TimeFormat = (iso: string) => string;

/** The refusal of the Acknowledge page, or null. */
export function acknowledgeRefusalText(
  page: Pick<AcknowledgePage, "refusal" | "acknowledged">,
  format: TimeFormat,
): string | null {
  const { refusal, acknowledged } = page;
  if (refusal === null) {
    return null;
  }
  if (refusal.key === "already_acknowledged" && acknowledged !== null) {
    return `Already acknowledged by ${acknowledged.user.display_name} at ${format(acknowledged.at)}.`;
  }
  return refusal.message;
}

/** The refusal of the Approval page, or null. */
export function decisionRefusalText(
  page: Pick<ApprovalPage, "refusal" | "decided" | "state" | "expires_at">,
  format: TimeFormat,
): string | null {
  const { refusal, decided, state } = page;
  if (refusal === null) {
    return null;
  }
  if (
    refusal.key === "already_decided" &&
    decided !== null &&
    (state === "approved" || state === "rejected")
  ) {
    return `Already ${state} by ${decided.user.display_name} at ${format(decided.at)}.`;
  }
  if (refusal.key === "expired") {
    return `This request expired at ${format(page.expires_at)}.`;
  }
  return refusal.message;
}

/** "Acknowledged by {user display name} at {time}." (DC-S04-04). */
export function acknowledgedText(
  answer: { user: { display_name: string }; at: string },
  format: TimeFormat,
): string {
  return `Acknowledged by ${answer.user.display_name} at ${format(answer.at)}.`;
}

/** The outcome of a decision (DC-S05-05). */
export function decidedText(
  answer: {
    state: "approved" | "rejected";
    user: { display_name: string };
    at: string;
  },
  product: string,
  format: TimeFormat,
): string {
  const by = `by ${answer.user.display_name} at ${format(answer.at)}.`;
  return answer.state === "approved"
    ? `Approved ${by} ${product} will check the conditions again before it runs the action.`
    : `Rejected ${by}`;
}
