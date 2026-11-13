import { afterEach, beforeEach, expect, it, vi } from "vitest";
import {
  ApiFailure,
  apiDelete,
  apiGet,
  apiPost,
  type ClientDeps,
} from "./client";

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

function deps(
  answer: (url: string, init: RequestInit) => Promise<Response>,
  cookies = "",
): ClientDeps {
  return {
    fetch: (input, init) => answer(String(input), init ?? {}),
    cookies: () => cookies,
  };
}

async function failure(promise: Promise<unknown>): Promise<ApiFailure> {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(ApiFailure);
    return error as ApiFailure;
  }
  throw new Error("no failure");
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

it("AR-W-02 an answer that has not arrived after 15 s gives No connection.", async () => {
  let aborted = false;
  const never = deps(
    (_url, init) =>
      new Promise((_resolve, reject) => {
        init.signal?.addEventListener("abort", () => {
          aborted = true;
          reject(new DOMException("aborted", "AbortError"));
        });
      }),
  );
  const pending = failure(apiGet("/api/v1/alerts/strip", { deps: never }));
  await vi.advanceTimersByTimeAsync(14_999);
  expect(aborted).toBe(false);
  await vi.advanceTimersByTimeAsync(1);
  const result = await pending;
  expect(aborted).toBe(true);
  expect([result.status, result.code, result.reason]).toEqual([
    0,
    null,
    "No connection.",
  ]);
});

it("AR-W-02 a network failure gives No connection.", async () => {
  const down = deps(() => Promise.reject(new TypeError("Failed to fetch")));
  expect((await failure(apiGet("/api/v1/tenant", { deps: down }))).reason).toBe(
    "No connection.",
  );
});

it("AR-W-02 the reason is the message of an answer in the AR-I-03 format", async () => {
  const refused = deps(() =>
    Promise.resolve(
      json(401, {
        error: {
          code: "not_signed_in",
          message: "Username or password is incorrect.",
        },
      }),
    ),
  );
  const result = await failure(
    apiPost("/api/v1/session", {
      body: { username: "presenter", password: "x" },
      deps: refused,
    }),
  );
  expect([result.status, result.code, result.reason]).toEqual([
    401,
    "not_signed_in",
    "Username or password is incorrect.",
  ]);
});

it("AR-W-02 an AR-I-03 answer without a message gives its code's default text, and starting its own", async () => {
  const cases: [number, string, string][] = [
    [404, "not_found", "Not found."],
    [404, "unknown_host", "This address is not set up."],
    [401, "session_ended", "Your session ended. Sign in again."],
    [500, "internal_error", "Something went wrong. Try again."],
    [503, "dependency_failed", "A service is not available. Try again later."],
    [503, "starting", "A service is not available. Try again later."],
  ];
  for (const [status, code, reason] of cases) {
    const answer = deps(() =>
      Promise.resolve(json(status, { error: { code } })),
    );
    const result = await failure(apiGet("/api/v1/tenant", { deps: answer }));
    expect([result.status, result.code, result.reason]).toEqual([
      status,
      code,
      reason,
    ]);
  }
});

it("AR-W-02 any other answer gives Something went wrong. Try again.", async () => {
  const bodies = [
    new Response("<html>Bad gateway</html>", { status: 502 }),
    json(500, { message: "x" }),
    json(400, { error: { code: "no_such_code", message: "x" } }),
    new Response("not json", { status: 200 }),
  ];
  for (const body of bodies) {
    const result = await failure(
      apiGet("/api/v1/tenant", { deps: deps(() => Promise.resolve(body)) }),
    );
    expect([result.code, result.reason]).toEqual([
      null,
      "Something went wrong. Try again.",
    ]);
  }
});

it("DC-U-09 DELETE carries X-Vigil-CSRF equal to the cookie vigil_csrf; GET carries none", async () => {
  const seen: RequestInit[] = [];
  const recorder = deps((_url, init) => {
    seen.push(init);
    return Promise.resolve(new Response(null, { status: 204 }));
  }, "vigil_other=1; vigil_csrf=6f1c2a9e-3b7d-4c55-9a0e-2d4f8e1b7c30");
  await apiDelete("/api/v1/session", { deps: recorder });
  expect(seen[0].method).toBe("DELETE");
  expect(seen[0].credentials).toBe("same-origin");
  expect((seen[0].headers as Record<string, string>)["X-Vigil-CSRF"]).toBe(
    "6f1c2a9e-3b7d-4c55-9a0e-2d4f8e1b7c30",
  );
  seen.length = 0;
  const get = deps((_url, init) => {
    seen.push(init);
    return Promise.resolve(json(200, {}));
  }, "vigil_csrf=abc");
  await apiGet("/api/v1/tenant", { deps: get });
  expect(seen[0].headers).not.toHaveProperty("X-Vigil-CSRF");
});

it("AR-W-02 requests are same-origin under /api/v1 with the query", async () => {
  const urls: string[] = [];
  const recorder = deps((url) => {
    urls.push(url);
    return Promise.resolve(json(200, { items: [], next_cursor: null }));
  });
  await apiGet("/api/v1/incidents", {
    query: { limit: "50", after: "MTI" },
    deps: recorder,
  });
  expect(urls).toEqual(["/api/v1/incidents?limit=50&after=MTI"]);
});
