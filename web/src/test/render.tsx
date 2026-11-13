// Server rendering for the workspace's markup tests (CTX-D-085 (12)): times at the fixtures' clock in UTC.
import type { ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { TimeContext } from "../workspace/lines";
import { time } from "./fixtures";

export function render(node: ReactNode): string {
  return renderToStaticMarkup(<TimeContext value={time}>{node}</TimeContext>);
}

const ENTITIES: Record<string, string> = {
  "&amp;": "&",
  "&lt;": "<",
  "&gt;": ">",
  "&quot;": '"',
  "&#x27;": "'",
};

/** The text of markup: tags removed, entities decoded. */
export function textOf(html: string): string {
  return html
    .replace(/<[^>]+>/g, "")
    .replace(/&(amp|lt|gt|quot|#x27);/g, (entity) => ENTITIES[entity]);
}

/** The texts of the elements whose opening tag matches `open` (no nesting of the same tag inside). */
export function texts(html: string, tag: string, attribute = ""): string[] {
  const pattern = new RegExp(
    `<${tag}(?: [^>]*)?${attribute}[^>]*>([\\s\\S]*?)</${tag}>`,
    "g",
  );
  return [...html.matchAll(pattern)].map((match) => textOf(match[1]));
}
