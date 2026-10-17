"""Tests of deploy/gpu.sh, deploy/gpu/host-gpu.sh and deploy/models.sh (P-05 spec Section 7)."""

import base64
import re

from conftest import tags_json, write_lock

LLM = "c" * 64
EMBED = "d" * 64
NEW = "e" * 64
MAIN = {"Name": "vigil-demo-main", "State": "running"}
NO_GPU_HOST = "The GPU host does not exist yet (deploy/edge.sh, BP-OT-09)."


def gpu_host(state):
    return {"i-0main": MAIN, "i-0gpu": {"Name": "vigil-demo-gpu", "State": state}}


def gpu_table(harness, docker_version="28.1.1", chat=(("500", 0), ("200", 0))):
    harness.table(
        [
            ("docker", r"^version --format", [(docker_version + "\n", 0)]),
            ("docker", r"^compose -p vigil-gpu -f compose\.yaml config --quiet$", [("", 0)]),
            ("curl", r"http://10\.40\.1\.20:11434/api/tags$", [(tags_json({"gemma4:12b": LLM}), 0)]),
            ("curl", r"http://10\.40\.1\.20:11434/api/chat$", list(chat)),
        ]
    )


def inline_files(parameters):
    """The files a run_on_host command writes on the host: {name: content}."""
    files = {}
    for line in parameters["commands"]:
        match = re.fullmatch(r'printf %s (\S+) \| base64 -d > "\$D/([^"]+)" \|\| .*', line)
        if match:
            files[match.group(2)] = base64.b64decode(match.group(1)).decode()
    return files


def test_gpu_host_missing_stop_and_status(harness):
    """AR-G-31 gpu.sh without a GPU host, stop with its wait, status of a stopped host, and the usage line."""
    harness.seed(instances={"i-0main": MAIN})
    for command in ("start", "stop"):
        result = harness.run("deploy/gpu.sh", command)
        assert result.returncode == 1
        assert result.stdout.strip() == NO_GPU_HOST
    result = harness.run("deploy/gpu.sh", "status")
    assert result.returncode == 0
    assert result.stdout.strip() == "gpu host: not created"
    for arguments in ([], ["restart"], ["start", "now"]):
        result = harness.run("deploy/gpu.sh", *arguments)
        assert result.returncode == 2
        assert result.stdout.strip() == "Usage: deploy/gpu.sh start|stop|status"

    harness.seed(instances=gpu_host("running"))
    result = harness.run("deploy/gpu.sh", "stop")
    assert result.returncode == 0, result.stdout + result.stderr
    assert harness.state()["instances"]["i-0gpu"]["State"] == "stopped"
    assert len(harness.aws_calls("ec2", "stop-instances")) == 1

    result = harness.run("deploy/gpu.sh", "status")
    assert result.returncode == 0
    assert result.stdout.strip() == "gpu host: i-0gpu stopped"
    assert harness.aws_calls("ssm", "send-command") == []


def test_gpu_start(harness):
    """AR-G-31 gpu.sh start: capacity refusal, then start, wait, the command's files, the models check, the warm-up."""
    harness.seed(instances=gpu_host("stopped"), errors={"ec2 start-instances": "InsufficientInstanceCapacity"})
    result = harness.run("deploy/gpu.sh", "start")
    assert result.returncode == 1
    assert result.stdout.strip() == "AWS has no g6.xlarge capacity now; try again in a few minutes (AR-ASM-06)."

    write_lock(harness.root / "deploy" / "models.lock", EMBED, LLM)
    harness.seed(instances=gpu_host("stopped"))
    gpu_table(harness)
    result = harness.run("deploy/gpu.sh", "start")
    assert result.returncode == 0, result.stdout + result.stderr
    assert re.fullmatch(r"vigil-llm ready after [01] s", result.stdout.splitlines()[-1])
    assert harness.state()["instances"]["i-0gpu"]["State"] == "running"
    command = next(iter(harness.state()["commands"].values()))
    files = inline_files(command["Parameters"])
    assert list(files) == ["host-gpu.sh", "compose.yaml", "models.lock"]
    assert files["host-gpu.sh"] == (harness.root / "deploy" / "gpu" / "host-gpu.sh").read_text()
    assert files["compose.yaml"] == (harness.root / "deploy" / "gpu" / "compose.yaml").read_text()
    assert files["models.lock"] == (harness.root / "deploy" / "models.lock").read_text()
    assert (harness.vigil_root / "gpu" / "models.lock").read_text() == files["models.lock"]

    calls = [(call["command"], call["args"]) for call in harness.fake_calls()]
    compose = ["compose", "-p", "vigil-gpu", "-f", "compose.yaml"]
    assert [args for command, args in calls if command == "docker"] == [
        ["version", "--format", "{{.Server.Version}}"],
        compose + ["config", "--quiet"],
        compose + ["pull", "--quiet"],
        compose + ["up", "-d"],
    ]
    curls = [args for command, args in calls if command == "curl"]
    assert curls[0][-1] == "http://10.40.1.20:11434/api/tags"
    assert [args[-1] for args in curls[1:]] == ["http://10.40.1.20:11434/api/chat"] * 2
    chat = curls[1]
    assert chat[chat.index("--max-time") + 1] == "60"
    assert chat[chat.index("-d") + 1] == (
        '{"model":"gemma4:12b","messages":[{"role":"user","content":"Reply with OK."}],"stream":false}'
    )


def test_gpu_start_warm_up_fails(harness):
    """AR-G-31 a warm-up that never answers 200 gives up after 15 attempts."""
    write_lock(harness.root / "deploy" / "models.lock", EMBED, LLM)
    harness.seed(instances=gpu_host("running"))
    gpu_table(harness, chat=[("000", 28)])
    result = harness.run("deploy/gpu.sh", "start")
    assert result.returncode == 1
    assert result.stdout.splitlines()[-1] == "vigil-llm did not answer the warm-up."
    assert len([call for call in harness.fake_calls("curl") if call["args"][-1].endswith("/api/chat")]) == 15


def test_old_docker(harness):
    """AR-T-18 host-gpu.sh refuses Docker older than 28.0."""
    harness.seed(instances=gpu_host("running"))
    gpu_table(harness, docker_version="27.5.1")
    result = harness.run("deploy/gpu.sh", "start")
    assert result.returncode == 1
    assert result.stdout.strip() == "Docker 27.5.1 is older than 28.0 (AR-T-18)."
    assert [call["args"][0] for call in harness.fake_calls("docker")] == ["version"]


def test_models_update(harness):
    """AR-H-11 models.sh update writes the digest Ollama reports into models.lock; an unknown model exits 2."""
    lock = harness.root / "deploy" / "models.lock"
    original = lock.read_text()
    harness.seed(instances={"i-0main": MAIN})
    harness.table(
        [
            ("docker", r"^inspect --format \{\{\.State\.Running\}\} vigil-embed$", [("true\n", 0)]),
            ("docker", r"^inspect --format \{\{with index", [("172.30.0.11\n", 0)]),
            ("curl", r"http://172\.30\.0\.11:11434/api/tags$", [(tags_json({"embeddinggemma:latest": NEW}), 0)]),
        ]
    )
    result = harness.run("deploy/models.sh", "update", "embeddinggemma")
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.strip() == f"embeddinggemma: unrecorded -> {NEW}"
    assert lock.read_text() == original.replace(
        "embeddinggemma vigil-embed unrecorded", f"embeddinggemma vigil-embed {NEW}"
    )
    assert ["exec", "vigil-embed", "ollama", "pull", "embeddinggemma"] in [
        c["args"] for c in harness.fake_calls("docker")
    ]

    result = harness.run("deploy/models.sh", "update", "llama3")
    assert result.returncode == 2
    assert result.stdout.strip() == "Unknown model llama3; models.lock lists: embeddinggemma, gemma4:12b."

    result = harness.run("deploy/models.sh", "update", "gemma4:12b")
    assert result.returncode == 1
    assert result.stdout.strip() == NO_GPU_HOST

    harness.table([("docker", r"^inspect --format \{\{\.State\.Running\}\}", [("false\n", 0)])])
    result = harness.run("deploy/models.sh", "update", "embeddinggemma")
    assert result.returncode == 1
    assert result.stdout.strip() == "Start vigil-embed first: deploy/deploy.sh or deploy/gpu.sh start."
