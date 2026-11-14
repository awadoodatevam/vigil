// The session handling of the signed-in pages (P-28 spec Section 6.3 item 2; moved here by P-30, CTX-D-091 (1)),
// shared by the desktop layout and the mobile layout: any 401 of a page's reads opens the login screen with `next`;
// "Sign out" ends the session.
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { ApiFailure, apiDelete, reasonOf } from "../api/client";
import { SESSION_ENDED, SIGNED_OUT } from "../session/messages";
import { loginAddress } from "../session/next";

/** Opens the login screen on the first 401 of any read while the page is shown (CTX-D-085 (3)). */
export function useSessionEnd(leaving: { current: boolean }) {
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

/** "Sign out" (DC-S01-04): a sign-out answered 401 counts as signed out (CTX-D-085 (3)). */
export function useSignOut(setLeaving: (value: boolean) => void) {
  const navigate = useNavigate();
  const [signingOut, setSigningOut] = useState(false);
  const [signOutFailure, setSignOutFailure] = useState<string | null>(null);

  async function signOut() {
    setSigningOut(true);
    setSignOutFailure(null);
    setLeaving(true);
    try {
      await apiDelete("/api/v1/session");
    } catch (error) {
      if (!(error instanceof ApiFailure && error.status === 401)) {
        setLeaving(false);
        setSigningOut(false);
        setSignOutFailure(reasonOf(error));
        return;
      }
    }
    navigate("/login", { state: { message: SIGNED_OUT } });
  }

  return { signOut, signingOut, signOutFailure };
}
