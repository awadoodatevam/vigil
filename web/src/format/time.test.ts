import { describe, expect, it } from "vitest";
import { viewerTime, zoneNote } from "./time";

describe("viewerTime", () => {
  const now = new Date("2026-10-09T12:00:00Z");

  it("DC-U-07 a time of the current day is HH:MM:SS", () => {
    expect(viewerTime("2026-10-09T09:12:03.000Z", now, "UTC")).toBe("09:12:03");
  });

  it("DC-U-07 a time of another day is D Mon YYYY, HH:MM", () => {
    expect(viewerTime("2026-10-08T16:20:59.000Z", now, "UTC")).toBe(
      "8 Oct 2026, 16:20",
    );
    expect(viewerTime("2026-09-30T07:05:00.000Z", now, "UTC")).toBe(
      "30 Sep 2026, 07:05",
    );
  });

  it("DC-U-07 September and every month abbreviation", () => {
    const months = [
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
    months.forEach((month, index) => {
      const instant = new Date(Date.UTC(2025, index, 3, 4, 5));
      expect(viewerTime(instant, now, "UTC")).toBe(`3 ${month} 2025, 04:05`);
    });
  });

  it("DC-U-07 five minutes after midnight is 00:05:00 (24-hour clock)", () => {
    expect(viewerTime("2026-10-09T00:05:00.000Z", now, "UTC")).toBe("00:05:00");
  });

  it("DC-U-07 the day boundary follows the viewer's zone", () => {
    // 20:30 UTC on 8 Oct is 01:30 on 9 Oct in Asia/Karachi (UTC+05:00), the same day as now there.
    expect(viewerTime("2026-10-08T20:30:00.000Z", now, "Asia/Karachi")).toBe(
      "01:30:00",
    );
    expect(viewerTime("2026-10-08T20:30:00.000Z", now, "UTC")).toBe(
      "8 Oct 2026, 20:30",
    );
    // 18:59 UTC on 9 Oct is 23:59 in Karachi; 19:00 UTC is already 10 Oct there.
    const evening = new Date("2026-10-09T18:59:00Z");
    expect(
      viewerTime("2026-10-09T19:00:00.000Z", evening, "Asia/Karachi"),
    ).toBe("10 Oct 2026, 00:00");
  });
});

describe("zoneNote", () => {
  const now = new Date("2026-10-09T12:00:00Z");

  it("AR-W-06 the zone note for +05:00, +00:00, +05:30 and -03:00", () => {
    expect(zoneNote(now, "Asia/Karachi")).toBe("Times in UTC+05:00");
    expect(zoneNote(now, "UTC")).toBe("Times in UTC+00:00");
    expect(zoneNote(now, "Asia/Kolkata")).toBe("Times in UTC+05:30");
    expect(zoneNote(now, "America/Sao_Paulo")).toBe("Times in UTC-03:00");
  });
});
