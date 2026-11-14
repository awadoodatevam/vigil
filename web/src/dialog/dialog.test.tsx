import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { approvalPage } from "../test/mobile";
import { buttons } from "../test/markup";
import { textOf } from "../test/render";
import { ApprovalView, confirmQuestion } from "../mobile/ApprovalPage";
import { INITIAL } from "../mobile/actionArea";
import mobile from "../mobile/mobile.module.css";
import { ConfirmDialog, type ConfirmDialogProps } from "./ConfirmDialog";
import styles from "./dialog.module.css";

const dialog = (props: Partial<ConfirmDialogProps> = {}) =>
  renderToStaticMarkup(
    <ConfirmDialog
      question="Approve Purge archived transaction logs on Subscriber DB server?"
      confirmLabel="Approve"
      tone="primary"
      open
      onConfirm={() => {}}
      onCancel={() => {}}
      {...props}
    />,
  );

it("DC-U-11 the Approve and Reject questions and buttons", () => {
  const page = approvalPage();
  expect(confirmQuestion(page, "approve")).toBe(
    "Approve Purge archived transaction logs on Subscriber DB server?",
  );
  expect(confirmQuestion(page, "reject")).toBe(
    "Reject Purge archived transaction logs on Subscriber DB server?",
  );
  expect(
    confirmQuestion(
      approvalPage({ target: { target_key: "lab-db-01", name: null } }),
      "approve",
    ),
  ).toBe("Approve Purge archived transaction logs on lab-db-01?");
  const approve = dialog();
  expect(textOf(approve)).toBe(
    "Approve Purge archived transaction logs on Subscriber DB server?CancelApprove",
  );
  expect(buttons(approve)).toEqual(["Cancel", "Approve"]);
  const reject = dialog({
    question: confirmQuestion(page, "reject"),
    confirmLabel: "Reject",
    tone: "destructive",
  });
  expect(textOf(reject)).toBe(
    "Reject Purge archived transaction logs on Subscriber DB server?CancelReject",
  );
  expect(buttons(reject)).toEqual(["Cancel", "Reject"]);
});

it("VD-C-27 Cancel comes first and the confirming button is primary or destructive", () => {
  const html = dialog();
  expect(html).toMatch(/^<dialog /);
  expect(html).not.toMatch(/<dialog[^>]* open/);
  const labelled = /<dialog[^>]* aria-labelledby="([^"]+)"/.exec(html);
  expect(labelled).not.toBeNull();
  expect(html).toContain(`<p id="${labelled![1]}" class="${styles.question}">`);
  expect(html).toContain(`class="${styles.dialog}"`);
  const order = [
    ...html.matchAll(/<button type="button" class="([^"]+)"/g),
  ].map((match) => match[1]);
  expect(order).toEqual([styles.cancel, styles.primary]);
  const destructive = dialog({ confirmLabel: "Reject", tone: "destructive" });
  expect(
    [...destructive.matchAll(/<button type="button" class="([^"]+)"/g)].map(
      (match) => match[1],
    ),
  ).toEqual([styles.cancel, styles.destructive]);
});

it("VD-C-09 Reject is the destructive button", () => {
  const html = renderToStaticMarkup(
    <ApprovalView
      page={approvalPage()}
      local={INITIAL}
      now={0}
      format={(iso) => iso}
      onPress={() => {}}
    />,
  );
  const classes = [
    ...html.matchAll(/<button type="button" class="([^"]+)"[^>]*>([^<]+)</g),
  ].map((match) => [match[2], match[1]]);
  expect(classes).toEqual([
    ["Approve", mobile.button],
    ["Reject", `${mobile.button} ${mobile.destructive}`],
  ]);
});
