"""Tests of tools/trace.py (BP-R-15)."""

import importlib.util
import subprocess
import textwrap
from pathlib import Path

# tools/trace.py is loaded by path because its name is also the name of a standard library module.
_spec = importlib.util.spec_from_file_location("vigil_trace", Path(__file__).with_name("trace.py"))
trace_module = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(trace_module)

SPEC_HEADER = textwrap.dedent(
    """\
    # P-99 — Example

    ## 4. Requirements built

    | ID | Part built | Proof |
    | --- | --- | --- |
    """
)


def write(root, relative, content):
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(content), encoding="utf-8")
    return path


def spec(root, rows):
    return write(root, "docs/packages/P-99.md", SPEC_HEADER + "".join(rows) + "\n## 5. Not in this package\n")


def test_check_passes_for_java_display_name(tmp_path, capsys):
    """BP-R-15 a test row with a matching JUnit test passes the check."""
    spec(tmp_path, ["| AR-Q-01 | | test |\n"])
    write(
        tmp_path,
        "app/src/test/java/vigil/FooTest.java",
        """\
        class FooTest {
            @DisplayName("AR-Q-01 modules verify")
            void verifies() {}
        }
        """,
    )
    assert trace_module.main(["--check"], tmp_path) == 0
    assert capsys.readouterr().out == ""


def test_check_passes_for_typescript_and_python_names(tmp_path):
    """BP-R-15 Vitest titles and pytest docstrings count as test names."""
    spec(tmp_path, ["| AR-W-01 | | test |\n", "| BP-R-05 | | test |\n", "| AR-W-02 | | test |\n"])
    write(tmp_path, "web/src/app.test.tsx", "describe('AR-W-01 serving', () => { it(\"x\", () => {}); });\n")
    write(tmp_path, "tests/e2e/flow.spec.ts", "test(`AR-W-02 routes`, async () => {});\n")
    write(
        tmp_path,
        "tools/test_loc.py",
        '''\
        def test_counts():
            """BP-R-05 lines are counted."""
        ''',
    )
    assert trace_module.main(["--check"], tmp_path) == 0


def test_check_fails_without_a_matching_test(tmp_path, capsys):
    """BP-R-15 a test row without a test makes the check exit 1 and prints the ID."""
    spec(tmp_path, ["| AR-Q-01 | | test |\n", "| AR-Q-03 | | test |\n"])
    write(tmp_path, "app/src/test/java/vigil/FooTest.java", '@DisplayName("AR-Q-01 modules verify")\n')
    assert trace_module.main(["--check"], tmp_path) == 1
    output = capsys.readouterr().out
    assert "AR-Q-03" in output
    assert "AR-Q-01" not in output


def test_only_the_first_word_of_a_name_counts(tmp_path):
    """BP-R-15 an ID later in the name or with other text attached does not count."""
    spec(tmp_path, ["| AR-Q-01 | | test |\n"])
    write(tmp_path, "app/src/test/java/vigil/FooTest.java", '@DisplayName("modules verify AR-Q-01")\n')
    assert trace_module.main(["--check"], tmp_path) == 1


def test_a_range_row_is_an_error(tmp_path, capsys):
    """BP-R-15 a row with a range is an error with file and line."""
    spec(tmp_path, ["| AR-M-01 to AR-M-03 | | test |\n"])
    assert trace_module.main(["--check"], tmp_path) == 1
    assert "P-99.md:7" in capsys.readouterr().out


def test_another_proof_value_is_an_error(tmp_path):
    """BP-R-15 a proof other than test or checked by review is an error."""
    spec(tmp_path, ["| AR-Q-01 | | manual |\n"])
    assert trace_module.main(["--check"], tmp_path) == 1


def test_checked_by_review_rows_need_no_test(tmp_path):
    """BP-R-15 rows checked by review pass without a test."""
    spec(tmp_path, ["| AR-T-01 | Java 25 | checked by review: the compiler release |\n"])
    assert trace_module.main(["--check"], tmp_path) == 0


def test_reports_are_not_read_as_specs(tmp_path):
    """BP-R-15 files named P-##-report.md are not specs."""
    write(tmp_path, "docs/packages/P-99-report.md", SPEC_HEADER + "| AR-Q-01 to AR-Q-03 | | test |\n")
    assert trace_module.main(["--check"], tmp_path) == 0


def test_report_lists_untested_ids_except_bp(tmp_path, capsys):
    """BP-R-15 without --check the defined IDs without a test are listed per prefix, BP excluded."""
    write(
        tmp_path,
        "docs/50-architecture.md",
        """\
        | ID | Rule |
        | --- | --- |
        | AR-Q-01 | tested |
        | **AR-Q-02** | untested |
        | AR-Q-03 | reviewed |
        | BP-R-01 | not listed |
        """,
    )
    write(
        tmp_path,
        "docs/packages/P-99.md",
        SPEC_HEADER + "| AR-Q-03 | | checked by review: the pull request |\n\n## 5. Not in this package\n",
    )
    write(tmp_path, "docs/packages/P-99-report.md", "# report\n")
    write(tmp_path, "app/src/test/java/vigil/FooTest.java", '@DisplayName("AR-Q-01 modules verify")\n')
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    assert trace_module.main([], tmp_path) == 0
    output = capsys.readouterr().out
    assert "AR-Q-02" in output
    assert "AR-Q-01" not in output
    assert "AR-Q-03" not in output
    assert "BP-R-01" not in output
    assert Path(tmp_path).exists()


def test_spikes_tests_count(tmp_path):
    """BP-R-15 a pytest test under spikes/ names its ID for --check."""
    spec(tmp_path, ["| SB-T-01 | | test |\n"])
    write(
        tmp_path,
        "spikes/storm-timing/test_storm_timing.py",
        '''\
        def test_storm():
            """SB-T-01 the storm passes."""
        ''',
    )
    assert trace_module.main(["--check"], tmp_path) == 0
