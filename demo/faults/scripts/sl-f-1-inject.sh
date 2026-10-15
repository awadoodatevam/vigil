#!/usr/bin/env bash
set -euo pipefail
# SL-F-1 inject (DD-F-10 faults.SL-F-1.inject steps 1 to 4), run as root in lab-db-01 by demo-faults.
# (1) free space F; (2) N files of 256 MiB while at least 320 MiB stay free, plus one last file so 64 MiB stay free;
# (3) txlog_000001.arc to txlog_{N+1}.arc in /data/txlog_archive with fallocate; (4) modification times evenly from
# 47 h to 26 h before now. Prints "{N + 1} {bytes}".
free=$(df -B1 --output=avail /data | tail -n 1 | tr -d ' ')
size=268435456
n=0
while [ $((free - (n + 1) * size)) -ge $((320 * 1048576)) ]; do n=$((n + 1)); done
last=$(((free - n * size - 64 * 1048576) / 4096 * 4096))
for i in $(seq 1 $((n + 1))); do
  if [ "$i" -le "$n" ]; then bytes=$size; else bytes=$last; fi
  fallocate -l "$bytes" "$(printf '/data/txlog_archive/txlog_%06d.arc' "$i")"
done
now=$(date +%s)
for i in $(seq 1 $((n + 1))); do
  touch -m -d "@$((now - 47 * 3600 + (i - 1) * 21 * 3600 / n))" "$(printf '/data/txlog_archive/txlog_%06d.arc' "$i")"
done
echo "$((n + 1)) $((n * size + last))"
