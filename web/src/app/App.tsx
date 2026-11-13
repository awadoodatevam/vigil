// The application: the host's tenant first (AR-I-10). A host with no alias, or any other failure of AR-I-10, shows the
// DC-U-09 error state on every route, without banner, header or sign-in form, and with an empty tab title (AR-B-10,
// CTX-D-085 (13)).
import { useQuery } from "@tanstack/react-query";
import { useRoutes } from "react-router";
import { reasonOf } from "../api/client";
import type { TenantSettings } from "../api/types";
import { brandingQuery } from "../session/queries";
import { Loading, PageError } from "../states/states";
import styles from "./app.module.css";
import { routes } from "./routes";
import { useTitle } from "./title";

export default function App() {
  const branding = useQuery(brandingQuery);
  if (branding.isSuccess) {
    return <Screens branding={branding.data} />;
  }
  return (
    <div className={styles.content}>
      {branding.isError ? (
        <NoTenant
          reason={reasonOf(branding.error)}
          onTryAgain={() => void branding.refetch()}
        />
      ) : (
        <Loading />
      )}
    </div>
  );
}

function NoTenant({
  reason,
  onTryAgain,
}: {
  reason: string;
  onTryAgain: () => void;
}) {
  useTitle("");
  return <PageError reason={reason} onTryAgain={onTryAgain} />;
}

function Screens({ branding }: { branding: TenantSettings }) {
  return useRoutes(routes(branding));
}
