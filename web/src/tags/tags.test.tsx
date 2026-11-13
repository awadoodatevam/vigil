import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { KindTag, type KindWord } from "./KindTag";
import styles from "./tags.module.css";

const WORDS: KindWord[] = [
  "Hypothesis",
  "Observation",
  "AI analysis",
  "History match",
  "Proposed action",
  "Approval",
  "Execution result",
  "Verified outcome",
  "Not recovered",
  "Test",
  "Illustrative baseline",
];

// The data-kind of each word (P-44 spec Section 6.7).
const KINDS: Record<KindWord, string> = {
  Hypothesis: "hypothesis",
  Observation: "observation",
  "AI analysis": "ai-analysis",
  "History match": "history-match",
  "Proposed action": "proposed-action",
  Approval: "approval",
  "Execution result": "execution-result",
  "Verified outcome": "verified-outcome",
  "Not recovered": "not-recovered",
  Test: "test",
  "Illustrative baseline": "illustrative-baseline",
};

it("DC-U-10 every word of the complete list and a record label are shown as a tag", () => {
  for (const word of WORDS) {
    expect(renderToStaticMarkup(<KindTag word={word} />)).toBe(
      `<span class="${styles.tag}" data-kind="${KINDS[word]}">${word}</span>`,
    );
  }
  expect(renderToStaticMarkup(<KindTag recordLabel="Fictional record" />)).toBe(
    `<span class="${styles.tag}" data-kind="record">Fictional record</span>`,
  );
});

it("PM-M24-05 the kind tag is one small visible component for every kind", () => {
  const classes = new Set(
    [
      ...WORDS.map((word) => <KindTag word={word} />),
      <KindTag recordLabel="Fictional record" />,
    ].map((tag) => /class="([^"]*)"/.exec(renderToStaticMarkup(tag))?.[1]),
  );
  expect(classes).toEqual(new Set([styles.tag]));
  expect(styles.tag).toBeTruthy();
});

it("VD-C-16 each word has its data-kind and one class for every tag", () => {
  const kinds = [
    ...WORDS.map((word) => <KindTag word={word} />),
    <KindTag recordLabel="Fictional record" />,
  ].map((tag) => {
    const html = renderToStaticMarkup(tag);
    expect(/class="([^"]*)"/.exec(html)?.[1]).toBe(styles.tag);
    return /data-kind="([^"]*)"/.exec(html)?.[1];
  });
  expect(kinds).toEqual([
    "hypothesis",
    "observation",
    "ai-analysis",
    "history-match",
    "proposed-action",
    "approval",
    "execution-result",
    "verified-outcome",
    "not-recovered",
    "test",
    "illustrative-baseline",
    "record",
  ]);
});
