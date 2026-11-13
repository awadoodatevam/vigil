// The customer logo (AR-I-11) when the tenant has one, else the customer name (DC-U-03).
import type { TenantSettings } from "../api/types";
import styles from "./layout.module.css";

export function Customer({ settings }: { settings: TenantSettings }) {
  if (settings.has_logo) {
    return (
      <img
        className={styles.logo}
        src="/api/v1/public/logo"
        alt={settings.customer_name}
      />
    );
  }
  return <span>{settings.customer_name}</span>;
}
