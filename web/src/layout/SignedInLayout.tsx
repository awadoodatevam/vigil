// The layout of the desktop pages after sign-in (P-28 spec Sections 6.3 item 2, 6.5, 6.8): reads AR-I-13 when the page
// opens; any 401 of the page's reads opens the login screen with `next`; sends a user to the page their permissions
// allow; banner, header, the tenant topic and, on the screens of DC-U-08, the interrupted line.
import {
  useIsFetching,
  useQuery,
  useQueryClient,
  type QueryKey,
} from "@tanstack/react-query";
import {
  createContext,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Navigate, useLocation, useNavigate } from "react-router";
import { ApiFailure, apiDelete, reasonOf } from "../api/client";
import type { TenantSettings, User } from "../api/types";
import { titleOf, useTitle } from "../app/title";
import { viewerTime } from "../format/time";
import { lastUpdate, queriesOf, useLiveTopics } from "../live/useLiveTopics";
import { SESSION_ENDED, SIGNED_OUT } from "../session/messages";
import { loginAddress } from "../session/next";
import {
  brandingQuery,
  hasView,
  sessionQuery,
  tenantQuery,
} from "../session/queries";
import { LiveInterrupted, Loading, PageError } from "../states/states";
import { Banner } from "./Banner";
import { Header } from "./Header";
import styles from "./layout.module.css";

export interface SignedInLayoutProps {
  /** "view": the page needs `view`; "no-access": the page of a user without `view`. */
  access: "view" | "no-access";
  /** The screen name of the tab title; null for `{product}` alone. */
  screen: string | null;
  /** The page's live topics besides `tenant`. */
  topics: readonly string[];
  /** Whether the page shows the interrupted line (DC-U-08). */
  live: boolean;
  children: ReactNode;
}

/** Whether the live-interrupted line is shown under the header (P-29 spec Section 6.6 item 8). */
export const InterruptedContext = createContext(false);

/** Opens the login screen on the first 401 of any read while the page is shown (CTX-D-085 (3)). */
function useSessionEnd(leaving: { current: boolean }) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const location = useLocation();
  const path = location.pathname + location.search;
  useEffect(() => {
    let done = false;
    return client.getQueryCache().subscribe((event) => {
      if (
        done ||
        leaving.current ||
        event.type !== "updated" ||
        event.action.type !== "error"
      ) {
        return;
      }
      const error = event.action.error;
      if (error instanceof ApiFailure && error.status === 401) {
        done = true;
        leaving.current = true;
        navigate(loginAddress(path), {
          state:
            error.code === "session_ended" ? { message: SESSION_ENDED } : null,
        });
      }
    });
  }, [client, navigate, path, leaving]);
}

export function SignedInLayout(props: SignedInLayoutProps) {
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
        <div className={styles.content}>
          <Loading />
        </div>
      </>
    );
  }
  if (session.isError) {
    return (
      <>
        <Banner label={branding.data?.environment_label} />
        <div className={styles.content}>
          <PageError
            reason={reasonOf(session.error)}
            onTryAgain={() => void session.refetch()}
          />
        </div>
      </>
    );
  }
  const user = session.data;
  if (props.access === "view" && !hasView(user)) {
    return <Navigate to="/no-access" replace />;
  }
  if (props.access === "no-access" && hasView(user)) {
    return <Navigate to="/incidents" replace />;
  }
  return (
    <SignedIn
      {...props}
      user={user}
      branding={branding.data}
      setLeaving={setLeaving}
    >
      {props.children}
    </SignedIn>
  );
}

interface SignedInProps extends SignedInLayoutProps {
  user: User;
  branding: TenantSettings | undefined;
  setLeaving: (value: boolean) => void;
}

function SignedIn(props: SignedInProps) {
  const navigate = useNavigate();
  const tenant = useQuery(tenantQuery);
  const topics = [...props.topics, "tenant"];
  const interrupted = useLiveTopics(topics);
  const [signingOut, setSigningOut] = useState(false);
  const [signOutFailure, setSignOutFailure] = useState<string | null>(null);
  const settings = tenant.data ?? props.branding;

  async function signOut() {
    setSigningOut(true);
    setSignOutFailure(null);
    props.setLeaving(true);
    try {
      await apiDelete("/api/v1/session");
    } catch (error) {
      if (!(error instanceof ApiFailure && error.status === 401)) {
        props.setLeaving(false);
        setSigningOut(false);
        setSignOutFailure(reasonOf(error));
        return;
      }
    }
    navigate("/login", { state: { message: SIGNED_OUT } });
  }

  const lineShown = props.live && interrupted;
  return (
    <InterruptedContext value={lineShown}>
      <Banner label={settings?.environment_label} />
      {settings && (
        <Header
          settings={settings}
          displayName={props.user.display_name}
          full={props.access === "view"}
          withNavigation={hasView(props.user)}
          onSignOut={() => void signOut()}
          signingOut={signingOut}
          signOutFailure={signOutFailure}
        />
      )}
      {lineShown && <InterruptedLine keys={queriesOf(props.topics)} />}
      <main className={styles.content}>{props.children}</main>
    </InterruptedContext>
  );
}

/** "Last update {time}": the latest successful read of the screen's queries, matched by prefix (CTX-D-085 (6)). */
function InterruptedLine({ keys }: { keys: QueryKey[] }) {
  const client = useQueryClient();
  useIsFetching();
  const [shownAt] = useState(() => Date.now());
  const latest = lastUpdate(client, keys);
  return (
    <LiveInterrupted
      time={viewerTime(new Date(latest > 0 ? latest : shownAt))}
    />
  );
}
