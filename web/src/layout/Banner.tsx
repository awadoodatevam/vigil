// The environment banner (DC-U-01): only when the label is neither null nor empty; it cannot be closed.
import styles from "./layout.module.css";

export function Banner({ label }: { label: string | null | undefined }) {
  if (label === null || label === undefined || label === "") {
    return null;
  }
  return (
    <div className={styles.banner} data-testid="environment-banner">
      {label}
    </div>
  );
}
