import { expect, it } from "vitest";
import { grouped } from "./numbers";

it("DC-S10-02 numbers with a comma between groups of three digits", () => {
  expect(grouped(525, 0)).toBe("525");
  expect(grouped(1000, 0)).toBe("1,000");
  expect(grouped(1234.5, 1)).toBe("1,234.5");
  expect(grouped(0, 1)).toBe("0.0");
  expect(grouped(41, 1)).toBe("41.0");
  expect(grouped(1234567, 0)).toBe("1,234,567");
});
