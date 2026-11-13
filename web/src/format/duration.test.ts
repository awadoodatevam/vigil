import { expect, it } from "vitest";
import { duration } from "./duration";

it("DC-U-07 durations below 120 s are in seconds", () => {
  expect(duration(0)).toBe("0 s");
  expect(duration(119)).toBe("119 s");
});

it("DC-U-07 whole minutes are in minutes, others in minutes and seconds", () => {
  expect(duration(120)).toBe("2 min");
  expect(duration(180)).toBe("3 min");
  expect(duration(181)).toBe("3 min 1 s");
  expect(duration(3600)).toBe("60 min");
});
