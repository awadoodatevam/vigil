// DC-S02-02 The incoming alerts strip (complete list of items, left to right).
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
      <p className={styles.item}>
        Alerts received in the last 10 minutes: {received}
      </p>
      {strip.forming.incidents >= 1 && (
        <p className={styles.item}>
          Grouping in progress: {count(strip.forming.alerts, "alert", "alerts")}{" "}
          {isAre(strip.forming.alerts)} being grouped
        </p>
      )}
      <p
        className={
          sources.healthy < sources.total ? styles.warning : styles.item
        }
      >
        Monitoring: {sources.healthy} of {sources.total} sources healthy
        {sources.not_healthy.map((source) => (
          <span key={source.source_key} className={styles.source}>
            {source.name}: {HEALTH[source.health]}
          </span>
        ))}
      </p>
      {received === 0 ? (
        <p className={styles.item}>No alerts in the last 10 minutes.</p>
      ) : (
        <ol className={styles.latest} aria-label="Latest alerts">
          {strip.latest.map((alert) => (
            <li key={alert.alert_id}>
              <span>{viewerTime(alert.first_received_at, now, zone)}</span>
              <span>{sourceKindName(alert.source_kind)}</span>
              <span>{alert.alert_type_name}</span>
              <span>{alert.title}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
