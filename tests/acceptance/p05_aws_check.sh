#!/usr/bin/env bash
# shellcheck disable=SC2317,SC2016 # the parts run through part(); the check commands expand their arguments in sh
# Owner-run step of P-05 (spec Sections 6.12 and 6.13 step 9): records the demo installation's AWS resources, the main
# host and the HTTPS answers in tests/acceptance/results/P-05-aws.txt and ends with one PASS or FAIL line per check.
# Run from the repository root on the Mac while the main host runs: tests/acceptance/p05_aws_check.sh {installation}.
# Each part's exit code is recorded; only the checks decide the exit status. Never prints a secret value.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: tests/acceptance/p05_aws_check.sh {installation}"
  exit 2
}

if [ "$#" -ne 1 ] || ! known_installation "$1"; then
  usage
fi
I=$1
check_identity
D=$(values_value "deploy/values/$I.yaml" demo_domain)
RESULT=tests/acceptance/results/P-05-aws.txt
WORK=$(temp_folder)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$WORK'" EXIT

: > "$RESULT"

# Runs a part: its heading, its output (also kept in the work folder) and its exit code.
part() {
  local name=$1 code
  shift
  echo "== $name" | tee -a "$RESULT"
  set +e
  ("$@") 2>&1 | tee -a "$RESULT" | tee "$WORK/$name.out"
  code=${PIPESTATUS[0]}
  set -e
  echo "exit $code" | tee -a "$RESULT"
}

part_date() {
  date -u
  aws --version
  echo "caller: $CALLER_ARN"
}

part_edge() {
  deploy/edge.sh "$I"
}

zone_id() {
  aws route53 list-hosted-zones-by-name --dns-name "$D" --output json |
    json '[z["Id"].split("/")[-1] for z in d["HostedZones"] if z["Name"] == "'"$D"'." and not z["Config"]["PrivateZone"]][:1]'
}

part_delegation() {
  echo "dig +short NS $D:"
  dig +short NS "$D"
  echo "dig +short NS $D @1.1.1.1:"
  dig +short NS "$D" @1.1.1.1 | tee "$WORK/ns-public"
  echo "zone name servers:"
  aws route53 get-hosted-zone --id "$(zone_id)" --output json |
    json '[n.lower().rstrip(".") for n in d["DelegationSet"]["NameServers"]]' | tee "$WORK/ns-zone"
}

part_network() {
  local vpc subnet
  vpc=$(aws ec2 describe-vpcs --filters "Name=tag:Name,Values=vigil-$I" --output json |
    json '[v["VpcId"] + " " + v["CidrBlock"] for v in d["Vpcs"]]')
  echo "vpc vigil-$I: $vpc"
  vpc=${vpc%% *}
  aws ec2 describe-vpc-attribute --vpc-id "$vpc" --attribute enableDnsSupport --output json |
    json '"dns support: " + str(d["EnableDnsSupport"]["Value"]).lower()'
  aws ec2 describe-vpc-attribute --vpc-id "$vpc" --attribute enableDnsHostnames --output json |
    json '"dns host names: " + str(d["EnableDnsHostnames"]["Value"]).lower()'
  aws ec2 describe-internet-gateways --filters "Name=tag:Name,Values=vigil-$I" --output json |
    json '["internet gateway " + g["InternetGatewayId"] + " attached to " + ", ".join(a["VpcId"] + " " + a["State"] for a in g.get("Attachments", [])) for g in d["InternetGateways"]]'
  for subnet in $(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" --output json | json '[s["SubnetId"] for s in d["Subnets"]]'); do
    aws ec2 describe-subnets --subnet-ids "$subnet" --output json |
      json '["subnet " + next((t["Value"] for t in s.get("Tags", []) if t["Key"] == "Name"), "") + " " + s["SubnetId"] + " " + s["CidrBlock"] + " " + s["AvailabilityZone"] + " public IPv4 on launch " + str(s["MapPublicIpOnLaunch"]).lower() for s in d["Subnets"]]'
    aws ec2 get-subnet-cidr-reservations --subnet-id "$subnet" --output json |
      json '["  reservation " + r["Cidr"] + " " + r["ReservationType"] for r in d.get("SubnetIpv4CidrReservations", [])]'
  done
  aws ec2 describe-route-tables --filters "Name=tag:Name,Values=vigil-$I" --output json |
    json '["route table " + t["RouteTableId"] for t in d["RouteTables"]] + ["  route " + r.get("DestinationCidrBlock", "") + " -> " + r.get("GatewayId", r.get("NatGatewayId", "other")) for t in d["RouteTables"] for r in t["Routes"]] + ["  association " + a.get("SubnetId", "main") for t in d["RouteTables"] for a in t["Associations"]]'
}

part_security_groups() {
  local name group
  for name in "vigil-$I-alb" "vigil-$I-main" "vigil-$I-gpu"; do
    group=$(aws ec2 describe-security-groups --filters "Name=group-name,Values=$name" --output json |
      json '[g["GroupId"] for g in d["SecurityGroups"]][:1]')
    echo "security group $name $group"
    aws ec2 describe-security-group-rules --filters "Name=group-id,Values=$group" --output json |
      json '["  inbound " + r["IpProtocol"] + " " + str(r.get("FromPort")) + "-" + str(r.get("ToPort")) + " from " + (r.get("CidrIpv4") or r.get("CidrIpv6") or r.get("ReferencedGroupInfo", {}).get("GroupId") or r.get("PrefixListId", "?")) for r in d["SecurityGroupRules"] if not r["IsEgress"]]'
  done
}

part_dns() {
  aws route53 list-resource-record-sets --hosted-zone-id "$(zone_id)" --output json |
    json '[r["Name"] + " " + r["Type"] + " " + (("alias " + r["AliasTarget"]["DNSName"]) if "AliasTarget" in r else " ".join(v["Value"] for v in r.get("ResourceRecords", []))) for r in d["ResourceRecordSets"]]'
}

certificate_arn() {
  aws acm list-certificates --output json |
    json '[c["CertificateArn"] for c in d["CertificateSummaryList"] if c["DomainName"] == "demo.'"$D"'"]'
}

part_certificate() {
  local arn
  for arn in $(certificate_arn); do
    aws acm describe-certificate --certificate-arn "$arn" --output json |
      json '["certificate " + d["Certificate"]["CertificateArn"], "  names " + ", ".join(d["Certificate"]["SubjectAlternativeNames"]), "  status " + d["Certificate"]["Status"], "  validation " + ", ".join(sorted({o.get("ValidationMethod", "") for o in d["Certificate"].get("DomainValidationOptions", [])}))]'
  done
}

part_load_balancer() {
  local lb listener group
  aws elbv2 describe-load-balancers --names vigil-alb --output json |
    json '["load balancer " + b["LoadBalancerName"] + " " + b["Scheme"] + " " + b["DNSName"] + " subnets " + ", ".join(z["SubnetId"] for z in b["AvailabilityZones"]) + " security groups " + ", ".join(b["SecurityGroups"]) for b in d["LoadBalancers"]]' |
    tee "$WORK/lb"
  lb=$(aws elbv2 describe-load-balancers --names vigil-alb --output json | json 'd["LoadBalancers"][0]["LoadBalancerArn"]')
  aws elbv2 describe-load-balancer-attributes --load-balancer-arn "$lb" --output json |
    json '["  " + a["Key"] + " = " + a["Value"] for a in d["Attributes"] if a["Key"] == "idle_timeout.timeout_seconds"]'
  aws elbv2 describe-listeners --load-balancer-arn "$lb" --output json |
    json '["listener " + str(l["Port"]) + " " + l["Protocol"] + " policy " + str(l.get("SslPolicy")) + " certificate " + ", ".join(c["CertificateArn"] for c in l.get("Certificates", [])) + " default " + json.dumps(l["DefaultActions"]) for l in d["Listeners"]]'
  listener=$(aws elbv2 describe-listeners --load-balancer-arn "$lb" --output json |
    json '[l["ListenerArn"] for l in d["Listeners"] if l["Port"] == 443][:1]')
  if [ -n "$listener" ]; then
    aws elbv2 describe-rules --listener-arn "$listener" --output json | python3 -c 'import json, sys
for r in json.load(sys.stdin)["Rules"]:
    conditions = ["%s %s" % (c["Field"], ",".join(c.get("Values") or c.get("HostHeaderConfig", {}).get("Values") or c.get("PathPatternConfig", {}).get("Values", []))) for c in r["Conditions"]]
    actions = [a["Type"] + " " + (a.get("TargetGroupArn") or json.dumps(a.get("FixedResponseConfig") or a.get("ForwardConfig") or {})) for a in r["Actions"]]
    print("rule", r["Priority"], "; ".join(conditions) or "-", "->", "; ".join(actions))'
  fi
  for group in vigil-app demo-control; do
    aws elbv2 describe-target-groups --names "$group" --output json |
      json '["target group " + g["TargetGroupName"] + " port " + str(g["Port"]) + " health " + g["HealthCheckPath"] + " every " + str(g["HealthCheckIntervalSeconds"]) + " s code " + g["Matcher"]["HttpCode"] for g in d["TargetGroups"]]'
    aws elbv2 describe-target-health --target-group-arn "$(aws elbv2 describe-target-groups --names "$group" --output json |
      json 'd["TargetGroups"][0]["TargetGroupArn"]')" --output json |
      json '["  target " + t["Target"]["Id"] + ":" + str(t["Target"]["Port"]) + " " + t["TargetHealth"]["State"] for t in d["TargetHealthDescriptions"]]'
  done
}

show_instance() {
  local found=$1 id image volume
  id=${found%% *}
  aws ec2 describe-instances --instance-ids "$id" --output json |
    json '[i["InstanceId"] + " " + i["InstanceType"] + " " + i["ImageId"] + " " + i["Placement"]["AvailabilityZone"] + " " + i.get("PrivateIpAddress", "") + " " + i["State"]["Name"] + " HttpTokens " + i["MetadataOptions"]["HttpTokens"] + " profile " + i.get("IamInstanceProfile", {}).get("Arn", "") for r in d["Reservations"] for i in r["Instances"]]'
  image=$(aws ec2 describe-instances --instance-ids "$id" --output json | json 'd["Reservations"][0]["Instances"][0]["ImageId"]')
  aws ec2 describe-images --image-ids "$image" --output json | json '["  image name " + i.get("Name", "") for i in d["Images"]]'
  volume=$(aws ec2 describe-instances --instance-ids "$id" --output json |
    json '[b["Ebs"]["VolumeId"] for i in [d["Reservations"][0]["Instances"][0]] for b in i["BlockDeviceMappings"] if b["DeviceName"] == i["RootDeviceName"]]')
  aws ec2 describe-volumes --volume-ids "$volume" --output json |
    json '["  root volume " + str(v["Size"]) + " GiB " + v["VolumeType"] for v in d["Volumes"]]'
}

part_instances() {
  local found
  found=$(find_instance "vigil-$I-main")
  echo "main host: $found"
  if [ -n "$found" ]; then
    show_instance "$found"
  fi
  found=$(find_instance "vigil-$I-gpu")
  if [ -n "$found" ]; then
    echo "gpu host: $found"
    show_instance "$found"
  else
    echo "gpu host: not created"
  fi
}

part_ecr_and_bucket() {
  local image bucket
  for image in vigil-app demo-control demo-lab demo-faults lab-zabbix-provision sse-probe; do
    aws ecr describe-repositories --repository-names "$I/$image" --output json |
      json '["repository " + r["repositoryName"] + " " + r["imageTagMutability"] for r in d["repositories"]]'
    aws ecr list-images --repository-name "$I/$image" --output json |
      json '"  tags " + ", ".join(sorted(i["imageTag"] for i in d["imageIds"] if "imageTag" in i))'
  done
  bucket=$(bucket_name "$I")
  echo "bucket $bucket"
  aws s3api get-public-access-block --bucket "$bucket" --output json |
    json '"  public access block " + json.dumps(d["PublicAccessBlockConfiguration"])'
  aws s3api get-bucket-ownership-controls --bucket "$bucket" --output json |
    json '"  object ownership " + ", ".join(r["ObjectOwnership"] for r in d["OwnershipControls"]["Rules"])'
  aws s3api get-bucket-encryption --bucket "$bucket" --output json |
    json '"  encryption " + ", ".join(r["ApplyServerSideEncryptionByDefault"]["SSEAlgorithm"] for r in d["ServerSideEncryptionConfiguration"]["Rules"])'
}

part_iam() {
  local role
  for role in "vigil-$I-main" "vigil-$I-gpu" "vigil-$I-github-push"; do
    echo "role $role"
    aws iam get-role --role-name "$role" --output json |
      python3 -c 'import json, sys; print("  trust", json.dumps(json.load(sys.stdin)["Role"]["AssumeRolePolicyDocument"]))'
    aws iam list-attached-role-policies --role-name "$role" --output json |
      json '["  attached " + p["PolicyArn"] for p in d["AttachedPolicies"]]'
    aws iam get-role-policy --role-name "$role" --policy-name "$role" --output json |
      python3 -c 'import json, sys; print("  inline", json.dumps(json.load(sys.stdin)["PolicyDocument"]))'
  done
  aws iam get-open-id-connect-provider \
    --open-id-connect-provider-arn "arn:aws:iam::$ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com" --output json |
    json '"oidc provider " + d["Url"] + " client IDs " + ", ".join(d["ClientIDList"])'
}

part_ses() {
  aws sesv2 get-email-identity --email-identity "$D" --output json |
    json '"identity '"$D"' verified for sending " + str(d["VerifiedForSendingStatus"]).lower() + ", DKIM " + d["DkimAttributes"]["Status"]'
}

part_parameters() {
  aws ssm describe-parameters --parameter-filters "Key=Path,Option=OneLevel,Values=/vigil/$I" --output json |
    json 'sorted(p["Name"] for p in d["Parameters"]) + ["count " + str(len(d["Parameters"]))]'
}

part_status() {
  deploy/status.sh "$I"
}

part_main_host() {
  require_running_host "vigil-$I-main" main
  run_on_host "$HOST_ID" tests/acceptance/p05_host_check.sh
}

part_https() {
  local lb_dns
  lb_dns=$(aws elbv2 describe-load-balancers --names vigil-alb --output json | json 'd["LoadBalancers"][0]["DNSName"]')
  curl -sS -o /dev/null -D "$WORK/redirect-headers" -w '%{http_code}\n' "http://demo.$D/" | tee "$WORK/http-redirect"
  awk 'tolower($1) == "location:" { sub(/\r$/, "", $2); print "Location: " $2 }' "$WORK/redirect-headers" |
    tee "$WORK/http-redirect-location"
  curl -sS -o /dev/null -w '%{http_code}\n' "https://demo.$D/" | tee "$WORK/https-demo"
  curl -sS -o /dev/null -w '%{http_code}\n' "https://demo.$D/actuator/health" | tee "$WORK/actuator-404"
  curl -sS -o /dev/null -w '%{http_code}\n' "https://control.$D/" | tee "$WORK/control-502"
  curl -sS -k -o /dev/null -w '%{http_code}\n' "https://$lb_dns/" | tee "$WORK/unknown-host-404"
  set +e
  /usr/bin/curl -sS -o /dev/null --tlsv1.0 --tls-max 1.1 "https://demo.$D/" 2> "$WORK/tls-error"
  echo "exit code $?" | tee "$WORK/tls-code"
  set -e
  cat "$WORK/tls-error"
}

part date part_date
part edge part_edge
part delegation part_delegation
part network part_network
part security-groups part_security_groups
part dns part_dns
part certificate part_certificate
part load-balancer part_load_balancer
part instances part_instances
part ecr-and-bucket part_ecr_and_bucket
part iam part_iam
part ses part_ses
part parameters part_parameters
part status part_status
part main-host part_main_host
part https part_https

# The checks (P-05 spec Section 6.12).
failed=0
check() {
  local name=$1
  shift
  if "$@"; then
    echo "PASS $name" | tee -a "$RESULT"
  else
    echo "FAIL $name" | tee -a "$RESULT"
    failed=1
  fi
}
content() {
  cat "$WORK/$1" 2> /dev/null || true
}
check http-redirect sh -c '[ "$1" = 301 ] && case "$2" in "Location: https://demo.$3"*) true ;; *) false ;; esac' sh \
  "$(content http-redirect)" "$(content http-redirect-location)" "$D"
check https-demo [ "$(content https-demo)" = 200 ]
check actuator-404 [ "$(content actuator-404)" = 404 ]
check control-502 [ "$(content control-502)" = 502 ]
check unknown-host-404 [ "$(content unknown-host-404)" = 404 ]
check tls-1.1-refused sh -c '[ "$1" = "exit code 35" ] && printf "%s" "$2" | grep -qi "alert protocol version"' sh \
  "$(content tls-code)" "$(content tls-error)"
check AR-ASM-09 python3 -c '
import sys

def names(text):
    return {n.lower().rstrip(".") for n in text.split()}

sys.exit(0 if names(sys.argv[1]) and names(sys.argv[1]) == names(sys.argv[2]) else 1)
' "$(content ns-public)" "$(content ns-zone)"
check embedding grep -qx 'embedding length 768' "$WORK/main-host.out"
check DOCKER-USER sh -c 'grep -- "-s 172.31.0.0/24" "$1" | grep -- "-d 172.30.0.10/32" | grep -- "--ctstate NEW" | grep -q -- "-j DROP"' sh \
  "$WORK/main-host.out"
check clock sh -c 'grep "Reference ID" "$1" | grep -q "169.254.169.123" && grep "Time zone" "$1" | grep -q "UTC"' sh \
  "$WORK/main-host.out"
exit "$failed"
