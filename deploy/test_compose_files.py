"""Tests of the compose files of the hosts and the bootstrap's Docker settings (P-05 and P-32 spec Section 7)."""

import json
import os
import re
import subprocess
import sys

import yaml
from conftest import REPOSITORY

DEPLOY = REPOSITORY / "deploy"
OLLAMA = re.compile(r"^ollama/ollama:\d+\.\d+\.\d+@sha256:[0-9a-f]{64}$")


def compose(name):
    return yaml.safe_load((DEPLOY / name / "compose.yaml").read_text())


REGISTRY_IMAGE = "${VIGIL_REGISTRY:-registry.invalid}/${VIGIL_INSTALLATION:-demo}/%s:${VIGIL_RELEASE:-unset}"
UNITS = [
    "vigil-db",
    "vigil-migrate",
    "vigil-app",
    "vigil-embed",
    "lab-db-01",
    "lab-api-01",
    "lab-vas-01",
    "lab-probe-01",
    "lab-rpt-01",
    "lab-zabbix-db",
    "lab-zabbix-server",
    "lab-zabbix-web",
    "lab-zabbix-provision",
    "lab-prometheus",
    "lab-alertmanager",
    "demo-faults",
    "demo-control",
]
LAB_HOSTS = ["lab-db-01", "lab-api-01", "lab-vas-01", "lab-probe-01", "lab-rpt-01"]
ONE_SHOT = {"vigil-migrate", "lab-zabbix-provision"}
APP_NETWORKS = {
    "vigil-net": {"ipv4_address": "172.30.0.10", "gw_priority": 1},
    "lab-net": {"ipv4_address": "172.31.0.10", "gw_priority": 0},
}


def secret_mount(service, unit):
    """The service's bind of /run/secrets/vigil, or None."""
    for volume in service.get("volumes", []):
        if isinstance(volume, dict) and volume.get("target") == "/run/secrets/vigil":
            return volume
    return None


def binds(service):
    """{target: volume} of the service's long-syntax binds."""
    return {v["target"]: v for v in service.get("volumes", []) if isinstance(v, dict)}


def test_vigil_embed():
    """AR-U-03 vigil-embed is on vigil-net only, publishes no port, keeps its models on a volume, pinned Ollama."""
    main = compose("main")
    assert main["name"] == "vigil"
    embed = main["services"]["vigil-embed"]
    assert OLLAMA.match(embed["image"])
    assert embed == {
        "image": embed["image"],
        "container_name": "vigil-embed",
        "networks": ["vigil-net"],
        "volumes": ["vigil-embed-models:/root/.ollama"],
        "restart": "unless-stopped",
    }
    assert "vigil-embed-models" in main["volumes"]
    assert "vigil-embed" in UNITS


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
    """AR-S-08 vigil-app at its addresses with vigil-net as gateway, the networks, the DOCKER-USER rule."""
    main = compose("main")
    app = main["services"]["vigil-app"]
    assert app["image"] == REGISTRY_IMAGE % "vigil-app"
    assert app["networks"] == APP_NETWORKS
    assert app["ports"] == ["10.40.1.10:8080:8080"]
    assert app["user"] == "10001"
    assert "sse-probe" not in main["services"]
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


def test_vigil_app():
    """AR-U-01 vigil-app at its fixed addresses, bound to 172.30.0.10, published only on 10.40.1.10:8080, after
    vigil-migrate, with health details."""
    services = compose("main")["services"]
    app, migrate = services["vigil-app"], services["vigil-migrate"]
    assert app["container_name"] == "vigil-app"
    assert app["profiles"] == ["vigil"]
    assert app["command"] == [
        "--vigil.http.bind-address=172.30.0.10",
        "--management.endpoint.health.show-details=always",
    ]
    assert app["depends_on"] == {"vigil-migrate": {"condition": "service_completed_successfully"}}
    assert app["networks"] == APP_NETWORKS
    assert app["ports"] == ["10.40.1.10:8080:8080"]
    assert app["restart"] == "unless-stopped"
    published = {name: s["ports"] for name, s in services.items() if "ports" in s}
    assert published == {"vigil-app": ["10.40.1.10:8080:8080"], "demo-control": ["10.40.1.10:8090:8090"],
                         "dev-recorder": ["10.40.1.10:8080:8080"]}  # fmt: skip
    assert migrate["image"] == app["image"] == REGISTRY_IMAGE % "vigil-app"
    assert migrate["environment"] == {"SPRING_PROFILES_ACTIVE": "migrate"}
    assert migrate["depends_on"] == {"vigil-db": {"condition": "service_healthy"}}
    assert migrate["user"] == "10001" and "profiles" not in migrate and migrate["restart"] == "no"
    assert secret_mount(app, "vigil-app")["source"] == "/srv/vigil/secrets/vigil-app"
    assert secret_mount(migrate, "vigil-migrate")["source"] == "/srv/vigil/secrets/vigil-migrate"


def test_vigil_db_health():
    """AR-U-02 vigil-db's health check is AR-U-01's in both compose files."""
    query = "select 1 from pg_roles where rolname = 'vigil_app'"
    expected = {
        "test": ["CMD-SHELL", f'psql -h 127.0.0.1 -U postgres -d vigil -tAc "{query}" | grep -qx 1'],
        "interval": "2s",
        "timeout": "2s",
        "retries": 60,
    }
    main, local = compose("main")["services"]["vigil-db"], compose("local")["services"]["vigil-db"]
    assert main["healthcheck"] == local["healthcheck"] == expected
    assert binds(main)["/docker-entrypoint-initdb.d"]["source"] == "/srv/vigil/deploy/db/init"
    assert binds(local)["/docker-entrypoint-initdb.d"]["source"] == "../db/init"
    assert "vigil-db-data:/var/lib/postgresql/data" in main["volumes"]
    assert main["networks"] == ["vigil-net"] and "ports" not in main


def test_demo_control():
    """AR-U-06 demo-control on lab-net, published only on 10.40.1.10:8090, with derived.yaml and its state volume."""
    main, local = compose("main")["services"]["demo-control"], compose("local")["services"]["demo-control"]
    assert main["image"] == REGISTRY_IMAGE % "demo-control"
    assert main["profiles"] == ["vigil"]
    assert main["networks"] == ["lab-net"]
    assert main["ports"] == ["10.40.1.10:8090:8090"]
    assert main["command"] == ["--demo-control.vigil-url=https://demo.${VIGIL_DEMO_DOMAIN:-demo.invalid}"]
    assert "demo-control-state:/var/lib/demo-control" in main["volumes"]
    derived = binds(main)["/run/values/derived.yaml"]
    assert derived["source"] == "/srv/vigil/values/derived.yaml" and derived["read_only"] is True
    assert "/run/values/values.yaml" not in binds(main)
    assert main["healthcheck"] == local["healthcheck"]
    assert "/dev/tcp/127.0.0.1/8090" in main["healthcheck"]["test"][-1]


def test_demo_faults():
    """AR-U-07 demo-faults on the host network as root with NET_ADMIN, NET_RAW and the Docker socket."""
    main = compose("main")["services"]["demo-faults"]
    assert main["image"] == REGISTRY_IMAGE % "demo-faults"
    assert main["network_mode"] == "host" and "networks" not in main
    assert main["user"] == "0"
    assert main["cap_add"] == ["NET_ADMIN", "NET_RAW"]
    assert main["environment"] == {"FAULTS_LISTEN": "172.31.0.1:8091"}
    assert "/var/run/docker.sock:/var/run/docker.sock" in main["volumes"]
    assert "demo-faults-state:/var/lib/demo-faults" in main["volumes"]
    assert "ports" not in main


def test_lab_hosts():
    """AR-U-08 the five lab hosts, lab-db-01 privileged with the lab profile."""
    services = compose("main")["services"]
    for name in LAB_HOSTS:
        host = services[name]
        assert host["image"] == REGISTRY_IMAGE % "demo-lab"
        assert host["hostname"] == name
        assert host["environment"]["LAB_ROLE"] == name
        assert host["networks"] == ["lab-net"]
        assert host.get("privileged", False) is (name == "lab-db-01")
    assert services["lab-db-01"]["environment"]["LAB_PROFILE"] == "${VIGIL_LAB_PROFILE:-demo}"
    assert "lab-db-01-data:/var/lib/lab-data" in services["lab-db-01"]["volumes"]


def test_zabbix():
    """AR-U-09 Zabbix with the provisioning's generated folder and webhook base."""
    services = compose("main")["services"]
    provision = services["lab-zabbix-provision"]
    assert provision["image"] == REGISTRY_IMAGE % "lab-zabbix-provision"
    assert provision["user"] == "10001"
    assert provision["environment"] == {
        "WEBHOOK_BASE_URL": "${VIGIL_WEBHOOK_BASE_URL:-https://demo.invalid}",
        "ZABBIX_URL": "http://lab-zabbix-web:8080",
    }
    generated = binds(provision)["/srv/vigil/generated"]
    assert generated["source"] == "/srv/vigil/generated" and not generated.get("read_only")
    assert generated["bind"] == {"create_host_path": False}
    for name in ("lab-zabbix-db", "lab-zabbix-server", "lab-zabbix-web"):
        assert services[name]["networks"] == ["lab-net"]
    assert "lab-zabbix-db-data:/var/lib/postgresql/data" in services["lab-zabbix-db"]["volumes"]


def test_prometheus_and_alertmanager():
    """AR-U-10 Prometheus and Alertmanager read the deployed configuration."""
    services = compose("main")["services"]
    prometheus = binds(services["lab-prometheus"])["/etc/prometheus"]
    assert prometheus["source"] == "/srv/vigil/deploy/monitoring/prometheus" and prometheus["read_only"] is True
    alertmanager = binds(services["lab-alertmanager"])["/etc/alertmanager/alertmanager.yml"]
    assert alertmanager["source"] == "/srv/vigil/config/alertmanager.yml" and alertmanager["read_only"] is True
    assert services["lab-prometheus"]["networks"] == services["lab-alertmanager"]["networks"] == ["lab-net"]


def test_units_and_profiles():
    """AR-R-02 every unit of AR Section 6 in profile vigil or no profile; the recorder only in profile spike at
    vigil-app's addresses."""
    main, local = compose("main"), compose("local")
    services = main["services"]
    assert sorted(services) == sorted(UNITS + ["dev-recorder"])
    for name in UNITS:
        assert services[name].get("profiles", ["vigil"]) == ["vigil"], name
    assert [n for n in UNITS if "profiles" in services[n]] == ["vigil-app", "demo-control"]
    recorder = services["dev-recorder"]
    assert recorder["profiles"] == ["spike"]
    assert recorder["image"] == REGISTRY_IMAGE % "dev-recorder"
    assert recorder["container_name"] == "dev-recorder"
    assert recorder["networks"] == APP_NETWORKS
    assert recorder["ports"] == ["10.40.1.10:8080:8080"]
    assert binds(recorder)["/recordings"]["source"] == "/srv/vigil/spike/recordings"
    assert secret_mount(recorder, "dev-recorder")["source"] == "/srv/vigil/secrets/vigil-app"
    assert recorder["healthcheck"] == local["services"]["dev-recorder"]["healthcheck"]
    built = {
        "vigil-app": "app",
        "demo-control": "demo/control",
        "demo-lab": "demo/lab",
        "lab-zabbix-provision": "demo/monitoring/provision",
        "demo-faults": "demo/faults",
        "dev-recorder": "dev/recorder",
    }
    for name, service in services.items():
        image = service["image"]
        if image.startswith("${VIGIL_REGISTRY"):
            assert image in {REGISTRY_IMAGE % i for i in built}, name
        else:
            assert "@sha256:" in image, name
    for image, folder in built.items():
        assert (REPOSITORY / folder / "Dockerfile").is_file(), image
    assert sorted(main["volumes"]) == sorted(
        ["vigil-db-data", "vigil-embed-models", "lab-db-01-data", "lab-zabbix-db-data", "demo-faults-state",
         "demo-control-state"]
    )  # fmt: skip
    # Each unit as in the local compose file, except the differences of BP-R-25 and the P-32 spec Section 6.3.
    for name in UNITS + ["dev-recorder"]:
        service, twin = services[name], local["services"][name]
        assert service["container_name"] == twin["container_name"] == name
        assert service.get("healthcheck") == twin.get("healthcheck") or name == "vigil-embed", name
        assert service.get("depends_on") == twin.get("depends_on"), name
        expected_restart = "no" if name in ONE_SHOT else "unless-stopped"
        assert service["restart"] == expected_restart, name
        mount = secret_mount(service, name)
        if secret_mount(twin, name) is None:
            assert mount is None, name
        else:
            source = "vigil-app" if name == "dev-recorder" else name
            assert mount == {
                "type": "bind",
                "source": f"/srv/vigil/secrets/{source}",
                "target": "/run/secrets/vigil",
                "read_only": True,
                "bind": {"create_host_path": False},
            }, name
        if name not in ("lab-db-01", "lab-zabbix-provision"):
            assert service.get("environment") == twin.get("environment"), name
        for key in ("hostname", "privileged", "cap_add", "network_mode"):
            assert service.get(key) == twin.get(key), (name, key)
        if not service["image"].startswith("${VIGIL_REGISTRY"):
            assert service["image"] == twin["image"].split(" ")[0], name


def test_third_party_pins():
    """BP-R-08 the third-party pins equal those of the local compose file."""
    pinned = re.compile(r"^[a-z0-9./-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$")
    main, local = compose("main")["services"], compose("local")["services"]
    third_party = {n: s["image"] for n, s in main.items() if not s["image"].startswith("${VIGIL_REGISTRY")}
    assert sorted(third_party) == [
        "lab-alertmanager", "lab-prometheus", "lab-zabbix-db", "lab-zabbix-server", "lab-zabbix-web", "vigil-db",
        "vigil-embed",
    ]  # fmt: skip
    for name, image in third_party.items():
        assert pinned.match(image), name
        assert image == local[name]["image"], name


def test_validates_without_values(tmp_path):
    """BP-R-09 the file validates with no secret and no value."""
    environment = {key: value for key, value in os.environ.items() if not key.startswith(("VIGIL_", "COMPOSE_"))}
    for profiles in ("", "vigil", "spike"):
        result = subprocess.run(
            ["docker", "compose", "-f", str(DEPLOY / "main" / "compose.yaml"), "config", "--quiet"],
            cwd=tmp_path,
            env={**environment, "COMPOSE_PROFILES": profiles},
            capture_output=True,
            text=True,
            check=False,
        )
        assert result.returncode == 0, result.stderr
    text = (DEPLOY / "main" / "compose.yaml").read_text()
    for variable in re.findall(r"\$\{([A-Z_]+)", text):
        assert re.search(r"\$\{" + variable + r":-[^}]+\}", text), variable


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
