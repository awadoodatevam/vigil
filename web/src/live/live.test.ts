import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { LiveConnection, type LiveSource } from "./live";

class TestSource implements LiveSource {
  readyState = 0;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  closed = false;
  private listeners: ((event: MessageEvent) => void)[] = [];

  constructor(readonly url: string) {}

  addEventListener(_type: "change", listener: (event: MessageEvent) => void) {
    this.listeners.push(listener);
  }

  close() {
    this.closed = true;
    this.readyState = 2;
  }

  open() {
    this.readyState = 1;
    this.onopen?.(new Event("open"));
  }

  fail(closed: boolean) {
    this.readyState = closed ? 2 : 0;
    this.onerror?.(new Event("error"));
  }

  change(topic: string) {
    const data = JSON.stringify({
      topic,
      id: "00000000-0000-4000-8000-000000000001",
      at: "2026-10-10T09:12:00.000Z",
    });
    for (const listener of this.listeners) {
      listener(new MessageEvent("change", { data }));
    }
  }
}

let sources: TestSource[];
let calls: string[];
let interrupted: boolean[];
let connection: LiveConnection;

beforeEach(() => {
  vi.useFakeTimers();
  sources = [];
  calls = [];
  interrupted = [];
  connection = new LiveConnection(
    ["incidents", "alerts", "sources", "tenant"],
    {
      invalidate: (topic) => calls.push(topic),
      invalidateAll: () => calls.push("*"),
      interrupted: (value) => interrupted.push(value),
    },
    (url) => {
      const source = new TestSource(url);
      sources.push(source);
      return source;
    },
  );
  connection.start();
});

afterEach(() => {
  connection.stop();
  vi.useRealTimers();
});

it("AR-W-02 one stream with the screen's topics", () => {
  expect(sources.map((source) => source.url)).toEqual([
    "/api/v1/live?topics=incidents,alerts,sources,tenant",
  ]);
});

it("AR-W-02 at most one invalidation per 500 ms per topic, and one more at its end", () => {
  const source = sources[0];
  source.open();
  source.change("incidents");
  expect(calls).toEqual(["incidents"]);
  vi.advanceTimersByTime(100);
  source.change("incidents");
  source.change("incidents");
  source.change("alerts");
  expect(calls).toEqual(["incidents", "alerts"]);
  vi.advanceTimersByTime(399);
  expect(calls).toEqual(["incidents", "alerts"]);
  vi.advanceTimersByTime(1);
  expect(calls).toEqual(["incidents", "alerts", "incidents"]);
  vi.advanceTimersByTime(500);
  expect(calls).toEqual(["incidents", "alerts", "incidents"]);
  source.change("incidents");
  expect(calls).toEqual(["incidents", "alerts", "incidents", "incidents"]);
});

it("AR-W-02 every query of the screen is invalidated on a reconnection, not on the first open", () => {
  const source = sources[0];
  source.open();
  expect(calls).toEqual([]);
  source.fail(false);
  source.open();
  expect(calls).toEqual(["*"]);
});

it("AR-W-02 a stream closed by an error answer is opened again after 1 s, and again while it fails", () => {
  sources[0].open();
  sources[0].fail(true);
  expect(sources[0].closed).toBe(true);
  vi.advanceTimersByTime(999);
  expect(sources).toHaveLength(1);
  vi.advanceTimersByTime(1);
  expect(sources).toHaveLength(2);
  sources[1].fail(true);
  vi.advanceTimersByTime(1000);
  expect(sources).toHaveLength(3);
  sources[2].open();
  expect(calls).toEqual(["*"]);
  sources[0].change("incidents");
  expect(calls).toEqual(["*"]);
});

it("DC-U-09 the interrupted line after more than 5 s down, removed on open", () => {
  sources[0].open();
  sources[0].fail(false);
  vi.advanceTimersByTime(4999);
  expect(interrupted).toEqual([]);
  vi.advanceTimersByTime(2);
  expect(interrupted).toEqual([true]);
  sources[0].open();
  expect(interrupted).toEqual([true, false]);
});

it("DC-U-09 a stream down for less than 5 s shows no interrupted line", () => {
  sources[0].open();
  sources[0].fail(true);
  vi.advanceTimersByTime(1000);
  sources[1].open();
  vi.advanceTimersByTime(10_000);
  expect(interrupted).toEqual([]);
});
