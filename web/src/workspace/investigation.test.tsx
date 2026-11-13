import { expect, it } from "vitest";
import { at, investigation } from "../test/fixtures";
import { render, texts } from "../test/render";
import { InvestigationContent } from "./Investigation";

const lines = (over: Parameters<typeof investigation>[0] = {}) =>
  texts(render(<InvestigationContent section={investigation(over)} />), "p");
const result = investigation().result!;

it("DC-S03-09 the result lines in order", () => {
  expect(lines()).toEqual([
    "AI analysis",
    "Likely cause: Archive catch-up filled /data on the Subscriber DB server.",
    "Hypothesis: Confirms",
    "Evidence used:",
    "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
    "Still uncertain: Whether the database is read-only.",
    "Runbooks given: RB-API-01, RB-DB-01, RB-USSD-01",
    "Runbooks cited: RB-DB-01, RB-USSD-01",
    "Provider: Claude — claude-sonnet-5-5",
    "10:01:30 · From the workflow",
    "Summary (AI): Subscriber DB /data is full.",
  ]);
});

it("DC-S03-09 the unavailable warning, no result, a re-run, no verdict, the summary sources and No summary yet.", () => {
  expect(
    lines({
      ai_status: "unavailable",
      ai_unavailable_reason: "no_provider_reachable",
      ai_status_changed_at: at("10:01:20"),
      result: null,
      summary: { text: "Template summary.", source: "template" },
    }),
  ).toEqual([
    "AI analysis",
    "AI investigation unavailable — no allowed AI provider was reachable. 10:01:20",
    "Summary (template): Template summary.",
  ]);
  expect(lines({ ai_status: "not_run", result: null, summary: null })).toEqual([
    "AI analysis",
    "No AI investigation yet.",
    "No summary yet.",
  ]);
  const rerun = lines({
    result: {
      ...result,
      origin: "rerun",
      requested_by: { user_id: "u", display_name: "Admin (Demo)" },
      verdict: null,
      likely_cause: null,
      uncertainty: null,
      runbooks_supplied: [],
      runbooks_cited: [],
      provider_kind: null,
      model_name: "qwen3",
    },
  });
  expect(rerun).toEqual([
    "AI analysis",
    "Likely cause: ",
    "Evidence used:",
    "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)",
    "Still uncertain: ",
    "Runbooks given: none",
    "Runbooks cited: none",
    "Provider: qwen3",
    "10:01:30 · Re-run by Admin (Demo)",
    "Summary (AI): Subscriber DB /data is full.",
  ]);
  expect(lines({ result: { ...result, verdict: "disputes" } })).toContain(
    "Hypothesis: Disputes",
  );
});
