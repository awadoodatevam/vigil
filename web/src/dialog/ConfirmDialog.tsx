// The confirmation dialog of DC-U-11 (P-30 spec Section 6.6, VD-C-27): the browser's modal <dialog>, opened with
// showModal() when `open` becomes true, "Cancel" first and focused; the cancel event (Escape) and "Cancel" call
// onCancel, the confirming button onConfirm. The element never carries the `open` attribute in its markup.
import { useEffect, useId, useRef } from "react";
import styles from "./dialog.module.css";

export interface ConfirmDialogProps {
  question: string;
  confirmLabel: string;
  tone: "primary" | "destructive";
  open: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog(props: ConfirmDialogProps) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const questionId = useId();
  const { open } = props;

  useEffect(() => {
    const element = dialog.current;
    if (element === null) {
      return;
    }
    if (open && !element.open) {
      element.showModal();
      cancel.current?.focus();
    } else if (!open && element.open) {
      element.close();
    }
  }, [open]);

  return (
    <dialog
      ref={dialog}
      className={styles.dialog}
      aria-labelledby={questionId}
      onCancel={(event) => {
        event.preventDefault();
        props.onCancel();
      }}
    >
      <p id={questionId} className={styles.question}>
        {props.question}
      </p>
      <div className={styles.buttons}>
        <button
          ref={cancel}
          type="button"
          className={styles.cancel}
          onClick={props.onCancel}
        >
          Cancel
        </button>
        <button
          type="button"
          className={
            props.tone === "destructive" ? styles.destructive : styles.primary
          }
          onClick={props.onConfirm}
        >
          {props.confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
