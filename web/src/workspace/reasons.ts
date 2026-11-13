// The grouping reason of a member alert (DC-TX-02, DC-S03-05; P-29 spec Section 6.5 item 3): composed from AR-I-23's
// `reason` and the incident's grouping window; a null value leaves its placeholder empty.
import type { Reason } from "../api/types";
import { duration } from "../format/duration";
import { sourceKindName } from "../format/names";

const seconds = (value: number | null) =>
  value === null ? "" : duration(value);

export function reasonText(
  reason: Reason,
  windowSeconds: number | null,
): string {
  if (reason.relation === "only_alert") {
    return "The only alert of this incident.";
  }
  const related =
    reason.related === null
      ? ""
      : `'${reason.related.title}' (${sourceKindName(reason.related.source_kind)})`;
  const delta = seconds(reason.time_delta_seconds);
  const names = reason.path.map((component) => component.name);
  let first: string;
  switch (reason.relation) {
    case "same_component":
      first = `Same component as ${related}.`;
      break;
    case "depends_on":
      first = `Depends on the component of ${related}: ${names.join(" → ")}.`;
      break;
    case "depended_on_by":
      first = `The component of ${related} depends on this one: ${names.join(" ← ")}.`;
      break;
    case "same_unknown_identity":
      first = `Unknown component, with the same ${reason.identity?.name ?? ""} '${reason.identity?.value ?? ""}' as ${related}.`;
      break;
  }
  const last = reason.reopened
    ? `Reopened the incident ${delta} after ${related} fired.`
    : `Fired ${delta} apart, within the ${seconds(windowSeconds)} window.`;
  return `${first} ${last}`;
}
