import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { ConfirmDialog } from "../dialog/ConfirmDialog";
import { time } from "../test/fixtures";
import { buttons } from "../test/markup";
import { acknowledgePage, approvalPage } from "../test/mobile";
import { texts } from "../test/render";
import { ACKNOWLEDGE_DENIED, AcknowledgeView } from "./AcknowledgePage";
import { INITIAL, NO_PERMISSION } from "./actionArea";
import { APPROVE_DENIED, ApprovalView } from "./ApprovalPage";
import styles from "./mobile.module.css";

const labels = (html: string) =>
  texts(html, "span", ` class="${styles.label}"`);

it("VD-R-01 the pages' fixed texts are those of 40-demo-cut", () => {
  const acknowledge = renderToStaticMarkup(
    <AcknowledgeView
      page={acknowledgePage()}
      local={INITIAL}
      format={time}
      onAcknowledge={() => {}}
    />,
  );
  expect(texts(acknowledge, "h1")).toEqual(["Acknowledge incident"]);
  expect(labels(acknowledge)).toEqual([
    "Severity:",
    "Owner:",
    "Detected:",
    "Alerts:",
    "Summary:",
    "State:",
  ]);
  expect(buttons(acknowledge)).toEqual(["Acknowledge"]);

  const approval = renderToStaticMarkup(
    <ApprovalView
      page={approvalPage()}
      local={INITIAL}
      now={0}
      format={time}
      onPress={() => {}}
    />,
  );
  expect(texts(approval, "h1")).toEqual(["Approve action"]);
  expect(labels(approval)).toEqual(["State:"]);
  expect(texts(approval, "h2")).toEqual([
    "Action",
    "Parameters",
    "Target server",
    "Limits",
    "Expires",
    "Based on",
    "Recovery checks that will be used",
  ]);
  expect(buttons(approval)).toEqual(["Approve", "Reject"]);

  const viewer = renderToStaticMarkup(
    <AcknowledgeView
      page={acknowledgePage({ may_act: false })}
      local={INITIAL}
      format={time}
      onAcknowledge={() => {}}
    />,
  );
  expect(texts(viewer, "p", ` class="${styles.viewOnly}"`)).toEqual([
    "View only.",
  ]);
  expect(ACKNOWLEDGE_DENIED).toBe(
    "You do not have permission to acknowledge incidents.",
  );
  expect(APPROVE_DENIED).toBe("You do not have permission to approve actions.");
  expect(NO_PERMISSION).toBe("You do not have permission to do this.");
  const dialog = renderToStaticMarkup(
    <ConfirmDialog
      question="q"
      confirmLabel="Approve"
      tone="primary"
      open
      onConfirm={() => {}}
      onCancel={() => {}}
    />,
  );
  expect(buttons(dialog)).toEqual(["Cancel", "Approve"]);
});
