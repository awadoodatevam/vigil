// DC-S03-11 Recovery (P-29 spec Section 6.5 item 7), from AR-I-28 (polled every 1 s while observing): per
// verification its check, conditions, sources and progress or outcome; "Holding for" counts up every second.
import type { RecoverySection, Verification } from "../api/types";
import { duration } from "../format/duration";
import { listText } from "../format/names";
import { elapsedSeconds } from "../format/time";
import { KindTag } from "../tags/KindTag";
import { Line, OutcomeWord, Time } from "./lines";
import { useNow } from "./useNow";
import styles from "./workspace.module.css";

const holding = (v: Verification) =>
  v.state === "observing" &&
  v.window_started_at !== null &&
  v.conditions.every((condition) => condition.held);

export function RecoveryContent({
  section,
  now,
}: {
  section: RecoverySection;
  /** A fixed clock for tests; the 1-second clock otherwise. */
  now?: number;
}) {
  const clock = useNow(
    now === undefined && section.verifications.some(holding),
  );
  const at = now ?? clock;
  return (
    <>
      {section.verifications.map((v) => (
        <div key={v.recovery_verification_id} className={styles.block}>
          <Line lead="Check:">{v.check_name}</Line>
          {v.conditions.map((condition, index) => (
            <Line key={index}>
              {condition.detail}:{" "}
              <OutcomeWord
                outcome={condition.held ? "good" : "warning"}
                text={condition.held ? "Holding" : "Not holding"}
              />
            </Line>
          ))}
          {v.sources_healthy === true && (
            <Line>Monitoring sources: all healthy</Line>
          )}
          {v.sources_healthy === false && (
            <Line>
              Monitoring sources: {listText(v.unhealthy_sources)} not healthy
            </Line>
          )}
          {v.state === "observing" &&
            (holding(v) ? (
              <Line>
                Holding for {elapsedSeconds(v.window_started_at ?? "", at)} s of{" "}
                {duration(v.observation_window_seconds)}
              </Line>
            ) : (
              <Line>
                Waiting for the conditions (gives up at{" "}
                <Time at={v.max_wait_until} />)
              </Line>
            ))}
          {v.state === "recovered" && (
            <p className={styles.tagged}>
              <KindTag word="Verified outcome" />
              <span>
                Recovered at{" "}
                {v.decided_at !== null && <Time at={v.decided_at} />}
              </span>
            </p>
          )}
          {v.state === "not_recovered" && (
            <p className={styles.tagged}>
              <KindTag word="Not recovered" />
              <span>
                Not recovered by{" "}
                {v.decided_at !== null && <Time at={v.decided_at} />}
              </span>
            </p>
          )}
        </div>
      ))}
    </>
  );
}
