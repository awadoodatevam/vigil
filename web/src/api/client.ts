// The API client (P-28 spec Section 6.2, 6.4): a wrapper of fetch typed with the generated paths. Every request is
// same-origin under /api/v1; POST, PUT and DELETE carry X-Vigil-CSRF (AR-S-03); a request is aborted after 15 s;
// every failure becomes an ApiFailure {status, code, reason} (DC-U-09, AR-W-02).
import type { paths } from "./generated/vigil-api";
import type { ErrorCode } from "./types";

export const TIMEOUT_MS = 15_000;
export const NO_CONNECTION = "No connection.";
export const SOMETHING_WENT_WRONG = "Something went wrong. Try again.";

/** The default text of each code of AR-I-03 (BP-A-20); `starting` has none, so Section 6.4 gives it one. */
const DEFAULT_MESSAGES: Record<ErrorCode, string> = {
  bad_request: "The request is not valid.",
  session_ended: "Your session ended. Sign in again.",
  not_signed_in: "You are not signed in.",
  bad_token: "The token is not valid.",
  forbidden: "You do not have permission to do this.",
  not_found: "Not found.",
  unknown_host: "This address is not set up.",
  conflict: "This was changed meanwhile. Reload and try again.",
  too_large: "The request is too large.",
  refused: "The request was refused.",
  invalid: "The request has problems.",
  internal_error: "Something went wrong. Try again.",
  dependency_failed: "A service is not available. Try again later.",
  starting: "A service is not available. Try again later.",
};

/** A failed request: status 0 when no answer arrived; code null for an answer not in the AR-I-03 format. */
export class ApiFailure extends Error {
  readonly status: number;
  readonly code: ErrorCode | null;
  readonly reason: string;
  /** The error answer's `details` (AR-I-03), or null. */
  readonly details: Record<string, unknown> | null;

  constructor(
    status: number,
    code: ErrorCode | null,
    reason: string,
    details: Record<string, unknown> | null = null,
  ) {
    super(reason);
    this.name = "ApiFailure";
    this.status = status;
    this.code = code;
    this.reason = reason;
    this.details = details;
  }
}

export interface ClientDeps {
  fetch: typeof fetch;
  /** The document's cookies, as `document.cookie` gives them. */
  cookies: () => string;
}

const browserDeps: ClientDeps = {
  fetch: (input, init) => fetch(input, init),
  cookies: () => document.cookie,
};

type Method = "get" | "post" | "put" | "delete";
type PathsWith<M extends Method> = {
  [P in keyof paths]: paths[P][M] extends { responses: unknown } ? P : never;
}[keyof paths];
type Operation<P extends keyof paths, M extends Method> = paths[P][M];
type Json<T> = T extends { content: { "application/json": infer B } }
  ? B
  : undefined;

/** The body of the operation's success answer (200, else 201, else 204 without a body). */
export type Answer<P extends keyof paths, M extends Method> =
  Operation<P, M> extends { responses: { 200: infer R } }
    ? Json<R>
    : Operation<P, M> extends { responses: { 201: infer R } }
      ? Json<R>
      : undefined;
type Query<P extends keyof paths, M extends Method> =
  Operation<P, M> extends { parameters: { query?: infer Q } } ? Q : never;
type Body<P extends keyof paths, M extends Method> =
  Operation<P, M> extends {
    requestBody: { content: { "application/json": infer B } };
  }
    ? B
    : never;

export interface RequestOptions<Q, B> {
  /** The path parameters: each `{name}` of the path becomes `encodeURIComponent(params[name])`. */
  params?: Record<string, string>;
  query?: Q;
  body?: B;
  signal?: AbortSignal;
  deps?: ClientDeps;
}

/** The value of the cookie vigil_csrf, or the empty text. */
export function csrfToken(cookies: string): string {
  for (const part of cookies.split(";")) {
    const [name, ...value] = part.trim().split("=");
    if (name === "vigil_csrf") {
      return value.join("=");
    }
  }
  return "";
}

/** The reason of an answer that is not a success (Section 6.4). */
export async function failureOf(response: Response): Promise<ApiFailure> {
  let body: unknown;
  try {
    body = await response.json();
  } catch {
    body = null;
  }
  const error = (
    body as {
      error?: { code?: unknown; message?: unknown; details?: unknown };
    } | null
  )?.error;
  const code = typeof error?.code === "string" ? error.code : null;
  if (error && code !== null && Object.hasOwn(DEFAULT_MESSAGES, code)) {
    const known = code as ErrorCode;
    const message =
      typeof error.message === "string" && error.message !== ""
        ? error.message
        : DEFAULT_MESSAGES[known];
    const details =
      typeof error.details === "object" &&
      error.details !== null &&
      !Array.isArray(error.details)
        ? (error.details as Record<string, unknown>)
        : null;
    return new ApiFailure(response.status, known, message, details);
  }
  return new ApiFailure(response.status, null, SOMETHING_WENT_WRONG);
}

async function send(
  method: Method,
  path: string,
  options: RequestOptions<unknown, unknown>,
): Promise<unknown> {
  const deps = options.deps ?? browserDeps;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  const outer = options.signal;
  const onOuterAbort = () => controller.abort();
  outer?.addEventListener("abort", onOuterAbort);
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "get") {
    headers["X-Vigil-CSRF"] = csrfToken(deps.cookies());
  }
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  let url = path.replace(/\{(\w+)\}/g, (_, name: string) =>
    encodeURIComponent(options.params?.[name] ?? ""),
  );
  const base = url;
  const query = options.query as Record<string, string | undefined> | undefined;
  if (query) {
    const parameters = new URLSearchParams();
    for (const [name, value] of Object.entries(query)) {
      if (value !== undefined) {
        parameters.set(name, value);
      }
    }
    const text = parameters.toString();
    if (text !== "") {
      url = `${base}?${text}`;
    }
  }
  try {
    let response: Response;
    try {
      response = await deps.fetch(url, {
        method: method.toUpperCase(),
        headers,
        credentials: "same-origin",
        body:
          options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: controller.signal,
      });
    } catch (error) {
      if (outer?.aborted) {
        throw error;
      }
      throw new ApiFailure(0, null, NO_CONNECTION);
    }
    if (!response.ok) {
      throw await failureOf(response);
    }
    if (response.status === 204) {
      return undefined;
    }
    try {
      return await response.json();
    } catch (error) {
      if (outer?.aborted) {
        throw error;
      }
      if (controller.signal.aborted) {
        throw new ApiFailure(0, null, NO_CONNECTION);
      }
      throw new ApiFailure(response.status, null, SOMETHING_WENT_WRONG);
    }
  } finally {
    clearTimeout(timer);
    outer?.removeEventListener("abort", onOuterAbort);
  }
}

export function apiGet<P extends PathsWith<"get">>(
  path: P,
  options: RequestOptions<Query<P, "get">, never> = {},
): Promise<Answer<P, "get">> {
  return send("get", path, options) as Promise<Answer<P, "get">>;
}

export function apiPost<P extends PathsWith<"post">>(
  path: P,
  options: RequestOptions<Query<P, "post">, Body<P, "post">> = {},
): Promise<Answer<P, "post">> {
  return send("post", path, options) as Promise<Answer<P, "post">>;
}

export function apiDelete<P extends PathsWith<"delete">>(
  path: P,
  options: RequestOptions<Query<P, "delete">, never> = {},
): Promise<Answer<P, "delete">> {
  return send("delete", path, options) as Promise<Answer<P, "delete">>;
}

/** The `{reason}` of any failure (DC-U-09). */
export function reasonOf(error: unknown): string {
  return error instanceof ApiFailure ? error.reason : SOMETHING_WENT_WRONG;
}
