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

it("DC-U-10 every word of the complete list and a record label are shown as a tag", () => {
  for (const word of WORDS) {
    expect(renderToStaticMarkup(<KindTag word={word} />)).toBe(
      `<span class="${styles.tag}">${word}</span>`,
    );
  }
  expect(renderToStaticMarkup(<KindTag recordLabel="Fictional record" />)).toBe(
    `<span class="${styles.tag}">Fictional record</span>`,
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
