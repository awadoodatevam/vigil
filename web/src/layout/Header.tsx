// The header of the desktop pages (DC-U-03, DC-U-04, DC-S01-05): {product}; the customer; the navigation; the zone
// note; the display name; "Sign out".
import { NavLink } from "react-router";
import type { TenantSettings } from "../api/types";
import { zoneNote } from "../format/time";
import { ActionFailed } from "../states/states";
import { Customer } from "./Customer";
import styles from "./layout.module.css";

/** The navigation items built so far, in the order of DC-U-04 (BP-R-22). */
export const NAVIGATION = [{ label: "Incidents", path: "/incidents" }] as const;

export interface HeaderProps {
  settings: TenantSettings;
  displayName: string;
  /** False on the no-access page: only {product}, the customer, the display name and "Sign out". */
  full: boolean;
  /** The navigation is shown only to a viewer with `view`. */
  withNavigation: boolean;
  onSignOut: () => void;
  signingOut: boolean;
  signOutFailure: string | null;
  now?: Date;
  zone?: string;
}

export function Header(props: HeaderProps) {
  const { settings, full } = props;
  return (
    <header className={styles.header}>
      <span className={styles.product}>{settings.product_display_name}</span>
      <Customer settings={settings} place="header" />
      {full && props.withNavigation && (
        <nav className={styles.navigation} aria-label="Navigation">
          {NAVIGATION.map((item) => (
            <NavLink
              key={item.path}
              to={item.path}
              end
              className={({ isActive }) =>
                isActive
                  ? `${styles.navItem} ${styles.navCurrent}`
                  : styles.navItem
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      )}
      {full && (
        <span className={styles.zone}>{zoneNote(props.now, props.zone)}</span>
      )}
      <span className={styles.user}>
        <span className={styles.glyph} aria-hidden="true">
          <svg
            aria-hidden="true"
            width="14"
            height="14"
            viewBox="0 0 14 14"
            fill="none"
            stroke="currentColor"
            strokeWidth={1.6}
          >
            <circle cx="7" cy="4.5" r="2.6" />
            <path d="M1.8 13c0-2.9 2.3-4.8 5.2-4.8s5.2 1.9 5.2 4.8" />
          </svg>
        </span>
        {props.displayName}
      </span>
      <div className={styles.signOut}>
        <button
          type="button"
          className={styles.ghost}
          onClick={props.onSignOut}
          disabled={props.signingOut}
        >
          Sign out
        </button>
        {props.signOutFailure !== null && (
          <ActionFailed action="sign out" reason={props.signOutFailure} />
        )}
      </div>
    </header>
  );
}
