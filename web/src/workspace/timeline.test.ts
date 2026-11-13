import { expect, it } from "vitest";
import type { IncidentTimeline, TimelineEntry } from "../api/types";
import { at, timeline } from "../test/fixtures";
import { collapse } from "./timeline";

const shown = (value: IncidentTimeline) =>
  collapse(value).map((entry) => [entry.text, entry.holds.length]);

const entry = (
  n: number,
  when: string,
  type: TimelineEntry["entry_type"],
  text = `entry ${n}`,
): TimelineEntry => ({
  timeline_entry_id: `e-${n}`,
  at: at(when),
  actor: { kind: "module", user_id: null, display_name: null },
  entry_type: type,
  text,
});

it("DC-S03-13 entries up to stable_at collapse into the grouping entry", () => {
  const entries = collapse(timeline());
  const grouping = entries.find((e) => e.text.startsWith("Grouping"))!;
  expect(grouping.text).toBe(
    "Grouping: 126 alerts joined; hypothesis Subscriber DB server; 3 groups merged",
  );
  expect(grouping.at).toBe(at("10:00:50"));
  expect(grouping.actor).toBeNull();
  expect(grouping.collapsed).toBe(true);
  expect(grouping.holds.map((held) => held.text)).toEqual([
    "Opened as INC-0002: the alert group is stable.",
    "Alert 'Balance API errors' (Alertmanager) joined.",
    "Incident created from alert 'Disk full: /data free space below 1 GiB' (Zabbix).",
  ]);
  expect(entries.at(-1)).toBe(grouping);
  const one = collapse({
    stable_grouping: {
      stable_at: at("10:00:30"),
      alert_count: 1,
      merged_group_count: 1,
      hypothesis_name: null,
    },
    entries: [
      entry(2, "10:00:40", "title_changed"),
      entry(1, "10:00:10", "created"),
    ],
  });
  expect(one.map((e) => e.text)).toEqual([
    "entry 2",
    "Grouping: 1 alert joined; hypothesis none; 1 group merged",
  ]);
  expect(
    collapse({
      stable_grouping: null,
      entries: [entry(1, "10:00:10", "created")],
    }),
  ).toHaveLength(1);
});

it("DC-S03-13 consecutive alert joins after stable_at collapse", () => {
  const entries = collapse(timeline());
  const joined = entries.find((e) => e.text === "2 alerts joined")!;
  expect(joined.at).toBe(at("10:02:02"));
  expect(joined.key).toBe("00000000-0000-4000-8000-000000001106");
  expect(joined.holds.map((held) => held.timeline_entry_id)).toEqual([
    "00000000-0000-4000-8000-000000001107",
    "00000000-0000-4000-8000-000000001106",
  ]);
  expect(entries.map((e) => e.text)).toContain(
    "Alert 'USSD timeouts' (Zabbix) joined.",
  );
  expect(
    shown({
      stable_grouping: null,
      entries: [
        entry(6, "10:06:00", "alert_joined"),
        entry(5, "10:05:00", "alert_joined"),
        entry(4, "10:04:00", "acknowledged"),
        entry(3, "10:03:00", "alert_joined"),
        entry(2, "10:02:00", "alert_joined"),
        entry(1, "10:01:00", "alert_joined"),
      ],
    }),
  ).toEqual([
    ["2 alerts joined", 2],
    ["entry 4", 0],
    ["3 alerts joined", 3],
  ]);
});

it("DC-U-06 the module actor is Techfinity (automatic)", async () => {
  const { render, texts } = await import("../test/render");
  const { TimelineContent } = await import("./Side");
  const html = render(
    TimelineContent({
      timeline: timeline(),
      product: "Techfinity",
      open: { isOpen: () => false, toggle: () => {} },
    }),
  );
  const actors = texts(html, "span").filter((text) => !/^\d/.test(text));
  expect(actors).toContain("Techfinity (automatic)");
  expect(actors).toContain("Hira Baig (NOC L1)");
});

it("DC-TX-04 the stored timeline texts are shown unchanged", () => {
  const texts = collapse(timeline()).map((e) => e.text);
  expect(texts[0]).toBe("Resolved: recovery verified.");
  expect(texts).toContain("Acknowledged by Hira Baig (NOC L1).");
  expect(texts).toContain(
    "Database Operations notified (incident notification).",
  );
});
