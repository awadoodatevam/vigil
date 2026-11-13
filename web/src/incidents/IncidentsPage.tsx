// DC-S02 Incident list (SL-A4-02), for `view`: topics incidents, alerts, sources and tenant (AR-I-04).
import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useNavigate } from "react-router";
import { reasonOf } from "../api/client";
import { SignedInLayout } from "../layout/SignedInLayout";
import { brandingQuery, tenantQuery } from "../session/queries";
import { IncidentsView } from "./IncidentsView";
import { incidentsQuery, stripQuery } from "./queries";

const TOPICS = ["incidents", "alerts", "sources"] as const;

export function IncidentsPage() {
  return (
    <SignedInLayout access="view" screen="Incidents" topics={TOPICS} live>
      <Incidents />
    </SignedInLayout>
  );
}

function Incidents() {
  const navigate = useNavigate();
  const branding = useQuery(brandingQuery);
  const tenant = useQuery(tenantQuery);
  const strip = useQuery(stripQuery());
  const rows = useInfiniteQuery(incidentsQuery());
  const product = (tenant.data ?? branding.data)?.product_display_name ?? "";
  const rowsFailed = rows.isError && !rows.isFetchNextPageError;
  return (
    <IncidentsView
      product={product}
      strip={{
        data: strip.data,
        failure: strip.isError ? reasonOf(strip.error) : null,
      }}
      rows={{
        rows: rows.data?.pages.flatMap((page) => page.items),
        hasMore: rows.hasNextPage,
        failure: rowsFailed ? reasonOf(rows.error) : null,
        moreFailure: rows.isFetchNextPageError ? reasonOf(rows.error) : null,
        loadingMore: rows.isFetchingNextPage,
      }}
      onTryAgainStrip={() => void strip.refetch()}
      onTryAgainRows={() => void rows.refetch()}
      onShowMore={() => void rows.fetchNextPage()}
      onOpen={(incidentId) => void navigate(`/incidents/${incidentId}`)}
    />
  );
}
