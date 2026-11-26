// DC-S10 Dashboard (SL-A4-10), for `view`: the layout's topic `tenant` only and no live-interrupted line (the dashboard
// is not a screen of DC-U-08's list); AR-I-80 is read when the screen opens and on "Refresh" or "Try again".
import { useQuery } from "@tanstack/react-query";
import { reasonOf } from "../api/client";
import { SignedInLayout } from "../layout/SignedInLayout";
import { DashboardView } from "./DashboardView";
import { dashboardQuery, TOPICS } from "./queries";

export function DashboardPage() {
  return (
    <SignedInLayout
      access="view"
      screen="Dashboard"
      topics={TOPICS}
      live={false}
    >
      <DashboardContent />
    </SignedInLayout>
  );
}

function DashboardContent() {
  const dashboard = useQuery(dashboardQuery());
  return (
    <DashboardView
      data={dashboard.data}
      fetching={dashboard.isFetching}
      failure={dashboard.isError ? reasonOf(dashboard.error) : null}
      onRefresh={() => void dashboard.refetch()}
    />
  );
}
