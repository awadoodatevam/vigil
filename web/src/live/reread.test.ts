// P-34 fix round 1 (spec Section 6.10 item 3): a notice never cancels a running read, and one more read follows it.
import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, expect, it, vi } from "vitest";
import { liveHandlers } from "./useLiveTopics";

const ID = "0192f1a2-7b3c-7d4e-8f50-6a7b8c9d0e1f";
const TOPIC = `incident:${ID}`;

interface Read {
  signal: AbortSignal;
  answer: (value: string) => void;
}

/** A client whose ["incident", ID] query is shown (an observer) and read by reads the test answers itself. */
function screen() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const reads: Read[] = [];
  const observer = new QueryObserver(client, {
    queryKey: ["incident", ID],
    queryFn: ({ signal }) =>
      new Promise<string>((answer) => reads.push({ signal, answer })),
  });
  const stop = observer.subscribe(() => {});
  const handlers = liveHandlers(client, [TOPIC], () => {});
  const data = () => client.getQueryData<string>(["incident", ID]);
  cleanups.push(() => {
    stop();
    client.clear();
  });
  return { reads, handlers, data };
}

const cleanups: (() => void)[] = [];
afterEach(() => {
  while (cleanups.length > 0) {
    cleanups.pop()!();
  }
});

/** Lets the promises settle. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

/** The screen after its first read, with a re-read running (started by a notice), as during a storm's burst. */
async function rereading() {
  const shown = screen();
  await vi.waitFor(() => expect(shown.reads).toHaveLength(1));
  shown.reads[0].answer("shown");
  await vi.waitFor(() => expect(shown.data()).toBe("shown"));
  shown.handlers.invalidate(TOPIC);
  await vi.waitFor(() => expect(shown.reads).toHaveLength(2));
  return shown;
}

it("AR-W-02 a notice during a running read does not cancel it", async () => {
  const { reads, handlers, data } = await rereading();
  handlers.invalidate(TOPIC);
  handlers.invalidate(TOPIC);
  await settle();
  expect(reads[1].signal.aborted).toBe(false);
  reads[1].answer("re-read");
  await vi.waitFor(() => expect(data()).toBe("re-read"));
});

it("AR-W-02 a notice during a running read leads to exactly one more read after it", async () => {
  const { reads, handlers, data } = await rereading();
  for (let notice = 0; notice < 3; notice++) {
    handlers.invalidate(TOPIC);
  }
  handlers.invalidateAll();
  await settle();
  expect(reads).toHaveLength(2);
  reads[1].answer("before the notices");
  await vi.waitFor(() => expect(reads).toHaveLength(3));
  expect(data()).toBe("before the notices");
  reads[2].answer("after the notices");
  await vi.waitFor(() => expect(data()).toBe("after the notices"));
  await settle();
  expect(reads).toHaveLength(3);

  // A notice during the extra read leads to one more read again.
  handlers.invalidate(TOPIC);
  await vi.waitFor(() => expect(reads).toHaveLength(4));
  handlers.invalidate(TOPIC);
  reads[3].answer("fourth");
  await vi.waitFor(() => expect(reads).toHaveLength(5));
  expect(reads[3].signal.aborted).toBe(false);
});

it("AR-W-02 a notice with no read running reads at once", async () => {
  const { reads, handlers, data } = screen();
  await vi.waitFor(() => expect(reads).toHaveLength(1));
  reads[0].answer("first");
  await vi.waitFor(() => expect(data()).toBe("first"));
  handlers.invalidate(TOPIC);
  await vi.waitFor(() => expect(reads).toHaveLength(2));
  reads[1].answer("second");
  await vi.waitFor(() => expect(data()).toBe("second"));
  await settle();
  expect(reads).toHaveLength(2);
});
