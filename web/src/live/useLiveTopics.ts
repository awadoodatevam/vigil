// The hook of a screen's live topics (AR-I-04, AR-W-02): the queries of each topic, and the interrupted state.
import {
  useQueryClient,
  type QueryClient,
  type QueryKey,
} from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { LiveConnection, type LiveHandlers } from "./live";

/** The query keys a topic re-reads, matched by prefix (complete list, P-29 spec Section 6.2 item 4, P-30 spec 6.2 item 5). */
export function topicQueries(topic: string): QueryKey[] {
  switch (topic) {
    case "tenant":
      return [["tenant"]];
    case "incidents":
      return [["incidents"]];
    case "alerts":
      return [["strip"], ["incident-alerts"]];
    case "sources":
      return [["strip"]];
  }
  if (topic.startsWith("incident:")) {
    const id = topic.slice("incident:".length);
    return [
      ["incident", id],
      ["incident-alerts", id],
      ["acknowledgement", id],
      ["approval-request"],
    ];
  }
  if (topic.startsWith("run:")) {
    return [["incident"]];
  }
  if (topic.startsWith("approval:")) {
    return [["approval-request", topic.slice("approval:".length)]];
  }
  return [];
}

export function queriesOf(topics: readonly string[]): QueryKey[] {
  const keys: QueryKey[] = [];
  for (const topic of topics) {
    for (const key of topicQueries(topic)) {
      if (
        !keys.some((known) => JSON.stringify(known) === JSON.stringify(key))
      ) {
        keys.push(key);
      }
    }
  }
  return keys;
}

/**
 * Re-reads the cached queries that match the key by prefix without cancelling a read that runs (P-34 fix round 1,
 * AR-W-02): a query being read is read once more as soon as that read ends, so the screen ends on a read that started
 * after the last notice; however many notices arrive meanwhile, one more read follows (`waiting` holds those queries).
 */
function reread(
  client: QueryClient,
  queryKey: QueryKey,
  waiting: Set<string>,
): void {
  for (const query of client.getQueryCache().findAll({ queryKey })) {
    const exact = { queryKey: query.queryKey, exact: true };
    if (query.state.fetchStatus !== "fetching") {
      void client.invalidateQueries(exact, { cancelRefetch: false });
    } else if (!waiting.has(query.queryHash)) {
      waiting.add(query.queryHash);
      // With cancelRefetch false the promise is that of the running read.
      void client
        .invalidateQueries(exact, { cancelRefetch: false })
        .finally(() => {
          waiting.delete(query.queryHash);
          void client.invalidateQueries(exact, { cancelRefetch: false });
        });
    }
  }
}

/** The handlers of a screen's stream: each topic re-reads its queries; a reconnection re-reads all of them. */
export function liveHandlers(
  client: QueryClient,
  topics: readonly string[],
  interrupted: (value: boolean) => void,
): LiveHandlers {
  const waiting = new Set<string>();
  return {
    invalidate: (topic) => {
      for (const queryKey of topicQueries(topic)) {
        reread(client, queryKey, waiting);
      }
    },
    invalidateAll: () => {
      for (const queryKey of queriesOf(topics)) {
        reread(client, queryKey, waiting);
      }
    },
    interrupted,
  };
}

/** The latest successful read of every cached query that matches one of the keys by prefix; 0 when none. */
export function lastUpdate(client: QueryClient, keys: QueryKey[]): number {
  let latest = 0;
  for (const queryKey of keys) {
    for (const query of client.getQueryCache().findAll({ queryKey })) {
      latest = Math.max(latest, query.state.dataUpdatedAt);
    }
  }
  return latest;
}

/**
 * Opens one stream for the topics while the screen is mounted; true while the stream is interrupted. A stream opened
 * because the topics changed re-reads every query of its topics on its first open.
 */
export function useLiveTopics(topics: readonly string[]): boolean {
  const client = useQueryClient();
  const [interrupted, setInterrupted] = useState(false);
  const first = useRef(true);
  const joined = topics.join(",");
  useEffect(() => {
    const list = joined.split(",");
    const connection = new LiveConnection(
      list,
      liveHandlers(client, list, setInterrupted),
      undefined,
      !first.current,
    );
    first.current = false;
    connection.start();
    return () => {
      connection.stop();
      setInterrupted(false);
    };
  }, [client, joined]);
  return interrupted;
}
