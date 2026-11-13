// The kind tags of DC-U-10 (PM-M24-05): one of the words, or a record label (Example: "Fictional record").
import styles from "./tags.module.css";

/** The words of DC-U-10 (complete list). */
export type KindWord =
  | "Hypothesis"
  | "Observation"
  | "AI analysis"
  | "History match"
  | "Proposed action"
  | "Approval"
  | "Execution result"
  | "Verified outcome"
  | "Not recovered"
  | "Test"
  | "Illustrative baseline";

export type KindTagProps = { word: KindWord } | { recordLabel: string };

export function KindTag(props: KindTagProps) {
  return (
    <span className={styles.tag}>
      {"word" in props ? props.word : props.recordLabel}
    </span>
  );
}
