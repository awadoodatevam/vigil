// The severity badge, state chip and stage chip of VD-C-07, shared by the incident list and the workspace (CTX-D-089
// (11)): P-44's markup; an empty severity or stage draws nothing.
import { severityName, stageName, stateName } from "../format/names";
import styles from "./badges.module.css";

export function SeverityBadge({ severity }: { severity: string | null }) {
  const name = severityName(severity);
  return (
    name && (
      <span className={styles.severity} data-severity={name}>
        {name}
      </span>
    )
  );
}

export function StateChip({ state }: { state: string }) {
  return (
    <span className={styles.state} data-state={state}>
      {stateName(state)}
    </span>
  );
}

/** A stage chip with the stage name of `stage`, or with a text given as is (the run state, an output tag). */
export function StageChip({
  stage,
  text,
}: {
  stage?: string | null;
  text?: string;
}) {
  const name = text ?? stageName(stage ?? null);
  return name && <span className={styles.stage}>{name}</span>;
}
