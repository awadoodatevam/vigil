#!/usr/bin/env bash
# labctl: the control points of the lab faults (P-03 spec Section 6.4), installed as /usr/local/bin/labctl.
# db read-only [on|off] only on lab-db-01 (SL-F-2); report [fail|succeed] only on lab-rpt-01 (SL-F-3).
set -euo pipefail

root="${LABCTL_ROOT:-/var/lib/lab}"
control="$root/control"
role="${LAB_ROLE:-}"

usage() {
  echo "Usage: labctl db read-only [on|off] (lab-db-01) | labctl report [fail|succeed] (lab-rpt-01)"
  exit 2
}

case "${1:-}" in
  db)
    if [ "$role" != "lab-db-01" ] || [ "${2:-}" != "read-only" ] || [ "$#" -gt 3 ]; then
      usage
    fi
    case "${3:-}" in
      on) touch "$control/db_read_only" ;;
      off) rm -f "$control/db_read_only" ;;
      "")
        if [ "$#" -ne 2 ]; then
          usage
        fi
        if [ -e "$control/db_read_only" ]; then echo on; else echo off; fi
        ;;
      *) usage ;;
    esac
    ;;
  report)
    if [ "$role" != "lab-rpt-01" ] || [ "$#" -gt 2 ]; then
      usage
    fi
    case "${2:-}" in
      fail | succeed)
        echo "$2" > "$control/report_mode"
        touch "$control/report_run_now"
        ;;
      "")
        if [ "$#" -ne 1 ]; then
          usage
        fi
        if [ -f "$control/report_mode" ]; then cat "$control/report_mode"; else echo succeed; fi
        ;;
      *) usage ;;
    esac
    ;;
  *) usage ;;
esac
