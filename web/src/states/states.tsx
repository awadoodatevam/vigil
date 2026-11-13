// The screen states of DC-U-09 (P-28 spec Section 6.7).
import styles from "./states.module.css";

/** A loading indicator in place of the content. */
export function Loading() {
  return (
    <p className={styles.loading} role="status">
      <span className={styles.spinner} aria-hidden="true" />
      Loading…
    </p>
  );
}

/** The screen's empty text. */
export function Empty({ text }: { text: string }) {
  return (
    <div className={styles.empty}>
      <span className={styles.ring} aria-hidden="true" />
      <p className={styles.emptyText}>{text}</p>
    </div>
  );
}

interface ErrorProps {
  reason: string;
  onTryAgain: () => void;
}

/** "Could not load this page. {reason}" with "Try again". */
export function PageError({ reason, onTryAgain }: ErrorProps) {
  return (
    <div className={styles.error} role="alert">
      <p className={styles.message}>Could not load this page. {reason}</p>
      <button type="button" onClick={onTryAgain}>
        Try again
      </button>
    </div>
  );
}

/** "Could not load this part. {reason}" with "Try again". */
export function PartError({ reason, onTryAgain }: ErrorProps) {
  return (
    <div className={styles.error} role="alert">
      <p className={styles.message}>Could not load this part. {reason}</p>
      <button type="button" onClick={onTryAgain}>
        Try again
      </button>
    </div>
  );
}

/** "Could not {action}. {reason}", shown under the control. */
export function ActionFailed({
  action,
  reason,
}: {
  action: string;
  reason: string;
}) {
  return (
    <p className={styles.actionFailed} role="alert">
      Could not {action}. {reason}
    </p>
  );
}

/** The line of DC-U-09 for the screens of DC-U-08; `time` is a viewer time. */
export function LiveInterrupted({ time }: { time: string }) {
  return (
    <p className={styles.interrupted} role="status">
      Live updates interrupted. Reconnecting… Last update {time}.
    </p>
  );
}
