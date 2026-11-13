// DC-S02-04 The rows of the incident list, in the answer's order; a click outside the ticket link opens the incident.
import type { IncidentRow } from "../api/types";
import {
  incidentNumber,
  severityName,
  stageName,
  stateName,
} from "../format/names";
import { viewerTime } from "../format/time";
import styles from "./incidents.module.css";

export const COLUMNS = [
  "Number",
  "Title",
  "Severity",
  "State",
  "Stage",
  "Owner",
  "Acknowledged",
  "Alerts",
  "Detected",
  "Ticket",
] as const;

export interface RowsProps {
  rows: readonly IncidentRow[];
  onOpen: (incidentId: string) => void;
  now?: Date;
  zone?: string;
}

export function acknowledgedText(
  row: IncidentRow,
  now?: Date,
  zone?: string,
): string {
  return row.acknowledged === null
    ? "No"
    : `${row.acknowledged.user.display_name}, ${viewerTime(row.acknowledged.at, now, zone)}`;
}

export function Rows({ rows, onOpen, now, zone }: RowsProps) {
  return (
    <table className={styles.table}>
      <thead>
        <tr>
          {COLUMNS.map((column) => (
            <th key={column} scope="col">
              {column}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr
            key={row.incident_id}
            className={styles.row}
            onClick={() => onOpen(row.incident_id)}
          >
            <td>{incidentNumber(row.number)}</td>
            <td>{row.title}</td>
            <td>
              <Severity name={severityName(row.severity)} />
            </td>
            <td>
              <span className={styles.state} data-state={row.state}>
                {stateName(row.state)}
              </span>
            </td>
            <td>
              <Stage name={stageName(row.stage)} />
            </td>
            <td>{row.owner === null ? "" : row.owner.name}</td>
            <td>
              {row.acknowledged === null ? (
                <span className={styles.no}>No</span>
              ) : (
                acknowledgedText(row, now, zone)
              )}
            </td>
            <td>{row.alert_count}</td>
            <td>{viewerTime(row.first_alert_fired_at, now, zone)}</td>
            <td>
              {row.ticket !== null && (
                <a
                  href={row.ticket.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={(event) => event.stopPropagation()}
                >
                  {row.ticket.key}
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
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** The severity badge (VD-C-07); an empty severity draws nothing. */
function Severity({ name }: { name: string }) {
  return (
    name && (
      <span className={styles.severity} data-severity={name}>
        {name}
      </span>
    )
  );
}

/** The stage chip (VD-C-07); an empty stage draws nothing. */
function Stage({ name }: { name: string }) {
  return name && <span className={styles.stage}>{name}</span>;
}
