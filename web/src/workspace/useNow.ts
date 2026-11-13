// A 1-second clock for the countdowns and "Holding for" (P-29 spec Section 6.8): it re-renders only while active.
import { useEffect, useState } from "react";

export function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) {
      return;
    }
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [active]);
  return now;
}
