"""Tests of the parts of common.py that P-07 adds (P-07 spec Sections 6.1, 6.6 and 7)."""

import json

import common
import pytest
import spike_fakes


def test_run_package(tmp_path, monkeypatch, capsys):
    """SB-T-06 Run with package writes P-07-{spike}.json; without it, the name of result_package() as before."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    guard = common.Guard()
    run = common.Run("SB-T-06", guard, package="P-07")
    run.check("post-noc-l1", True)
    assert run.write({"channels": []}) == 0
    assert setup.result("SB-T-06", package="P-07")["channels"] == []
    run = common.Run("SB-T-07", guard)
    run.check("myself", False, "status 401")
    assert run.write({}) == 1
    assert setup.result("SB-T-07")["passed"] is False
    monkeypatch.setenv("VIGIL_SPIKE_PACKAGE", "P-20")
    common.Run("claude", guard).write({})
    capsys.readouterr()
    assert sorted(path.name for path in setup.results.iterdir()) == [
        "P-06-SB-T-07.json",
        "P-07-SB-T-06.json",
        "P-20-claude.json",
    ]


def ask(monkeypatch, capsys, answers, question, lines=(), *, kind):
    stdin = spike_fakes.answer_with(monkeypatch, spike_fakes.Clock(), answers)
    try:
        return common.ask(question, lines, kind=kind), capsys.readouterr().out, stdin.read
    except common.NoAnswer:
        return common.NoAnswer, capsys.readouterr().out, stdin.read


def test_ask_kinds(monkeypatch, capsys):
    """SB-T-06 ask: the question, its lines indented by two spaces, the prompt of each kind, the answers it takes and
    the repeated prompt for any other answer."""
    answer, output, read = ask(monkeypatch, capsys, ["maybe", " YES "], "Does it?", ["one", "two"], kind="yes_no")
    assert answer is True and read == 2
    assert output == "Does it?\n  one\n  two\n(y or n) (y or n) "
    assert ask(monkeypatch, capsys, ["N"], "Q", kind="yes_no")[0] is False
    assert ask(monkeypatch, capsys, ["y"], "Q", kind="yes_no")[0] is True
    assert ask(monkeypatch, capsys, ["No "], "Q", kind="yes_no")[0] is False

    answer, output, read = ask(monkeypatch, capsys, ["later", ""], "Press Enter", kind="enter_or_no")
    assert answer is True and read == 2 and output == "Press Enter\n(Enter, or n) (Enter, or n) "
    assert ask(monkeypatch, capsys, ["n"], "Q", kind="enter_or_no")[0] is False
    assert ask(monkeypatch, capsys, ["NO"], "Q", kind="enter_or_no")[0] is False

    answer, output, read = ask(monkeypatch, capsys, ["anything"], "Open it.", kind="enter")
    assert answer is True and read == 1 and output == "Open it.\n(Enter) "

    answer, output, _ = ask(monkeypatch, capsys, ["  https://x.example/a  "], "Paste:", kind="text")
    assert answer == "https://x.example/a" and output == "Paste:\n> "
    assert ask(monkeypatch, capsys, [""], "Paste:", kind="text")[0] == ""


def test_ask_no_answer(monkeypatch, capsys):
    """SB-T-06 an end of input raises NoAnswer, also after an answer that repeats the prompt."""
    for kind in ("yes_no", "enter_or_no", "enter", "text"):
        assert ask(monkeypatch, capsys, [], "Q", kind=kind)[0] is common.NoAnswer
    assert ask(monkeypatch, capsys, ["perhaps"], "Q", kind="yes_no")[0] is common.NoAnswer
    with pytest.raises(KeyError):
        common.ask("Q", kind="other")


def test_guard_redact():
    """SB-T-06 Guard.redact replaces every value the guard holds: secrets (longest first), the email without regard to
    case and the account forms."""
    guard = common.Guard(secrets=["abc", "abcdef", ""], email="Noc.Spike@example.test")
    guard.account("712020:a-b")
    text = "abcdef abc NOC.SPIKE@EXAMPLE.TEST 712020:a-b 712020%3Aa-b other"
    assert guard.redact(text) == "<redacted> <redacted> <redacted> <redacted> <redacted> other"
    assert common.Guard().redact("nothing held") == "nothing held"


def test_recorder_url(tmp_path, monkeypatch):
    """SB-T-06 Recorder.record writes url when given, and placeholder + path otherwise."""
    spike_fakes.prepare(tmp_path, monkeypatch)
    recorder = common.Recorder("teams", "${endpoint.x}", common.Guard())
    answer = common.Answer("post-noc-l1", "POST", "/p", [("Content-Type", "application/json")], "{}", common.utc_now())
    answer.error = "refused"
    recorder.record(answer, url="secret:teams-team-noc-l1")
    recorder.record(answer)
    first, second = sorted((tmp_path / "recordings" / "teams").glob("*.json"))
    assert json.loads(first.read_text())["request"]["url"] == "secret:teams-team-noc-l1"
    assert json.loads(second.read_text())["request"]["url"] == "${endpoint.x}/p"
