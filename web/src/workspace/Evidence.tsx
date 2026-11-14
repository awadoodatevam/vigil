// DC-S03-08 Evidence (DC-U-10, DC-TX-03; P-29 spec Section 6.5 item 4), from AR-I-24: one entry per line in the
// answer's order; a diagnostic entry's summary opens its output values.
import type { EvidenceLine, EvidenceLines } from "../api/types";
import { EVIDENCE_KIND_WORDS, valueText } from "../format/names";
import { KindTag, type KindWord } from "../tags/KindTag";
import type { OpenState } from "./Alerts";
import { Disclosure, EmptyLine, Line, Time } from "./lines";
import styles from "./workspace.module.css";

/** The kind tag of an evidence line; a recovery verification by its summary (CTX-D-089 (7)). */
export function evidenceWord(line: EvidenceLine): KindWord {
  return line.kind === "recovery_verification" &&
    line.summary.startsWith("Not recovered")
    ? "Not recovered"
    : EVIDENCE_KIND_WORDS[line.kind];
}

export function EvidenceContent({
  evidence,
  open,
}: {
  evidence: EvidenceLines;
  open: OpenState;
}) {
  if (evidence.entries.length === 0) {
    return <EmptyLine text="No evidence yet." />;
  }
  return (
    <ul className={styles.list}>
      {evidence.entries.map((entry) => {
        const values = entry.output_values;
        const opens = entry.kind === "diagnostic_result" && values !== null;
        const isOpen = open.isOpen(entry.evidence_entry_id);
        return (
          <li key={entry.evidence_entry_id}>
            <p className={styles.tagged}>
              <KindTag word={evidenceWord(entry)} />
              {opens ? (
                <Disclosure
                  open={isOpen}
                  onToggle={() => open.toggle(entry.evidence_entry_id)}
                >
                  {entry.summary}
                </Disclosure>
              ) : (
                <span>{entry.summary}</span>
              )}
            </p>
            {opens && isOpen && (
              <div className={styles.box}>
                {values.map((value) => (
                  <p key={value.name} className={styles.boxLine}>
                    {value.name}: {valueText(value.value)}
                  </p>
                ))}
              </div>
            )}
            <Line secondary>Source: {entry.source_system}</Line>
            <Line secondary>
              Collected: <Time at={entry.collected_at} />
            </Line>
          </li>
        );
      })}
    </ul>
  );
}
