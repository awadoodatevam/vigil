// The collapsing of the timeline (DC-S03-13; P-29 spec Section 6.7 item 2, complete list): (1) every entry at or
// before stable_at becomes one grouping entry; (2) every run of two or more consecutive alert_joined entries among
// the others becomes one entry. Entries are newest first, as AR-I-30 answers them.
import type { IncidentTimeline, TimelineEntry } from "../api/types";
import { count } from "../format/names";

export interface ShownEntry {
  /** The key that keeps a collapsed entry open across re-reads; the entry's id for a single entry. */
  key: string;
  at: string;
  /** null for the module actor "{product} (automatic)". */
  actor: string | null;
  text: string;
  /** The entries a collapsed entry holds, newest first; empty for a single entry. */
  holds: TimelineEntry[];
}

export function actorOf(entry: TimelineEntry): string | null {
  return entry.actor.kind === "module"
    ? null
    : (entry.actor.display_name ?? "");
}

const single = (entry: TimelineEntry): ShownEntry => ({
  key: entry.timeline_entry_id,
  at: entry.at,
  actor: actorOf(entry),
  text: entry.text,
  holds: [],
});

export function collapse(timeline: IncidentTimeline): ShownEntry[] {
  const grouping = timeline.stable_grouping;
  const stableAt = grouping === null ? null : Date.parse(grouping.stable_at);
  const before = (entry: TimelineEntry) =>
    stableAt !== null && Date.parse(entry.at) <= stableAt;
  const later = timeline.entries.filter((entry) => !before(entry));
  const shown: ShownEntry[] = [];
  for (let i = 0; i < later.length;) {
    let end = i;
    while (
      later[i].entry_type === "alert_joined" &&
      end + 1 < later.length &&
      later[end + 1].entry_type === "alert_joined"
    ) {
      end++;
    }
    if (end === i) {
      shown.push(single(later[i]));
    } else {
      const holds = later.slice(i, end + 1);
      shown.push({
        key: holds[holds.length - 1].timeline_entry_id,
        at: holds[0].at,
        actor: null,
        text: `${count(holds.length, "alert", "alerts")} joined`,
        holds,
      });
    }
    i = end + 1;
  }
  if (grouping !== null) {
    const entry: ShownEntry = {
      key: grouping.stable_at,
      at: grouping.stable_at,
      actor: null,
      text: `Grouping: ${count(grouping.alert_count, "alert", "alerts")} joined; hypothesis ${grouping.hypothesis_name ?? "none"}; ${count(grouping.merged_group_count, "group", "groups")} merged`,
      holds: timeline.entries.filter(before),
    };
    const at = Date.parse(grouping.stable_at);
    const place = shown.findIndex((other) => Date.parse(other.at) < at);
    shown.splice(place === -1 ? shown.length : place, 0, entry);
  }
  return shown;
}
