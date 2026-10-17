#!/usr/bin/env bash
# Deploys a release to the main host: uploads the deployment files to the deployment bucket and runs the deployment
# sequence on the host through Systems Manager (AR-H-11, BP-A-21; P-05 spec Section 6.8). Run on the Mac:
# deploy/deploy.sh {installation} {release}.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/deploy.sh {installation} {release}"
  exit 2
}

if [ "$#" -ne 2 ] || ! known_installation "$1" || ! valid_release "$2"; then
  usage
fi
I=$1
RELEASE=$2
check_identity
require_running_host "vigil-$I-main" main

# The images of the compose file under ${VIGIL_REGISTRY}.
images=$(python3 -c '
import re
import sys

with open(sys.argv[1]) as file:
    for match in re.finditer(r"image:\s*\$\{VIGIL_REGISTRY[^}]*\}/\$\{VIGIL_INSTALLATION[^}]*\}/([a-z0-9-]+):", file.read()):
        print(match.group(1))
' deploy/main/compose.yaml)
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
aws s3 cp --only-show-errors "deploy/values/$I.yaml" "$prefix/values/$I.yaml"

code=0
run_on_host "$HOST_ID" deploy/main/host-fetch.sh -- "$BUCKET" "$RELEASE" "$I" "$REGISTRY" || code=$?
exit "$code"
