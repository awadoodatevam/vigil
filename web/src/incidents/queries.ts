// The reads of the incident list: the strip (AR-I-20, polled every 1 s, AR-I-05, AR-TM-09) and the pages of rows
// (AR-I-21, DC-U-12: 50 rows a page; a re-read reads every page shown, in order, from the first, CTX-D-085 (8)).
import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";
import { apiGet } from "../api/client";
import type { AlertStrip, IncidentPage } from "../api/types";
import { polling } from "../live/polling";

export const STRIP_INTERVAL_MS = 1000;
export const PAGE_SIZE = "50";

export type FetchPage = (
  after: string | null,
  signal: AbortSignal,
) => Promise<IncidentPage>;
export type FetchStrip = (signal: AbortSignal) => Promise<AlertStrip>;

const readPage: FetchPage = (after, signal) =>
  apiGet("/api/v1/incidents", {
    query: after === null ? { limit: PAGE_SIZE } : { limit: PAGE_SIZE, after },
    signal,
  });

const readStrip: FetchStrip = (signal) =>
  apiGet("/api/v1/alerts/strip", { signal });

export function stripQuery(fetchStrip: FetchStrip = readStrip) {
  return queryOptions({
    queryKey: ["strip"],
    queryFn: ({ signal }) => fetchStrip(signal),
    ...polling(STRIP_INTERVAL_MS),
  });
}

export function incidentsQuery(fetchPage: FetchPage = readPage) {
  return infiniteQueryOptions({
    queryKey: ["incidents"],
    queryFn: ({ pageParam, signal }) => fetchPage(pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (last: IncidentPage) => last.next_cursor,
  });
}
