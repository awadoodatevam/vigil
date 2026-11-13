// DC-S01 Login (P-28 spec Sections 6.3, 6.9): goes on to `next` or the default page with a valid session; sign-in
// through AR-I-12; the session-ended and signed-out messages come in the route's state.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router";
import { ApiFailure, apiPost, reasonOf } from "../api/client";
import type { TenantSettings } from "../api/types";
import { titleOf, useTitle } from "../app/title";
import { Banner } from "../layout/Banner";
import { Customer } from "../layout/Customer";
import { messageOf } from "../session/messages";
import { validNext } from "../session/next";
import { defaultPage, loginSessionQuery } from "../session/queries";
import { ActionFailed, Loading } from "../states/states";
import styles from "./login.module.css";

export function LoginPage({ branding }: { branding: TenantSettings }) {
  const location = useLocation();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [message] = useState(() => messageOf(location.state));
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [running, setRunning] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const session = useQuery(loginSessionQuery);
  const next = validNext(new URLSearchParams(location.search).get("next"));
  useTitle(titleOf("Sign in", branding.product_display_name));

  useEffect(() => {
    // Reads of an earlier session are not shown after the next sign-in.
    client.removeQueries({
      predicate: (query) =>
        query.queryKey[0] !== "branding" && query.queryKey[1] !== "login",
    });
    if (location.state !== null) {
      navigate(location.pathname + location.search, {
        replace: true,
        state: null,
      });
    }
    // Once, when the screen opens (Section 6.3 item 6).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (session.isSuccess) {
    return <Navigate to={next ?? defaultPage(session.data)} replace />;
  }

  async function signIn(event: FormEvent) {
    event.preventDefault();
    setRunning(true);
    setRefusal(null);
    setFailure(null);
    try {
      const user = await apiPost("/api/v1/session", {
        body: { username, password },
      });
      client.setQueryData(loginSessionQuery.queryKey, user);
    } catch (error) {
      setRunning(false);
      if (error instanceof ApiFailure && error.status === 401) {
        setRefusal(error.reason);
        setPassword("");
      } else {
        setFailure(reasonOf(error));
      }
    }
  }

  return (
    <>
      <Banner label={branding.environment_label} />
      <div className={styles.page}>
        <span className={styles.product}>{branding.product_display_name}</span>
        <Customer settings={branding} />
        {message !== null && <p className={styles.message}>{message}</p>}
        <h1 className={styles.heading}>Sign in</h1>
        {session.isPending ? (
          <Loading />
        ) : (
          <form
            className={styles.form}
            onSubmit={(event) => void signIn(event)}
          >
            <label className={styles.field}>
              Username
              <input
                type="text"
                name="username"
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
              />
            </label>
            <label className={styles.field}>
              Password
              <input
                type="password"
                name="password"
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            <button type="submit" disabled={running}>
              Sign in
            </button>
            {refusal !== null && (
              <p className={styles.refusal} role="alert">
                {refusal}
              </p>
            )}
            {failure !== null && (
              <ActionFailed action="sign in" reason={failure} />
            )}
          </form>
        )}
      </div>
    </>
  );
}
