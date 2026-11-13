import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import { ApiFailure } from "../api/client";
import { at, STORM_TITLE, time } from "../test/fixtures";
import { blocks, buttons } from "../test/markup";
import { acknowledgePage, HIRA, STORM_SUMMARY } from "../test/mobile";
import { textOf } from "../test/render";
import { ACKNOWLEDGE_DENIED, AcknowledgeView } from "./AcknowledgePage";
import { failureOf, INITIAL, lineOf, type Local } from "./actionArea";
import styles from "./mobile.module.css";
import { ReadState } from "./parts";
import { acknowledgedText } from "./refusals";

const view = (page = acknowledgePage(), local: Local = INITIAL) =>
  renderToStaticMarkup(
    <AcknowledgeView
      page={page}
      local={local}
      format={time}
      onAcknowledge={() => {}}
    />,
  );

const state = (data: unknown, error: unknown) =>
  textOf(
    renderToStaticMarkup(
      <ReadState
        data={data}
        error={error}
        onTryAgain={() => {}}
        denied={ACKNOWLEDGE_DENIED}
      >
        {() => <p>page</p>}
      </ReadState>,
    ),
  );

const ACKNOWLEDGED: Local = {
  ...INITIAL,
  outcome: {
    text: acknowledgedText({ user: HIRA, at: at("10:07:30") }, time),
    tone: "success",
    refusalKey: "already_acknowledged",
  },
};

it("DC-S04-03 the content in order", () => {
  expect(blocks(view())).toEqual([
    "Acknowledge incident",
    `INC-0002 ${STORM_TITLE}`,
    "Severity: P1",
    "Owner: Database Operations",
    "Detected: 10:00:12",
    "Alerts: 126",
    `Summary: ${STORM_SUMMARY}`,
    "State: Open",
  ]);
  expect(STORM_TITLE).toBe(
    "Subscriber DB server: Disk full — 3 services affected",
  );
  expect(
    blocks(
      view(acknowledgePage({ owner: null, summary: null, severity: null })),
    ),
  ).toContain("Owner: ");
});

it("DC-S04-01 the button with acknowledge, View only. without it, the permission text on 403", () => {
  const actor = view();
  expect(buttons(actor)).toEqual(["Acknowledge"]);
  expect(textOf(actor)).not.toContain("View only.");
  const viewer = view(acknowledgePage({ may_act: false }));
  expect(buttons(viewer)).toEqual([]);
  expect(blocks(viewer).at(-1)).toBe("View only.");
  expect(state(undefined, new ApiFailure(403, "forbidden", "x"))).toBe(
    "You do not have permission to acknowledge incidents.",
  );
});

it("DC-S04-04 the outcome replaces the button and stays with already_acknowledged", () => {
  const done = view(acknowledgePage(), ACKNOWLEDGED);
  expect(buttons(done)).toEqual([]);
  expect(blocks(done).at(-1)).toBe(
    "Acknowledged by Hira Baig (NOC L1) at 10:07:30.",
  );
  expect(done).toContain(`class="${styles.success}"`);
  const reread = view(
    acknowledgePage({
      acknowledged: { user: HIRA, at: at("10:07:30") },
      refusal: { key: "already_acknowledged", message: "Already … UTC." },
    }),
    ACKNOWLEDGED,
  );
  expect(blocks(reread).at(-1)).toBe(
    "Acknowledged by Hira Baig (NOC L1) at 10:07:30.",
  );
  const resolved = view(
    acknowledgePage({
      state: "resolved",
      acknowledged: { user: HIRA, at: at("10:07:30") },
      refusal: {
        key: "not_open",
        message: "This incident is resolved. It does not need acknowledgement.",
      },
    }),
    ACKNOWLEDGED,
  );
  expect(blocks(resolved).at(-1)).toBe(
    "This incident is resolved. It does not need acknowledgement.",
  );
});

it("DC-S04-06 a re-read with a refusal replaces the button", () => {
  const html = view(
    acknowledgePage({
      acknowledged: { user: HIRA, at: at("10:07:30") },
      refusal: {
        key: "already_acknowledged",
        message:
          "Already acknowledged by Hira Baig (NOC L1) at 2026-10-10 10:07:30 UTC.",
      },
    }),
  );
  expect(buttons(html)).toEqual([]);
  expect(blocks(html).at(-1)).toBe(
    "Already acknowledged by Hira Baig (NOC L1) at 10:07:30.",
  );
  expect(html).toContain(`class="${styles.warning}"`);
});

it("DC-U-09 Loading, Could not load this page. Not found., Could not acknowledge. {reason}", () => {
  expect(state(undefined, null)).toBe("Loading…");
  expect(state(undefined, new ApiFailure(404, "not_found", "Not found."))).toBe(
    "Could not load this page. Not found.Try again",
  );
  expect(
    state(acknowledgePage(), new ApiFailure(0, null, "No connection.")),
  ).toBe("Could not load this page. No connection.Try again");
  const line = lineOf(
    failureOf(new ApiFailure(0, null, "No connection.")),
    "acknowledge",
  );
  expect(line).toBe("Could not acknowledge. No connection.");
  const html = view(acknowledgePage(), { ...INITIAL, line });
  expect(buttons(html)).toEqual(["Acknowledge"]);
  expect(blocks(html).at(-1)).toBe("Could not acknowledge. No connection.");
});

it("DC-U-05 You do not have permission to do this. on a 403 of the action", () => {
  const line = lineOf(
    failureOf(
      new ApiFailure(
        403,
        "forbidden",
        "You do not have permission to do this.",
      ),
    ),
    "acknowledge",
  );
  expect(line).toBe("You do not have permission to do this.");
  const html = view(acknowledgePage(), { ...INITIAL, line });
  expect(buttons(html)).toEqual(["Acknowledge"]);
  expect(blocks(html).at(-1)).toBe("You do not have permission to do this.");
  expect(html).toContain(`class="${styles.failed}"`);
});
