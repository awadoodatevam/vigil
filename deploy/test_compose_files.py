"""Tests of the compose files of the hosts and the bootstrap's Docker settings (P-05 spec Section 7)."""

import json
import re
import subprocess
import sys

import yaml
from conftest import REPOSITORY

DEPLOY = REPOSITORY / "deploy"
OLLAMA = re.compile(r"^ollama/ollama:\d+\.\d+\.\d+@sha256:[0-9a-f]{64}$")


def compose(name):
    return yaml.safe_load((DEPLOY / name / "compose.yaml").read_text())


def test_vigil_embed():
    """AR-U-03 vigil-embed is on vigil-net only, publishes no port, keeps its models on a volume, pinned Ollama."""
    main = compose("main")
    assert main["name"] == "vigil"
    embed = main["services"]["vigil-embed"]
    assert OLLAMA.match(embed["image"])
    assert embed["container_name"] == "vigil-embed"
    assert embed["networks"] == ["vigil-net"]
    assert "ports" not in embed
    assert embed["volumes"] == ["vigil-embed-models:/root/.ollama"]
    assert embed["restart"] == "unless-stopped"
    assert "vigil-embed-models" in main["volumes"]
    assert set(main["services"]) == {"sse-probe", "vigil-embed"}


def test_vigil_llm():
    """AR-U-04 vigil-llm is published on the GPU host's address, reserves one NVIDIA GPU and keeps its models."""
    gpu = compose("gpu")
    assert gpu["name"] == "vigil-gpu"
    assert set(gpu["services"]) == {"vigil-llm"}
    llm = gpu["services"]["vigil-llm"]
    assert llm["image"] == compose("main")["services"]["vigil-embed"]["image"]
    assert llm["container_name"] == "vigil-llm"
    assert llm["ports"] == ["10.40.1.20:11434:11434"]
    assert llm["volumes"] == ["vigil-llm-models:/root/.ollama"]
    assert llm["deploy"]["resources"]["reservations"]["devices"] == [
        {"driver": "nvidia", "count": 1, "capabilities": ["gpu"]}
    ]
    assert llm["restart"] == "unless-stopped"
    assert "vigil-llm-models" in gpu["volumes"]


def test_network_exposure():
    """AR-S-08 the probe at vigil-app's addresses with vigil-net as gateway, the networks, the DOCKER-USER rule."""
    main = compose("main")
    probe = main["services"]["sse-probe"]
    assert probe["image"] == (
        "${VIGIL_REGISTRY:-registry.invalid}/${VIGIL_INSTALLATION:-demo}/sse-probe:${VIGIL_RELEASE:-unset}"
    )
    assert probe["networks"] == {
        "vigil-net": {"ipv4_address": "172.30.0.10", "gw_priority": 1},
        "lab-net": {"ipv4_address": "172.31.0.10", "gw_priority": 0},
    }
    assert probe["ports"] == ["10.40.1.10:8080:8080"]
    assert probe["environment"] == {"PROBE_ADDRESS": "172.30.0.10"}
    assert probe["user"] == "10001"
    assert main["networks"] == {
        "vigil-net": {
            "name": "vigil-net",
            "driver": "bridge",
            "ipam": {"config": [{"subnet": "172.30.0.0/24", "gateway": "172.30.0.1"}]},
        },
        "lab-net": {
            "name": "lab-net",
            "driver": "bridge",
            "ipam": {"config": [{"subnet": "172.31.0.0/24", "gateway": "172.31.0.1"}]},
        },
    }
    user_data = (DEPLOY / "hosts" / "main-user-data.sh").read_text()
    rule = "DOCKER-USER -s 172.31.0.0/24 -d 172.30.0.10 -m conntrack --ctstate NEW -j DROP"
    assert f"iptables -C {rule}" in user_data
    assert f"iptables -I {rule}" in user_data
    assert "systemctl enable vigil-docker-user.service" in user_data
    assert "WantedBy=docker.service" in user_data


def test_log_settings():
    """AR-H-12 the main host's daemon configuration and the GPU host's merge, which keeps the NVIDIA runtime."""
    user_data = (DEPLOY / "hosts" / "main-user-data.sh").read_text()
    line = re.search(r"^echo '(.*)' > /etc/docker/daemon\.json$", user_data, re.MULTILINE)
    assert json.loads(line.group(1)) == {"log-driver": "local", "log-opts": {"max-size": "10m", "max-file": "5"}}
    for service in list(compose("main")["services"].values()) + list(compose("gpu")["services"].values()):
        assert "logging" not in service


def test_gpu_daemon_merge(tmp_path):
    """AR-H-12 the merge program of gpu-user-data.sh keeps the image's NVIDIA runtime and adds the log settings."""
    gpu_data = (DEPLOY / "hosts" / "gpu-user-data.sh").read_text()
    program = re.search(r"python3 - /etc/docker/daemon\.json <<'PYTHON'\n(.*?)\nPYTHON\n", gpu_data, re.DOTALL).group(1)
    daemon = tmp_path / "daemon.json"
    runtimes = {"nvidia": {"args": [], "path": "nvidia-container-runtime"}}
    daemon.write_text(json.dumps({"runtimes": runtimes}))
    subprocess.run([sys.executable, "-", str(daemon)], input=program, text=True, check=True)
    assert json.loads(daemon.read_text()) == {
        "runtimes": runtimes,
        "log-driver": "local",
        "log-opts": {"max-size": "10m", "max-file": "5"},
    }
