// DC-S03-05 Alerts (VD-C-20, VD-C-23; P-29 spec Section 6.5 item 3), from AR-I-23: the counts and one disclosure per
// alert type group; an open group lists its alerts with "Why grouped".
import type { IncidentAlerts, MemberAlert } from "../api/types";
import { sourceKindName } from "../format/names";
import { Disclosure, Line, OutcomeWord, Time } from "./lines";
import { reasonText } from "./reasons";
import styles from "./workspace.module.css";

export interface OpenState {
  isOpen: (key: string) => boolean;
  toggle: (key: string) => void;
}

export function AlertsContent({
  alerts,
  groups,
  reasons,
}: {
  alerts: IncidentAlerts;
  groups: OpenState;
  reasons: OpenState;
}) {
  return (
    <>
      <Line>
        Firing {alerts.firing} · Resolved {alerts.resolved}
      </Line>
      <ul className={styles.list}>
        {alerts.groups.map((group) => {
          const open = groups.isOpen(group.alert_type);
          return (
            <li key={group.alert_type}>
              <Disclosure
                open={open}
                onToggle={() => groups.toggle(group.alert_type)}
              >
                {group.alert_type_name} ({group.count})
              </Disclosure>
              {open && (
                <ul className={styles.opened}>
                  {group.alerts.map((alert) => (
                    <AlertLine
                      key={alert.alert_id}
                      alert={alert}
                      window={alerts.grouping_window_seconds}
                      open={reasons.isOpen(alert.alert_id)}
                      onToggle={() => reasons.toggle(alert.alert_id)}
                    />
                  ))}
                </ul>
              )}
            </li>
          );
        })}
      </ul>
    </>
  );
}

function AlertLine({
  alert,
  window,
  open,
  onToggle,
}: {
  alert: MemberAlert;
  window: number | null;
  open: boolean;
  onToggle: () => void;
}) {
  return (
    <li>
      <div className={styles.alert}>
        <Time at={alert.fired_at} />
        <span className={styles.kind}>{sourceKindName(alert.source_kind)}</span>
        <span className={styles.alertTitle}>{alert.title}</span>
        <span className={styles.component}>
          {alert.component_name ?? "Unknown component"}
        </span>
        {alert.status === "firing" && (
          <OutcomeWord outcome="warning" text="Firing" />
        )}
        {alert.status === "resolved" && (
          <OutcomeWord outcome="good" text="Resolved" />
        )}
        <button
          type="button"
          className={styles.why}
          aria-expanded={open}
          onClick={onToggle}
        >
          Why grouped
        </button>
      </div>
      {open && <p className={styles.box}>{reasonText(alert.reason, window)}</p>}
    </li>
  );
}
