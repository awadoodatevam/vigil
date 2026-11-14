import { QueryClient } from "@tanstack/react-query";
import { expect, it } from "vitest";
import {
  lastUpdate,
  liveHandlers,
  queriesOf,
  topicQueries,
} from "./useLiveTopics";
import { approvalTopics } from "../mobile/queries";

const ID = "0192f1a2-7b3c-7d4e-8f50-6a7b8c9d0e1f";

/** A client holding the workspace's queries and the list's, each read at `at`. */
function client(at = 1000): QueryClient {
  const queries = new QueryClient();
  const keys = [
    ["tenant"],
    ["strip"],
    ["incidents"],
    ["incident", ID],
    ["incident-alerts", ID],
    ["incident", ID, "evidence"],
    ["incident", ID, "investigation"],
    ["incident", ID, "response"],
    ["incident", ID, "recovery"],
    ["incident", ID, "run"],
    ["incident", ID, "timeline"],
    ["incident", ID, "notified"],
  ];
  for (const queryKey of keys) {
    queries.setQueryData(queryKey, { read: true }, { updatedAt: at });
  }
  return queries;
}

const stale = (queries: QueryClient) =>
  queries
    .getQueryCache()
    .findAll({ stale: true })
    .filter((query) => query.state.isInvalidated)
    .map((query) => JSON.stringify(query.queryKey))
    .sort();

it("AR-I-04 the workspace's topics re-read their parts", () => {
  expect(topicQueries("tenant")).toEqual([["tenant"]]);
  expect(topicQueries("incidents")).toEqual([["incidents"]]);
  expect(topicQueries("alerts")).toEqual([["strip"], ["incident-alerts"]]);
  expect(topicQueries("sources")).toEqual([["strip"]]);
  expect(topicQueries(`incident:${ID}`)).toEqual([
    ["incident", ID],
    ["incident-alerts", ID],
    ["acknowledgement", ID],
    ["approval-request"],
  ]);
  expect(topicQueries("run:r-1")).toEqual([["incident"]]);
  expect(topicQueries("test-replay:x")).toEqual([]);
  expect(queriesOf(["alerts", "sources", "alerts"])).toEqual([
    ["strip"],
    ["incident-alerts"],
  ]);

  const incident = client();
  liveHandlers(incident, [], () => {}).invalidate(`incident:${ID}`);
  expect(stale(incident)).toEqual(
    [
      ["incident", ID],
      ["incident-alerts", ID],
      ["incident", ID, "evidence"],
      ["incident", ID, "investigation"],
      ["incident", ID, "response"],
      ["incident", ID, "recovery"],
      ["incident", ID, "run"],
      ["incident", ID, "timeline"],
      ["incident", ID, "notified"],
    ]
      .map((key) => JSON.stringify(key))
      .sort(),
  );

  const run = client();
  liveHandlers(run, [], () => {}).invalidate("run:r-1");
  const reread = stale(run);
  expect(reread).toHaveLength(8);
  expect(reread).not.toContain(JSON.stringify(["incident-alerts", ID]));

  const alerts = client();
  liveHandlers(alerts, [], () => {}).invalidate("alerts");
  expect(stale(alerts)).toEqual(
    [JSON.stringify(["incident-alerts", ID]), JSON.stringify(["strip"])].sort(),
  );
});

it("DC-U-09 the last update time of prefix keys", () => {
  const queries = client(1000);
  queries.setQueryData(
    ["incident", ID, "recovery"],
    { read: 2 },
    { updatedAt: 5000 },
  );
  queries.setQueryData(["incidents"], { read: 2 }, { updatedAt: 9000 });
  const keys = queriesOf([`incident:${ID}`, "run:r-1", "alerts"]);
  expect(lastUpdate(queries, keys)).toBe(5000);
  expect(lastUpdate(queries, [["nothing"]])).toBe(0);
});

it("AR-I-04 the mobile pages' topics", () => {
  const queries = new QueryClient();
  const keys = [
    ["acknowledgement", ID],
    ["approval-request", "q-1"],
    ["approval-request", "q-2"],
    ["acknowledgement", "other"],
  ];
  for (const queryKey of keys) {
    queries.setQueryData(queryKey, { read: true }, { updatedAt: 1000 });
  }
  liveHandlers(queries, [], () => {}).invalidate(`incident:${ID}`);
  expect(stale(queries)).toEqual(
    [
      ["acknowledgement", ID],
      ["approval-request", "q-1"],
      ["approval-request", "q-2"],
    ]
      .map((key) => JSON.stringify(key))
      .sort(),
  );

  expect(topicQueries("approval:q-1")).toEqual([["approval-request", "q-1"]]);
  const approval = new QueryClient();
  for (const queryKey of keys) {
    approval.setQueryData(queryKey, { read: true }, { updatedAt: 1000 });
  }
  liveHandlers(approval, [], () => {}).invalidate("approval:q-1");
  expect(stale(approval)).toEqual([
    JSON.stringify(["approval-request", "q-1"]),
  ]);
});

it("AR-W-02 the Approval page subscribes to its incident once it is known", () => {
  expect(approvalTopics("q-1", undefined)).toEqual(["approval:q-1"]);
  expect(approvalTopics("q-1", ID)).toEqual(["approval:q-1", `incident:${ID}`]);
});
