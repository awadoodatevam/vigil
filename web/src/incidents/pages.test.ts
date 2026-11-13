import { InfiniteQueryObserver, QueryClient } from "@tanstack/react-query";
import { expect, it } from "vitest";
import { createQueryClient } from "../app/queryClient";
import { page } from "./fixtures";
import { incidentsQuery, type FetchPage } from "./queries";

function setup(answers: Record<string, ReturnType<typeof page>>) {
  const reads: (string | null)[] = [];
  const fetchPage: FetchPage = (after) => {
    reads.push(after);
    return Promise.resolve(answers[after ?? "first"]);
  };
  const client: QueryClient = createQueryClient();
  const observer = new InfiniteQueryObserver(client, incidentsQuery(fetchPage));
  const unsubscribe = observer.subscribe(() => {});
  return { reads, observer, unsubscribe };
}

it("DC-U-12 Show more adds the next page after the rows shown", async () => {
  const pages = { first: page([60, 59], "Mjk"), Mjk: page([58, 57], null) };
  const { reads, observer, unsubscribe } = setup(pages);
  await observer.refetch();
  expect(observer.getCurrentResult().hasNextPage).toBe(true);
  await observer.fetchNextPage();
  const result = observer.getCurrentResult();
  expect(
    result.data?.pages.flatMap((p) => p.items.map((item) => item.number)),
  ).toEqual([60, 59, 58, 57]);
  expect(result.hasNextPage).toBe(false);
  expect(reads.slice(-2)).toEqual([null, "Mjk"]);
  unsubscribe();
});

it("DC-U-12 a re-read reads every page shown, in order, from the first, and keeps the number of pages", async () => {
  const pages = { first: page([60, 59], "Mjk"), Mjk: page([58, 57], null) };
  const { reads, observer, unsubscribe } = setup(pages);
  await observer.refetch();
  await observer.fetchNextPage();
  reads.length = 0;
  pages.first = page([61, 60], "NTk");
  Object.assign(pages, { NTk: page([59, 58], "NTc") });
  await observer.refetch();
  const result = observer.getCurrentResult();
  expect(reads).toEqual([null, "NTk"]);
  expect(result.data?.pages).toHaveLength(2);
  expect(
    result.data?.pages.flatMap((p) => p.items.map((item) => item.number)),
  ).toEqual([61, 60, 59, 58]);
  unsubscribe();
});
