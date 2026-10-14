#!/usr/bin/env bash
# Lines of production code added and changed between two revisions (BP-R-05).
# Usage: tools/loc.sh {base} {head}
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: tools/loc.sh {base} {head}" >&2
  exit 2
fi

git diff --numstat "$1...$2" | awk -F '\t' '
  $1 == "-" { next }
  {
    path = $3
    if (path !~ /^(app\/src\/main\/|web\/src\/|demo\/|dev\/|deploy\/)/) next
    if (path !~ /\.(java|ts|tsx|py|sh)$/) next
    if (path ~ /\/src\/test\// || path ~ /\/tests\// || path ~ /\/recordings\//) next
    n = split(path, parts, "/")
    name = parts[n]
    if (name ~ /^test_.*\.py$/ || name ~ /\.test\.tsx?$/) next
    total += $1
  }
  END { print total + 0 }
'
