// Browser tab titles (DC-U-15).
import { useEffect } from "react";

/** "{screen} — {product}"; the screen name alone while the product is not known; `{product}` alone without a screen. */
export function titleOf(screen: string | null, product: string | null): string {
  if (screen === null) {
    return product ?? "";
  }
  return product ? `${screen} — ${product}` : screen;
}

export function useTitle(title: string): void {
  useEffect(() => {
    document.title = title;
  }, [title]);
}
