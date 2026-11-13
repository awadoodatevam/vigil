// DC-S03-09 AI investigation (P-29 spec Section 6.5 item 5), from AR-I-25; the re-run control and the AI usage link
// are P-39's.
import type { InvestigationSection } from "../api/types";
import {
  aiUnavailableReasonText,
  listText,
  providerDisplayName,
} from "../format/names";
import { KindTag } from "../tags/KindTag";
import { EmptyLine, Line, Time } from "./lines";
import styles from "./workspace.module.css";

export function InvestigationContent({
  section,
}: {
  section: InvestigationSection;
}) {
  const { result, summary } = section;
  const unavailable = section.ai_status === "unavailable";
  return (
    <>
      <p className={styles.tagged}>
        <KindTag word="AI analysis" />
      </p>
      {unavailable && (
        <p className={styles.warning} role="note">
          AI investigation unavailable —{" "}
          {aiUnavailableReasonText(section.ai_unavailable_reason)}.{" "}
          {section.ai_status_changed_at !== null && (
            <Time at={section.ai_status_changed_at} />
          )}
        </p>
      )}
      {result !== null && (
        <div className={styles.block}>
          <Line lead="Likely cause:">{result.likely_cause ?? ""}</Line>
          {result.verdict !== null && (
            <Line lead="Hypothesis:">
              {result.verdict === "confirms" ? "Confirms" : "Disputes"}
            </Line>
          )}
          <Line lead="Evidence used:" />
          {result.evidence_used.map((used) => (
            <Line key={used.evidence_entry_id}>{used.summary}</Line>
          ))}
          <Line lead="Still uncertain:">{result.uncertainty ?? ""}</Line>
          <Line lead="Runbooks given:">
            {result.runbooks_supplied.length === 0
              ? "none"
              : result.runbooks_supplied.join(", ")}
          </Line>
          <Line lead="Runbooks cited:">{listText(result.runbooks_cited)}</Line>
          <Line lead="Provider:">
            {providerDisplayName(result.provider_kind, result.model_name)}
          </Line>
          <Line secondary>
            <Time at={result.created_at} /> ·{" "}
            {result.origin === "workflow_step"
              ? "From the workflow"
              : `Re-run by ${result.requested_by?.display_name ?? ""}`}
          </Line>
        </div>
      )}
      {result === null && !unavailable && (
        <EmptyLine text="No AI investigation yet." />
      )}
      {summary === null ? (
        <EmptyLine text="No summary yet." />
      ) : (
        <Line
          lead={
            summary.source === "ai" ? "Summary (AI):" : "Summary (template):"
          }
        >
          {summary.text}
        </Line>
      )}
    </>
  );
}
