// Numbers on screens (CTX-D-097 (3)): a comma between groups of three digits, a fixed number of decimals.

/** The number with `decimals` decimals and a comma between groups of three digits (Example: "1,234.5"). */
export function grouped(n: number, decimals: number): string {
  return new Intl.NumberFormat("en-US", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
    useGrouping: true,
  }).format(n);
}
