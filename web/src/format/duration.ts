// Durations (DC-U-07 rule (2)).
export function duration(seconds: number): string {
  if (seconds < 120) {
    return `${seconds} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return rest === 0 ? `${minutes} min` : `${minutes} min ${rest} s`;
}
