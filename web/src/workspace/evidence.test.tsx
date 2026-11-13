import { expect, it } from "vitest";
import type { EvidenceLine } from "../api/types";
import tags from "../tags/tags.module.css";
import {
  evidence,
  failed,
  header,
  loading,
  stormParts,
  answered,
} from "../test/fixtures";
import { render, texts } from "../test/render";
import { EvidenceContent, evidenceWord, valueText } from "./Evidence";
import { mainSections } from "./WorkspaceView";

const closed = { isOpen: () => false, toggle: () => {} };
const open = { isOpen: () => true, toggle: () => {} };
const kinds = (html: string) =>
  [
    ...html.matchAll(
      new RegExp(
        `<span class="${tags.tag}" data-kind="([^"]+)">([^<]*)</span>`,
        "g",
      ),
    ),
  ].map((match) => match[2]);

it("DC-S03-08 one entry per evidence line with its tag, summary, source and time", () => {
  const html = render(<EvidenceContent evidence={evidence()} open={closed} />);
  expect(kinds(html)).toEqual([
    "Observation",
    "Observation",
    "Observation",
    "AI analysis",
    "History match",
    "Approval",
    "Execution result",
    "Verified outcome",
  ]);
  const lines = texts(html, "p");
  expect(lines.slice(0, 3)).toEqual([
    "Observation/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
    "Source: lab-db-01 via runner, operation fs_usage",
    "Collected: 10:01:02",
  ]);
  expect(lines).toHaveLength(24);
  const notRecovered: EvidenceLine = {
    ...evidence().entries[7],
    summary:
      "Not recovered within 5 min: 3 alerts of the incident are still firing.",
  };
  expect(evidenceWord(notRecovered)).toBe("Not recovered");
  expect(evidenceWord(evidence().entries[7])).toBe("Verified outcome");
});

it("DC-S03-08 a diagnostic entry opens its output values", () => {
  const html = render(<EvidenceContent evidence={evidence()} open={open} />);
  expect(texts(html, "button")).toEqual([
    "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
    "Largest folder under /data: /data/txlog_archive (8.5 GiB)",
    "8.5 GiB written to 34 files under /data in the last 15 minutes",
  ]);
  const values = texts(html, "div");
  expect(values[0]).toBe("used_pct: 100used_gib: 15.9mount: /data");
  expect(
    render(<EvidenceContent evidence={evidence()} open={closed} />),
  ).not.toContain("used_pct");
  expect(valueText("/data")).toBe("/data");
  expect(valueText(12884901888)).toBe("12884901888");
  expect(valueText(true)).toBe("true");
  expect(valueText(["a", 1])).toBe('["a",1]');
});

it("DC-S03-08 Evidence (n), Evidence while unknown, No evidence yet.", () => {
  const heading = (part: Parameters<typeof stormParts>[0]) =>
    mainSections(header(), stormParts(part))[2].heading;
  expect(heading({})).toBe("Evidence (8)");
  expect(heading({ evidence: loading() })).toBe("Evidence");
  expect(heading({ evidence: failed() })).toBe("Evidence");
  expect(heading({ evidence: answered({ entries: [] }) })).toBe("Evidence (0)");
  expect(
    texts(
      render(<EvidenceContent evidence={{ entries: [] }} open={closed} />),
      "p",
    ),
  ).toEqual(["No evidence yet."]);
});

it("DC-U-10 each evidence kind has its tag", () => {
  const word = (kind: EvidenceLine["kind"]) =>
    evidenceWord({ ...evidence().entries[0], kind, summary: "x" });
  expect(
    (
      [
        "diagnostic_result",
        "ai_investigation",
        "similarity_match",
        "approval",
        "action_execution",
        "recovery_verification",
      ] as const
    ).map(word),
  ).toEqual([
    "Observation",
    "AI analysis",
    "History match",
    "Approval",
    "Execution result",
    "Verified outcome",
  ]);
});

it("DC-TX-03 the stored summaries and sources are shown unchanged", () => {
  const html = render(<EvidenceContent evidence={evidence()} open={closed} />);
  const lines = texts(html, "p");
  for (const entry of evidence().entries) {
    expect(lines.some((line) => line.endsWith(entry.summary))).toBe(true);
    expect(lines).toContain(`Source: ${entry.source_system}`);
  }
});
