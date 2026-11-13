// The messages of the login screen (DC-S01-04), passed in the route's state.
export const SESSION_ENDED = "Your session ended. Sign in again.";
export const SIGNED_OUT = "You are signed out.";

export interface LoginState {
  message: string;
}

export function messageOf(state: unknown): string | null {
  const message = (state as Partial<LoginState> | null)?.message;
  return typeof message === "string" ? message : null;
}
