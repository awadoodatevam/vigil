// The action area of the mobile pages (P-30 spec Section 6.3 item 2, VD-C-26): what it shows, from the latest answer
// and this page's own actions, in the order of the complete list (a) to (f).
import { ApiFailure, reasonOf } from "../api/client";

/** The outcome of this page's own action and the refusal key that action causes. */
export interface Outcome {
  text: string;
  tone: "success" | "info";
  refusalKey: "already_acknowledged" | "already_decided";
}

/** What this page remembers of its own actions until it is reloaded. */
export interface Local {
  outcome: Outcome | null;
  /** The message of a decision refused `snapshot_changed`, kept until the page is reloaded. */
  snapshotChanged: string | null;
  /** The message of another 422 whose re-read carried no refusal, kept until an answer carries a refusal. */
  failedMessage: string | null;
  /** "You do not have permission to do this." or "Could not … {reason}", until the next press of a button. */
  line: string | null;
  sending: boolean;
}

export const INITIAL: Local = {
  outcome: null,
  snapshotChanged: null,
  failedMessage: null,
  line: null,
  sending: false,
};

export interface Area {
  outcome: Outcome | null;
  /** A refusal or a refused action's message, in place of the buttons (a warning notice). */
  notice: string | null;
  buttons: boolean;
  line: string | null;
  viewOnly: boolean;
}

/** The action area for the latest answer (`refusalText` is its composed refusal, or null). */
export function actionArea(
  answer: { may_act: boolean; refusal: { key: string } | null },
  refusalText: string | null,
  local: Local,
): Area {
  const viewOnly = !answer.may_act;
  const base = { outcome: null, notice: null, buttons: false, viewOnly };
  if (
    local.outcome !== null &&
    (answer.refusal === null || answer.refusal.key === local.outcome.refusalKey)
  ) {
    return { ...base, outcome: local.outcome, line: null };
  }
  const notice =
    answer.refusal !== null
      ? refusalText
      : (local.snapshotChanged ?? local.failedMessage);
  if (notice !== null) {
    return { ...base, notice, line: null };
  }
  return { ...base, buttons: answer.may_act, line: local.line };
}

/** The local state after a new answer: a refusal ends the message of item (d). */
export function afterAnswer(
  local: Local,
  answer: { refusal: unknown } | undefined,
): Local {
  return answer !== undefined &&
    answer.refusal !== null &&
    local.failedMessage !== null
    ? { ...local, failedMessage: null }
    : local;
}

/** How a failed action is handled (P-30 spec Sections 6.4 item 2, 6.5 item 3). */
export type Failure =
  | { kind: "snapshot_changed"; message: string }
  | { kind: "reread"; message: string }
  | { kind: "forbidden" }
  | { kind: "signed_out"; sessionEnded: boolean }
  | { kind: "failed"; reason: string };

export function failureOf(error: unknown): Failure {
  if (error instanceof ApiFailure) {
    if (error.status === 422) {
      return error.details?.refusal === "snapshot_changed"
        ? { kind: "snapshot_changed", message: error.reason }
        : { kind: "reread", message: error.reason };
    }
    if (error.status === 403) {
      return { kind: "forbidden" };
    }
    if (error.status === 401) {
      return {
        kind: "signed_out",
        sessionEnded: error.code === "session_ended",
      };
    }
  }
  return { kind: "failed", reason: reasonOf(error) };
}

export const NO_PERMISSION = "You do not have permission to do this.";

/** The line under the buttons for a failure (DC-U-05, DC-U-09), or null for the failures handled otherwise. */
export function lineOf(failure: Failure, action: string): string | null {
  switch (failure.kind) {
    case "forbidden":
      return NO_PERMISSION;
    case "failed":
      return `Could not ${action}. ${failure.reason}`;
    default:
      return null;
  }
}
