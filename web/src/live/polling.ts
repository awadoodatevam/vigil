// Polling (AR-I-05, AR-TM-09): TanStack Query's interval, also while the tab is in the background.
export function polling(intervalMs: number) {
  return {
    refetchInterval: intervalMs,
    refetchIntervalInBackground: true,
  } as const;
}
