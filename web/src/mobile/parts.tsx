// The pieces both mobile pages share (P-30 spec Section 6.3; VD-C-25, VD-C-26, VD-S-06): the states of the page's
// read, the labelled values and the action area.
import type { ReactNode } from "react";
import { ApiFailure, reasonOf } from "../api/client";
import { Loading, PageError } from "../states/states";
import type { Area } from "./actionArea";
import styles from "./mobile.module.css";

/** The read's state (Section 6.3 item 1): Loading in a card; on 403 the permission text; else PageError. */
export function ReadState<T>({
  data,
  error,
  onTryAgain,
  denied,
  children,
}: {
  data: T | undefined;
  error: unknown;
  onTryAgain: () => void;
  /** The text of DC-S04-01 or DC-S05-01 for a user without the permission and without `view`. */
  denied: string;
  children: (data: T) => ReactNode;
}) {
  if (error !== null && error !== undefined) {
    if (error instanceof ApiFailure && error.status === 403) {
      return (
        <p className={styles.warning} role="status">
          {denied}
        </p>
      );
    }
    return <PageError reason={reasonOf(error)} onTryAgain={onTryAgain} />;
  }
  if (data === undefined) {
    return (
      <div className={styles.card}>
        <Loading />
      </div>
    );
  }
  return children(data);
}

/** A labelled value: the label (the text up to and including its colon) and the value (VD-C-25). */
export function Value({
  label,
  children,
}: {
  label: string;
  children?: ReactNode;
}) {
  return (
    <p className={styles.value}>
      <span className={styles.label}>{label}</span>{" "}
      <span className={styles.valueText}>{children}</span>
    </p>
  );
}

/** The action area (Section 6.3 item 2, VD-C-26). */
export function ActionArea({
  area,
  buttons,
  children,
}: {
  area: Area;
  /** The page's button or buttons, shown when the area shows buttons. */
  buttons: ReactNode;
  /** The confirmation dialog of the Approval page. */
  children?: ReactNode;
}) {
  return (
    <div className={styles.area}>
      {area.outcome !== null && (
        <p
          className={
            area.outcome.tone === "success"
              ? styles.success
              : styles.outcomeInfo
          }
          role="status"
        >
          {area.outcome.text}
        </p>
      )}
      {area.notice !== null && (
        <p className={styles.warning} role="status">
          {area.notice}
        </p>
      )}
      {area.buttons && buttons}
      {area.line !== null && (
        <p className={styles.failed} role="alert">
          {area.line}
        </p>
      )}
      {area.viewOnly && <p className={styles.viewOnly}>View only.</p>}
      {children}
    </div>
  );
}
