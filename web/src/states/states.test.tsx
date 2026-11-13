import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import {
  ActionFailed,
  Empty,
  LiveInterrupted,
  Loading,
  PageError,
  PartError,
} from "./states";

const text = (html: string) => html.replace(/<[^>]+>/g, "");

it("DC-U-09 each state's text", () => {
  expect(text(renderToStaticMarkup(<Loading />))).toBe("Loading…");
  expect(text(renderToStaticMarkup(<Empty text="No incidents yet." />))).toBe(
    "No incidents yet.",
  );
  expect(
    text(
      renderToStaticMarkup(
        <PageError reason="No connection." onTryAgain={() => {}} />,
      ),
    ),
  ).toBe("Could not load this page. No connection.Try again");
  expect(
    text(
      renderToStaticMarkup(
        <PartError reason="Not found." onTryAgain={() => {}} />,
      ),
    ),
  ).toBe("Could not load this part. Not found.Try again");
  expect(
    text(
      renderToStaticMarkup(
        <ActionFailed action="sign out" reason="No connection." />,
      ),
    ),
  ).toBe("Could not sign out. No connection.");
  expect(text(renderToStaticMarkup(<LiveInterrupted time="09:12:03" />))).toBe(
    "Live updates interrupted. Reconnecting… Last update 09:12:03.",
  );
});

it("DC-U-09 the error states have a Try again button", () => {
  expect(
    renderToStaticMarkup(<PageError reason="x" onTryAgain={() => {}} />),
  ).toContain('<button type="button">Try again</button>');
  expect(
    renderToStaticMarkup(<PartError reason="x" onTryAgain={() => {}} />),
  ).toContain('<button type="button">Try again</button>');
});
