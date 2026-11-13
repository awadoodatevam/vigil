// The live update client (AR-I-04, AR-W-02, P-28 spec Section 6.8): one stream per screen; each topic's queries are
// invalidated at most once per 500 ms; every query of the screen on a reconnection; a stream closed by an error answer
// is opened again after 1 s; the screen is told when the stream has been down for more than 5 s. A stream opened because
// the screen's topics changed re-reads every query of its topics on its first open too (P-29 spec Section 6.2 item 4).

export const THROTTLE_MS = 500;
export const REOPEN_MS = 1000;
export const INTERRUPTED_MS = 5000;
const CLOSED = 2;

/** The part of EventSource the client uses. */
export interface LiveSource {
  readonly readyState: number;
  onopen: ((event: Event) => void) | null;
  onerror: ((event: Event) => void) | null;
  addEventListener(
    type: "change",
    listener: (event: MessageEvent) => void,
  ): void;
  close(): void;
}

export type SourceFactory = (url: string) => LiveSource;

export interface LiveHandlers {
  /** Invalidate the queries of one topic. */
  invalidate(topic: string): void;
  /** Invalidate every query of the screen. */
  invalidateAll(): void;
  /** The stream has been down for more than 5 s (true), or has opened again (false). */
  interrupted(value: boolean): void;
}

export const browserSource: SourceFactory = (url) => new EventSource(url);

export function liveUrl(topics: readonly string[]): string {
  return `/api/v1/live?topics=${topics.join(",")}`;
}

interface Window {
  timer: ReturnType<typeof setTimeout>;
  pending: boolean;
}

export class LiveConnection {
  private source: LiveSource | null = null;
  private opened = false;
  private down = false;
  private shown = false;
  private stopped = false;
  private interruptTimer: ReturnType<typeof setTimeout> | null = null;
  private reopenTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly windows = new Map<string, Window>();

  constructor(
    private readonly topics: readonly string[],
    private readonly handlers: LiveHandlers,
    private readonly factory: SourceFactory = browserSource,
    rereadOnFirstOpen = false,
  ) {
    this.opened = rereadOnFirstOpen;
  }

  start(): void {
    this.stopped = false;
    this.open();
  }

  stop(): void {
    this.stopped = true;
    this.source?.close();
    this.source = null;
    for (const window of this.windows.values()) {
      clearTimeout(window.timer);
    }
    this.windows.clear();
    if (this.interruptTimer !== null) clearTimeout(this.interruptTimer);
    if (this.reopenTimer !== null) clearTimeout(this.reopenTimer);
    this.interruptTimer = null;
    this.reopenTimer = null;
  }

  private open(): void {
    const source = this.factory(liveUrl(this.topics));
    this.source = source;
    source.onopen = () => this.onOpen(source);
    source.onerror = () => this.onError(source);
    source.addEventListener("change", (event) => this.onChange(source, event));
  }

  private onOpen(source: LiveSource): void {
    if (this.stopped || source !== this.source) return;
    if (this.opened) {
      this.handlers.invalidateAll();
    }
    this.opened = true;
    if (this.down) {
      this.down = false;
      if (this.interruptTimer !== null) {
        clearTimeout(this.interruptTimer);
        this.interruptTimer = null;
      }
      if (this.shown) {
        this.shown = false;
        this.handlers.interrupted(false);
      }
    }
  }

  private onError(source: LiveSource): void {
    if (this.stopped || source !== this.source) return;
    if (!this.down) {
      this.down = true;
      this.interruptTimer = setTimeout(() => {
        this.interruptTimer = null;
        this.shown = true;
        this.handlers.interrupted(true);
      }, INTERRUPTED_MS);
    }
    if (source.readyState === CLOSED && this.reopenTimer === null) {
      source.close();
      this.reopenTimer = setTimeout(() => {
        this.reopenTimer = null;
        if (!this.stopped) this.open();
      }, REOPEN_MS);
    }
  }

  private onChange(source: LiveSource, event: MessageEvent): void {
    if (this.stopped || source !== this.source) return;
    let topic: unknown;
    try {
      topic = (JSON.parse(String(event.data)) as { topic?: unknown }).topic;
    } catch {
      return;
    }
    if (typeof topic === "string") {
      this.notice(topic);
    }
  }

  private notice(topic: string): void {
    const window = this.windows.get(topic);
    if (window) {
      window.pending = true;
      return;
    }
    this.handlers.invalidate(topic);
    this.startWindow(topic);
  }

  private startWindow(topic: string): void {
    const window: Window = {
      pending: false,
      timer: setTimeout(() => {
        this.windows.delete(topic);
        if (window.pending && !this.stopped) {
          this.handlers.invalidate(topic);
          this.startWindow(topic);
        }
      }, THROTTLE_MS),
    };
    this.windows.set(topic, window);
  }
}
