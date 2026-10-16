#!/usr/bin/env bash
set -euo pipefail
# SL-F-1 clear (DD-F-10 faults.SL-F-1.clear): deletes the injected files that still exist.
find /data/txlog_archive -maxdepth 1 -type f -name 'txlog_*.arc' -delete
