// Reading the markup of the mobile pages' tests: the text blocks (h1, h2, p and plain li) in document order.
import { textOf } from "./render";

export function blocks(html: string): string[] {
  const found: [number, string][] = [];
  for (const match of html.matchAll(/<(h1|h2|p)\b[^>]*>([\s\S]*?)<\/\1>/g)) {
    found.push([match.index, textOf(match[2])]);
  }
  for (const match of html.matchAll(/<li>([^<]*)<\/li>/g)) {
    found.push([match.index, textOf(match[1])]);
  }
  return found.sort((a, b) => a[0] - b[0]).map(([, text]) => text);
}

/** The texts of the buttons, in order. */
export function buttons(html: string): string[] {
  return [...html.matchAll(/<button\b[^>]*>([\s\S]*?)<\/button>/g)].map(
    (match) => textOf(match[1]),
  );
}
