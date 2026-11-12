"""Builds the seed bundle of a local installation (P-26 spec Section 6.4; AR-B-05, AR-B-07, DD-R-11).

Usage: bundle.py --data docs/31-demo-data --values FILE [--values FILE ...] --out FILE

The values files are flat lines "name: value" ("#" comments and blank lines ignored; a value in double quotes loses
them; any other value is its text as written); a name in two files is an error. Each "${name}" of the nine data files
is replaced as text; a placeholder left over stops the build. The ZIP holds exactly the ten entries of AR-B-05 in its
order, each with the time 1980-01-01 00:00:00, so the same input gives the same bytes. Standard library only.
"""

import re
import sys
import zipfile
from io import BytesIO
from pathlib import Path

USAGE = "Usage: bundle.py --data DIR --values FILE [--values FILE ...] --out FILE"
DATA_FILES = (
    "01-tenant-identity.yaml",
    "02-service-map.yaml",
    "03-monitoring.yaml",
    "04-operations-actions-recovery.yaml",
    "05-routing-escalation.yaml",
    "06-workflow.yaml",
    "07-settings.yaml",
    "08-runbooks.md",
    "09-past-incidents.csv",
)
LINE = re.compile(r"^([^\s:#][^:]*):[ \t]*(.*?)\s*$")
PLACEHOLDER = re.compile(r"\$\{([^}]*)\}")
VERSION = re.compile(r"^\| Version \| ([^ |]+) \|$", re.MULTILINE)
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


class BundleError(Exception):
    """A build that cannot be made; the message is printed."""


def read_values(paths):
    """The merged values of the files, {name: text}."""
    values = {}
    for path in paths:
        for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), start=1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            match = LINE.match(line)
            if match is None:
                raise BundleError(f"{path} line {number} is not a line name: value.")
            name, text = match.group(1).strip(), match.group(2)
            if len(text) >= 2 and text.startswith('"') and text.endswith('"'):
                text = text[1:-1]
            if name in values:
                raise BundleError(f"The value {name} is given twice.")
            values[name] = text
    return values


def replace(text, values):
    return PLACEHOLDER.sub(lambda match: values.get(match.group(1), match.group(0)), text)


def entries(data, values):
    """The ten entries of AR-B-05 in order: (name, bytes)."""
    data = Path(data)
    version = VERSION.search(data.joinpath("README.md").read_text(encoding="utf-8"))
    if version is None:
        raise BundleError(f"{data}/README.md has no Version.")
    result = [("bundle.yaml", f'bundle_name: "jazz-demo"\nbundle_version: "{version.group(1)}"\n')]
    for name in DATA_FILES:
        result.append((name, replace(data.joinpath(name).read_text(encoding="utf-8"), values)))
    left = []
    for _, text in result:
        for index in [m.start() for m in re.finditer(r"\$\{", text)]:
            match = PLACEHOLDER.match(text, index)
            name = match.group(1) if match else text[index + 2 : index + 42].split("\n")[0]
            if name not in left:
                left.append(name)
    if left:
        raise BundleError(f"The bundle still contains placeholders: {', '.join(left)}.")
    return [(name, text.encode("utf-8")) for name, text in result]


def build(data, value_paths):
    """The bytes of the bundle ZIP built from the data folder and the values files."""
    values = read_values(value_paths)
    out = BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, content in entries(data, values):
            info = zipfile.ZipInfo(name, date_time=FIXED_TIME)
            info.external_attr = 0o644 << 16
            archive.writestr(info, content)
    return out.getvalue()


def parse(arguments):
    data, out, values = None, None, []
    index = 0
    while index < len(arguments):
        if index + 1 >= len(arguments):
            return None
        flag, value = arguments[index], arguments[index + 1]
        if flag == "--data" and data is None:
            data = value
        elif flag == "--out" and out is None:
            out = value
        elif flag == "--values":
            values.append(value)
        else:
            return None
        index += 2
    if data is None or out is None or not values:
        return None
    return data, values, out


def main(arguments):
    parsed = parse(arguments)
    if parsed is None:
        print(USAGE)
        return 2
    data, values, out = parsed
    try:
        content = build(data, values)
    except BundleError as error:
        print(error)
        return 1
    Path(out).write_bytes(content)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
