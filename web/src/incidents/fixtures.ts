// Test data of the incident list (not used by the application).
import type { AlertStrip, IncidentPage, IncidentRow } from "../api/types";

export const NOW = new Date("2026-10-10T09:20:00Z");

export function strip(overrides: Partial<AlertStrip> = {}): AlertStrip {
  return {
    received_last_10_minutes: 0,
    forming: { incidents: 0, alerts: 0 },
    sources: { healthy: 2, total: 2, not_healthy: [] },
    latest: [],
    ...overrides,
  };
}

export function row(
  number: number,
  overrides: Partial<IncidentRow> = {},
): IncidentRow {
  return {
    incident_id: `00000000-0000-4000-8000-${String(number).padStart(12, "0")}`,
    number,
    title: `Incident ${number}`,
    severity: null,
    state: "open",
    stage: null,
    owner: null,
    acknowledged: null,
    alert_count: 1,
    first_alert_fired_at: "2026-10-10T09:12:00.000Z",
    ticket: null,
    ...overrides,
  };
}

export function page(
  numbers: number[],
  nextCursor: string | null,
): IncidentPage {
  return { items: numbers.map((n) => row(n)), next_cursor: nextCursor };
}
