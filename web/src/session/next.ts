// The page requested before sign-in (DC-U-13, P-28 spec Section 6.3 item 3).

/** `next` when, decoded, it starts with "/" and its second character is neither "/" nor "\"; else null. */
export function validNext(next: string | null): string | null {
  if (next === null || !next.startsWith("/")) {
    return null;
  }
  const second = next.charAt(1);
  return second === "/" || second === "\\" ? null : next;
}

/** The address of the login screen that returns to `path` (path and query) after sign-in. */
export function loginAddress(path: string): string {
  return `/login?next=${encodeURIComponent(path)}`;
}
