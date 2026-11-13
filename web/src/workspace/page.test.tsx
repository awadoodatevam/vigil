import { matchRoutes } from "react-router";
import { expect, it } from "vitest";
import { titleOf } from "../app/title";
import { routes } from "../app/routes";
import { failed, header, loading, stormParts } from "../test/fixtures";
import { render, textOf } from "../test/render";
import {
  incidentOf,
  mergedAddress,
  PageBody,
  screenName,
  workspaceTopics,
} from "./WorkspacePage";
import { WorkspaceView } from "./WorkspaceView";

const BRANDING = {
  product_display_name: "Techfinity",
  customer_name: "Jazz",
  has_logo: false,
  environment_label: "Demo environment — fictional data",
};

it("DC-S03-01 a merged incident opens the incident it was merged into", () => {
  const into = "0192f1a2-7b3c-7d4e-8f50-6a7b8c9d0e1f";
  expect(mergedAddress({ merged_into_incident_id: into })).toBe(
    `/incidents/${into}`,
  );
  expect(mergedAddress(header())).toBeNull();
  expect(mergedAddress(undefined)).toBeNull();
  expect(incidentOf({ merged_into_incident_id: into })).toBeUndefined();
});

it("DC-U-09 the workspace shows Loading, the page error and a part error", () => {
  const view = (
    <WorkspaceView
      incident={header()}
      parts={stormParts()}
      product="Techfinity"
      withCanvas={false}
    />
  );
  const loadingPage = render(
    <PageBody incident={undefined} failure={null} onTryAgain={() => {}}>
      {view}
    </PageBody>,
  );
  expect(textOf(loadingPage)).toBe("Loading…");
  const errorPage = render(
    <PageBody incident={header()} failure="Not found." onTryAgain={() => {}}>
      {view}
    </PageBody>,
  );
  expect(textOf(errorPage)).toBe(
    "Could not load this page. Not found.Try again",
  );
  const partial = render(
    <PageBody incident={header()} failure={null} onTryAgain={() => {}}>
      <WorkspaceView
        incident={header()}
        parts={stormParts({
          evidence: failed("No connection."),
          notified: loading(),
        })}
        product="Techfinity"
        withCanvas={false}
      />
    </PageBody>,
  );
  const text = textOf(partial);
  expect(text).toContain(
    "EvidenceCould not load this part. No connection.Try again",
  );
  expect(text).toContain("NotifiedLoading…");
  expect(text).toContain("Alerts (126)");
  expect(text).toContain("AI investigation");
  expect(text).not.toContain("Could not load this page.");
});

it("DC-U-15 the tab title is {number} {title} — {product}", () => {
  const screen = screenName({ data: header(), isSuccess: true });
  expect(titleOf(screen, "Techfinity")).toBe(
    "INC-0002 Subscriber DB server: Disk full — 3 services affected — Techfinity",
  );
  expect(
    titleOf(screenName({ data: undefined, isSuccess: false }), "Techfinity"),
  ).toBe("Techfinity");
  expect(
    titleOf(screenName({ data: header(), isSuccess: false }), "Techfinity"),
  ).toBe("Techfinity");
  expect(
    screenName({ data: { merged_into_incident_id: "x" }, isSuccess: true }),
  ).toBeNull();
});

it("PM-M24-01 the incident workspace screen is the screen of /incidents/:incidentId", () => {
  const matches = matchRoutes(routes(BRANDING), "/incidents/abc");
  expect(matches?.at(-1)?.route.id).toBe("workspace");
  expect(workspaceTopics(undefined, undefined)).toEqual(["alerts"]);
  expect(workspaceTopics("i-1", "r-1")).toEqual([
    "incident:i-1",
    "run:r-1",
    "alerts",
  ]);
});

it("SL-A4-03 the workspace shows its parts and no re-run action", () => {
  const html = render(
    <WorkspaceView
      incident={header()}
      parts={stormParts()}
      product="Techfinity"
      withCanvas={false}
    />,
  );
  const text = textOf(html);
  for (const part of [
    "INC-0002 Subscriber DB server: Disk full — 3 services affected",
    "Hypothesis and customer impact",
    "Subscriber DB server (srv-db-01)",
    "Customer impact:",
    "Alerts (126)",
    "API error rate high (2)",
    "Evidence (8)",
    "AI investigation",
    "Response",
    "Recovery",
    "Workflow",
    "Run steps",
    "Timeline",
    "Notified",
    "Jira NOC-2",
  ]) {
    expect(text, part).toContain(part);
  }
  expect(html).toContain('href="https://jira.example/browse/NOC-2"');
  expect(text).not.toContain("Re-run AI investigation");
  expect(text).not.toContain("AI usage of the latest investigation");
});
