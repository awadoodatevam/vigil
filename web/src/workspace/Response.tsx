// DC-S03-10 Response (P-29 spec Section 6.5 item 6), from AR-I-27: the similar past incident, the proposed action, the
// approvals and the executions, in this order.
import type { ResponseSection, TeamName } from "../api/types";
import {
  approvalStateName,
  count,
  executionStateName,
  listText,
  policyResultName,
  recheckName,
  valueText,
} from "../format/names";
import { KindTag } from "../tags/KindTag";
import { Line, OutcomeWord, Time } from "./lines";
import styles from "./workspace.module.css";

const names = (teams: readonly TeamName[]) =>
  listText(teams.map((team) => team.name));

function Similar({ section }: { section: ResponseSection }) {
  const similar = section.similar;
  if (similar === null) {
    return null;
  }
  if (similar.state === "unavailable") {
    return <Line>Similar-incident search unavailable.</Line>;
  }
  if (similar.outcome === "no_match") {
    return (
      <Line>
        {similar.score === null
          ? "No similar past incident."
          : `No similar past incident (best score ${similar.score} is below the minimum ${similar.min_similarity ?? ""}).`}
      </Line>
    );
  }
  const past = similar.past_incident;
  if (similar.outcome !== "match" || past === null) {
    return null;
  }
  return (
    <div className={styles.block}>
      <p className={styles.tagged}>
        <span className={styles.strong}>
          {past.past_incident_key} {past.title}
        </span>
        <KindTag recordLabel={past.record_label} />
      </p>
      <Line>Similarity {similar.score ?? ""}</Line>
      <Line>
        Fix succeeded{" "}
        {similar.success_count === null
          ? ""
          : count(similar.success_count, "time", "times")}
      </Line>
      <Line lead="Fix:">{past.fix_description}</Line>
    </div>
  );
}

function Proposal({ section }: { section: ResponseSection }) {
  const proposal = section.proposal;
  if (proposal === null) {
    return section.no_proposal ? <Line>No action was proposed.</Line> : null;
  }
  const target = proposal.target;
  return (
    <div className={styles.block}>
      <p className={styles.tagged}>
        <KindTag word="Proposed action" />
        <span className={styles.strong}>{proposal.action_name}</span>
      </p>
      {proposal.parameters.map((parameter) => (
        <p key={parameter.name} className={styles.parameter}>
          {parameter.name} = {valueText(parameter.value)}
        </p>
      ))}
      <Line lead="Target:">
        {target.name === null
          ? target.target_key
          : `${target.name} (${target.target_key})`}
      </Line>
      <Line lead="Policy:">{policyResultName(proposal.policy_outcome)}</Line>
    </div>
  );
}

export function ResponseContent({ section }: { section: ResponseSection }) {
  return (
    <>
      <Similar section={section} />
      <Proposal section={section} />
      {section.approvals.map((approval) => (
        <div key={approval.approval_request_id} className={styles.block}>
          <Line lead={`Approval ${approval.sequence_in_run}`} />
          <Line lead="Eligible:">{names(approval.eligible_teams)}</Line>
          <Line>
            {approvalStateName(approval.state, approval.refusal_reason)}
          </Line>
          {approval.decided !== null && (
            <Line secondary>
              by {approval.decided.user.display_name} at{" "}
              <Time at={approval.decided.at} />
            </Line>
          )}
          {approval.state === "pending" && (
            <Line secondary>
              expires <Time at={approval.expires_at} />
            </Line>
          )}
        </div>
      ))}
      {section.executions.map((execution) => (
        <div key={execution.action_execution_id} className={styles.block}>
          <p className={styles.tagged}>
            <KindTag word="Execution result" />
            <span className={styles.strong}>
              {execution.attempt_limit === null
                ? `Attempt ${execution.attempt_number}`
                : `Attempt ${execution.attempt_number} of ${execution.attempt_limit}`}
            </span>
          </p>
          <Line>{executionStateName(execution.state)}</Line>
          {execution.recheck.map((recheck) => (
            <div key={recheck.check}>
              <Line>
                {recheckName(recheck.check)}:{" "}
                <OutcomeWord
                  outcome={recheck.passed ? "good" : "bad"}
                  text={recheck.passed ? "passed" : "failed"}
                />
              </Line>
              <Line secondary>{recheck.detail}</Line>
            </div>
          ))}
          {execution.result_summary !== null && (
            <Line>{execution.result_summary}</Line>
          )}
          <Line secondary>
            Started: <Time at={execution.started_at} />
          </Line>
          {execution.finished_at !== null && (
            <Line secondary>
              Finished: <Time at={execution.finished_at} />
            </Line>
          )}
        </div>
      ))}
    </>
  );
}
