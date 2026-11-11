"""Tests of the email spike (P-07 spec Sections 6.5, 6.6 and 7) against the SMTP fake of spike_fakes."""

import base64
import email
import email.policy
import json
import shutil

import common
import email_spike
import pytest
import spike_fakes
import yaml

MAILBOXES = {"noc-l1": "vigil-noc-l1@techfinity.ae", "dbops-engineer": "vigil-dbops-engineer@techfinity.ae"}
SENDER = "Techfinity <techfinity-demo@vigil.solviclabs.com>"
HEADERS = ["From", "To", "Subject", "Date", "MIME-Version", "Content-Type", "Content-Transfer-Encoding"]


@pytest.fixture
def smtp(tmp_path, monkeypatch):
    """The SMTP fake with a generated secret, trusted by the spike through SSL_CERT_FILE."""
    setup = spike_fakes.prepare(tmp_path / "spike", monkeypatch)
    cert, key = spike_fakes.certificate(tmp_path / "tls")
    monkeypatch.setenv("SSL_CERT_FILE", str(cert))
    secret = spike_fakes.smtp_secret_value()
    setup.secret("smtp", json.dumps(secret))
    with spike_fakes.SmtpFake(cert, key, secret["username"], secret["password"]) as fake:
        yield setup, fake, secret


def parsed(message):
    return email.message_from_bytes(message["data"], policy=email.policy.SMTP)


def link_of(fake, mailbox, number):
    """The link of message {number} of 10 that the fake accepted for that mailbox."""
    for message in fake.messages:
        body = message["data"].split(b"\r\n\r\n", 1)[1].decode()
        if message["recipients"] == [mailbox] and f"message {number} of 10\r\n" in body:
            return body.split("Acknowledge: ", 1)[1].rstrip("\r\n")
    raise AssertionError((mailbox, number))


def last_choice(monkeypatch):
    """random.choice of the spike takes the last passed message, so the test knows message j."""
    monkeypatch.setattr(email_spike.random, "choice", lambda items: items[-1])


def answers_for(fake, key, j, arrival=("", 5), lines="y"):
    return ["", arrival, lines, lambda: link_of(fake, MAILBOXES[key], j)]


def run(setup, fake, monkeypatch, answers):
    stdin = spike_fakes.answer_with(monkeypatch, setup.clock, answers)
    code = email_spike.main(["--address", fake.address])
    return code, stdin


def details(setup):
    return {c["name"]: (c["passed"], c["detail"]) for c in setup.result("AR-SP-02", package="P-07")["checks"]}


def test_email_run(smtp, monkeypatch, capsys):
    """AR-K-20 the email run: STARTTLS before AUTH; 20 messages, each with its own link, From, To, Subject = line 1,
    text/plain UTF-8 7bit, the headers in order, the four lines joined and ended by CRLF; result file."""
    setup, fake, secret = smtp
    last_choice(monkeypatch)
    code, stdin = run(
        setup, fake, monkeypatch, answers_for(fake, "noc-l1", 10) + answers_for(fake, "dbops-engineer", 10)
    )
    output = capsys.readouterr().out
    assert code == 0, output
    assert stdin.answers == []
    result = setup.result("AR-SP-02", package="P-07")
    run_id = result["run_id"]
    names = []
    for key in MAILBOXES:
        names += [f"connect-{key}"] + [f"send-{key}-{k}" for k in range(1, 11)]
        names += [f"arrival-{key}", f"lines-{key}", f"link-{key}"]
    assert [c["name"] for c in result["checks"]] == names
    assert all(c["passed"] and c["detail"] == "" for c in result["checks"])
    assert result["from"] == SENDER
    assert result["server"] == fake.address
    assert result["mailboxes"] == [
        {
            "key": key,
            "address": address,
            "accepted": 10,
            "seconds_to_arrival": 5.0,
            "missing_in_junk": None,
            "checked_message": 10,
        }
        for key, address in MAILBOXES.items()
    ]
    assert output.splitlines()[-1] == f"result: {setup.results / 'P-07-AR-SP-02.json'}"

    verbs = [verb for verb, _ in fake.commands]
    assert verbs[:4] == ["EHLO", "STARTTLS", "EHLO", "AUTH"]
    assert [tls for verb, tls in fake.commands if verb == "AUTH"] == [True, True]
    assert fake.connections == 2 and verbs.count("QUIT") == 2

    assert len(fake.messages) == 20
    links = set()
    for index, message in enumerate(fake.messages):
        key = list(MAILBOXES)[index // 10]
        number = index % 10 + 1
        mailbox = MAILBOXES[key]
        assert message["sender"] == "techfinity-demo@vigil.solviclabs.com"
        assert message["recipients"] == [mailbox]
        head, body = message["data"].split(b"\r\n\r\n", 1)
        mail = parsed(message)
        assert [name for name, _ in mail.items()] == HEADERS
        line_1 = f"AR-SP-02 spike {run_id} - message {number} of 10"
        assert mail["From"] == SENDER and mail["To"] == mailbox and mail["Subject"] == line_1
        assert mail["MIME-Version"] == "1.0"
        assert b'\r\nContent-Type: text/plain; charset="utf-8"\r\nContent-Transfer-Encoding: 7bit' in head
        lines = body.decode("ascii").split("\r\n")
        assert len(lines) == 5 and lines[4] == ""
        assert lines[0] == line_1
        assert lines[1].startswith("Sent at 20") and lines[1].endswith(f"Z to {mailbox}.")
        assert lines[2] == "Third line: sent by the P-07 email spike; nothing else uses this message."
        link = link_of(fake, mailbox, number)
        assert lines[3] == f"Acknowledge: {link}" and len(lines[3]) > 78
        assert link.startswith("https://demo.vigil.solviclabs.com/m/acknowledge/")
        links.add(link)
    assert len(links) == 20
    assert (
        "Open the Inbox of vigil-noc-l1@techfinity.ae in Outlook on the web (your account, Open another mailbox) "
        "and keep it open. Press Enter when it is open." in output
    )
    assert f'Press Enter as soon as all 10 messages "AR-SP-02 spike {run_id} - message … of 10" are in the' in output
    assert (
        "Does every one show four lines, each on its own line, with the link whole, as message 10 does here?" in output
    )
    assert "Right-click the link in message 10 of 10, choose Copy link, and paste it here:" in output


def test_email_auth_login(smtp, monkeypatch, capsys):
    """AR-K-20 a server that offers only AUTH LOGIN: the login passes after STARTTLS."""
    setup, fake, secret = smtp
    fake.auth = "LOGIN"
    last_choice(monkeypatch)
    code, _ = run(setup, fake, monkeypatch, answers_for(fake, "noc-l1", 10) + answers_for(fake, "dbops-engineer", 10))
    assert code == 0, capsys.readouterr().out
    assert len(fake.messages) == 20


def test_email_failures(smtp, monkeypatch, capsys):
    """AR-SP-02 refusals: a refused RCPT, DATA command and message after its body each fail their send and are
    followed by RSET and a passing next message; the connection ended by the fake fails the rest with "connection
    lost"; a refused login fails connect and makes that mailbox's other checks "not run" while the other goes on."""
    setup, fake, secret = smtp
    last_choice(monkeypatch)
    fake.refuse_rcpt = {3}
    fake.refuse_data = {4}
    fake.reject_after_body = {6}
    fake.close_after = 9
    code, _ = run(setup, fake, monkeypatch, answers_for(fake, "noc-l1", 10) + answers_for(fake, "dbops-engineer", 2))
    output = capsys.readouterr().out
    assert code == 1
    checks = details(setup)
    passed = (True, "")
    assert checks["connect-noc-l1"] == passed
    assert checks["send-noc-l1-3"] == (False, "rcpt 550 Mailbox unavailable")
    assert checks["send-noc-l1-5"] == (False, "data 451 Try again later")
    assert checks["send-noc-l1-7"] == (False, "data 554 Message rejected: Email address is not verified.")
    for number in (1, 2, 4, 6, 8, 9, 10):
        assert checks[f"send-noc-l1-{number}"] == passed
    assert checks["arrival-noc-l1"] == checks["lines-noc-l1"] == checks["link-noc-l1"] == passed
    verbs = [verb for verb, _ in fake.commands]
    # each refused transaction (messages 3, 5 and 7) ends with RSET, and the next message starts with MAIL
    assert verbs.count("RSET") == 3
    after = [verbs[i + 1] for i, verb in enumerate(verbs) if verb == "RSET"]
    assert after == ["MAIL", "MAIL", "MAIL"]

    assert checks["send-dbops-engineer-1"] == checks["send-dbops-engineer-2"] == passed
    lost = checks["send-dbops-engineer-3"]
    assert lost[0] is False and lost[1].startswith("Connection unexpectedly closed")
    for number in range(4, 11):
        assert checks[f"send-dbops-engineer-{number}"] == (False, "connection lost")
    assert checks["arrival-dbops-engineer"] == passed
    result = setup.result("AR-SP-02", package="P-07")
    assert [m["accepted"] for m in result["mailboxes"]] == [7, 2]
    assert result["mailboxes"][1]["checked_message"] == 2
    assert "send-dbops-engineer-4: FAILED (connection lost)" in output.splitlines()

    files = setup.recording_files("email")
    assert files == [f"{n:04d}-send-noc-l1-{n}.json" for n in range(1, 11)] + [
        f"{n:04d}-send-dbops-engineer-{n - 10}.json" for n in range(11, 14)
    ]
    rcpt = setup.recording("email", "0003-send-noc-l1-3.json")
    assert rcpt["response"] == {"status": 550, "headers": {}, "body": "Mailbox unavailable"}
    assert rcpt["error"] is None
    data_command = setup.recording("email", "0005-send-noc-l1-5.json")
    assert data_command["response"] == {"status": 451, "headers": {}, "body": "Try again later"}
    assert data_command["error"].startswith("(451, b'Try again later')")
    body = setup.recording("email", "0007-send-noc-l1-7.json")
    assert body["response"]["status"] == 554 and body["error"] is None
    closed = setup.recording("email", "0013-send-dbops-engineer-3.json")
    assert closed["response"] is None
    assert closed["error"].startswith("Connection unexpectedly closed")


def test_email_login_refused(smtp, monkeypatch, capsys):
    """AR-SP-02 a refused login fails connect-* with its reply; the mailbox's other checks are "not run", and the
    other mailbox goes on."""
    setup, fake, secret = smtp
    last_choice(monkeypatch)
    fake.refuse_login = {1}
    code, stdin = run(setup, fake, monkeypatch, [""] + answers_for(fake, "dbops-engineer", 10))
    output = capsys.readouterr().out
    assert code == 1
    assert stdin.answers == []
    checks = details(setup)
    assert checks["connect-noc-l1"] == (False, "login 535 Authentication Credentials Invalid")
    for name in [f"send-noc-l1-{k}" for k in range(1, 11)] + ["arrival-noc-l1", "lines-noc-l1", "link-noc-l1"]:
        assert checks[name] == (False, "not run")
    assert all(passed for name, (passed, _) in checks.items() if "dbops-engineer" in name)
    assert len(fake.messages) == 10
    result = setup.result("AR-SP-02", package="P-07")
    assert result["mailboxes"][0] == {
        "key": "noc-l1",
        "address": MAILBOXES["noc-l1"],
        "accepted": 0,
        "seconds_to_arrival": None,
        "missing_in_junk": None,
        "checked_message": None,
    }
    assert secret["username"] not in output and secret["password"] not in output


def test_email_connect_refused(smtp, monkeypatch, capsys):
    """AR-SP-02 a server that cannot be reached fails connect-* with the error text for both mailboxes."""
    setup, fake, secret = smtp
    fake.close()
    spike_fakes.answer_with(monkeypatch, setup.clock, ["", ""])
    assert email_spike.main(["--address", fake.address]) == 1
    capsys.readouterr()
    checks = details(setup)
    assert checks["connect-noc-l1"][0] is False and "refused" in checks["connect-noc-l1"][1].lower()
    assert checks["connect-dbops-engineer"][0] is False
    assert checks["arrival-dbops-engineer"] == (False, "not run")
    assert setup.recording_files("email") == []


def test_email_arrival(smtp, monkeypatch, capsys):
    """AR-SP-02 arrival: Enter after 59 s on the test clock passes, after 61 s fails with its seconds."""
    setup, fake, secret = smtp
    last_choice(monkeypatch)
    answers = answers_for(fake, "noc-l1", 10, arrival=("", 59)) + answers_for(
        fake, "dbops-engineer", 10, arrival=("", 61)
    )
    code, _ = run(setup, fake, monkeypatch, answers)
    capsys.readouterr()
    assert code == 1
    checks = details(setup)
    assert checks["arrival-noc-l1"] == (True, "")
    assert checks["arrival-dbops-engineer"] == (False, "61.0 s after the first message was accepted")
    result = setup.result("AR-SP-02", package="P-07")
    assert [m["seconds_to_arrival"] for m in result["mailboxes"]] == [59.0, 61.0]


def test_email_arrival_missing(smtp, monkeypatch, capsys):
    """AR-SP-02 arrival: n fails with "the owner reported messages not in the Inbox" and sets missing_in_junk from
    the next answer; lines and link are still asked."""
    setup, fake, secret = smtp
    last_choice(monkeypatch)
    answers = ["", ("n", 125), "y", "y", lambda: link_of(fake, MAILBOXES["noc-l1"], 10)]
    answers += ["", ("no", 130), "n", "n", "not a link"]
    code, _ = run(setup, fake, monkeypatch, answers)
    capsys.readouterr()
    assert code == 1
    checks = details(setup)
    reported = (False, "the owner reported messages not in the Inbox")
    assert checks["arrival-noc-l1"] == checks["arrival-dbops-engineer"] == reported
    assert checks["lines-noc-l1"] == checks["link-noc-l1"] == (True, "")
    assert checks["lines-dbops-engineer"] == (False, "the owner answered no")
    assert checks["link-dbops-engineer"] == (False, "the pasted link is not the link sent")
    result = setup.result("AR-SP-02", package="P-07")
    assert [m["missing_in_junk"] for m in result["mailboxes"]] == [True, False]
    assert [m["seconds_to_arrival"] for m in result["mailboxes"]] == [125.0, 130.0]


def test_email_no_answer(smtp, monkeypatch, capsys):
    """AR-SP-02 an end of input at the first question of mailbox 1 fails every check from there with "no answer",
    sends nothing to mailbox 2, and the result file is still written."""
    setup, fake, secret = smtp
    code, _ = run(setup, fake, monkeypatch, [])
    output = capsys.readouterr().out
    assert code == 1
    result = setup.result("AR-SP-02", package="P-07")
    assert len(result["checks"]) == 28
    assert all(c["passed"] is False and c["detail"] == "no answer" for c in result["checks"])
    assert fake.connections == 0 and fake.messages == []
    assert output.count("Open the Inbox of") == 1
    assert setup.recording_files("email") == []


def test_email_no_answer_at_junk(smtp, monkeypatch, capsys):
    """AR-SP-02 an end of input at the Junk question leaves arrival failed with its detail; the checks after it, and
    every check of mailbox 2, are "no answer"."""
    setup, fake, secret = smtp
    code, _ = run(setup, fake, monkeypatch, ["", "n"])
    capsys.readouterr()
    assert code == 1
    checks = details(setup)
    assert checks["arrival-noc-l1"] == (False, "the owner reported messages not in the Inbox")
    assert checks["lines-noc-l1"] == checks["link-noc-l1"] == (False, "no answer")
    assert checks["connect-dbops-engineer"] == checks["link-dbops-engineer"] == (False, "no answer")
    result = setup.result("AR-SP-02", package="P-07")
    assert result["mailboxes"][0]["missing_in_junk"] is None
    assert result["mailboxes"][0]["checked_message"] is None
    assert len(fake.messages) == 10 and fake.connections == 1


def test_email_recordings(smtp, monkeypatch, capsys):
    """AR-SP-02 recordings: the keys and values of Section 6.5; no file holds the username, the password, or the base64
    of either or of the AUTH PLAIN string."""
    setup, fake, secret = smtp
    last_choice(monkeypatch)
    stale = setup.recordings / "email" / "0099-old.json"
    stale.parent.mkdir(parents=True)
    stale.write_text("{}")
    code, _ = run(setup, fake, monkeypatch, answers_for(fake, "noc-l1", 10) + answers_for(fake, "dbops-engineer", 10))
    output = capsys.readouterr().out
    assert code == 0
    files = setup.recording_files("email")
    assert (
        len(files) == 20 and files[0] == "0001-send-noc-l1-1.json" and files[19] == "0020-send-dbops-engineer-10.json"
    )
    first = setup.recording("email", files[0])
    assert list(first) == ["seq", "call", "started_at", "duration_ms", "request", "response", "error"]
    assert first["seq"] == 1 and first["call"] == "send-noc-l1-1"
    assert isinstance(first["duration_ms"], int) and first["started_at"].endswith("Z")
    request = first["request"]
    assert request["method"] == "SMTP" and request["url"] == "${endpoint.smtp}"
    assert list(request["headers"]) == HEADERS
    assert request["headers"]["From"] == SENDER and request["headers"]["To"] == MAILBOXES["noc-l1"]
    assert request["headers"]["Content-Type"] == 'text/plain; charset="utf-8"'
    assert request["headers"]["Content-Transfer-Encoding"] == "7bit"
    assert request["body"] == fake.messages[0]["data"].split(b"\r\n\r\n", 1)[1].decode()
    assert first["response"]["status"] == 250 and first["response"]["headers"] == {}
    assert first["response"]["body"].startswith("Ok 0100")
    assert first["error"] is None
    username, password = secret["username"], secret["password"]
    forbidden = [username, password, base64.b64encode(username.encode()).decode()]
    forbidden += [base64.b64encode(password.encode()).decode()]
    forbidden += [base64.b64encode(f"\0{username}\0{password}".encode()).decode()]
    texts = [(setup.recordings / "email" / name).read_text() for name in files]
    texts += [(setup.results / "P-07-AR-SP-02.json").read_text(), output]
    for value in forbidden:
        assert all(value not in text for text in texts)


def test_email_secret_in_reply_stops(smtp, monkeypatch, capsys):
    """AR-SP-02 a reply that would put the password into a recording stops the spike; nothing more is written."""
    setup, fake, secret = smtp
    original = fake.session

    def leaking(handler, context):
        handler.send = _leaky(handler.send, secret["password"])
        return original(handler, context)

    fake.session = leaking
    code, _ = run(setup, fake, monkeypatch, [""])
    output = capsys.readouterr().out
    path = setup.recordings / "email" / "0001-send-noc-l1-1.json"
    assert code == 1
    assert output.splitlines()[-1] == f"A secret would be written to {path}; nothing written."
    assert setup.recording_files("email") == []
    assert not setup.results.exists()


def _leaky(send, password):
    def send_line(line):
        return send(f"{line} {password}" if line.startswith("250 Ok 0100") else line)

    return send_line


def test_email_secrets(tmp_path, monkeypatch, capsys):
    """AR-S-13 a missing smtp parameter, or a value that is not the JSON object of username and password, stops the
    spike with the AR-S-13 message before any file changes."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    assert email_spike.main([]) == 1
    assert capsys.readouterr().out == "Missing secret /vigil/demo/smtp (AR-S-13).\n"
    for value in (
        "not json",
        "[1, 2]",
        json.dumps({"username": "u"}),
        json.dumps({"username": "", "password": "p"}),
        json.dumps({"username": "u", "password": 7}),
    ):
        setup.secret("smtp", value)
        assert email_spike.main([]) == 1
        assert capsys.readouterr().out == "Secret /vigil/demo/smtp is not valid (AR-S-13).\n"
    assert setup.changed_files() == []


def test_email_usage(tmp_path, monkeypatch, capsys):
    """AR-SP-02 wrong arguments or an address that is not {host}:{port} print the usage line and exit 2."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    for arguments in (
        ["--address"],
        ["--address", "smtp.example.test"],
        ["--address", ":587"],
        ["--address", "host:"],
        ["--address", "host:0"],
        ["--address", "host:65536"],
        ["--address", "host:5a7"],
        ["--address", "host:+587"],
        ["--address", "host:587", "extra"],
        ["--server", "host:587"],
    ):
        assert email_spike.main(arguments) == 2, arguments
        assert capsys.readouterr().out == "Usage: email_spike.py [--address {host}:{port}]\n"
    assert email_spike.server_argument(["--address", "::1:2525"]) == ("::1", 2525)
    assert email_spike.server_argument([]) == ("email-smtp.us-east-1.amazonaws.com", 587)
    assert setup.changed_files() == []


def test_mailbox_values(tmp_path, monkeypatch, capsys):
    """AR-K-21 deploy/values/demo.yaml holds mailbox.{key} for exactly the nine keys, each vigil-{key}@techfinity.ae;
    a values file without mailbox.dbops-engineer stops the email spike with its message."""
    keys = [
        "noc-l1",
        "db-ops",
        "vas-service-owners",
        "it-apps",
        "noc-shift-lead",
        "noc-management",
        "customer-care",
        "dbops-engineer",
        "dbops-lead",
    ]
    values = yaml.safe_load(common.VALUES.read_text())
    mailboxes = {name: value for name, value in values.items() if name.startswith("mailbox.")}
    assert list(mailboxes) == [f"mailbox.{key}" for key in keys]
    assert mailboxes == {f"mailbox.{key}": f"vigil-{key}@techfinity.ae" for key in keys}

    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    setup.secret("smtp", json.dumps(spike_fakes.smtp_secret_value()))
    copy = tmp_path / "demo.yaml"
    shutil.copy(common.VALUES, copy)
    copy.write_text("".join(line for line in copy.read_text().splitlines(True) if "mailbox.dbops-engineer" not in line))
    monkeypatch.setattr(common, "VALUES", copy)
    assert email_spike.main([]) == 1
    assert capsys.readouterr().out == "deploy/values/demo.yaml has no mailbox.dbops-engineer (AR-K-21).\n"
    assert setup.changed_files() == []


def test_email_inputs(tmp_path, monkeypatch, capsys):
    """AR-SP-02 a missing demo_domain or from_address stops the spike with "{file} has no {name}."."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    copy = tmp_path / "demo.yaml"
    copy.write_text("endpoint.jira: https://example.test\n")
    monkeypatch.setattr(common, "VALUES", copy)
    assert email_spike.main([]) == 1
    assert capsys.readouterr().out == "deploy/values/demo.yaml has no demo_domain.\n"
    data = tmp_path / "data"
    shutil.copytree(common.ROOT / "docs" / "31-demo-data", data / "docs" / "31-demo-data")
    settings = data / "docs" / "31-demo-data" / "07-settings.yaml"
    settings.write_text(settings.read_text().replace('from_address: "techfinity-demo@${demo_domain}"', "x: 1"))
    monkeypatch.setattr(common, "VALUES", common.ROOT / "deploy" / "values" / "demo.yaml")
    monkeypatch.setattr(common, "ROOT", data)
    assert email_spike.main([]) == 1
    assert capsys.readouterr().out == (
        "docs/31-demo-data/07-settings.yaml has no connector_instances.email-demo.settings.from_address.\n"
    )
    assert setup.changed_files() == []
