"""Traceability between package specs and test names (BP-R-15).

With --check: fails when a row of a package spec with proof "test" has no test whose name starts with its ID.
Without --check: prints, per document prefix, the defined IDs that no test names and no finished package marks
"checked by review:".
"""

import ast
import re
import subprocess
import sys
from pathlib import Path

ID_PATTERN = r"\b(?:CTX|SL|PM|DM|SB|DD|DC|AR|BP)-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*\b"
ID_RE = re.compile(ID_PATTERN)
ID_FULL_RE = re.compile(ID_PATTERN.replace(r"\b", ""))
CELL_ID_RE = re.compile(r"(?:\*\*)?(" + ID_PATTERN.replace(r"\b", "") + r")(?:\*\*)?")

SPEC_RE = re.compile(r"^P-\d+\.md$")
REPORT_RE = re.compile(r"^P-\d+-report\.md$")
REQUIREMENTS_HEADING = "## 4. Requirements built"

DISPLAY_NAME_RE = re.compile(r'@DisplayName\(\s*"((?:[^"\\]|\\.)*)"')
TS_NAME_RE = re.compile(r"""\b(?:test|it|describe)\s*\(\s*(["'`])((?:\\.|(?!\1).)*)\1""")

JAVA_ROOTS = ("app/src/test", "demo/*/src/test")
TS_UNIT_ROOTS = ("web/src",)
TS_E2E_ROOTS = ("tests/e2e",)
PYTHON_ROOTS = ("tools", "demo", "dev", "deploy", "spikes", "tests")


def first_word_id(name):
    """Return the first word of a test name when it is a requirement ID, otherwise None."""
    words = name.split()
    if words and ID_FULL_RE.fullmatch(words[0]):
        return words[0]
    return None


def files_under(root, patterns, suffixes):
    """Files below the root-relative glob patterns whose name ends with one of the suffixes."""
    found = set()
    for pattern in patterns:
        for base in root.glob(pattern):
            if base.is_dir():
                found.update(p for p in base.rglob("*") if p.is_file() and p.name.endswith(suffixes))
    return sorted(found)


def test_name_ids(root):
    """The set of IDs at the start of test names (BP-R-10)."""
    ids = set()
    for path in files_under(root, JAVA_ROOTS, (".java",)):
        for name in DISPLAY_NAME_RE.findall(path.read_text(encoding="utf-8")):
            ids.add(first_word_id(name))
    for path in files_under(root, TS_UNIT_ROOTS, (".test.ts", ".test.tsx")) + files_under(
        root, TS_E2E_ROOTS, (".spec.ts",)
    ):
        for _quote, name in TS_NAME_RE.findall(path.read_text(encoding="utf-8")):
            ids.add(first_word_id(name))
    for path in files_under(root, PYTHON_ROOTS, (".py",)):
        if not path.name.startswith("test_"):
            continue
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"))
        except SyntaxError:
            continue
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_"):
                ids.add(first_word_id(ast.get_docstring(node) or ""))
    ids.discard(None)
    return ids


def split_row(line):
    """The cells of a markdown table row."""
    cells = line.strip().strip("|").split("|")
    return [cell.strip() for cell in cells]


def spec_rows(spec_path, errors):
    """The (line number, ID, proof) rows of part 4 of a package spec; malformed rows go to errors."""
    rows = []
    in_section = False
    in_table = False
    for number, line in enumerate(spec_path.read_text(encoding="utf-8").splitlines(), start=1):
        if line.startswith("## "):
            in_section = line.strip() == REQUIREMENTS_HEADING
            in_table = False
            continue
        if not in_section:
            continue
        if not line.startswith("|"):
            in_table = False
            continue
        cells = split_row(line)
        if not in_table:
            in_table = True
            continue  # header row
        if all(set(cell) <= set("-: ") for cell in cells):
            continue  # separator row
        if len(cells) < 3:
            errors.append(f"{spec_path}:{number}: row has fewer than 3 cells")
            continue
        first, proof = cells[0], cells[2]
        match = CELL_ID_RE.fullmatch(first)
        if not match or len(ID_RE.findall(first)) != 1:
            errors.append(f"{spec_path}:{number}: first cell must be exactly one requirement ID, found '{first}'")
            continue
        if proof != "test" and not proof.startswith("checked by review:"):
            errors.append(f"{spec_path}:{number}: proof must be 'test' or start with 'checked by review:'")
            continue
        rows.append((number, match.group(1), proof))
    return rows


def spec_paths(root):
    packages = root / "docs" / "packages"
    if not packages.is_dir():
        return []
    return sorted(p for p in packages.iterdir() if SPEC_RE.match(p.name))


def report_on_main(root, spec_path):
    """True when the report of a spec exists on origin/main (or in the working tree without that ref)."""
    report = spec_path.with_name(spec_path.stem + "-report.md")
    relative = report.relative_to(root).as_posix()
    has_ref = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", "origin/main"], cwd=root, capture_output=True, check=False
    )
    if has_ref.returncode != 0:
        return report.is_file()
    exists = subprocess.run(
        ["git", "cat-file", "-e", f"origin/main:{relative}"], cwd=root, capture_output=True, check=False
    )
    return exists.returncode == 0


def defined_ids(root):
    """The IDs defined in docs/*.md and docs/31-demo-data/README.md, by prefix, in document order."""
    documents = sorted((root / "docs").glob("*.md")) + [root / "docs" / "31-demo-data" / "README.md"]
    defined = {}
    for document in documents:
        if not document.is_file():
            continue
        for line in document.read_text(encoding="utf-8").splitlines():
            if not line.startswith("|"):
                continue
            cells = split_row(line)
            match = CELL_ID_RE.fullmatch(cells[0]) if cells else None
            if match:
                defined.setdefault(match.group(1).split("-")[0], {})[match.group(1)] = None
    return {prefix: list(ids) for prefix, ids in defined.items()}


def run_check(root):
    errors = []
    named = test_name_ids(root)
    missing = []
    for spec in spec_paths(root):
        for number, requirement, proof in spec_rows(spec, errors):
            if proof == "test" and requirement not in named:
                missing.append(f"{spec.relative_to(root)}:{number}: no test name starts with {requirement}")
    for message in errors + missing:
        print(message)
    return 1 if errors or missing else 0


def run_report(root):
    errors = []
    named = test_name_ids(root)
    reviewed = set()
    for spec in spec_paths(root):
        rows = spec_rows(spec, errors)
        if report_on_main(root, spec):
            reviewed.update(requirement for _n, requirement, proof in rows if proof.startswith("checked by review:"))
    for message in errors:
        print(message)
    for prefix, ids in defined_ids(root).items():
        if prefix == "BP":
            continue
        untested = [i for i in ids if i not in named and i not in reviewed]
        if untested:
            print(f"{prefix}: {len(untested)} without a test or a review mark")
            for requirement in untested:
                print(f"  {requirement}")
    return 0


def main(argv, root=None):
    root = root or Path(__file__).resolve().parent.parent
    if "--check" in argv:
        return run_check(root)
    return run_report(root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
