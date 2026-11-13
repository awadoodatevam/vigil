// The layout of the mobile pages (P-30 spec Section 6.2 items 3 and 4; DC-S04-02, DC-S05-02, VD-L-06, VD-C-24): the
// session handling of the desktop pages without their permission redirects (CTX-D-091 (1)); the banner; the mobile bar
// with {product}, the customer name, the display name and "Sign out"; the live-interrupted line under the bar; the page.
import { useQuery, type QueryKey } from "@tanstack/react-query";
import { useCallback, useRef, type ReactNode } from "react";
import { ApiFailure, reasonOf } from "../api/client";
import type { TenantSettings } from "../api/types";
import { titleOf, useTitle } from "../app/title";
import { useLiveTopics } from "../live/useLiveTopics";
import { brandingQuery, sessionQuery, tenantQuery } from "../session/queries";
import { ActionFailed, Loading, PageError } from "../states/states";
import { Banner } from "./Banner";
import styles from "./layout.module.css";
import mobile from "./mobile.module.css";
import { useSessionEnd, useSignOut } from "./session";
import { InterruptedLine } from "./SignedInLayout";

/** The screen names of the mobile pages' tab titles (DC-U-15). */
export type MobileScreen = "Acknowledge" | "Approve";

export interface MobileLayoutProps {
  screen: MobileScreen;
  /** The page's live topics besides `tenant`. */
  topics: readonly string[];
  /** The page's query, whose latest successful read is the interrupted line's "Last update" (CTX-D-085 (6)). */
  queryKey: QueryKey;
  children: ReactNode;
}

export function MobileLayout(props: MobileLayoutProps) {
  const branding = useQuery(brandingQuery);
  const session = useQuery(sessionQuery);
  const leaving = useRef(false);
  const setLeaving = useCallback((value: boolean) => {
    leaving.current = value;
  }, []);
  useSessionEnd(leaving);
  const product = branding.data?.product_display_name ?? null;
  useTitle(titleOf(props.screen, product));

  if (
    session.isPending ||
    (session.isError &&
      session.error instanceof ApiFailure &&
      session.error.status === 401)
  ) {
    return (
      <>
        <Banner label={branding.data?.environment_label} />
        <div className={mobile.content}>
          <Loading />
        </div>
      </>
    );
  }
  if (session.isError) {
    return (
      <>
        <Banner label={branding.data?.environment_label} />
        <div className={mobile.content}>
          <PageError
            reason={reasonOf(session.error)}
            onTryAgain={() => void session.refetch()}
          />
        </div>
      </>
    );
  }
  return (
    <MobileSignedIn
      {...props}
      displayName={session.data.display_name}
      branding={branding.data}
      setLeaving={setLeaving}
    />
  );
}

function MobileSignedIn(
  props: MobileLayoutProps & {
    displayName: string;
    branding: TenantSettings | undefined;
    setLeaving: (value: boolean) => void;
  },
) {
  const tenant = useQuery(tenantQuery);
  const interrupted = useLiveTopics([...props.topics, "tenant"]);
  const { signOut, signingOut, signOutFailure } = useSignOut(props.setLeaving);
  return (
    <MobileView
      settings={tenant.data ?? props.branding}
      displayName={props.displayName}
      onSignOut={() => void signOut()}
      signingOut={signingOut}
      signOutFailure={signOutFailure}
      line={interrupted ? <InterruptedLine keys={[props.queryKey]} /> : null}
    >
      {props.children}
    </MobileView>
  );
}

export interface MobileBarProps {
  settings: TenantSettings;
  displayName: string;
  onSignOut: () => void;
  signingOut: boolean;
  signOutFailure: string | null;
}

/** The mobile bar (VD-C-24): {product}, the customer name (never the logo), the display name and "Sign out". */
export function MobileBar(props: MobileBarProps) {
  return (
    <header className={mobile.bar}>
      <span className={styles.product}>
        {props.settings.product_display_name}
      </span>
      <span className={styles.customerHeader}>
        {props.settings.customer_name}
      </span>
      <span className={mobile.user}>
        <span className={mobile.name}>{props.displayName}</span>
        <span className={styles.signOut}>
          <button
            type="button"
            className={`${styles.ghost} ${mobile.signOutButton}`}
            onClick={props.onSignOut}
            disabled={props.signingOut}
          >
            Sign out
          </button>
          {props.signOutFailure !== null && (
            <ActionFailed action="sign out" reason={props.signOutFailure} />
          )}
        </span>
      </span>
    </header>
  );
}

/** The mobile page (VD-L-06): the banner, the bar, the interrupted line when shown, then the page in one column. */
export function MobileView({
  settings,
  line,
  children,
  ...bar
}: Omit<MobileBarProps, "settings"> & {
  settings: TenantSettings | undefined;
  line: ReactNode;
  children: ReactNode;
}) {
  return (
    <>
      <Banner label={settings?.environment_label} />
      {settings && <MobileBar settings={settings} {...bar} />}
      {line}
      <main className={mobile.content}>{children}</main>
    </>
  );
}
