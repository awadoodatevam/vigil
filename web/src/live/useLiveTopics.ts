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

/** The handlers of a screen's stream: each topic re-reads its queries; a reconnection re-reads all of them. */
export function liveHandlers(
  client: QueryClient,
  topics: readonly string[],
  interrupted: (value: boolean) => void,
): LiveHandlers {
  return {
    invalidate: (topic) => {
      for (const queryKey of topicQueries(topic)) {
        void client.invalidateQueries({ queryKey });
      }
    },
    invalidateAll: () => {
      for (const queryKey of queriesOf(topics)) {
        void client.invalidateQueries({ queryKey });
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
