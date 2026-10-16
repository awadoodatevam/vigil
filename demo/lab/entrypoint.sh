#!/usr/bin/env bash
# lab-entrypoint: starts the processes of one lab host (P-03 spec Section 6.3; AR-U-08, AR-X-01).
set -euo pipefail

role="${LAB_ROLE:-}"
case "$role" in
  lab-db-01 | lab-api-01 | lab-vas-01 | lab-probe-01 | lab-rpt-01) ;;
  *)
    echo "unknown LAB_ROLE"
    exit 1
    ;;
esac

secrets=/run/secrets/vigil
image_dir=/var/lib/lab-data
gib=1073741824
pids=()
ports=()

rm -f /run/lab/ready /run/sshd.pid /run/zabbix/zabbix_agent2.pid /run/zabbix/agent.sock
install -d -m 0755 /run/lab /var/lib/lab/status /var/lib/lab/control

# Starts one process in the background; its port (or "none") is checked before the host is ready.
start() {
  local port=$1
  shift
  "$@" &
  pids+=("$!")
  ports+=("$port")
}

stop_processes() {
  local pid
  for pid in "${pids[@]}"; do
    kill -TERM "$pid" 2> /dev/null || true
  done
  for pid in "${pids[@]}"; do
    wait "$pid" 2> /dev/null || true
  done
}

detach_image_loops() {
  local device
  for device in $(losetup -j "$1" | cut -d: -f1); do
    losetup -d "$device" || true
  done
}

# shellcheck disable=SC2317 # called by the trap
on_term() {
  stop_processes
  if [ "$role" = "lab-db-01" ] && mountpoint -q /data; then
    umount /data || echo "unmount of /data failed"
  fi
  exit 0
}
trap on_term TERM INT

# /data set-up of lab-db-01 (AR-X-01, BP-A-05, BP-R-30).
setup_data() {
  local device_bytes db_bytes export_bytes image new=no n number i
  case "${LAB_PROFILE:-}" in
    test)
      device_bytes=$((4 * gib))
      db_bytes=$((8 * gib / 10))
      export_bytes=$((gib / 10))
      ;;
    demo)
      device_bytes=$((16 * gib))
      db_bytes=$((6 * gib))
      export_bytes=$gib
      ;;
    *)
      echo "unknown LAB_PROFILE"
      exit 1
      ;;
  esac
  image="$image_dir/data-$LAB_PROFILE.xfs"

  if [ ! -e "$image" ]; then
    truncate -s "$device_bytes" "$image"
    mkfs.xfs -q "$image"
    new=yes
  fi

  for i in /sys/block/loop*; do
    number=${i##*/loop}
    if [ ! -e "/dev/loop$number" ]; then
      mknod "/dev/loop$number" b 7 "$number"
    fi
  done
  detach_image_loops "$image"
  n=$(losetup -f)
  if [ ! -e "$n" ]; then
    mknod "$n" b 7 "${n#/dev/loop}"
  fi
  mkdir -p /data
  if ! mount -t xfs -o "loop=$n,nouuid" "$image" /data; then
    echo "mount of $image at /data failed"
    detach_image_loops "$image"
    exit 1
  fi

  mkdir -p /data/db /data/export /data/txlog_archive
  chown root:vigil-diag /data /data/db /data/export
  chmod 0750 /data /data/db /data/export
  chown vigil-action:vigil-diag /data/txlog_archive
  chmod 0750 /data/txlog_archive

  if [ "$new" = yes ]; then
    local old
    old="@$(($(date +%s) - 30 * 86400))"
    for i in 1 2 3 4 5 6 7 8; do
      baseline_file "/data/db/datafile_0$i.dbf" $((db_bytes / 8 / 4096 * 4096)) "$old"
    done
    for i in 1 2 3 4; do
      baseline_file "/data/export/export_0$i.dat" $((export_bytes / 4 / 4096 * 4096)) "$old"
    done
  fi
}

baseline_file() {
  fallocate -l "$2" "$1"
  chown root:vigil-diag "$1"
  chmod 0640 "$1"
  touch -d "$3" "$1"
}

# Keys of lab-db-01 from its secret files, at every start (AR-X-01, AR-S-04).
install_keys() {
  local name user
  for name in lab-db-01-host-key lab-db-01-diag-pub lab-db-01-action-pub; do
    if [ ! -f "$secrets/$name" ]; then
      echo "missing secret $name"
      exit 1
    fi
  done
  install -m 0600 -o root -g root /dev/null /etc/ssh/ssh_host_ed25519_key
  { cat "$secrets/lab-db-01-host-key"; echo; } > /etc/ssh/ssh_host_ed25519_key
  ssh-keygen -y -f /etc/ssh/ssh_host_ed25519_key > /etc/ssh/ssh_host_ed25519_key.pub
  chmod 0644 /etc/ssh/ssh_host_ed25519_key.pub
  for user in vigil-diag vigil-action; do
    install -d -m 0700 -o "$user" -g "$user" "/home/$user/.ssh"
    install -m 0600 -o "$user" -g "$user" /dev/null "/home/$user/.ssh/authorized_keys"
    { cat "$secrets/lab-db-01-${user#vigil-}-pub"; echo; } > "/home/$user/.ssh/authorized_keys"
  done
  install -d -m 0755 /run/sshd
}

# Zabbix agent 2 configuration, written at every start (P-04 MAY change it).
start_agent() {
  install -d -m 0755 /etc/zabbix
  install -d -m 0755 -o zabbix -g zabbix /run/zabbix
  cat > /etc/zabbix/zabbix_agent2.conf << CONF
Hostname=$role
Server=172.31.0.0/24,127.0.0.1
ListenPort=10050
ServerActive=
LogType=console
PidFile=/run/zabbix/zabbix_agent2.pid
ControlSocket=/run/zabbix/agent.sock
CONF
  start 10050 setpriv --reuid=zabbix --regid=zabbix --init-groups \
    /usr/sbin/zabbix_agent2 -f -c /etc/zabbix/zabbix_agent2.conf
}

mocks=(/opt/venv/bin/python -m labmocks)
case "$role" in
  lab-db-01)
    setup_data
    install_keys
    start 22 /usr/sbin/sshd -D -e
    start 9100 setpriv --reuid=nobody --regid=nogroup --clear-groups \
      prometheus-node-exporter --web.listen-address=:9100
    start_agent
    start 7000 "${mocks[@]}" db
    ;;
  lab-api-01)
    start "8101 8102 8103 8104 8201 8202 8203 8204 9200" "${mocks[@]}" api
    ;;
  lab-vas-01)
    start_agent
    start "7100 9200" "${mocks[@]}" vas
    ;;
  lab-probe-01)
    start 9200 "${mocks[@]}" probe
    ;;
  lab-rpt-01)
    start_agent
    start none "${mocks[@]}" rpt
    ;;
esac

# Ready when every process with a port answers a TCP connect on it and every other one runs 1 s after its start;
# checked every 0.5 s (check number $1) for at most 60 s.
is_up() {
  local index port
  for index in "${!pids[@]}"; do
    if [ "${ports[$index]}" = none ]; then
      [ "$1" -ge 2 ] && kill -0 "${pids[$index]}" 2> /dev/null || return 1
      continue
    fi
    for port in ${ports[$index]}; do
      (exec 3<> "/dev/tcp/127.0.0.1/$port") 2> /dev/null || return 1
    done
  done
}

ready=no
for check in $(seq 0 120); do
  if [ "$check" -gt 0 ]; then
    sleep 0.5
  fi
  if is_up "$check"; then
    ready=yes
    break
  fi
done
if [ "$ready" != yes ]; then
  echo "the processes of $role did not start within 60 s"
  stop_processes
  exit 1
fi
touch /run/lab/ready

code=0
wait -n || code=$?
stop_processes
exit "$code"
