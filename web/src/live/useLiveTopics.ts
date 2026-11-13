// The hook of a screen's live topics (AR-I-04, AR-W-02): the queries of each topic, and the interrupted state.
import {
  useQueryClient,
  type QueryClient,
  type QueryKey,
} from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { LiveConnection, type LiveHandlers } from "./live";

/** The queries each topic re-reads (complete list for the screens built so far). */
export const TOPIC_QUERIES: Record<string, readonly QueryKey[]> = {
  tenant: [["tenant"]],
  incidents: [["incidents"]],
  alerts: [["strip"]],
  sources: [["strip"]],
};

export function queriesOf(topics: readonly string[]): QueryKey[] {
  const keys: QueryKey[] = [];
  for (const topic of topics) {
    for (const key of TOPIC_QUERIES[topic] ?? []) {
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
      for (const queryKey of TOPIC_QUERIES[topic] ?? []) {
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

/** Opens one stream for the topics while the screen is mounted; true while the stream is interrupted. */
export function useLiveTopics(topics: readonly string[]): boolean {
  const client = useQueryClient();
  const [interrupted, setInterrupted] = useState(false);
  const joined = topics.join(",");
  useEffect(() => {
    const list = joined.split(",");
    const connection = new LiveConnection(
      list,
      liveHandlers(client, list, setInterrupted),
    );
    connection.start();
    return () => {
      connection.stop();
      setInterrupted(false);
    };
  }, [client, joined]);
  return interrupted;
}
