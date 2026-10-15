"""Reports which secret files have a value that occurs in a log (AR-Q-07).

Usage: log_scan.py --log FILE --secrets-dir DIR

Every file under DIR (sub-folders included) holds one secret value; when its content is a JSON object, every string
value inside it is a secret value too. Values shorter than 8 characters are ignored. The relative path of each file
one of whose values occurs in the log is printed, never the value. Exit code 1 when one does, 0 otherwise.
"""

import argparse
import json
import sys
from pathlib import Path

MIN_LENGTH = 8


def _strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from _strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)


def secret_values(content: str) -> list[str]:
    values = [content]
    try:
        parsed = json.loads(content)
    except ValueError:
        parsed = None
    if isinstance(parsed, dict):
        values.extend(_strings(parsed))
    return [value for value in values if len(value) >= MIN_LENGTH]


def leaked_files(log_text: str, secrets_dir: Path) -> list[str]:
    leaked = []
    for path in sorted(p for p in secrets_dir.rglob("*") if p.is_file()):
        content = path.read_text(encoding="utf-8", errors="replace")
        if any(value in log_text for value in secret_values(content)):
            leaked.append(path.relative_to(secrets_dir).as_posix())
    return leaked


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Report secret files whose values occur in a log.")
    parser.add_argument("--log", required=True, type=Path)
    parser.add_argument("--secrets-dir", required=True, type=Path)
    args = parser.parse_args(argv)
    log_text = args.log.read_text(encoding="utf-8", errors="replace")
    leaked = leaked_files(log_text, args.secrets_dir)
    for relative in leaked:
        print(relative)
    return 1 if leaked else 0


if __name__ == "__main__":
    sys.exit(main())
