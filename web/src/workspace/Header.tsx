// DC-S03-02 The workspace header (VD-C-17; P-29 spec Section 6.4): the page heading and the labelled values in order.
import type { IncidentHeader } from "../api/types";
import { SeverityBadge, StageChip, StateChip } from "../badges/Badges";
import { count, incidentNumber, listText } from "../format/names";
import { Pair, Time } from "./lines";
import styles from "./workspace.module.css";

const names = (teams: readonly { name: string }[]) =>
  listText(teams.map((team) => team.name));

export function WorkspaceHeader({ incident }: { incident: IncidentHeader }) {
  const { acknowledged, escalation, ticket } = incident;
  return (
    <div className={styles.header}>
      <h1 className={styles.title}>
        {incidentNumber(incident.number)} {incident.title}
      </h1>
      <div className={styles.values}>
        <Pair label="State:">
          <StateChip state={incident.state} />
        </Pair>
        {incident.stage !== null && (
          <Pair label="Stage:">
            <StageChip stage={incident.stage} />
          </Pair>
        )}
        <Pair label="Severity:">
          <SeverityBadge severity={incident.severity} />
        </Pair>
        <Pair label="Owner:">{incident.owner?.name}</Pair>
        <Pair label="Informed:">{names(incident.informed)}</Pair>
        <Pair label="Rule:">{incident.rule_name}</Pair>
        <Pair label="Acknowledged:">
          {acknowledged === null ? (
            <span className={styles.no}>no</span>
          ) : (
            <>
              {acknowledged.user.display_name}, <Time at={acknowledged.at} />
            </>
          )}
        </Pair>
        {escalation !== null && (
          <Pair label="Escalated:">
            level {escalation.level}, {names(escalation.teams)}
          </Pair>
        )}
        <Pair label="Detected:">
          <Time at={incident.first_alert_fired_at} />
        </Pair>
        {(incident.state === "resolved" || incident.state === "closed") && (
          <Pair label="Resolved:">
            {incident.resolved_at !== null && (
              <Time at={incident.resolved_at} />
            )}
          </Pair>
        )}
        <Pair label="Ticket:">
          {ticket === null ? (
            "none"
          ) : (
            <a
              className={styles.ticket}
              href={ticket.url}
              target="_blank"
              rel="noopener noreferrer"
            >
              Jira {ticket.key}
              <svg
                aria-hidden="true"
                width="12"
                height="12"
                viewBox="0 0 12 12"
                fill="none"
                stroke="currentColor"
                strokeWidth={1.5}
              >
                <path d="M5 2H2v8h8V7M7 1.5h3.5V5M10.5 1.5 5.5 6.5" />
              </svg>
            </a>
          )}
        </Pair>
        {incident.reopen_count > 0 && (
          <Pair label="Reopened:">
            {count(incident.reopen_count, "time", "times")}
          </Pair>
        )}
      </div>
    </div>
  );
}
