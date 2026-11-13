// DC-S02-02 The incoming alerts strip (complete list of items, left to right), as the tiles and feed of VD-C-05.
import type { AlertStrip } from "../api/types";
import { count, isAre, sourceKindName } from "../format/names";
import { viewerTime } from "../format/time";
import styles from "./incidents.module.css";

const HEALTH: Record<string, string> = {
  stale: "Stale",
  disconnected: "Disconnected",
};

export function Strip({
  strip,
  now,
  zone,
}: {
  strip: AlertStrip;
  now?: Date;
  zone?: string;
}) {
  const { sources } = strip;
  const received = strip.received_last_10_minutes;
  return (
    <section
      className={styles.strip}
      aria-label="Incoming alerts"
      data-testid="alerts-strip"
    >
      <p className={styles.tile}>
        <span className={styles.label}>
          {"Alerts received in the last 10 minutes: "}
        </span>
        <span className={styles.value}>{received}</span>
      </p>
      {strip.forming.incidents >= 1 && (
        <p className={`${styles.tile} ${styles.grouping}`}>
          <span className={styles.label}>
            <span className={styles.pulse} aria-hidden="true" />
            {"Grouping in progress: "}
          </span>
          <span className={styles.value}>
            {count(strip.forming.alerts, "alert", "alerts")}{" "}
            {isAre(strip.forming.alerts)} being grouped
          </span>
        </p>
      )}
      <p
        className={
          sources.healthy < sources.total
            ? `${styles.tile} ${styles.warning}`
            : styles.tile
        }
      >
        <span className={styles.label}>{"Monitoring: "}</span>
        <span className={styles.health}>
          <span className={styles.dot} aria-hidden="true" />
          {sources.healthy} of {sources.total} sources healthy
        </span>
        {sources.not_healthy.map((source) => (
          <span key={source.source_key} className={styles.source}>
            {source.name}: {HEALTH[source.health]}
          </span>
        ))}
      </p>
      {received === 0 ? (
        <p className={styles.feedEmpty}>No alerts in the last 10 minutes.</p>
      ) : (
        <ol className={styles.latest} aria-label="Latest alerts">
          {strip.latest.map((alert) => (
            <li key={alert.alert_id}>
              <span className={styles.latestTime}>
                {viewerTime(alert.first_received_at, now, zone)}
              </span>
              <span className={styles.latestKind}>
                {sourceKindName(alert.source_kind)}
              </span>
              <span className={styles.latestType}>{alert.alert_type_name}</span>
              <span className={styles.latestTitle}>{alert.title}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
