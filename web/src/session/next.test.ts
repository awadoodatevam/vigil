import { expect, it } from "vitest";
import { loginAddress, validNext } from "./next";

it("DC-U-13 a path that starts with one slash is accepted", () => {
  expect(validNext("/incidents")).toBe("/incidents");
  expect(validNext("/incidents/00000000-0000-4000-8000-000000000000")).toBe(
    "/incidents/00000000-0000-4000-8000-000000000000",
  );
  expect(validNext("/ai?request_group_id=1")).toBe("/ai?request_group_id=1");
  expect(validNext("/")).toBe("/");
});

it("DC-U-13 any other next is refused", () => {
  expect(validNext(null)).toBeNull();
  expect(validNext("")).toBeNull();
  expect(validNext("incidents")).toBeNull();
  expect(validNext("//jira.example/browse/NOC-1")).toBeNull();
  expect(validNext("/\\jira.example")).toBeNull();
  expect(validNext("https://jira.example/browse/NOC-1")).toBeNull();
  expect(validNext("javascript:alert(1)")).toBeNull();
});

it("DC-U-13 the login address carries the path and query, encoded, and decodes back", () => {
  const address = loginAddress("/incidents?a=1&b=2");
  expect(address).toBe("/login?next=%2Fincidents%3Fa%3D1%26b%3D2");
  expect(
    validNext(new URLSearchParams(address.split("?")[1]).get("next")),
  ).toBe("/incidents?a=1&b=2");
});
