#!/usr/bin/env bash
# Deploys a release to the main host: uploads the deployment files to the deployment bucket and runs the deployment
# sequence on the host through Systems Manager (AR-H-11, BP-A-21; P-05 spec Section 6.8, P-32 spec Section 6.4). The
# compose profile is vigil, or spike with --spike (the lab with the webhook recorder in place of vigil-app, for
# SB-T-01; CTX-D-095 (2)). Run on the Mac: deploy/deploy.sh {installation} {release} [--spike].
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/deploy.sh {installation} {release} [--spike]"
  exit 2
}

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ] || ! known_installation "$1" || ! valid_release "$2"; then
  usage
fi
I=$1
RELEASE=$2
PROFILE=vigil
if [ "$#" -eq 3 ]; then
  if [ "$3" != --spike ]; then
    usage
  fi
  PROFILE=spike
fi
check_identity
require_running_host "vigil-$I-main" main

# The images under ${VIGIL_REGISTRY} of the services of the profile (services without a profile and those of the
# profile), each once.
images=$(python3 -c '
import re
import sys

profile, section, service, found = sys.argv[2], None, None, {}
with open(sys.argv[1]) as file:
    for line in file:
        if re.match(r"^\S", line):
            service = None
            section = line.strip()
        elif section == "services:" and re.match(r"^  [a-z0-9-]+:\s*$", line):
            service = line.strip()[:-1]
            found[service] = [None, None]
        elif service and (match := re.match(r"^    image:\s*\$\{VIGIL_REGISTRY[^}]*\}/\$\{VIGIL_INSTALLATION[^}]*\}/([a-z0-9-]+):", line)):
            found[service][0] = match.group(1)
        elif service and (match := re.match(r"^    profiles:\s*\[\s*\"?([a-z0-9-]+)\"?\s*\]", line)):
            found[service][1] = match.group(1)
seen = []
for image, service_profile in found.values():
    if image and service_profile in (None, profile) and image not in seen:
        seen.append(image)
        print(image)
' deploy/main/compose.yaml "$PROFILE")
for image in $images; do
  if ! out=$(aws ecr describe-images --repository-name "$I/$image" --image-ids "imageTag=$RELEASE" --output json 2>&1); then
    case "$(error_code "$out")" in
      ImageNotFoundException | RepositoryNotFoundException)
        echo "Image $I/$image:$RELEASE is missing (deploy/build.sh)."
        ;;
      *) echo "$out" ;;
    esac
    exit 1
  fi
done

BUCKET=$(bucket_name "$I")
prefix="s3://$BUCKET/releases/$RELEASE"
for file in deploy/main/compose.yaml deploy/main/host-deploy.sh deploy/units.yaml deploy/secrets.sh deploy/web_config.py \
  deploy/models.lock; do
  aws s3 cp --only-show-errors "$file" "$prefix/${file##*/}"
done
# {source}:{path under the release} (BP-A-21; P-32 spec Section 6.4 item 1).
uploads="deploy/values/$I.yaml:values/$I.yaml deploy/db/init/10-vigil-roles.sh:db/init/10-vigil-roles.sh
demo/monitoring/prometheus/prometheus.yml:monitoring/prometheus/prometheus.yml
demo/monitoring/prometheus/lab-rules.yml:monitoring/prometheus/lab-rules.yml
demo/monitoring/alertmanager/alertmanager.yml:monitoring/alertmanager/alertmanager.yml"
if [ "$PROFILE" = spike ]; then
  # The data the storm spike reads at /demo-data: the README, the nine data files of AR-B-05 and the lab's file.
  for file in README.md 01-tenant-identity.yaml 02-service-map.yaml 03-monitoring.yaml \
    04-operations-actions-recovery.yaml 05-routing-escalation.yaml 06-workflow.yaml 07-settings.yaml 08-runbooks.md \
    09-past-incidents.csv 10-lab-and-faults.yaml; do
    uploads="$uploads docs/31-demo-data/$file:spike/demo-data/$file"
  done
fi
for entry in $uploads; do
  aws s3 cp --only-show-errors "${entry%%:*}" "$prefix/${entry#*:}"
done

code=0
run_on_host "$HOST_ID" deploy/main/host-fetch.sh -- "$BUCKET" "$RELEASE" "$I" "$REGISTRY" "$PROFILE" || code=$?
exit "$code"
