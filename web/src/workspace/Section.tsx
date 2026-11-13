// A part of the workspace and its states (DC-U-09, VD-C-22; P-29 spec Section 6.3 item 2): Loading until the first
// answer, PartError when the latest read failed, else the content.
import type { ReactNode } from "react";
import { Loading, PartError } from "../states/states";
import styles from "./workspace.module.css";

export interface PartState<T> {
  /** The latest answer; undefined before the first one. */
  data: T | undefined;
  /** The reason of the latest read when it failed. */
  failure: string | null;
  onTryAgain: () => void;
}

/** The part's content, or its Loading or PartError. */
export function partContent<T>(
  part: PartState<T>,
  render: (data: T) => ReactNode,
): ReactNode {
  if (part.failure !== null) {
    return <PartError reason={part.failure} onTryAgain={part.onTryAgain} />;
  }
  return part.data === undefined ? <Loading /> : render(part.data);
}

/** A section card with its heading (VD-C-22); `id` is the target of its anchor. */
export function Section({
  id,
  heading,
  children,
}: {
  id?: string;
  heading: string;
  children: ReactNode;
}) {
  return (
    <section id={id} className={styles.section}>
      <h2 className={styles.heading}>{heading}</h2>
      {children}
    </section>
  );
}

/** An empty text of a section (VD-C-22). */
export function EmptyText({ text }: { text: string }) {
  return <p className={styles.empty}>{text}</p>;
}
