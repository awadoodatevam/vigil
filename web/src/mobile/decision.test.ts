import { expect, it } from "vitest";
import { ApiFailure, type ClientDeps } from "../api/client";
import { at, time } from "../test/fixtures";
import { approvalPage, USMAN } from "../test/mobile";
import {
  actionArea,
  afterAnswer,
  failureOf,
  INITIAL,
  type Local,
} from "./actionArea";
import { sendDecision } from "./decision";
import { decisionRefusalText } from "./refusals";

it("DC-S05-04 the decision sends exactly the decision and the hash shown", async () => {
  const sent: { url: string; init: RequestInit }[] = [];
  const deps: ClientDeps = {
    fetch: (input, init) => {
      sent.push({ url: String(input), init: init ?? {} });
      return Promise.resolve(
        new Response(
          JSON.stringify({
            state: "approved",
            user: USMAN,
            at: at("10:09:00"),
          }),
          { status: 201, headers: { "Content-Type": "application/json" } },
        ),
      );
    },
    cookies: () => "vigil_csrf=t-1",
  };
  const page = approvalPage();
  const answer = await sendDecision(
    page.approval_request_id,
    "approve",
    page.snapshot_hash,
    deps,
  );
  expect(answer).toEqual({
    state: "approved",
    user: USMAN,
    at: at("10:09:00"),
  });
  await sendDecision(page.approval_request_id, "reject", "other-hash", deps);
  expect(sent.map((request) => request.url)).toEqual([
    `/api/v1/approval-requests/${page.approval_request_id}/decisions`,
    `/api/v1/approval-requests/${page.approval_request_id}/decisions`,
  ]);
  expect(sent.map((request) => request.init.method)).toEqual(["POST", "POST"]);
  expect(sent.map((request) => JSON.parse(String(request.init.body)))).toEqual([
    { decision: "approve", snapshot_hash_shown: page.snapshot_hash },
    { decision: "reject", snapshot_hash_shown: "other-hash" },
  ]);
  const headers = sent[0].init.headers as Record<string, string>;
  expect(headers["X-Vigil-CSRF"]).toBe("t-1");
  expect(headers["Content-Type"]).toBe("application/json");
});

const SNAPSHOT_CHANGED =
  "This request changed after you opened it. Reload the page to see the current request.";

it("DC-S05-06 snapshot_changed shows its message until the page is reloaded, unless a re-read brings a refusal (already_decided shown); another 422 shows the re-read refusal", () => {
  const changed = failureOf(
    new ApiFailure(422, "refused", SNAPSHOT_CHANGED, {
      refusal: "snapshot_changed",
    }),
  );
  expect(changed).toEqual({
    kind: "snapshot_changed",
    message: SNAPSHOT_CHANGED,
  });
  const local: Local = { ...INITIAL, snapshotChanged: SNAPSHOT_CHANGED };
  const area = (page = approvalPage(), state = local) =>
    actionArea(page, decisionRefusalText(page, time), state);

  // Re-reads without a refusal keep the message in place of the buttons, also after the request changed.
  for (const page of [approvalPage(), approvalPage({ snapshot_hash: "new" })]) {
    const next = afterAnswer(local, page);
    expect(area(page, next)).toEqual({
      outcome: null,
      notice: SNAPSHOT_CHANGED,
      buttons: false,
      line: null,
      viewOnly: false,
    });
  }
  // A refusal of the latest answer comes first.
  const decided = approvalPage({
    state: "approved",
    decided: { user: USMAN, at: at("10:09:00") },
    refusal: { key: "already_decided", message: "Already approved … UTC." },
  });
  expect(area(decided).notice).toBe(
    "Already approved by Usman Tariq (DB Ops) at 10:09:00.",
  );
  // A reload starts from the initial state: the buttons again.
  expect(area(approvalPage(), INITIAL).buttons).toBe(true);

  // Another 422: the page reads again; its refusal is shown, or the 422's message when the read has none.
  const other = failureOf(
    new ApiFailure(
      422,
      "refused",
      "This request expired at 2026-10-10 10:15:00 UTC.",
      {
        refusal: "expired",
      },
    ),
  );
  expect(other).toEqual({
    kind: "reread",
    message: "This request expired at 2026-10-10 10:15:00 UTC.",
  });
  const expired = approvalPage({
    expires_at: at("10:15:00"),
    refusal: {
      key: "expired",
      message: "This request expired at 2026-10-10 10:15:00 UTC.",
    },
  });
  expect(area(expired, INITIAL).notice).toBe(
    "This request expired at 10:15:00.",
  );
  const failed: Local = {
    ...INITIAL,
    failedMessage: "The request was refused.",
  };
  expect(area(approvalPage(), failed).notice).toBe("The request was refused.");
  expect(afterAnswer(failed, expired).failedMessage).toBeNull();
  expect(afterAnswer(failed, approvalPage()).failedMessage).toBe(
    "The request was refused.",
  );
});
