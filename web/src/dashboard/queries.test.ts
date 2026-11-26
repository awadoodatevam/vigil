import { afterEach, expect, it, vi } from "vitest";
import { queriesOf, topicQueries } from "../live/useLiveTopics";
import { SEEDED } from "../test/dashboard";
import { dashboardQuery, TOPICS } from "./queries";

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Whether a key matches ["dashboard"] by prefix, as invalidateQueries matches it. */
const matchesDashboard = (key: readonly unknown[]) =>
  key.length === 0 || key[0] === "dashboard";

it("DC-S10-04 the dashboard is read on opening only", () => {
  const query = dashboardQuery();
  expect(query.queryKey).toEqual(["dashboard"]);
  expect(query.refetchOnMount).toBe("always");
  expect(query.gcTime).toBe(0);
  expect("refetchInterval" in query).toBe(false);
  expect("refetchIntervalInBackground" in query).toBe(false);
});

it("DC-U-08 no topic re-reads the dashboard", () => {
  for (const topic of [
    "tenant",
    "incidents",
    "alerts",
    "sources",
    "workflow",
    "incident:x",
    "run:x",
    "approval:x",
  ]) {
    for (const key of topicQueries(topic)) {
      expect(matchesDashboard(key), `${topic}: ${JSON.stringify(key)}`).toBe(
        false,
      );
    }
  }
});

it("AR-I-04 the dashboard's stream has the topic tenant only", () => {
  expect(TOPICS).toEqual([]);
  expect(queriesOf([...TOPICS, "tenant"])).toEqual([["tenant"]]);
});

it("AR-W-02 the dashboard reads AR-I-80", async () => {
  const fetch = vi.fn((input: unknown, init?: RequestInit) => {
    void input;
    void init;
    return Promise.resolve(
      new Response(JSON.stringify(SEEDED), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
  });
  vi.stubGlobal("fetch", fetch);
  const query = dashboardQuery();
  const signal = new AbortController().signal;
  const answer = await (
    query.queryFn as (context: { signal: AbortSignal }) => Promise<unknown>
  )({ signal });
  expect(answer).toEqual(SEEDED);
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch.mock.calls[0][0]).toBe("/api/v1/dashboard");
  expect(fetch.mock.calls[0][1]?.method).toBe("GET");
});
