// VD-R-02: the decorative elements carry no text and are hidden from assistive technology (P-44 spec Section 7).
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { expect, it } from "vitest";
import { NOW, row, strip } from "../incidents/fixtures";
import incidents from "../incidents/incidents.module.css";
import { Rows } from "../incidents/Rows";
import { Strip } from "../incidents/Strip";
import { Header } from "../layout/Header";
import layout from "../layout/layout.module.css";
import { Empty, Loading } from "../states/states";
import states from "../states/states.module.css";

interface Element {
  tag: string;
  attributes: string;
  inner: string;
}

/** Every element of rendered markup, with its attributes and its inner markup. */
function elements(html: string): Element[] {
  const found: Element[] = [];
  const open: { tag: string; attributes: string; at: number }[] = [];
  for (const match of html.matchAll(/<(\/?)([a-zA-Z0-9]+)([^>]*?)(\/?)>/g)) {
    const [whole, closing, tag, attributes, selfClosing] = match;
    const at = match.index!;
    if (closing) {
      const start = open.pop()!;
      expect(start.tag).toBe(tag);
      found.push({
        tag,
        attributes: start.attributes,
        inner: html.slice(start.at, at),
      });
    } else if (selfClosing || ["img", "input", "br"].includes(tag)) {
      found.push({ tag, attributes, inner: "" });
    } else {
      open.push({ tag, attributes, at: at + whole.length });
    }
  }
  expect(open).toEqual([]);
  return found;
}

const classes = (element: Element) =>
  /class="([^"]*)"/.exec(element.attributes)?.[1].split(" ") ?? [];

it("VD-R-02 decorative elements carry no text and are hidden", () => {
  const html = [
    renderToStaticMarkup(
      <MemoryRouter initialEntries={["/incidents"]}>
        <Header
          settings={{
            product_display_name: "Techfinity",
            customer_name: "Jazz",
            has_logo: false,
            environment_label: "Demo environment — fictional data",
          }}
          displayName="Demo Presenter"
          full
          withNavigation
          onSignOut={() => {}}
          signingOut={false}
          signOutFailure={null}
        />
      </MemoryRouter>,
    ),
    renderToStaticMarkup(
      <Strip
        strip={strip({
          received_last_10_minutes: 3,
          forming: { incidents: 1, alerts: 3 },
          sources: {
            healthy: 1,
            total: 2,
            not_healthy: [
              { source_key: "lab-zabbix", name: "Lab Zabbix", health: "stale" },
            ],
          },
        })}
        now={NOW}
        zone="UTC"
      />,
    ),
    renderToStaticMarkup(
      <Rows
        rows={[
          row(1, {
            severity: "P1",
            ticket: {
              key: "NOC-1",
              url: "https://jira.example/browse/NOC-1",
            },
          }),
        ]}
        onOpen={() => {}}
        now={NOW}
        zone="UTC"
      />,
    ),
    renderToStaticMarkup(<Loading />),
    renderToStaticMarkup(<Empty text="No incidents yet." />),
  ].join("");
  const decorative = [
    layout.glyph,
    incidents.pulse,
    incidents.dot,
    states.ring,
    states.spinner,
  ];
  const all = elements(html);
  const found = all.filter(
    (element) =>
      element.tag === "svg" ||
      classes(element).some((name) => decorative.includes(name)),
  );
  for (const name of decorative) {
    expect(
      found.filter((element) => classes(element).includes(name)),
      name,
    ).toHaveLength(1);
  }
  expect(found.filter((element) => element.tag === "svg")).toHaveLength(2);
  for (const element of found) {
    expect(element.attributes, element.attributes).toContain(
      'aria-hidden="true"',
    );
    expect(element.inner.replace(/<[^>]+>/g, "")).toBe("");
  }
});
