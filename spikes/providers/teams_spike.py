"""SB-T-06: the Teams spike (P-07 spec Section 6.4; AR-K-17 to AR-K-19).

Posts one message in the Adaptive Card payload of AR-K-18 to the Workflows webhook of each of the nine Teams channels
of DD-F-07, at least 300 ms apart (AR-K-19), then asks the owner whether each message shows its four lines and whether
its link can be copied from the Teams desktop client. Usage: teams_spike.py

The helpers of the inputs, the link and the link rule are also used by email_spike.py (P-07 spec Sections 6.1, 6.6).
"""

import json
import re
import urllib.parse
import uuid

import common
import yaml

USAGE = "Usage: teams_spike.py"
TIMEOUT = 15
GAP_S = 0.3
USER_AGENT = "vigil-p07-spike"
SETTINGS = "docs/31-demo-data/07-settings.yaml"
IDENTITY = "docs/31-demo-data/01-tenant-identity.yaml"
VALUES = "deploy/values/demo.yaml"
CHANNELS = 9


# ---------------------------------------------------------------- shared with email_spike.py
def load_data(relative):
    """A data file of docs/31-demo-data/ read with yaml.safe_load; a failure stops the spike before any file changes."""
    try:
        with open(common.ROOT / relative, encoding="utf-8") as file:
            return yaml.safe_load(file) or {}
    except (OSError, yaml.YAMLError) as error:
        raise common.Stop(f"{relative}: {error}") from None


def read_values():
    try:
        return common.read_values()
    except (OSError, yaml.YAMLError) as error:
        raise common.Stop(f"{VALUES}: {error}") from None


def text(value):
    """True for a non-empty string."""
    return isinstance(value, str) and value != ""


def required(value, file, name):
    """The value when it is a non-empty string; otherwise the spike stops with "{file} has no {name}."."""
    if not text(value):
        raise common.Stop(f"{file} has no {name}.")
    return value


def demo_domain(values):
    return required(values.get("demo_domain"), VALUES, "demo_domain")


def acknowledge_link(domain):
    """A link of the form of the Acknowledge page link of AR-I-101 with a new random identifier (CTX-D-065 (8))."""
    return f"https://demo.{domain}/m/acknowledge/{uuid.uuid4()}"


def link_detail(pasted, link):
    """The link rule of P-07 spec Section 6.6: "" when the pasted text is the link, otherwise the detail."""
    if pasted == link:
        return ""
    if not pasted:
        return "no link pasted"
    try:
        parts = urllib.parse.urlsplit(pasted)
        host = parts.hostname or ""
        wrapped = urllib.parse.parse_qs(parts.query).get("url", [])
    except ValueError:
        return "the pasted link is not the link sent"
    if parts.scheme == "https" and host.endswith("safelinks.protection.outlook.com") and link in wrapped[:1]:
        return "the link was rewritten by Safe Links"
    return "the pasted link is not the link sent"


class Questions:
    """The owner's questions of one run: after an end of input, every later check is "no answer" (Section 6.6)."""

    def __init__(self, run):
        self.run = run
        self.ended = False

    def ask(self, check, question, lines=(), *, kind):
        """The answer, or None when input has ended (then check, when given, is listed with "no answer")."""
        if not self.ended:
            try:
                return common.ask(question, lines, kind=kind)
            except common.NoAnswer:
                self.ended = True
        if check:
            self.run.check(check, False, "no answer")
        return None

    def yes(self, check, question, lines=()):
        """Check {check} from a yes-or-no question: yes passes, no fails with "the owner answered no"."""
        answer = self.ask(check, question, lines, kind="yes_no")
        if answer is not None:
            self.run.check(check, answer, "the owner answered no")

    def link(self, check, question, link):
        """Check {check} from a pasted link, judged by the link rule."""
        pasted = self.ask(check, question, kind="text")
        if pasted is not None:
            detail = link_detail(pasted, link)
            self.run.check(check, not detail, detail)

    def silent(self, *checks):
        """Lists the checks with "no answer"."""
        for check in checks:
            self.run.check(check, False, "no answer")


# ---------------------------------------------------------------- the Teams spike
def webhook_valid(value):
    """AR-S-13 for teams-*: a string without whitespace, an https address with a host (or http on 127.0.0.1)."""
    if not text(value) or re.search(r"\s", value):
        return False
    try:
        parts = urllib.parse.urlsplit(value)
        host = parts.hostname
    except ValueError:
        return False
    return (parts.scheme == "https" and bool(host)) or (parts.scheme == "http" and host == "127.0.0.1")


def guarded(address):
    """The values of a webhook address no file may hold: the address and its sig value, as written and decoded."""
    values = [address]
    query = urllib.parse.urlsplit(address).query
    for part in query.split("&"):
        if part.startswith("sig="):
            values.append(part[len("sig=") :])
    values += urllib.parse.parse_qs(query).get("sig", [])
    return values


def channels(settings):
    """The Teams delivery addresses of 07-settings.yaml in file order: [(key, address, secret name)]."""
    entries = settings.get("delivery_addresses") or []
    teams = [(index, entry) for index, entry in enumerate(entries) if isinstance(entry, dict)]
    teams = [(index, entry) for index, entry in teams if entry.get("channel") == "teams"]
    if len(teams) != CHANNELS:
        raise common.Stop("07-settings.yaml has not 9 Teams delivery addresses (DD-F-07).")
    references = {
        reference.get("handle"): reference
        for reference in settings.get("credential_references") or []
        if isinstance(reference, dict)
    }
    found = []
    for index, entry in teams:
        key = required(entry.get("subject_key"), SETTINGS, f"delivery_addresses[{index}].subject_key")
        address = required(entry.get("address"), SETTINGS, f"delivery_addresses[{index}].address")
        handle = required(entry.get("credential_ref"), SETTINGS, f"delivery_addresses[{index}].credential_ref")
        locator = (references.get(handle) or {}).get("locator")
        if not text(locator) or not locator.startswith("secret:") or locator == "secret:":
            raise common.Stop(f"{SETTINGS} has no credential_references.{handle}.locator.")
        found.append((key, address, locator[len("secret:") :]))
    return found


def message_lines(run_id, address, link):
    """The four lines of a Teams test message (P-07 spec Section 6.3)."""
    return [
        f"SB-T-06 spike {run_id} - {address}",
        "Second line: the four lines of this message are on separate lines.",
        "Third line: sent by the P-07 Teams spike; nothing else uses this message.",
        f"Acknowledge: {link}",
    ]


def payload(lines, link):
    """The Adaptive Card of AR-K-18: one TextBlock per line, line 1 bold, line 4 with the link as Markdown."""
    texts = lines[:3] + [f"Acknowledge: [{link}]({link})"]
    body = [{"type": "TextBlock", "text": texts[0], "wrap": True, "weight": "Bolder"}]
    body += [{"type": "TextBlock", "text": line, "wrap": True} for line in texts[1:]]
    return {
        "type": "message",
        "attachments": [
            {
                "contentType": "application/vnd.microsoft.card.adaptive",
                "content": {
                    "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                    "type": "AdaptiveCard",
                    "version": "1.4",
                    "body": body,
                },
            }
        ],
    }


class TeamsSpike:
    def __init__(self, domain, targets):
        """targets: [(key, address, secret name, webhook address)] in DD-F-07 order."""
        self.domain = domain
        self.targets = targets
        secrets = [value for target in targets for value in guarded(target[3])]
        self.guard = common.Guard(secrets=secrets)
        self.run = common.Run("SB-T-06", self.guard, package="P-07")
        self.recorder = common.Recorder("teams", "", self.guard)
        self.own = {"channels": []}

    def execute(self):
        run = self.run
        posted = []
        for number, (key, address, name, webhook) in enumerate(self.targets):
            if number:
                common.pause(GAP_S)
            link = acknowledge_link(self.domain)
            lines = message_lines(run.run_id, address, link)
            body = json.dumps(payload(lines, link), ensure_ascii=False)
            headers = [("Content-Type", "application/json")]
            answer = common.call_http(f"post-{key}", webhook, "POST", "", headers, body, TIMEOUT, user_agent=USER_AGENT)
            if answer.error is not None:
                answer.error = self.guard.redact(answer.error)
            self.recorder.record(answer, url=f"secret:{name}")
            self.own["channels"].append(
                {"key": key, "address": address, "status": answer.status, "duration_ms": answer.duration_ms}
            )
            if not answer.answered:
                detail = answer.error
            elif not 200 <= answer.status <= 299:
                detail = f"status {answer.status}"
            else:
                detail = ""
            passed = run.check(f"post-{key}", not detail, detail)
            posted.append((key, address, lines, link, passed))

        questions = Questions(run)
        for key, address, lines, link, passed in posted:
            if not passed:
                run.not_run(f"lines-{key}", f"link-{key}")
                continue
            questions.yes(
                f"lines-{key}",
                f'In the Teams desktop client, open the channel "{address}". Does the message '
                f'"{lines[0]}" show these four lines, each on its own line?',
                lines,
            )
            questions.link(
                f"link-{key}", "Right-click the link in that message, choose Copy link, and paste it here:", link
            )


def body(arguments):
    if arguments:
        raise common.Stop(USAGE, 2)
    settings = load_data(SETTINGS)
    domain = demo_domain(read_values())
    targets = []
    for key, address, name in channels(settings):
        webhook = common.read_secret(name)
        if not webhook_valid(webhook):
            raise common.Stop(f"Secret /vigil/demo/{name} is not valid (AR-S-13).")
        targets.append((key, address, name, webhook))
    spike = TeamsSpike(domain, targets)
    spike.recorder.clear()
    spike.execute()
    return spike.run.write(spike.own)


def main(arguments):
    return common.main_wrapper(body, arguments)


if __name__ == "__main__":
    common.exit_with(body)
