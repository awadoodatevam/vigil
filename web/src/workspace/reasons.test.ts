import { expect, it } from "vitest";
import type { Reason } from "../api/types";
import { reasonText } from "./reasons";

const DISK = {
  title: "Disk full: /data free space below 1 GiB",
  source_kind: "zabbix",
} as const;
const PATH = [
  { component_key: "app-balance-api", name: "Balance-check API" },
  { component_key: "db-subscriber", name: "Subscriber database" },
  { component_key: "srv-db-01", name: "Subscriber DB server" },
];

const reason = (over: Partial<Reason>): Reason => ({
  relation: "same_component",
  reopened: false,
  related: DISK,
  path: [],
  time_delta_seconds: 14,
  identity: null,
  ...over,
});

it("DC-TX-02 each grouping relation", () => {
  expect(reasonText(reason({}), 300)).toBe(
    "Same component as 'Disk full: /data free space below 1 GiB' (Zabbix). Fired 14 s apart, within the 5 min window.",
  );
  expect(reasonText(reason({ relation: "depends_on", path: PATH }), 300)).toBe(
    "Depends on the component of 'Disk full: /data free space below 1 GiB' (Zabbix): Balance-check API → Subscriber database → Subscriber DB server. Fired 14 s apart, within the 5 min window.",
  );
  expect(
    reasonText(
      reason({ relation: "depended_on_by", path: [...PATH].reverse() }),
      300,
    ),
  ).toBe(
    "The component of 'Disk full: /data free space below 1 GiB' (Zabbix) depends on this one: Subscriber DB server ← Subscriber database ← Balance-check API. Fired 14 s apart, within the 5 min window.",
  );
  expect(
    reasonText(
      reason({
        relation: "same_unknown_identity",
        related: { title: "Job latency high", source_kind: "alertmanager" },
        identity: { name: "job", value: "batch-7" },
        time_delta_seconds: 150,
      }),
      120,
    ),
  ).toBe(
    "Unknown component, with the same job 'batch-7' as 'Job latency high' (Alertmanager). Fired 2 min 30 s apart, within the 2 min window.",
  );
  expect(
    reasonText(reason({ relation: "only_alert", related: null }), 300),
  ).toBe("The only alert of this incident.");
  expect(
    reasonText(
      reason({ relation: "only_alert", related: null, reopened: true }),
      300,
    ),
  ).toBe("The only alert of this incident.");
  expect(
    reasonText(reason({ reopened: true, time_delta_seconds: 600 }), 300),
  ).toBe(
    "Same component as 'Disk full: /data free space below 1 GiB' (Zabbix). Reopened the incident 10 min after 'Disk full: /data free space below 1 GiB' (Zabbix) fired.",
  );
  expect(reasonText(reason({ time_delta_seconds: null }), null)).toBe(
    "Same component as 'Disk full: /data free space below 1 GiB' (Zabbix). Fired  apart, within the  window.",
  );
});
