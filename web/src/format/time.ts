// Viewer times and the zone note (DC-U-07 rule (1), AR-W-06): the browser's time zone through Intl, or a zone passed
// by tests; 24-hour clock and fixed month abbreviations.
const MONTHS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];

interface Parts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function partsOf(instant: Date, zone: string | undefined): Parts {
  const format = new Intl.DateTimeFormat("en-GB", {
    timeZone: zone,
    year: "numeric",
    month: "numeric",
    day: "numeric",
    hour: "numeric",
    minute: "numeric",
    second: "numeric",
    hourCycle: "h23",
  });
  const values: Record<string, number> = {};
  for (const part of format.formatToParts(instant)) {
    if (part.type !== "literal") {
      values[part.type] = Number(part.value);
    }
  }
  return {
    year: values.year,
    month: values.month,
    day: values.day,
    hour: values.hour,
    minute: values.minute,
    second: values.second,
  };
}

const two = (n: number) => String(n).padStart(2, "0");

/** `HH:MM:SS` on the same calendar day as `now` in the zone, else `D Mon YYYY, HH:MM`. */
export function viewerTime(
  instant: string | Date,
  now: Date = new Date(),
  zone?: string,
): string {
  const at = partsOf(
    typeof instant === "string" ? new Date(instant) : instant,
    zone,
  );
  const today = partsOf(now, zone);
  if (
    at.year === today.year &&
    at.month === today.month &&
    at.day === today.day
  ) {
    return `${two(at.hour)}:${two(at.minute)}:${two(at.second)}`;
  }
  return `${at.day} ${MONTHS[at.month - 1]} ${at.year}, ${two(at.hour)}:${two(at.minute)}`;
}

/** "Times in UTC+hh:mm" or "Times in UTC-hh:mm": the zone's offset at `now`. */
export function zoneNote(now: Date = new Date(), zone?: string): string {
  const p = partsOf(now, zone);
  const local = Date.UTC(
    p.year,
    p.month - 1,
    p.day,
    p.hour,
    p.minute,
    p.second,
  );
  const utc = Math.floor(now.getTime() / 1000) * 1000;
  const minutes = Math.round((local - utc) / 60_000);
  const sign = minutes < 0 ? "-" : "+";
  const size = Math.abs(minutes);
  return `Times in UTC${sign}${two(Math.floor(size / 60))}:${two(size % 60)}`;
}

/** The countdown "{m}:{ss}" to `dueAt` (DC-S03-07): whole minutes and two-digit seconds; "0:00" once due. */
export function countdown(dueAt: string, now: number): string {
  const left = Math.max(0, Math.ceil((Date.parse(dueAt) - now) / 1000));
  return `${Math.floor(left / 60)}:${two(left % 60)}`;
}

/** The whole seconds from `from` to `now`, at least 0 (DC-S03-11 "Holding for {s} s"). */
export function elapsedSeconds(from: string, now: number): number {
  return Math.max(0, Math.floor((now - Date.parse(from)) / 1000));
}

/**
 * The expiry countdown "in {m} min {s} s" of DC-S05-03 (CTX-D-091 (5)): whole minutes and the remaining whole seconds
 * to `dueAt`, counted up to the next whole second; null when `now` is not before `dueAt`.
 */
export function expiresIn(dueAt: string, now: number): string | null {
  const left = Date.parse(dueAt) - now;
  if (!(left > 0)) {
    return null;
  }
  const seconds = Math.ceil(left / 1000);
  return `in ${Math.floor(seconds / 60)} min ${seconds % 60} s`;
}
