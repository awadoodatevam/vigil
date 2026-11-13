// The customer logo (AR-I-11) when the tenant has one, else the customer name (DC-U-03).
import type { TenantSettings } from "../api/types";
import styles from "./layout.module.css";

export function Customer({
  settings,
  place,
}: {
  settings: TenantSettings;
  /** Where it is shown: the header's chip and 24px logo, or the login card's (VD-C-02, VD-S-01). */
  place: "header" | "login";
}) {
  if (settings.has_logo) {
    return (
      <img
        className={place === "header" ? styles.logoHeader : styles.logoLogin}
        src="/api/v1/public/logo"
        alt={settings.customer_name}
      />
    );
  }
  return (
    <span
      className={
        place === "header" ? styles.customerHeader : styles.customerLogin
      }
    >
      {settings.customer_name}
    </span>
  );
}
