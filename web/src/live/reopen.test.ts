import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { LiveConnection, type LiveSource } from "./live";

class Source implements LiveSource {
  readyState = 0;
  onopen: ((event: Event) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  addEventListener() {}
  close() {
    this.readyState = 2;
  }
  open() {
    this.readyState = 1;
    this.onopen?.(new Event("open"));
  }
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

it("AR-W-02 a stream opened because the topics changed re-reads on its first open", () => {
  const calls: string[] = [];
  const handlers = {
    invalidate: (topic: string) => calls.push(topic),
    invalidateAll: () => calls.push("*"),
    interrupted: () => {},
  };
  const sources: Source[] = [];
  const factory = () => {
    const source = new Source();
    sources.push(source);
    return source;
  };
  const first = new LiveConnection(["alerts", "tenant"], handlers, factory);
  first.start();
  sources[0].open();
  expect(calls).toEqual([]);
  first.stop();
  const changed = new LiveConnection(
    ["incident:i-1", "alerts", "tenant"],
    handlers,
    factory,
    true,
  );
  changed.start();
  sources[1].open();
  expect(calls).toEqual(["*"]);
  sources[1].open();
  expect(calls).toEqual(["*", "*"]);
  changed.stop();
});
