// Shared pieces of the workspace (VD-C-17, VD-C-20, VD-C-23): the viewer-time formatter, labelled values, lines with a
// leading label, outcome words and the disclosure.
import { createContext, use, useState, type ReactNode } from "react";
import { viewerTime } from "../format/time";
import styles from "./workspace.module.css";

/** Formats a time of an answer as a viewer time (DC-U-07); tests give a fixed clock and zone. */
export const TimeContext = createContext<(iso: string) => string>((iso) =>
  viewerTime(iso),
);

export function Time({ at }: { at: string }) {
  const format = use(TimeContext);
  return <span className={styles.time}>{format(at)}</span>;
}

/** A labelled value of the header: "{label} {value}" (VD-C-17). */
export function Pair({
  label,
  children,
}: {
  label: string;
  children?: ReactNode;
}) {
  return (
    <span className={styles.pair}>
      <span className={styles.pairLabel}>{label}</span>{" "}
      <span className={styles.pairValue}>{children}</span>
    </span>
  );
}

/** A body line; a leading label ending with a colon in its own span (VD-C-23). */
export function Line({
  lead,
  secondary,
  children,
}: {
  lead?: string;
  secondary?: boolean;
  children?: ReactNode;
}) {
  return (
    <p className={secondary ? styles.secondary : styles.line}>
      {lead !== undefined && (
        <>
          <span className={styles.lead}>{lead}</span>
          {children !== undefined && " "}
        </>
      )}
      {children}
    </p>
  );
}

export type Outcome = "good" | "warning" | "bad";

/** An outcome word in its colour, with its text (VD-C-23, VD-R-06). */
export function OutcomeWord({
  outcome,
  text,
}: {
  outcome: Outcome;
  text: string;
}) {
  return (
    <span className={styles.outcome} data-outcome={outcome}>
      {text}
    </span>
  );
}

/** A button with aria-expanded and the chevron that opens what follows it (VD-C-20). */
export function Disclosure({
  open,
  onToggle,
  children,
}: {
  open: boolean;
  onToggle: () => void;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      className={styles.disclosure}
      aria-expanded={open}
      onClick={onToggle}
    >
      <span className={styles.chevron} aria-hidden="true" />
      {children}
    </button>
  );
}

/** The keys that are open, kept while the part stays shown (closed when the page opens). */
export function useOpenSet(): [
  (key: string) => boolean,
  (key: string) => void,
] {
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());
  const toggle = (key: string) =>
    setOpen((now) => {
      const next = new Set(now);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });
  return [(key) => open.has(key), toggle];
}

/** An empty text of a section (VD-C-22). */
export function EmptyLine({ text }: { text: string }) {
  return <p className={styles.empty}>{text}</p>;
}
