import { QueryObserver } from "@tanstack/react-query";
import { renderToStaticMarkup } from "react-dom/server";
import { MemoryRouter } from "react-router";
import { afterEach, expect, it, vi } from "vitest";
import type { TenantSettings } from "../api/types";
import { createQueryClient } from "../app/queryClient";
import { LiveConnection, type LiveSource } from "../live/live";
import { liveHandlers } from "../live/useLiveTopics";
import { tenantQuery } from "../session/queries";
import { Header } from "./Header";

const BEFORE: TenantSettings = {
  product_display_name: "Techfinity",
  customer_name: "Jazz",
  has_logo: false,
  environment_label: "Demo environment — fictional data",
};

afterEach(() => {
  vi.unstubAllGlobals();
});

it("DC-U-02 the tenant notice re-reads AR-I-15 and the header shows the new product", async () => {
  const urls: string[] = [];
  let answer: TenantSettings = {
    ...BEFORE,
    product_display_name: "Techfinity NOC",
  };
  vi.stubGlobal("document", { cookie: "" });
  vi.stubGlobal("fetch", (url: string) => {
    urls.push(url);
    return Promise.resolve(
      new Response(JSON.stringify(answer), { status: 200 }),
    );
  });
  const client = createQueryClient();
  client.setQueryData(tenantQuery.queryKey, BEFORE);
  const observer = new QueryObserver(client, {
    ...tenantQuery,
    refetchOnMount: false,
  });
  const unsubscribe = observer.subscribe(() => {});

  let listener: ((event: MessageEvent) => void) | null = null;
  const source: LiveSource = {
    readyState: 1,
    onopen: null,
    onerror: null,
    addEventListener: (_type, l) => (listener = l),
    close: () => {},
  };
  const connection = new LiveConnection(
    ["tenant"],
    liveHandlers(client, ["tenant"], () => {}),
    () => source,
  );
  connection.start();
  expect(urls).toEqual([]);
  listener!(
    new MessageEvent("change", {
      data: JSON.stringify({ topic: "tenant", id: "t", at: "x" }),
    }),
  );
  await vi.waitFor(() =>
    expect(observer.getCurrentResult().data?.product_display_name).toBe(
      "Techfinity NOC",
    ),
  );
  expect(urls).toEqual(["/api/v1/tenant"]);

  const html = renderToStaticMarkup(
    <MemoryRouter initialEntries={["/incidents"]}>
      <Header
        settings={observer.getCurrentResult().data!}
        displayName="Demo Presenter"
        full
        withNavigation
        onSignOut={() => {}}
        signingOut={false}
        signOutFailure={null}
      />
    </MemoryRouter>,
  );
  expect(html).toContain("Techfinity NOC");
  answer = BEFORE;
  connection.stop();
  unsubscribe();
});
