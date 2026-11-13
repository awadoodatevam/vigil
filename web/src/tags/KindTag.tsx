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

/** The `data-kind` of each word, for its colours (VD-C-16); a record label is `record`. */
const KINDS: Record<KindWord, string> = {
  Hypothesis: "hypothesis",
  Observation: "observation",
  "AI analysis": "ai-analysis",
  "History match": "history-match",
  "Proposed action": "proposed-action",
  Approval: "approval",
  "Execution result": "execution-result",
  "Verified outcome": "verified-outcome",
  "Not recovered": "not-recovered",
  Test: "test",
  "Illustrative baseline": "illustrative-baseline",
};

export function KindTag(props: KindTagProps) {
  return (
    <span
      className={styles.tag}
      data-kind={"word" in props ? KINDS[props.word] : "record"}
    >
      {"word" in props ? props.word : props.recordLabel}
    </span>
  );
}
