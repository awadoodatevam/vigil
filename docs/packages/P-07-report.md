# P-07 — Report: Spikes: Teams and email

| Field | Value |
| --- | --- |
| Package | P-07 Spikes: Teams and email (spec `docs/packages/P-07.md`) |
| Branch | `p-07-spikes-teams-email` |
| Pin date (BP-R-08) | 10 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1+1) |
| State | Built; the gate command passes in the build session (Section 10); pull request opened. Owner-run steps of spec Section 6.8 done on 11 November 2026 (commit `a5360fb`): both spikes passed, all 27 and 28 checks. Results recorded on 11 November 2026 by the session "Record the owner-run results of P-07" (Section 8). |

## 1. What was built

| ID | Part built | Where | Proof |
| --- | --- | --- | --- |
| SB-T-06 | The Teams spike (spec 6.4): the nine Teams delivery addresses of `07-settings.yaml` in DD-F-07 order, their webhook secrets checked (AR-S-13), one post per channel (`post-{key}`), then the owner's questions `lines-{key}` and `link-{key}` for each channel whose post passed; result file `P-07-SB-T-06.json` (`channels`); recordings `dev/fakes/recordings/teams/` with `url` `secret:{secret name}`; `run.sh teams` (spec 6.7) | `spikes/providers/teams_spike.py`, `common.py`, `run.sh` | `test_teams_spike.py`, `test_providers_run.py`, `test_spike_common.py` |
| AR-SP-02 | The email spike (spec 6.5): per mailbox (`noc-l1`, `dbops-engineer`) the Inbox question, `connect-{key}`, `send-{key}-1` to `-10` with refusals, `RSET` and "connection lost", `arrival-{key}` (60 s from the first accepted message, `missing_in_junk`), `lines-{key}`, `link-{key}`; result file `P-07-AR-SP-02.json` (`from`, `server`, `mailboxes`); recordings `dev/fakes/recordings/email/`; `run.sh email` | `spikes/providers/email_spike.py`, `run.sh` | `test_email_spike.py`, `test_providers_run.py` |
| AR-K-18 | The Adaptive Card payload, exactly as spec 6.4 (key order, `weight` `Bolder` on line 1 only, line 4 `Acknowledge: [{link}]({link})`), `json.dumps(…, ensure_ascii=False)` as UTF-8, headers `Content-Type` and `User-Agent: vigil-p07-spike`; `post-*` passes for 200 to 299 | `teams_spike.py` | `test_teams_spike.py` (`test_teams_payload`) |
| AR-K-19 | `common.pause(0.3)` between two posts, after the earlier one's answer or error (eight pauses) | `teams_spike.py` | `test_teams_spike.py` (`test_teams_pacing`) |
| AR-K-20 | The SMTP messages: `smtplib.SMTP(host, port, timeout=15)`, `EHLO`, `STARTTLS` with `ssl.create_default_context()`, `EHLO`, login; `EmailMessage(policy=email.policy.SMTP)` with `From`, `To`, `Subject` (line 1), `Date`, `MIME-Version`, then `set_content(…, charset="utf-8", cte="7bit")`; the four lines joined and ended by CRLF; sent as `msg.as_bytes()`; `send-*` passes when `DATA`'s final reply is 250 | `email_spike.py` | `test_email_spike.py` (`test_email_run`, `test_email_auth_login`) |
| AR-K-21 | The nine lines `mailbox.{key}: vigil-{key}@techfinity.ae` after `endpoint.jira`, in AR-K-21's order; the email spike reads `mailbox.noc-l1` and `mailbox.dbops-engineer` | `deploy/values/demo.yaml`, `email_spike.py` | `test_email_spike.py` (`test_mailbox_values`) |
| AR-S-13 | The checks of the secrets `teams-team-{key}`, `teams-user-{username}` (a string without whitespace, `https` with a host, or `http` on `127.0.0.1`) and `smtp` (a JSON object with non-empty `username` and `password`); the guarded values of spec 6.2 (webhook addresses, `sig` as written and decoded, username, password, their base64 and that of `\0{username}\0{password}`) | `teams_spike.py`, `email_spike.py` | `test_teams_spike.py`, `test_email_spike.py` (`test_email_secrets`) |
| SL-ASM-02 | | owner-run | checked by review: `P-07-SB-T-06.json`, `post-*`, `lines-*` and `link-*` passed for all nine channels (Section 8.2) |
| AR-ASM-01 | | owner-run | checked by review: as SL-ASM-02 |
| AR-ASM-02 | | owner-run | checked by review: `P-07-SB-T-06.json` (nine channels and workflows) and `P-07-AR-SP-02.json` (two of the nine mailboxes), all checks passed (Sections 8.2, 8.3) |
| AR-ASM-04 | | owner-run | checked by review: `P-07-AR-SP-02.json` `arrival-*` passed for both mailboxes (15.3 s and 18.8 s, Section 8.3) |
| AR-H-08 | | owner-run | checked by review: `P-07-AR-SP-02.json` `connect-*` and all 20 `send-*` passed (Section 8.3) |
| AR-H-10 | | owner-run | checked by review: as AR-ASM-02 |

`common.py` changes (spec 6.1, complete list): `Run(spike, guard, package=None)`; `call_http(…, user_agent=USER_AGENT)`; `Recorder.record(answer, url=None)`; `Guard.redact(text)`; `ask(question, lines=(), *, kind)` and `NoAnswer` (spec 6.6). The P-06 spikes call none of the new arguments, so they behave as before (their tests pass unchanged).

Other changes (spec 6.9): `dev/fakes/recordings/teams/README.md` and `email/README.md`; `spikes/providers/README.md` (title "Provider spikes (P-06, P-20, P-07)", the two rows, the prerequisites, the questions, the result keys, the usage lines); `spikes/README.md` row `providers/`; `spikes/results/README.md` rows for `P-07-SB-T-06.json` and `P-07-AR-SP-02.json` and the closing sentence; `README.md` and `CLAUDE.md` command lines.

## 2. Tests added

All under `spikes/providers/`, pytest, run by the gate against fakes (no test reaches Teams, SES or AWS).

| Test | IDs | What it shows |
| --- | --- | --- |
| `test_teams_spike.py::test_teams_run` | SB-T-06 | Nine posts in DD-F-07 order; 200 and 202 pass, 400 fails `post-db-ops` with "status 400", a refused connection fails `post-it-apps` with the error text, both make their questions "not run"; `y` (also ` Y `) with a correct paste passes; `no` (after a repeated prompt for `maybe`), an empty paste, a wrong paste and a Safe Links address wrapping the link fail with their details; the questions' texts with the four lines; the result file and its `channels` |
| `test_teams_spike.py::test_teams_payload` | AR-K-18 | Each payload exactly as spec 6.4 (text and key order, `weight` on line 1 only, the Markdown link), the two headers, the webhook path and query as seeded, nine distinct links; 200 and 202 pass, 400 fails |
| `test_teams_spike.py::test_teams_pacing` | AR-K-19 | `common.pause` replaced by a recorder: called with 0.3 exactly eight times, each after post n and before post n+1 |
| `test_teams_spike.py::test_teams_no_answer` | SB-T-06 | An end of input at a question: that check and every later question's check "no answer", a channel whose post failed stays "not run", the result file is written |
| `test_teams_spike.py::test_teams_recordings` | SB-T-06 | One recording per post, `url` `secret:teams-team-noc-l1` … `secret:teams-user-dbops-lead`, keys and headers; the old `*.json` removed and the README kept; no recording, result file or printed line holds a webhook address or its `sig` value (written or decoded) |
| `test_teams_spike.py::test_teams_secret_in_answer_stops` | SB-T-06 | An answer body that echoes the decoded `sig` value stops the spike with "A secret would be written to …; nothing written." |
| `test_teams_spike.py::test_teams_error_redacted` | SB-T-06 | An error text naming the webhook address and the decoded `sig` is `<redacted>` in the check's detail, the recording and the output |
| `test_teams_spike.py::test_teams_usage`, `test_teams_link_rule` | SB-T-06 | Any argument: the usage line, exit 2, no file; the link rule of spec 6.6 |
| `test_email_spike.py::test_email_run` | AR-K-20 | `STARTTLS` before `AUTH` (both connections); 20 messages to the two mailboxes of the values file, each with its own link; `From` "Techfinity <techfinity-demo@vigil.solviclabs.com>", `To`, `Subject` = line 1, `MIME-Version`, `Content-Type: text/plain; charset="utf-8"`, `Content-Transfer-Encoding: 7bit`, the headers in the order of spec 6.5; body = the four lines joined by CRLF and ended by CRLF, line 4 whole (longer than 78 characters); all 28 checks pass; result file |
| `test_email_spike.py::test_email_auth_login` | AR-K-20 | A server offering only `AUTH LOGIN`: the login passes after `STARTTLS` |
| `test_email_spike.py::test_email_failures` | AR-SP-02 | A refused `RCPT` (message 3, `rcpt 550 …`), a refused `DATA` command (message 5, `data 451 …`) and a 554 after the body (message 7, `data 554 Message rejected: …`), each followed by `RSET` and a passing next message; the connection ended by the fake fails message 3 of mailbox 2 with its text and messages 4 to 10 with "connection lost"; the recordings of these cases (`response`, `error`) |
| `test_email_spike.py::test_email_login_refused` | AR-SP-02 | A refused login fails `connect-noc-l1` with `login 535 …`, the mailbox's other checks "not run", the other mailbox passes |
| `test_email_spike.py::test_email_connect_refused` | AR-SP-02 | No server: `connect-*` fails with the error text for both mailboxes, nothing recorded |
| `test_email_spike.py::test_email_arrival` | AR-SP-02 | Enter after 59 s on the test clock passes; after 61 s fails with "61.0 s after the first message was accepted" |
| `test_email_spike.py::test_email_arrival_missing` | AR-SP-02 | `n` fails with "the owner reported messages not in the Inbox" and sets `missing_in_junk` (true, false) from the next answer; `lines` and `link` still asked |
| `test_email_spike.py::test_email_no_answer` | AR-SP-02 | An end of input at the first question of mailbox 1: all 28 checks "no answer", no connection, nothing sent to mailbox 2, the result file written |
| `test_email_spike.py::test_email_no_answer_at_junk` | AR-SP-02 | An end of input at the Junk question: `arrival` keeps its detail, the later checks are "no answer" |
| `test_email_spike.py::test_email_recordings` | AR-SP-02 | Recording keys and values of spec 6.5; no recording, result file or printed line holds the username, the password, the base64 of either or of the `AUTH PLAIN` string |
| `test_email_spike.py::test_email_secret_in_reply_stops` | AR-SP-02 | A reply holding the password stops the spike before the first recording is written |
| `test_email_spike.py::test_email_secrets` | AR-S-13 | No `smtp` parameter, and values that are not the JSON object, stop with the AR-S-13 messages before any file changes |
| `test_email_spike.py::test_email_usage`, `test_email_inputs` | AR-SP-02 | Wrong arguments and addresses: the usage line, exit 2; a missing `demo_domain` or `from_address` stops with "{file} has no {name}." |
| `test_email_spike.py::test_mailbox_values` | AR-K-21 | `deploy/values/demo.yaml` holds exactly the nine `mailbox.{key}` values `vigil-{key}@techfinity.ae` in order; a copy without `mailbox.dbops-engineer` stops the spike with its message |
| `test_teams_spike.py::test_teams_secrets` | AR-S-13 | A missing `teams-team-*` and `teams-user-*` parameter; webhook values that are empty, not an address, not `https` (`ftp`), without a host, `http` for a host other than `127.0.0.1`, with whitespace, or not parseable: the AR-S-13 messages before any file changes |
| `test_teams_spike.py::test_teams_inputs` | SB-T-06 | Eight Teams delivery addresses stop with "07-settings.yaml has not 9 Teams delivery addresses (DD-F-07)."; an entry without `address` and a locator without `secret:` stop with "{file} has no {name}." |
| `test_providers_run.py::test_teams` | SB-T-06 | `run.sh teams` in the virtual environment with standard input passed on (the answers given to `run.sh` reach the spike) and exit 1 from the spike; `teams --package P-20`, `email --package P-20` and `teams email` print the usage line and exit 2 |
| `test_providers_run.py::test_email_without_secret` | SB-T-06 | `run.sh email` without `smtp`: "Missing secret /vigil/demo/smtp (AR-S-13)." and exit 1 |
| `test_spike_common.py` (5 tests) | SB-T-06 | `Run` with and without `package`; `ask` for each kind, its repeated prompt and `NoAnswer`; `Guard.redact`; `Recorder.record` with `url` |

Changed existing tests (spec 7, only where they must): `test_providers_run.py` — the new usage line; `slack` instead of `teams` in the wrong-argument cases of `test_usage_and_identity` and `test_package_wrong_values`; `Root.run` takes an optional `input` (it passes `stdin=subprocess.DEVNULL` only when none is given). `spike_fakes.py` — `Setup.result(name, package="P-06")`; `Answers` (standard input whose answers can advance the test clock, given as texts or as functions read when the answer is read, since the links are random); `answer_with`; `certificate` (`openssl req -x509` for `IP:127.0.0.1`); `smtp_secret_value` (generated at test start); `SmtpFake`.

## 3. Deviations from the spec

None in behaviour. Points the spec leaves open and how they were built:

1. The helpers both spikes use (reading the data and values files, `{file} has no {name}.`, the Acknowledge link, the link rule, the owner's questions with the "no answer" rule) are in `teams_spike.py`, which `email_spike.py` imports; no other module was added.
2. The `{name}` of "{file} has no {name}." for the items spec 6.1 names without a key path: `delivery_addresses[{index}].subject_key`, `delivery_addresses[{index}].address` and `delivery_addresses[{index}].credential_ref` (index in the file's list), `credential_references.{handle}.locator` (also when the locator does not start with `secret:`), `connector_instances.email-demo` and `connector_instances.email-demo.settings.from_address`, `tenant_settings.product_display_name`, `demo_domain`. A data or values file that cannot be read or parsed stops with `{file}: {error}`.
3. The Teams spike reads `07-settings.yaml` and `deploy/values/demo.yaml` only; it uses nothing of `01-tenant-identity.yaml`, so it does not read it.
4. `ask`: `enter` returns true for any answer; `enter_or_no` compares the answer after `strip()` and lower case, as `yes_no` does, so an answer of spaces is Enter and `N` is no.
5. `arrival-*` compares the seconds rounded half-up to one decimal with 60, so the verdict, the detail and `seconds_to_arrival` agree (60.04 s passes as 60.0).
6. `smtplib`'s `ehlo()` returns a refusing reply instead of raising; the spike treats an `EHLO` reply other than 250 as an `SMTPResponseException` at step `ehlo` (detail `ehlo {code} {message}`).
7. A send whose `MAIL` command was sent but whose connection then failed is recorded (with `response` `null` when no reply came); a send not attempted after "connection lost" is not. After a lost connection no `RSET` is sent.
8. The Teams recordings' `error` goes through `Guard.redact` as the check's detail does (spec 6.5 says so for email; for Teams it keeps the error out of the secret check's stop).
9. The `Date` header: `email.utils.formatdate(usegmt=True)` gives `… GMT`, and `EmailMessage` with `email.policy.SMTP` parses it into a date header that it writes as `… +0000` (the same time). The message and its recording therefore show `+0000`, "as `msg` holds it".
10. The SMTP fake numbers the `RCPT` and `DATA` commands it got (spec 7 "a given `RCPT`, a given `DATA` command or a given message after its body") and the connections whose login it refuses; it ends the connection at a `MAIL` after a given number of accepted messages.
11. In the tests `random.choice` of the email spike is replaced to take the last passed message, so the test can paste message `j`'s link; the seeded `sig` values contain `%2F`, so their written and decoded forms differ.
12. The AR-S-13 row of spec 7 is split by secret: the Teams cases are in `test_teams_spike.py::test_teams_secrets`, the `smtp` cases in `test_email_spike.py::test_email_secrets`.

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 10 November 2026)

None. The spikes use the Python standard library (`smtplib`, `ssl`, `email`, `urllib`) and PyYAML, already pinned in `spikes/providers/requirements.txt`. The tests use the system's `openssl` command to make the fake's certificate.

## 7. Values recorded under `docs/`

None (spec Section 9). `deploy/values/demo.yaml` gets the nine mailbox values (spec 6.9).

## 8. Owner-run results

### 8.1 Steps

Run by the owner on the Mac (BP-E-03) on 11 November 2026, committed as `a5360fb` ("P-07: owner-run results of SB-T-06 and AR-SP-02"): `spikes/results/P-07-SB-T-06.json`, `spikes/results/P-07-AR-SP-02.json`, nine recordings under `dev/fakes/recordings/teams/` and twenty under `dev/fakes/recordings/email/`.

1. Prerequisites (spec 6.8 step 1): done.
2. `spikes/providers/run.sh teams` → passed (run `20261111T052608Z`, 05:26:08 to 05:29:41 UTC).
3. `spikes/providers/run.sh email` → passed (run `20261111T112835Z`, 11:28:35 to 11:30:24 UTC). No message was in Junk Email and no link was rewritten by Safe Links, so there is no owner decision item.
4. Commit and push: done; this section filled by the session "Record the owner-run results of P-07".

Review list point 3 (spec Section 10), checked in this session: no file under `dev/fakes/recordings/teams/`, `dev/fakes/recordings/email/` or `spikes/results/P-07-*.json` contains `sig=`, `powerplatform`, `logic.azure`, `webhook.office` or `AKIA`. Every Teams recording's `url` is `secret:teams-team-{key}` or `secret:teams-user-{username}`. `gitleaks git --log-opts=origin/main..HEAD`: no leaks found.

### 8.2 SB-T-06 Teams (`P-07-SB-T-06.json`; SL-ASM-02, AR-ASM-01, AR-ASM-02, AR-K-18, AR-K-19)

`passed` true; all 27 checks passed: `post-*`, `lines-*` and `link-*` for the nine channels. The owner saw the four lines of each message on separate lines in the Teams desktop client, and pasted each channel's link exactly as sent.

| Channel (`key`) | `address` | `status` | `duration_ms` |
| --- | --- | --- | --- |
| `noc-l1` | Techfinity Demo / NOC L1 | 202 | 2287 |
| `db-ops` | Techfinity Demo / Database Operations | 202 | 526 |
| `vas-service-owners` | Techfinity Demo / VAS Service Owners | 202 | 561 |
| `it-apps` | Techfinity Demo / IT Applications | 202 | 520 |
| `noc-shift-lead` | Techfinity Demo / NOC Shift Lead | 202 | 348 |
| `noc-management` | Techfinity Demo / NOC Management | 202 | 408 |
| `customer-care` | Techfinity Demo / Customer Care | 202 | 353 |
| `dbops-engineer` | Techfinity Demo / Approvals — Usman | 202 | 371 |
| `dbops-lead` | Techfinity Demo / Approvals — Farah | 202 | 615 |

Readings from the recordings:
- Every Workflows webhook answered 202 with an empty body and no `Content-Type` (`response.headers` `{}`). This confirms the assumption of spec Section 11 (202, as Microsoft documents).
- Posts took 348 to 2,287 ms. The first was the slowest; the others took at most 615 ms.
- From the end of one post to the start of the next: 323 to 340 ms (the 300 ms pause of AR-K-19, plus the spike's own work).
- No post was refused or throttled.

### 8.3 AR-SP-02 email (`P-07-AR-SP-02.json`; AR-ASM-04, AR-H-08, AR-K-20)

`passed` true; all 28 checks passed (per mailbox: `connect`, 10 `send`, `arrival`, `lines`, `link`). `from` "Techfinity <techfinity-demo@vigil.solviclabs.com>"; `server` `email-smtp.us-east-1.amazonaws.com:587`.

| Mailbox (`key`) | `address` | `accepted` | `seconds_to_arrival` | `missing_in_junk` | `checked_message` |
| --- | --- | --- | --- | --- | --- |
| `noc-l1` | vigil-noc-l1@techfinity.ae | 10 | 15.3 | null | 6 |
| `dbops-engineer` | vigil-dbops-engineer@techfinity.ae | 10 | 18.8 | null | 2 |

Readings from the recordings and the result file:
- STARTTLS and the login with the SES SMTP credentials passed for both connections. SES runs outside the sandbox (BP-OT-08), so neither mailbox needed verifying.
- SES accepted all 20 messages with `250 Ok {message id}`.
- Each message took 1,361 to 1,706 ms from `MAIL` to the final `DATA` reply, on one connection per mailbox.
- All ten messages of each mailbox were in the Inbox, not in Junk, 15.3 s and 18.8 s after SES accepted the first one (at most 60 s, AR-SP-02). That timing includes the ten sends and the owner's reaction time.
- The owner saw message 6 (`noc-l1`) and message 2 (`dbops-engineer`) with four lines and the link whole, and pasted each link exactly as sent, so Safe Links did not rewrite it.
- The recorded `Date` header reads `… +0000` (Section 3 item 9).

### 8.4 Assumptions (spec Section 11)

| Item | Result |
| --- | --- |
| SL-ASM-02, AR-ASM-01 | Proved by SB-T-06: the AR-K-18 payload arrives in each channel with its lines separate and a copyable link. |
| AR-ASM-02 | Teams part proved (nine channels and workflows answer); mailboxes: two of the nine proved by AR-SP-02, the other seven as the owner stated (CTX-D-065 (5)). |
| AR-ASM-04 | Proved by AR-SP-02 (inbox, not Junk, within 60 s, lines and link intact). |
| Mail from SES with DKIM `From` domain `vigil.solviclabs.com` not put in Junk, links not rewritten by Safe Links | Held in this run: `missing_in_junk` null for both mailboxes, `link-*` passed. |
| The Workflows webhook answers a successful post with a 2xx status | Held: 202 for all nine posts. |

## 9. Lines of production code added and changed

`tools/loc.sh origin/main HEAD` prints **0** (size S). The changes under `spikes/` are not counted (BP-R-05); `deploy/values/demo.yaml` is a data file (nine value lines).

## 10. Gate command result

Cloud session (`tools/check-all.sh`): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 1a built the local images. Step 2: `vigil-app` 172 unit tests and 576 `*IT`, `demo-control` 1 and 2, all passing; the web build, lint, type check and Vitest pass. Step 3: `ruff` clean; 275 Python tests passed and 1 skipped, the pytest lab tests included (this package changes `spikes/` and `deploy/`, CTX-D-036). Step 4: `shellcheck`. Step 5: 49 data checks, 0 failed. Step 6: compose validation. Step 7: `tools/trace.py --check`. `gitleaks git --log-opts=origin/main..HEAD`: no leaks found.

The gate ran on the tree of commit `15843c0` (the Python step started after that commit); the later commit changes only this report. GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check (spec Section 8): (1) BP-DC-01 to BP-DC-07: every part 4 row with proof `test` has a test that names it; the rows checked by review are proved by the owner-run results (Sections 1 and 8); nothing beyond the spec (no product code, no API operation, no setting); no migration, OpenAPI operation or seed import part; `README.md` and `CLAUDE.md` name `run.sh teams|email`; this report. (2) The owner-run steps of spec Section 6.8 are done and their files committed (`a5360fb`); both spikes passed (Section 8). (3) The `ci.yml` job `gate` runs on the pull request after the owner's commit of the results and recordings and this report's commit.

## 11. Follow-up items for later packages

1. P-22: the `ms_teams` and `email` connectors test on the recordings under `dev/fakes/recordings/teams/` (nine posts, each answered 202 with an empty body and no `Content-Type`) and `email/` (twenty messages, each accepted with `250 Ok {message id}`), committed in `a5360fb`. Measured from the owner's Mac, a Teams post took at most 2,287 ms and an SES message at most 1,706 ms (Section 8), well within the 15 s call timeouts of AR-TM-01. A Teams recording's `url` is `secret:{secret name}`, never the webhook address (AR-K-17). The exact connect and call timeouts (AR-TM-01), the worker AR-R-32 and the texts of DC-OUT-05 to DC-OUT-07 are P-22's.
2. P-22: a message's body lines are kept whole only with a 7bit (or 8bit) transfer encoding; a quoted-printable or base64 body wraps or hides the link (spec 6.5). The `Date` header form (Section 3 item 9) is the library's.
3. P-32: the other values of `deploy/values/demo.yaml` (`endpoint.smtp`, `smtp_starttls` and the rest of AR-B-07); the nine mailbox values are there now.
