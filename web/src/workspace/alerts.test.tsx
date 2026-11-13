import { expect, it } from "vitest";
import { alerts } from "../test/fixtures";
import { render, textOf, texts } from "../test/render";
import { AlertsContent } from "./Alerts";
import styles from "./workspace.module.css";

const closed = { isOpen: () => false, toggle: () => {} };
const open = { isOpen: () => true, toggle: () => {} };

it("DC-S03-05 the heading, the counts and the groups closed", () => {
  const html = render(
    <AlertsContent alerts={alerts()} groups={closed} reasons={closed} />,
  );
  expect(texts(html, "p")).toEqual(["Firing 120 · Resolved 6"]);
  expect(texts(html, "button")).toEqual([
    "API error rate high (2)",
    "Disk full (1)",
  ]);
  expect(textOf(html)).not.toContain("Why grouped");
});

it("DC-S03-05 an open group lists its alert lines with Why grouped", () => {
  const lines = texts(
    render(<AlertsContent alerts={alerts()} groups={open} reasons={closed} />),
    "div",
    ` class="${styles.alert}"`,
  );
  expect(lines).toEqual([
    "10:00:26AlertmanagerBalance-check API error rate above 5 %Balance-check APIFiringWhy grouped",
    "10:00:40AlertmanagerUnknown job error rate above 5 %Unknown componentResolvedWhy grouped",
    "10:00:12ZabbixDisk full: /data free space below 1 GiBSubscriber DB serverWhy grouped",
  ]);
  const reasons = texts(
    render(<AlertsContent alerts={alerts()} groups={open} reasons={open} />),
    "p",
    ` class="${styles.box}"`,
  );
  expect(reasons[0]).toBe(
    "Depends on the component of 'Disk full: /data free space below 1 GiB' (Zabbix): Balance-check API → Subscriber database → Subscriber DB server. Fired 14 s apart, within the 5 min window.",
  );
  expect(reasons[2]).toBe("The only alert of this incident.");
});

it("VD-C-20 the disclosure carries aria-expanded", () => {
  const shut = render(
    <AlertsContent alerts={alerts()} groups={closed} reasons={closed} />,
  );
  expect(shut.match(/aria-expanded="false"/g)).toHaveLength(2);
  const opened = render(
    <AlertsContent alerts={alerts()} groups={open} reasons={closed} />,
  );
  expect(
    opened.match(/<button type="button" class="[^"]*" aria-expanded="true">/g),
  ).toHaveLength(2);
  expect(
    opened.match(
      new RegExp(
        `class="${styles.why}" aria-expanded="false">Why grouped`,
        "g",
      ),
    ),
  ).toHaveLength(3);
});

it("VD-R-02 the chevron carries no text and is hidden", () => {
  const html = render(
    <AlertsContent alerts={alerts()} groups={closed} reasons={closed} />,
  );
  const chevrons = [
    ...html.matchAll(
      new RegExp(`<span class="${styles.chevron}"([^>]*)>(.*?)</span>`, "g"),
    ),
  ];
  expect(chevrons).toHaveLength(2);
  for (const chevron of chevrons) {
    expect(chevron[1]).toBe(' aria-hidden="true"');
    expect(chevron[2]).toBe("");
  }
});
