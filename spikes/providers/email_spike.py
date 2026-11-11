"""AR-SP-02: the email spike (P-07 spec Section 6.5; AR-K-20, AR-K-21).

Sends 10 messages in the form of AR-K-20 over SMTP with STARTTLS to Amazon SES for each of the shared mailboxes noc-l1
and dbops-engineer, then asks the owner whether they arrived in the Inbox within 60 s, with their lines and link whole.
Usage: email_spike.py [--address {host}:{port}]
"""

import base64
import email.message
import email.policy
import email.utils
import json
import random
import re
import smtplib
import ssl
from decimal import Decimal

import common
from teams_spike import (
    IDENTITY,
    SETTINGS,
    VALUES,
    Questions,
    acknowledge_link,
    demo_domain,
    load_data,
    read_values,
    required,
    text,
)

USAGE = "Usage: email_spike.py [--address {host}:{port}]"
TIMEOUT = 15
SERVER = ("email-smtp.us-east-1.amazonaws.com", 587)
MAILBOXES = ("noc-l1", "dbops-engineer")
MESSAGES = 10
ARRIVAL_S = 60


def server_argument(arguments):
    """The SMTP server: --address {host}:{port}, or the host and port of endpoint.smtp's demo value."""
    if not arguments:
        return SERVER
    if len(arguments) == 2 and arguments[0] == "--address":
        host, _, port = arguments[1].rpartition(":")
        if host and re.fullmatch(r"[0-9]+", port) and 1 <= int(port) <= 65535:
            return host, int(port)
    raise common.Stop(USAGE, 2)


def smtp_secret():
    """The secret smtp: a JSON object with the non-empty strings username and password (AR-S-13)."""
    try:
        value = json.loads(common.read_secret("smtp"))
    except (ValueError, TypeError):
        value = None
    if not isinstance(value, dict) or not all(text(value.get(key)) for key in ("username", "password")):
        raise common.Stop("Secret /vigil/demo/smtp is not valid (AR-S-13).")
    return value


def b64(value):
    return base64.b64encode(value.encode("utf-8")).decode("ascii")


def decoded(reply):
    return reply.decode("utf-8", errors="replace") if isinstance(reply, bytes) else str(reply)


def message_lines(run_id, number, mailbox, link):
    """The four lines of an email test message (P-07 spec Section 6.3)."""
    return [
        f"AR-SP-02 spike {run_id} - message {number} of {MESSAGES}",
        f"Sent at {common.rfc3339(common.utc_now())} to {mailbox}.",
        "Third line: sent by the P-07 email spike; nothing else uses this message.",
        f"Acknowledge: {link}",
    ]


def build_message(sender, mailbox, lines):
    """The message of AR-K-20: text/plain UTF-8, 7bit so no line is wrapped; CRLF line ends on the wire."""
    message = email.message.EmailMessage(policy=email.policy.SMTP)
    message["From"] = sender
    message["To"] = mailbox
    message["Subject"] = lines[0]
    message["Date"] = email.utils.formatdate(usegmt=True)
    message["MIME-Version"] = "1.0"
    message.set_content("\n".join(lines) + "\n", charset="utf-8", cte="7bit")
    return message


class Refused(Exception):
    """A reply that refuses a command of a send: the detail {command} {code} {message}."""


class EmailSpike:
    def __init__(self, server, secret, sender, from_address, domain, mailboxes):
        self.host, self.port = server
        self.secret = secret
        self.sender = sender
        self.from_address = from_address
        self.domain = domain
        self.mailboxes = mailboxes
        username, password = secret["username"], secret["password"]
        self.guard = common.Guard(
            secrets=[username, password, b64(username), b64(password), b64(f"\0{username}\0{password}")]
        )
        self.run = common.Run("AR-SP-02", self.guard, package="P-07")
        self.recorder = common.Recorder("email", "${endpoint.smtp}", self.guard)
        self.questions = Questions(self.run)
        self.own = {"from": sender, "server": f"{self.host}:{self.port}", "mailboxes": []}

    def checks_of(self, key):
        return (
            [f"connect-{key}"]
            + [f"send-{key}-{number}" for number in range(1, MESSAGES + 1)]
            + [f"arrival-{key}", f"lines-{key}", f"link-{key}"]
        )

    def execute(self):
        for key, mailbox in self.mailboxes:
            entry = {
                "key": key,
                "address": mailbox,
                "accepted": 0,
                "seconds_to_arrival": None,
                "missing_in_junk": None,
                "checked_message": None,
            }
            self.own["mailboxes"].append(entry)
            if self.questions.ended:
                self.questions.silent(*self.checks_of(key))
                continue
            opened = self.questions.ask(
                None,
                f"Open the Inbox of {mailbox} in Outlook on the web (your account, Open another mailbox) and keep it "
                "open. Press Enter when it is open.",
                kind="enter",
            )
            if opened is None:
                self.questions.silent(*self.checks_of(key))
                continue
            self.mailbox(key, mailbox, entry)

    def connect(self, key):
        """Check connect-{key}: the connection after STARTTLS and login, or None."""
        step = "connect"
        smtp = None
        try:
            smtp = smtplib.SMTP(self.host, self.port, timeout=TIMEOUT)
            step = "ehlo"
            self.ehlo(smtp)
            step = "starttls"
            smtp.starttls(context=ssl.create_default_context())
            step = "ehlo"
            self.ehlo(smtp)
            step = "login"
            smtp.login(self.secret["username"], self.secret["password"])
        except smtplib.SMTPResponseException as error:
            detail = f"{step} {error.smtp_code} {decoded(error.smtp_error)}"
        except Exception as error:  # every other failure of the connection is the check's detail
            detail = str(error) or type(error).__name__
        else:
            self.run.check(f"connect-{key}", True)
            return smtp
        self.run.check(f"connect-{key}", False, self.guard.redact(detail))
        if smtp is not None:
            smtp.close()
        return None

    @staticmethod
    def ehlo(smtp):
        code, reply = smtp.ehlo()
        if code != 250:
            raise smtplib.SMTPResponseException(code, reply)

    def mailbox(self, key, mailbox, entry):
        run = self.run
        smtp = self.connect(key)
        if smtp is None:
            run.not_run(*self.checks_of(key)[1:])
            return
        sent = {}
        first_accepted = None
        lost = False
        for number in range(1, MESSAGES + 1):
            call = f"send-{key}-{number}"
            if lost:
                run.check(call, False, "connection lost")
                continue
            link = acknowledge_link(self.domain)
            lines = message_lines(run.run_id, number, mailbox, link)
            message = build_message(self.sender, mailbox, lines)
            detail, lost, accepted_at = self.send(smtp, call, mailbox, message)
            if run.check(call, not detail, detail):
                sent[number] = (lines, link)
                if first_accepted is None:
                    first_accepted = accepted_at
        try:
            smtp.quit()
        except Exception:  # quit's errors are ignored
            smtp.close()
        entry["accepted"] = len(sent)
        if not sent:
            run.not_run(f"arrival-{key}", f"lines-{key}", f"link-{key}")
            return
        self.arrival(key, entry, len(sent), first_accepted)
        number = random.choice(sorted(sent))
        if self.questions.ended:
            self.questions.silent(f"lines-{key}", f"link-{key}")
            return
        entry["checked_message"] = number
        lines, link = sent[number]
        self.questions.yes(
            f"lines-{key}",
            "Open each message. Does every one show four lines, each on its own line, with the link whole, as "
            f"message {number} does here?",
            lines,
        )
        self.questions.link(
            f"link-{key}",
            f"Right-click the link in message {number} of {MESSAGES}, choose Copy link, and paste it here:",
            link,
        )

    def send(self, smtp, call, mailbox, message):
        """One message on the connection: (detail, connection lost, monotonic time of acceptance); records it."""
        data = message.as_bytes()
        started_at = common.utc_now()
        start = common.monotonic()
        last = None
        error = None
        detail = ""
        lost = False
        accepted_at = None
        command = "mail"
        try:
            code, reply = last = smtp.mail(self.from_address)
            if code != 250:
                raise Refused()
            command = "rcpt"
            code, reply = last = smtp.rcpt(mailbox)
            if code not in (250, 251):
                raise Refused()
            command = "data"
            code, reply = last = smtp.data(data)
            if code != 250:
                raise Refused()
            accepted_at = common.monotonic()
        except Refused:
            detail = f"{command} {last[0]} {decoded(last[1])}"
        except smtplib.SMTPResponseException as failure:
            last = (failure.smtp_code, failure.smtp_error)
            detail = f"{command} {failure.smtp_code} {decoded(failure.smtp_error)}"
            error = self.guard.redact(str(failure) or type(failure).__name__)
        except Exception as failure:  # any other failure ends the transaction with its text
            error = self.guard.redact(str(failure) or type(failure).__name__)
            detail = error
            lost = isinstance(failure, (smtplib.SMTPServerDisconnected, OSError))
        duration_ms = int(round((common.monotonic() - start) * 1000))
        if detail and not lost:
            try:
                smtp.rset()
            except Exception:  # rset's errors are ignored
                pass
        self.record(call, started_at, duration_ms, message, data, last, error)
        return self.guard.redact(detail), lost, accepted_at

    def record(self, call, started_at, duration_ms, message, data, last, error):
        recorder = self.recorder
        recorder.count += 1
        content = {
            "seq": recorder.count,
            "call": call,
            "started_at": common.rfc3339(started_at),
            "duration_ms": duration_ms,
            "request": {
                "method": "SMTP",
                "url": "${endpoint.smtp}",
                "headers": {name: str(value) for name, value in message.items()},
                "body": data.split(b"\r\n\r\n", 1)[1].decode("utf-8"),
            },
            "response": None if last is None else {"status": last[0], "headers": {}, "body": decoded(last[1])},
            "error": error,
        }
        path = recorder.folder / f"{recorder.count:04d}-{call}.json"
        common.write_checked(path, json.dumps(content, indent=2, ensure_ascii=False) + "\n", self.guard)

    def arrival(self, key, entry, count, first_accepted):
        """Check arrival-{key}: the owner's answer within 60 s of the first accepted message, in the Inbox."""
        answer = self.questions.ask(
            f"arrival-{key}",
            f'Press Enter as soon as all {count} messages "AR-SP-02 spike {self.run.run_id} - message … of '
            f'{MESSAGES}" are in the Inbox. If after two minutes some are not in the Inbox, type n and Enter.',
            kind="enter_or_no",
        )
        if answer is None:
            return
        seconds = common.half_up(Decimal(repr(common.monotonic() - first_accepted)), 1)
        entry["seconds_to_arrival"] = float(seconds)
        if answer:
            self.run.check(f"arrival-{key}", seconds <= ARRIVAL_S, f"{seconds} s after the first message was accepted")
            return
        self.run.check(f"arrival-{key}", False, "the owner reported messages not in the Inbox")
        entry["missing_in_junk"] = self.questions.ask(None, "Are the missing messages in Junk Email?", kind="yes_no")


def mailbox_addresses(values):
    found = []
    for key in MAILBOXES:
        address = values.get(f"mailbox.{key}")
        if not text(address):
            raise common.Stop(f"{VALUES} has no mailbox.{key} (AR-K-21).")
        found.append((key, address))
    return found


def email_settings(settings):
    """The connector instance email-demo of 07-settings.yaml."""
    for instance in settings.get("connector_instances") or []:
        if isinstance(instance, dict) and instance.get("handle") == "email-demo":
            return instance
    raise common.Stop(f"{SETTINGS} has no connector_instances.email-demo.")


def body(arguments):
    server = server_argument(arguments)
    settings = load_data(SETTINGS)
    identity = load_data(IDENTITY)
    values = read_values()
    domain = demo_domain(values)
    instance = email_settings(settings)
    from_address = required(
        (instance.get("settings") or {}).get("from_address"),
        SETTINGS,
        "connector_instances.email-demo.settings.from_address",
    ).replace("${demo_domain}", domain)
    display = required(
        (identity.get("tenant_settings") or {}).get("product_display_name"),
        IDENTITY,
        "tenant_settings.product_display_name",
    )
    mailboxes = mailbox_addresses(values)
    secret = smtp_secret()
    sender = email.utils.formataddr((display, from_address))
    spike = EmailSpike(server, secret, sender, from_address, domain, mailboxes)
    spike.recorder.clear()
    spike.execute()
    return spike.run.write(spike.own)


def main(arguments):
    return common.main_wrapper(body, arguments)


if __name__ == "__main__":
    common.exit_with(body)
