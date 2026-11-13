// The side column (P-29 spec Section 6.7): DC-S03-12 run steps, DC-S03-13 timeline, DC-S03-14 notified teams.
import type { IncidentTimeline, LiveRun, NotifiedTeams } from "../api/types";
import { StageChip } from "../badges/Badges";
import { contentOf } from "../canvas/graph";
import { notifiedPurposeName, outputText, stepTypeName } from "../format/names";
import type { OpenState } from "./Alerts";
import { Disclosure, EmptyLine, Time } from "./lines";
import { actorOf, collapse, type ShownEntry } from "./timeline";
import styles from "./workspace.module.css";

const STATE_WORDS: Record<string, string> = {
  running: "Running",
  waiting: "Waiting",
  cancelled: "Cancelled",
};

export function RunStepsContent({ run }: { run: LiveRun | null }) {
  if (run === null) {
    return <EmptyLine text="No workflow run." />;
  }
  const steps = contentOf(run).steps;
  return (
    <ul className={styles.list}>
      {run.step_executions.map((execution) => (
        <li key={execution.step_execution_id} className={styles.step}>
          <div className={styles.stepTop}>
            <span className={styles.stepLabel}>
              {steps.find((step) => step.step_id === execution.step_id)?.label}
            </span>
            {execution.state === "completed" ? (
              <StageChip text={outputText(execution.named_output ?? "")} />
            ) : (
              <span className={styles.stepState} data-state={execution.state}>
                {STATE_WORDS[execution.state]}
              </span>
            )}
          </div>
          <div className={styles.secondary}>
            {stepTypeName(execution.step_type)}
          </div>
          <Time at={execution.started_at} />
        </li>
      ))}
    </ul>
  );
}

function Entry({
  entry,
  product,
  open,
}: {
  entry: ShownEntry;
  product: string;
  open?: OpenState;
}) {
  const isOpen = open?.isOpen(entry.key) ?? false;
  return (
    <li>
      <div className={styles.entryTop}>
        <Time at={entry.at} />
        <span className={styles.actor}>
          {entry.actor ?? `${product} (automatic)`}
        </span>
      </div>
      {open && entry.collapsed ? (
        <>
          <p className={styles.entryText}>
            <Disclosure open={isOpen} onToggle={() => open.toggle(entry.key)}>
              {entry.text}
            </Disclosure>
          </p>
          {isOpen && (
            <ul className={styles.held}>
              {entry.holds.map((held) => (
                <Entry
                  key={held.timeline_entry_id}
                  product={product}
                  entry={{
                    key: held.timeline_entry_id,
                    at: held.at,
                    actor: actorOf(held),
                    text: held.text,
                    holds: [],
                    collapsed: false,
                  }}
                />
              ))}
            </ul>
          )}
        </>
      ) : (
        <p className={styles.entryText}>{entry.text}</p>
      )}
    </li>
  );
}

export function TimelineContent({
  timeline,
  product,
  open,
}: {
  timeline: IncidentTimeline;
  product: string;
  open: OpenState;
}) {
  return (
    <ul className={styles.list}>
      {collapse(timeline).map((entry) => (
        <Entry key={entry.key} entry={entry} product={product} open={open} />
      ))}
    </ul>
  );
}

/** Ordered by first_notified_at, then name. */
export function notifiedOrder(teams: NotifiedTeams["teams"]) {
  return [...teams].sort(
    (a, b) =>
      Date.parse(a.first_notified_at) - Date.parse(b.first_notified_at) ||
      a.name.localeCompare(b.name, "en"),
  );
}

export function NotifiedContent({ notified }: { notified: NotifiedTeams }) {
  if (notified.teams.length === 0) {
    return <EmptyLine text="No team notified yet." />;
  }
  return (
    <ul className={styles.list}>
      {notifiedOrder(notified.teams).map((team) => (
        <li key={`${team.team_key} ${team.purpose}`} className={styles.line}>
          {team.name} — {notifiedPurposeName(team.purpose)} —{" "}
          <Time at={team.first_notified_at} />
        </li>
      ))}
    </ul>
  );
}
