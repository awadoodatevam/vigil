// The state of a mobile page's own action (P-30 spec Sections 6.3 item 2, 6.4 item 2, 6.5 item 3): the button(s)
// disabled while the request runs; the outcome; a 422 re-reads the page at once; 403 and other failures give the
// line under the buttons; a 401 opens the login screen with `next` (P-28's rule).
import { useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { SESSION_ENDED } from "../session/messages";
import { loginAddress } from "../session/next";
import {
  afterAnswer,
  failureOf,
  INITIAL,
  lineOf,
  type Local,
  type Outcome,
} from "./actionArea";

interface Reread {
  isSuccess: boolean;
  data?: { refusal: unknown } | undefined;
}

export function useAction(
  answer: { refusal: unknown } | undefined,
  reread: () => Promise<Reread>,
) {
  const [local, setLocal] = useState<Local>(INITIAL);
  const navigate = useNavigate();
  const location = useLocation();
  const path = location.pathname + location.search;

  // A new answer ends the message of item (d) when it carries a refusal (state adjusted while rendering).
  const [seen, setSeen] = useState(answer);
  if (answer !== seen) {
    setSeen(answer);
    const next = afterAnswer(local, answer);
    if (next !== local) {
      setLocal(next);
    }
  }

  /** Runs `send`; `action` is the action name of "Could not {action}. {reason}" (DC-U-09). */
  async function run<A>(
    send: () => Promise<A>,
    outcome: (answer: A) => Outcome,
    action: string,
  ) {
    setLocal((current) => ({ ...current, line: null, sending: true }));
    let result: A;
    try {
      result = await send();
    } catch (error) {
      const failure = failureOf(error);
      switch (failure.kind) {
        case "snapshot_changed":
          setLocal((current) => ({
            ...current,
            sending: false,
            snapshotChanged: failure.message,
          }));
          return;
        case "reread": {
          const read = await reread();
          const none = read.isSuccess && read.data?.refusal === null;
          setLocal((current) => ({
            ...current,
            sending: false,
            failedMessage: none ? failure.message : current.failedMessage,
          }));
          return;
        }
        case "signed_out":
          void navigate(loginAddress(path), {
            state: failure.sessionEnded ? { message: SESSION_ENDED } : null,
          });
          return;
        default:
          setLocal((current) => ({
            ...current,
            sending: false,
            line: lineOf(failure, action),
          }));
          return;
      }
    }
    setLocal((current) => ({
      ...current,
      sending: false,
      outcome: outcome(result),
    }));
  }

  return { local, run };
}
