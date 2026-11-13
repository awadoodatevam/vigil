import { expect, it } from "vitest";
import { verification } from "../test/fixtures";
import { recoveryInterval, recoveryQuery } from "./queries";

it("AR-I-05 the recovery part polls every 1 s only while a verification is observing", () => {
  expect(recoveryInterval(undefined)).toBe(false);
  expect(recoveryInterval({ verifications: [] })).toBe(false);
  expect(recoveryInterval({ verifications: [verification()] })).toBe(1000);
  expect(
    recoveryInterval({
      verifications: [
        verification({ state: "recovered" }),
        verification({ state: "observing" }),
      ],
    }),
  ).toBe(1000);
  expect(
    recoveryInterval({
      verifications: [
        verification({ state: "recovered" }),
        verification({ state: "cancelled" }),
        verification({ state: "not_recovered" }),
      ],
    }),
  ).toBe(false);
  const query = recoveryQuery("i-1");
  expect(query.queryKey).toEqual(["incident", "i-1", "recovery"]);
  expect(query.refetchIntervalInBackground).toBe(true);
});
