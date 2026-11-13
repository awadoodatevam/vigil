import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { NOW, strip } from "./fixtures";
import styles from "./incidents.module.css";
import { STRIP_INTERVAL_MS, stripQuery } from "./queries";
import { Strip } from "./Strip";

const render = (value: ReturnType<typeof strip>) =>
  renderToStaticMarkup(<Strip strip={value} now={NOW} zone="UTC" />);
const text = (html: string) =>
  html.replace(/<[^>]+>/g, "|").replace(/\|+/g, "|");

function latest(n: number) {
  return Array.from({ length: n }, (_, i) => ({
    alert_id: `a-${i}`,
    first_received_at: `2026-10-10T09:1${i % 10}:0${i % 10}.000Z`,
    source_kind: (i % 2 === 0 ? "alertmanager" : "zabbix") as
      "alertmanager" | "zabbix",
    alert_type_name: "Balance-check API errors",
    title: `Title ${i}`,
  }));
}

describe("Strip", () => {
  it("DC-S02-02 items 1, 3 and 4 without alerts: no grouping item, No alerts in the last 10 minutes.", () => {
    const html = render(strip());
    expect(text(html)).toBe(
      "|Alerts received in the last 10 minutes: 0|Monitoring: 2 of 2 sources healthy|No alerts in the last 10 minutes.|",
    );
    expect(html).not.toContain(styles.warning);
  });

  it("DC-S02-02 the grouping item only while an incident is forming, with alert or alerts and is or are", () => {
    expect(
      text(
        render(
          strip({
            forming: { incidents: 1, alerts: 1 },
            received_last_10_minutes: 1,
            latest: latest(1),
          }),
        ),
      ),
    ).toContain("|Grouping in progress: 1 alert is being grouped|");
    expect(
      text(
        render(
          strip({
            forming: { incidents: 2, alerts: 40 },
            received_last_10_minutes: 40,
            latest: latest(10),
          }),
        ),
      ),
    ).toContain("|Grouping in progress: 40 alerts are being grouped|");
    expect(
      text(render(strip({ forming: { incidents: 0, alerts: 0 } }))),
    ).not.toContain("Grouping");
  });

  it("DC-S02-02 the warning colour and one entry per source that is not healthy", () => {
    const html = render(
      strip({
        sources: {
          healthy: 0,
          total: 2,
          not_healthy: [
            {
              source_key: "lab-alertmanager",
              name: "Lab Alertmanager",
              health: "stale",
            },
            {
              source_key: "lab-zabbix",
              name: "Lab Zabbix",
              health: "disconnected",
            },
          ],
        },
      }),
    );
    expect(html).toContain(
      `<p class="${styles.warning}">Monitoring: 0 of 2 sources healthy`,
    );
    expect(text(html)).toContain(
      "|Monitoring: 0 of 2 sources healthy|Lab Alertmanager: Stale|Lab Zabbix: Disconnected|",
    );
    expect(html).not.toContain("href");
  });

  it("DC-S02-02 the latest alerts in the answer's order: viewer time, source kind, alert type, title", () => {
    const html = render(
      strip({ received_last_10_minutes: 3, latest: latest(3) }),
    );
    expect(text(html)).toContain(
      "|09:10:00|Alertmanager|Balance-check API errors|Title 0|09:11:01|Zabbix|Balance-check API errors|Title 1|09:12:02|Alertmanager|Balance-check API errors|Title 2|",
    );
    expect(html).not.toContain("No alerts in the last 10 minutes.");
  });

  it("AR-TM-09 the strip re-reads every 1 s", () => {
    const options = stripQuery();
    expect(STRIP_INTERVAL_MS).toBe(1000);
    expect(options.refetchInterval).toBe(1000);
    expect(options.refetchIntervalInBackground).toBe(true);
  });

  it("SL-A1-01 the strip during the storm: 127 alerts, the grouping and the latest 10 alerts", () => {
    const html = render(
      strip({
        received_last_10_minutes: 127,
        forming: { incidents: 1, alerts: 126 },
        latest: latest(10),
      }),
    );
    expect(text(html)).toContain(
      "|Alerts received in the last 10 minutes: 127|",
    );
    expect(text(html)).toContain(
      "|Grouping in progress: 126 alerts are being grouped|",
    );
    expect(html.match(/<li>/g)).toHaveLength(10);
  });
});
