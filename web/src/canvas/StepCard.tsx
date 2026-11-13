// The step card of a run canvas (DC-S06-05, DC-S03-07, VD-C-19): label, type, summary, the labelled outputs with their
// source handles, the target handle (none on a Trigger) and the state row; nothing on it can be changed (SL-WE-10).
import { Handle, Position } from "@xyflow/react";
import { StageChip } from "../badges/Badges";
import { countdown } from "../format/time";
import { useNow } from "../workspace/useNow";
import styles from "./canvas.module.css";
import type { StepCardData } from "./graph";

export function StepCard({ data, now }: { data: StepCardData; now?: number }) {
  const clock = useNow(data.countdownDue !== null && now === undefined);
  return (
    <div
      className={styles.card}
      data-step-id={data.stepId}
      data-state={data.state}
      data-current={data.current ? "true" : undefined}
      data-follow-target={data.followTarget ? "true" : undefined}
    >
      {data.target && (
        <Handle
          type="target"
          position={Position.Left}
          id="in"
          isConnectable={false}
        />
      )}
      <div className={styles.label}>{data.label}</div>
      <div className={styles.type}>{data.typeName}</div>
      <div className={styles.summary}>{data.summary}</div>
      <div className={styles.outputs}>
        {data.outputs.map((output) => (
          <div key={output.key} className={styles.output}>
            {output.text}
            <Handle
              type="source"
              position={Position.Right}
              id={output.key}
              isConnectable={false}
              data-taken={output.taken ? "true" : undefined}
            />
          </div>
        ))}
      </div>
      <StateRow data={data} now={now ?? clock} />
    </div>
  );
}

function StateRow({ data, now }: { data: StepCardData; now: number }) {
  switch (data.state) {
    case "completed":
      return (
        <div className={styles.state}>
          <StageChip text={data.output ?? ""} />
          {data.visit > 1 && (
            <span className={styles.visit}>×{data.visit}</span>
          )}
        </div>
      );
    case "waiting":
      return (
        <div className={styles.state}>
          <span className={styles.waiting}>Waiting</span>
          {data.countdownDue !== null && (
            <span className={styles.countdown}>
              {countdown(data.countdownDue, now)}
            </span>
          )}
        </div>
      );
    case "cancelled":
      return (
        <div className={styles.state}>
          <span className={styles.cancelled}>Cancelled</span>
        </div>
      );
    default:
      return null;
  }
}
