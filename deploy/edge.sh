#!/usr/bin/env bash
# Creates, idempotently, the AWS resources of an installation: network, security groups, roles, ECR repositories,
# deployment bucket, Route 53 zone, SES identity, certificate, load balancer and the two hosts (P-05 spec Section 6.4;
# AR-H-01 to AR-H-06, AR-H-08, BP-A-06, BP-A-21). Run on the Mac: deploy/edge.sh {installation} (deploy/edge.md).
# Exit codes: 0 done; 1 error; 2 usage; 3 GPU host not created; 4 delegation not visible yet.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/edge.sh {installation}"
  exit 2
}

if [ "$#" -ne 1 ] || ! known_installation "$1"; then
  usage
fi
I=$1

# Step 1: checks.
check_identity
D=$(values_value "deploy/values/$I.yaml" demo_domain)
if [ -z "$D" ]; then
  echo "deploy/values/$I.yaml has no demo_domain."
  exit 2
fi

WORK=$(temp_folder)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$WORK'" EXIT

line() {
  echo "$1 $2: $3"
}

# Prints the --tag-specifications JSON for the resource types (the arguments after the name).
tag_specifications() {
  python3 -c '
import json
import sys

name, installation, kinds = sys.argv[1], sys.argv[2], sys.argv[3:]
tags = [{"Key": "Name", "Value": name}, {"Key": "vigil:installation", "Value": installation}]
print(json.dumps([{"ResourceType": kind, "Tags": tags} for kind in kinds]))
' "$1" "$I" "${@:2}"
}

# Runs an AWS CLI call with JSON output into OUT; returns its exit code and leaves the error text in OUT.
call() {
  OUT=$(aws "$@" --output json 2>&1)
}

fail() {
  echo "$1"
  exit 1
}

# Step 2: Availability Zones (computed only for a subnet that does not exist yet).
ZONE_A=""
ZONE_B=""
choose_zones() {
  local zones offered zone
  zones=$(aws ec2 describe-availability-zones --filters Name=zone-type,Values=availability-zone --output json |
    json 'sorted(z["ZoneName"] for z in d["AvailabilityZones"])')
  if [ -z "$ZONE_A" ]; then
    offered=$(aws ec2 describe-instance-type-offerings --location-type availability-zone \
      --filters Name=instance-type,Values=m7i.xlarge,g6.xlarge --output json |
      json '[o["Location"] + " " + o["InstanceType"] for o in d["InstanceTypeOfferings"]]')
    for zone in $zones; do
      if printf '%s\n' "$offered" | grep -qx "$zone m7i.xlarge" && printf '%s\n' "$offered" | grep -qx "$zone g6.xlarge"; then
        ZONE_A=$zone
        break
      fi
    done
    if [ -z "$ZONE_A" ]; then
      fail "No Availability Zone offers both m7i.xlarge and g6.xlarge."
    fi
  fi
  if [ -z "$ZONE_B" ]; then
    for zone in $zones; do
      if [ "$zone" != "$ZONE_A" ]; then
        ZONE_B=$zone
        break
      fi
    done
  fi
}

# Step 3: network (AR-H-03).
VPC=$(aws ec2 describe-vpcs --filters "Name=tag:Name,Values=vigil-$I" --output json | json '[v["VpcId"] for v in d["Vpcs"]][:1]')
if [ -z "$VPC" ]; then
  VPC=$(aws ec2 create-vpc --cidr-block 10.40.0.0/16 --tag-specifications "$(tag_specifications "vigil-$I" vpc)" \
    --output json | json 'd["Vpc"]["VpcId"]')
  aws ec2 wait vpc-available --vpc-ids "$VPC"
  aws ec2 modify-vpc-attribute --vpc-id "$VPC" --enable-dns-support '{"Value":true}'
  aws ec2 modify-vpc-attribute --vpc-id "$VPC" --enable-dns-hostnames '{"Value":true}'
  line vpc "vigil-$I" created
else
  result=exists
  if [ "$(aws ec2 describe-vpc-attribute --vpc-id "$VPC" --attribute enableDnsSupport --output json |
    json 'd["EnableDnsSupport"]["Value"]')" != true ]; then
    aws ec2 modify-vpc-attribute --vpc-id "$VPC" --enable-dns-support '{"Value":true}'
    result="set"
  fi
  if [ "$(aws ec2 describe-vpc-attribute --vpc-id "$VPC" --attribute enableDnsHostnames --output json |
    json 'd["EnableDnsHostnames"]["Value"]')" != true ]; then
    aws ec2 modify-vpc-attribute --vpc-id "$VPC" --enable-dns-hostnames '{"Value":true}'
    result="set"
  fi
  line vpc "vigil-$I" "$result"
fi

IGW_JSON=$(aws ec2 describe-internet-gateways --filters "Name=tag:Name,Values=vigil-$I" --output json)
IGW=$(printf '%s' "$IGW_JSON" | json '[g["InternetGatewayId"] for g in d["InternetGateways"]][:1]')
if [ -z "$IGW" ]; then
  IGW=$(aws ec2 create-internet-gateway --tag-specifications "$(tag_specifications "vigil-$I" internet-gateway)" \
    --output json | json 'd["InternetGateway"]["InternetGatewayId"]')
  aws ec2 attach-internet-gateway --internet-gateway-id "$IGW" --vpc-id "$VPC"
  line internet-gateway "vigil-$I" created
else
  attached=$(printf '%s' "$IGW_JSON" | json '[a["VpcId"] for a in d["InternetGateways"][0].get("Attachments", [])]')
  if printf '%s\n' "$attached" | grep -qx "$VPC"; then
    line internet-gateway "vigil-$I" exists
  else
    aws ec2 attach-internet-gateway --internet-gateway-id "$IGW" --vpc-id "$VPC"
    line internet-gateway "vigil-$I" set
  fi
fi

# Prints "{subnet id} {zone} {public IPv4 on launch}" of the subnet with that name, or nothing.
find_subnet() {
  aws ec2 describe-subnets --filters "Name=vpc-id,Values=$VPC" "Name=tag:Name,Values=$1" --output json |
    json '[s["SubnetId"] + " " + s["AvailabilityZone"] + " " + str(s["MapPublicIpOnLaunch"]).lower() for s in d["Subnets"]][:1]'
}
SUBNET_A_INFO=$(find_subnet "vigil-$I-a")
SUBNET_B_INFO=$(find_subnet "vigil-$I-b")
if [ -n "$SUBNET_A_INFO" ]; then
  ZONE_A=$(echo "$SUBNET_A_INFO" | cut -d ' ' -f 2)
fi
if [ -n "$SUBNET_B_INFO" ]; then
  ZONE_B=$(echo "$SUBNET_B_INFO" | cut -d ' ' -f 2)
fi
if [ -z "$ZONE_A" ] || [ -z "$ZONE_B" ]; then
  choose_zones
fi

# Ensures a subnet; prints its line and sets SUBNET.
ensure_subnet() {
  local name=$1 cidr=$2 zone=$3 info=$4
  if [ -z "$info" ]; then
    SUBNET=$(aws ec2 create-subnet --vpc-id "$VPC" --cidr-block "$cidr" --availability-zone "$zone" \
      --tag-specifications "$(tag_specifications "$name" subnet)" --output json | json 'd["Subnet"]["SubnetId"]')
    aws ec2 modify-subnet-attribute --subnet-id "$SUBNET" --map-public-ip-on-launch
    line subnet "$name" created
  else
    SUBNET=$(echo "$info" | cut -d ' ' -f 1)
    if [ "$(echo "$info" | cut -d ' ' -f 3)" = true ]; then
      line subnet "$name" exists
    else
      aws ec2 modify-subnet-attribute --subnet-id "$SUBNET" --map-public-ip-on-launch
      line subnet "$name" set
    fi
  fi
}
ensure_subnet "vigil-$I-a" 10.40.1.0/24 "$ZONE_A" "$SUBNET_A_INFO"
SUBNET_A=$SUBNET
ensure_subnet "vigil-$I-b" 10.40.2.0/24 "$ZONE_B" "$SUBNET_B_INFO"
SUBNET_B=$SUBNET

reservations=$(aws ec2 get-subnet-cidr-reservations --subnet-id "$SUBNET_A" --output json |
  json '[r["Cidr"] for r in d.get("SubnetIpv4CidrReservations", [])]')
for cidr in 10.40.1.8/29 10.40.1.16/29; do
  if printf '%s\n' "$reservations" | grep -qx "$cidr"; then
    line subnet-reservation "$cidr" exists
  else
    aws ec2 create-subnet-cidr-reservation --subnet-id "$SUBNET_A" --cidr "$cidr" --reservation-type explicit > /dev/null
    line subnet-reservation "$cidr" created
  fi
done

ROUTE_JSON=$(aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$VPC" "Name=tag:Name,Values=vigil-$I" --output json)
ROUTE_TABLE=$(printf '%s' "$ROUTE_JSON" | json '[t["RouteTableId"] for t in d["RouteTables"]][:1]')
route_result=exists
if [ -z "$ROUTE_TABLE" ]; then
  ROUTE_TABLE=$(aws ec2 create-route-table --vpc-id "$VPC" --tag-specifications "$(tag_specifications "vigil-$I" route-table)" \
    --output json | json 'd["RouteTable"]["RouteTableId"]')
  ROUTE_JSON=$(aws ec2 describe-route-tables --route-table-ids "$ROUTE_TABLE" --output json)
  route_result=created
fi
default_target=$(printf '%s' "$ROUTE_JSON" |
  json '[r.get("GatewayId", "other") for r in d["RouteTables"][0]["Routes"] if r.get("DestinationCidrBlock") == "0.0.0.0/0"][:1]')
if [ -z "$default_target" ]; then
  aws ec2 create-route --route-table-id "$ROUTE_TABLE" --destination-cidr-block 0.0.0.0/0 --gateway-id "$IGW" > /dev/null
  route_result=${route_result/exists/set}
elif [ "$default_target" != "$IGW" ]; then
  aws ec2 replace-route --route-table-id "$ROUTE_TABLE" --destination-cidr-block 0.0.0.0/0 --gateway-id "$IGW"
  route_result=${route_result/exists/set}
fi
for subnet in "$SUBNET_A" "$SUBNET_B"; do
  current=$(aws ec2 describe-route-tables --filters "Name=association.subnet-id,Values=$subnet" --output json |
    json '[t["RouteTableId"] + " " + a["RouteTableAssociationId"] for t in d["RouteTables"] for a in t["Associations"] if a.get("SubnetId") == "'"$subnet"'"][:1]')
  if [ -z "$current" ]; then
    aws ec2 associate-route-table --route-table-id "$ROUTE_TABLE" --subnet-id "$subnet" > /dev/null
    route_result=${route_result/exists/set}
  elif [ "${current%% *}" != "$ROUTE_TABLE" ]; then
    aws ec2 replace-route-table-association --association-id "${current#* }" --route-table-id "$ROUTE_TABLE" > /dev/null
    route_result=${route_result/exists/set}
  fi
done
line route-table "vigil-$I" "$route_result"

# Step 4: security groups (AR-S-08, AR-H-04, AR-H-05). Rules are "{port} cidr {CIDR}" or "{port} group {group id}".
# Sets GROUP to the group's ID.
ensure_group() {
  local name=$1 description=$2 rules=$3 result changes rule port kind value
  GROUP=$(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$VPC" "Name=group-name,Values=$name" \
    --output json | json '[g["GroupId"] for g in d["SecurityGroups"]][:1]')
  if [ -z "$GROUP" ]; then
    GROUP=$(aws ec2 create-security-group --group-name "$name" --description "$description" --vpc-id "$VPC" \
      --tag-specifications "$(tag_specifications "$name" security-group)" --output json | json 'd["GroupId"]')
    result=created
  else
    result=exists
  fi
  changes=$(aws ec2 describe-security-group-rules --filters "Name=group-id,Values=$GROUP" --output json |
    RULES="$rules" python3 -c '
import json
import os
import sys

wanted = [line.split() for line in os.environ["RULES"].splitlines() if line.strip()]
present = set()
for rule in json.load(sys.stdin)["SecurityGroupRules"]:
    if rule["IsEgress"]:
        continue
    if rule.get("IpProtocol") == "tcp" and rule.get("FromPort") == rule.get("ToPort"):
        if rule.get("CidrIpv4"):
            key = [str(rule["FromPort"]), "cidr", rule["CidrIpv4"]]
        elif rule.get("ReferencedGroupInfo", {}).get("GroupId"):
            key = [str(rule["FromPort"]), "group", rule["ReferencedGroupInfo"]["GroupId"]]
        else:
            key = None
        if key in wanted and tuple(key) not in present:
            present.add(tuple(key))
            continue
    print("revoke " + rule["SecurityGroupRuleId"])
for key in wanted:
    if tuple(key) not in present:
        print("add " + " ".join(key))
')
  while read -r rule port kind value; do
    case "$rule" in
      revoke)
        aws ec2 revoke-security-group-ingress --group-id "$GROUP" --security-group-rule-ids "$port" > /dev/null
        ;;
      add)
        if [ "$kind" = cidr ]; then
          value="\"IpRanges\":[{\"CidrIp\":\"$value\"}]"
        else
          value="\"UserIdGroupPairs\":[{\"GroupId\":\"$value\"}]"
        fi
        aws ec2 authorize-security-group-ingress --group-id "$GROUP" \
          --ip-permissions "[{\"IpProtocol\":\"tcp\",\"FromPort\":$port,\"ToPort\":$port,$value}]" > /dev/null
        ;;
      *) continue ;;
    esac
    result=${result/exists/set}
  done <<< "$changes"
  line security-group "$name" "$result"
}
ensure_group "vigil-$I-alb" "Vigil $I load balancer" "443 cidr 0.0.0.0/0
80 cidr 0.0.0.0/0"
SG_ALB=$GROUP
ensure_group "vigil-$I-main" "Vigil $I main host" "8080 group $SG_ALB
8090 group $SG_ALB"
SG_MAIN=$GROUP
ensure_group "vigil-$I-gpu" "Vigil $I GPU host" "11434 cidr 10.40.1.10/32"
SG_GPU=$GROUP

# Step 5: roles (AR-H-06). The policy documents are written into the work folder.
BUCKET=$(bucket_name "$I")
python3 - "$WORK" "$ACCOUNT_ID" "$I" "$BUCKET" <<'EOF'
import json
import sys

work, account, installation, bucket = sys.argv[1:5]
parameters = [
    f"arn:aws:ssm:us-east-1:{account}:parameter/vigil/{installation}/*",
    f"arn:aws:ssm:us-east-1:{account}:parameter/vigil/{installation}",
]
repositories = f"arn:aws:ecr:us-east-1:{account}:repository/{installation}/*"
pull = [
    {"Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*"},
    {
        "Effect": "Allow",
        "Action": ["ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:BatchCheckLayerAvailability"],
        "Resource": repositories,
    },
]
reads = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
documents = {
    "trust-ec2": {
        "Version": "2012-10-17",
        "Statement": [{"Effect": "Allow", "Principal": {"Service": "ec2.amazonaws.com"}, "Action": "sts:AssumeRole"}],
    },
    "trust-github": {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Principal": {
                    "Federated": f"arn:aws:iam::{account}:oidc-provider/token.actions.githubusercontent.com"
                },
                "Action": "sts:AssumeRoleWithWebIdentity",
                "Condition": {
                    "StringEquals": {
                        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
                        "token.actions.githubusercontent.com:sub": "repo:awadoodatevam/vigil:ref:refs/heads/main",
                    }
                },
            }
        ],
    },
    "policy-main": {
        "Version": "2012-10-17",
        "Statement": [
            {"Effect": "Allow", "Action": ["ssm:GetParameter", "ssm:GetParametersByPath"], "Resource": parameters},
            *pull,
            {"Effect": "Allow", "Action": "s3:GetObject", "Resource": f"arn:aws:s3:::{bucket}/*"},
            {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": f"arn:aws:s3:::{bucket}"},
            {"Effect": "Deny", "Action": reads, "NotResource": parameters},
        ],
    },
    "policy-gpu": {
        "Version": "2012-10-17",
        "Statement": [*pull, {"Effect": "Deny", "Action": reads, "Resource": f"arn:aws:ssm:*:{account}:parameter/*"}],
    },
    "policy-github-push": {
        "Version": "2012-10-17",
        "Statement": [
            {"Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*"},
            {
                "Effect": "Allow",
                "Action": [
                    "ecr:BatchCheckLayerAvailability",
                    "ecr:InitiateLayerUpload",
                    "ecr:UploadLayerPart",
                    "ecr:CompleteLayerUpload",
                    "ecr:PutImage",
                    "ecr:BatchGetImage",
                    "ecr:DescribeImages",
                ],
                "Resource": repositories,
            },
        ],
    },
}
for name, document in documents.items():
    with open(f"{work}/{name}.json", "w") as file:
        json.dump(document, file)
EOF

SSM_POLICY=arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
# Ensures a role with its trust policy, inline policy and (for instance roles) the managed policy and instance profile.
ensure_role() {
  local name=$1 trust=$2 policy=$3 instance=$4 created=no roles role
  if call iam get-role --role-name "$name"; then
    aws iam update-assume-role-policy --role-name "$name" --policy-document "file://$WORK/$trust.json"
  elif [ "$(error_code "$OUT")" = NoSuchEntity ]; then
    aws iam create-role --role-name "$name" --assume-role-policy-document "file://$WORK/$trust.json" \
      --tags "Key=Name,Value=$name" "Key=vigil:installation,Value=$I" > /dev/null
    created=yes
  else
    fail "$OUT"
  fi
  if [ "$instance" = yes ]; then
    aws iam attach-role-policy --role-name "$name" --policy-arn "$SSM_POLICY"
  fi
  if [ "$created" = yes ]; then
    line role "$name" created
  else
    line role "$name" set
  fi
  aws iam put-role-policy --role-name "$name" --policy-name "$name" --policy-document "file://$WORK/$policy.json"
  if [ "$created" = yes ]; then
    line role-policy "$name" created
  else
    line role-policy "$name" set
  fi
  if [ "$instance" != yes ]; then
    return
  fi
  if call iam get-instance-profile --instance-profile-name "$name"; then
    roles=$(printf '%s' "$OUT" | json '[r["RoleName"] for r in d["InstanceProfile"]["Roles"]]')
    if [ "$roles" = "$name" ]; then
      line instance-profile "$name" exists
    else
      for role in $roles; do
        aws iam remove-role-from-instance-profile --instance-profile-name "$name" --role-name "$role"
      done
      aws iam add-role-to-instance-profile --instance-profile-name "$name" --role-name "$name"
      line instance-profile "$name" set
    fi
  elif [ "$(error_code "$OUT")" = NoSuchEntity ]; then
    aws iam create-instance-profile --instance-profile-name "$name" \
      --tags "Key=Name,Value=$name" "Key=vigil:installation,Value=$I" > /dev/null
    aws iam add-role-to-instance-profile --instance-profile-name "$name" --role-name "$name"
    line instance-profile "$name" created
  else
    fail "$OUT"
  fi
}
ensure_role "vigil-$I-main" trust-ec2 policy-main yes
ensure_role "vigil-$I-gpu" trust-ec2 policy-gpu yes

OIDC_URL=https://token.actions.githubusercontent.com
OIDC_ARN="arn:aws:iam::$ACCOUNT_ID:oidc-provider/token.actions.githubusercontent.com"
if call iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN"; then
  line oidc-provider "$OIDC_URL" exists
elif [ "$(error_code "$OUT")" = NoSuchEntity ]; then
  aws iam create-open-id-connect-provider --url "$OIDC_URL" --client-id-list sts.amazonaws.com \
    --tags "Key=Name,Value=$OIDC_URL" "Key=vigil:installation,Value=$I" > /dev/null
  line oidc-provider "$OIDC_URL" created
else
  fail "$OUT"
fi
ensure_role "vigil-$I-github-push" trust-github policy-github-push no
PUSH_ROLE_ARN="arn:aws:iam::$ACCOUNT_ID:role/vigil-$I-github-push"

# Step 6: ECR repositories and the deployment bucket (AR-H-11, BP-A-21).
for image in vigil-app demo-control demo-lab demo-faults lab-zabbix-provision sse-probe; do
  repository="$I/$image"
  if call ecr describe-repositories --repository-names "$repository"; then
    aws ecr put-image-tag-mutability --repository-name "$repository" --image-tag-mutability IMMUTABLE > /dev/null
    line ecr-repository "$repository" set
  elif [ "$(error_code "$OUT")" = RepositoryNotFoundException ]; then
    aws ecr create-repository --repository-name "$repository" --image-tag-mutability IMMUTABLE \
      --tags "Key=Name,Value=$repository" "Key=vigil:installation,Value=$I" > /dev/null
    line ecr-repository "$repository" created
  else
    fail "$OUT"
  fi
done

if call s3api head-bucket --bucket "$BUCKET" --expected-bucket-owner "$ACCOUNT_ID"; then
  bucket_result="set"
elif [ "$(error_code "$OUT")" = 404 ]; then
  aws s3api create-bucket --bucket "$BUCKET" > /dev/null
  aws s3api wait bucket-exists --bucket "$BUCKET"
  aws s3api put-bucket-tagging --bucket "$BUCKET" \
    --tagging "{\"TagSet\":[{\"Key\":\"Name\",\"Value\":\"$BUCKET\"},{\"Key\":\"vigil:installation\",\"Value\":\"$I\"}]}"
  bucket_result=created
else
  fail "$OUT"
fi
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-ownership-controls --bucket "$BUCKET" \
  --ownership-controls '{"Rules":[{"ObjectOwnership":"BucketOwnerEnforced"}]}'
aws s3api put-bucket-encryption --bucket "$BUCKET" \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
line bucket "$BUCKET" "$bucket_result"

# Step 7: the hosted zone (AR-H-05).
ZONE=$(aws route53 list-hosted-zones-by-name --dns-name "$D" --output json |
  json '[z["Id"].split("/")[-1] for z in d["HostedZones"] if z["Name"] == "'"$D"'." and not z["Config"]["PrivateZone"]][:1]')
zone_created=no
if [ -z "$ZONE" ]; then
  reference="vigil-$I-$(python3 -c 'import uuid; print(uuid.uuid4())')"
  ZONE=$(aws route53 create-hosted-zone --name "$D" --caller-reference "$reference" --output json |
    json 'd["HostedZone"]["Id"].split("/")[-1]')
  aws route53 change-tags-for-resource --resource-type hostedzone --resource-id "$ZONE" \
    --add-tags "Key=Name,Value=$D" "Key=vigil:installation,Value=$I"
  zone_created=yes
  line hosted-zone "$D" created
else
  line hosted-zone "$D" exists
fi
NAME_SERVERS=$(aws route53 get-hosted-zone --id "$ZONE" --output json |
  json '[n.lower().rstrip(".") for n in d["DelegationSet"]["NameServers"]]')
for name in $NAME_SERVERS; do
  echo "name server $name"
done

# Writes the records ("{name} {type} {value}" lines, or "{name} alias {zone} {DNS name}") into the zone with UPSERT.
upsert_records() {
  local purpose=$1 records=$2
  RECORDS="$records" python3 -c '
import json
import os
import sys

changes = []
for line in os.environ["RECORDS"].splitlines():
    parts = line.split()
    if parts[1] == "alias":
        record = {
            "Name": parts[0],
            "Type": "A",
            "AliasTarget": {"HostedZoneId": parts[2], "DNSName": parts[3], "EvaluateTargetHealth": False},
        }
    else:
        record = {"Name": parts[0], "Type": parts[1], "TTL": 300, "ResourceRecords": [{"Value": parts[2]}]}
    changes.append({"Action": "UPSERT", "ResourceRecordSet": record})
with open(sys.argv[1], "w") as file:
    json.dump({"Changes": changes}, file)
' "$WORK/records.json"
  aws route53 change-resource-record-sets --hosted-zone-id "$ZONE" --change-batch "file://$WORK/records.json" > /dev/null
  if [ "$zone_created" = yes ]; then
    line dns-records "$purpose" created
  else
    line dns-records "$purpose" set
  fi
}

# Step 8: SES identity with Easy DKIM (AR-H-08).
if call sesv2 get-email-identity --email-identity "$D"; then
  line ses-identity "$D" exists
elif [ "$(error_code "$OUT")" = NotFoundException ]; then
  OUT=$(aws sesv2 create-email-identity --email-identity "$D" --dkim-signing-attributes NextSigningKeyLength=RSA_2048_BIT \
    --tags "Key=Name,Value=$D" "Key=vigil:installation,Value=$I" --output json)
  line ses-identity "$D" created
else
  fail "$OUT"
fi
tokens=$(printf '%s' "$OUT" | json 'd["DkimAttributes"]["Tokens"]')
records=""
for token in $tokens; do
  records="$records$token._domainkey.$D CNAME $token.dkim.amazonses.com
"
done
upsert_records ses-dkim "$records"

# Step 9: the certificate (AR-H-05).
CERTIFICATE=$(aws acm list-certificates --certificate-statuses ISSUED PENDING_VALIDATION --output json |
  json '[c["CertificateArn"] for c in d["CertificateSummaryList"] if c["DomainName"] == "demo.'"$D"'" and sorted(c.get("SubjectAlternativeNameSummaries", [])) == sorted(["demo.'"$D"'", "control.'"$D"'"])][:1]')
if [ -z "$CERTIFICATE" ]; then
  CERTIFICATE=$(aws acm request-certificate --domain-name "demo.$D" --subject-alternative-names "control.$D" \
    --validation-method DNS --tags "Key=Name,Value=vigil-$I" "Key=vigil:installation,Value=$I" --output json |
    json 'd["CertificateArn"]')
  line certificate "vigil-$I" created
else
  line certificate "vigil-$I" exists
fi
attempt=0
while true; do
  certificate_json=$(aws acm describe-certificate --certificate-arn "$CERTIFICATE" --output json)
  if [ "$(printf '%s' "$certificate_json" |
    json 'len(d["Certificate"].get("DomainValidationOptions", [])) == 2 and all("ResourceRecord" in o for o in d["Certificate"]["DomainValidationOptions"])')" = true ]; then
    break
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 24 ]; then
    fail "ACM has not published the validation records."
  fi
  pause 5
done
validation=$(printf '%s' "$certificate_json" |
  json 'sorted(set(o["ResourceRecord"]["Name"] + " " + o["ResourceRecord"]["Type"] + " " + o["ResourceRecord"]["Value"] for o in d["Certificate"]["DomainValidationOptions"]))')
upsert_records certificate-validation "$validation"

# Step 10: target groups and load balancer (AR-H-05).
# Ensures a target group; sets TG_ARN.
ensure_target_group() {
  local name=$1 port=$2 path=$3
  if call elbv2 describe-target-groups --names "$name"; then
    TG_ARN=$(printf '%s' "$OUT" | json 'd["TargetGroups"][0]["TargetGroupArn"]')
    aws elbv2 modify-target-group --target-group-arn "$TG_ARN" --health-check-protocol HTTP --health-check-path "$path" \
      --health-check-interval-seconds 10 --matcher HttpCode=200 > /dev/null
    line target-group "$name" set
  elif [ "$(error_code "$OUT")" = TargetGroupNotFound ]; then
    TG_ARN=$(aws elbv2 create-target-group --name "$name" --protocol HTTP --port "$port" --vpc-id "$VPC" \
      --target-type instance --health-check-protocol HTTP --health-check-path "$path" --health-check-interval-seconds 10 \
      --matcher HttpCode=200 --tags "Key=Name,Value=$name" "Key=vigil:installation,Value=$I" --output json |
      json 'd["TargetGroups"][0]["TargetGroupArn"]')
    line target-group "$name" created
  else
    fail "$OUT"
  fi
}
ensure_target_group vigil-app 8080 /actuator/health/liveness
TG_APP=$TG_ARN
ensure_target_group demo-control 8090 /health
TG_CONTROL=$TG_ARN

if call elbv2 describe-load-balancers --names vigil-alb; then
  LB_ARN=$(printf '%s' "$OUT" | json 'd["LoadBalancers"][0]["LoadBalancerArn"]')
  lb_result="set"
elif [ "$(error_code "$OUT")" = LoadBalancerNotFound ]; then
  OUT=$(aws elbv2 create-load-balancer --name vigil-alb --type application --scheme internet-facing --ip-address-type ipv4 \
    --subnets "$SUBNET_A" "$SUBNET_B" --security-groups "$SG_ALB" \
    --tags "Key=Name,Value=vigil-alb" "Key=vigil:installation,Value=$I" --output json)
  LB_ARN=$(printf '%s' "$OUT" | json 'd["LoadBalancers"][0]["LoadBalancerArn"]')
  lb_result=created
else
  fail "$OUT"
fi
LB_DNS=$(printf '%s' "$OUT" | json 'd["LoadBalancers"][0]["DNSName"]')
LB_ZONE=$(printf '%s' "$OUT" | json 'd["LoadBalancers"][0]["CanonicalHostedZoneId"]')
aws elbv2 modify-load-balancer-attributes --load-balancer-arn "$LB_ARN" \
  --attributes Key=idle_timeout.timeout_seconds,Value=120 > /dev/null
line load-balancer vigil-alb "$lb_result"

# Prints the ARN of the load balancer's listener on that port, or nothing.
find_listener() {
  aws elbv2 describe-listeners --load-balancer-arn "$LB_ARN" --output json |
    json '[l["ListenerArn"] for l in d["Listeners"] if l["Port"] == '"$1"'][:1]'
}
if [ -n "$(find_listener 80)" ]; then
  line listener 80 exists
else
  aws elbv2 create-listener --load-balancer-arn "$LB_ARN" --protocol HTTP --port 80 \
    --default-actions '[{"Type":"redirect","RedirectConfig":{"Protocol":"HTTPS","Port":"443","StatusCode":"HTTP_301"}}]' > /dev/null
  line listener 80 created
fi

# Step 11: the main host (AR-H-01, BP-A-06).
# Launches an instance; sets LAUNCH_ID, or (when the last argument is "refusals") LAUNCH_ERROR to the EC2 error code
# of a quota or capacity refusal.
launch_instance() {
  local name=$1 type=$2 image_parameter=$3 address=$4 group=$5 user_data=$6 refusals=${7:-} image root attempt=0
  image=$(aws ssm get-parameter --name "$image_parameter" --output json | json 'd["Parameter"]["Value"]')
  root=$(aws ec2 describe-images --image-ids "$image" --output json | json 'd["Images"][0]["RootDeviceName"]')
  LAUNCH_ID=""
  LAUNCH_ERROR=""
  while true; do
    if call ec2 run-instances --image-id "$image" --instance-type "$type" --count 1 \
      --network-interfaces "[{\"DeviceIndex\":0,\"SubnetId\":\"$SUBNET_A\",\"PrivateIpAddress\":\"$address\",\"Groups\":[\"$group\"],\"AssociatePublicIpAddress\":true,\"DeleteOnTermination\":true}]" \
      --iam-instance-profile "Name=$name" \
      --block-device-mappings "[{\"DeviceName\":\"$root\",\"Ebs\":{\"VolumeSize\":100,\"VolumeType\":\"gp3\",\"DeleteOnTermination\":true}}]" \
      --metadata-options HttpTokens=required,HttpEndpoint=enabled \
      --user-data "file://$user_data" \
      --tag-specifications "$(tag_specifications "$name" instance volume network-interface)"; then
      LAUNCH_ID=$(printf '%s' "$OUT" | json 'd["Instances"][0]["InstanceId"]')
      return
    fi
    case "$(error_code "$OUT")" in
      VcpuLimitExceeded | InsufficientInstanceCapacity)
        if [ "$refusals" != refusals ]; then
          fail "$OUT"
        fi
        LAUNCH_ERROR=$(error_code "$OUT")
        return
        ;;
      InvalidParameterValue)
        case "$OUT" in
          *"$name"*) ;;
          *) fail "$OUT" ;;
        esac
        attempt=$((attempt + 1))
        if [ "$attempt" -ge 12 ]; then
          fail "The instance profile $name is not usable yet; run deploy/edge.sh $I again."
        fi
        pause 10
        ;;
      *) fail "$OUT" ;;
    esac
  done
}

# Waits (at most 20 minutes, every 10 s) until the host is online in Systems Manager and has its bootstrap marker.
wait_bootstrap() {
  local id=$1 attempt=0
  while [ "$attempt" -lt 120 ]; do
    attempt=$((attempt + 1))
    if [ "$(ping_status "$id")" = Online ] && (run_on_host "$id" deploy/hosts/marker.sh > /dev/null 2>&1); then
      return 0
    fi
    pause 10
  done
  return 1
}

MAIN=$(find_instance "vigil-$I-main")
if [ -z "$MAIN" ]; then
  launch_instance "vigil-$I-main" m7i.xlarge \
    /aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id \
    10.40.1.10 "$SG_MAIN" deploy/hosts/main-user-data.sh
  MAIN_ID=$LAUNCH_ID
  if ! wait_bootstrap "$MAIN_ID"; then
    fail "The main host has not finished its bootstrap; see /var/log/vigil-bootstrap.log on the host (Session Manager)."
  fi
  line instance "vigil-$I-main" created
else
  MAIN_ID=${MAIN%% *}
  if [ "${MAIN#* }" = running ] && ! wait_bootstrap "$MAIN_ID"; then
    fail "The main host has not finished its bootstrap; see /var/log/vigil-bootstrap.log on the host (Session Manager)."
  fi
  line instance "vigil-$I-main" exists
fi

main_state=$(instance_state "$MAIN_ID")
# Registers the main host in a target group when it runs (step 11).
ensure_target() {
  local group=$1 arn=$2 port=$3 registered
  registered=$(aws elbv2 describe-target-health --target-group-arn "$arn" --output json |
    json '[t["Target"]["Id"] for t in d["TargetHealthDescriptions"]]')
  if printf '%s\n' "$registered" | grep -qx "$MAIN_ID"; then
    line target "$group/vigil-$I-main" exists
  elif [ "$main_state" = running ]; then
    aws elbv2 register-targets --target-group-arn "$arn" --targets "Id=$MAIN_ID,Port=$port"
    line target "$group/vigil-$I-main" created
  else
    line target "$group/vigil-$I-main" "skipped ($main_state)"
  fi
}
ensure_target vigil-app "$TG_APP" 8080
ensure_target demo-control "$TG_CONTROL" 8090

# Step 12: delegation check (AR-ASM-09).
visible=$(dig +short NS "$D" @1.1.1.1 || true)
if [ "$(VISIBLE="$visible" ZONE_NS="$NAME_SERVERS" python3 -c '
import os

def names(text):
    return {n.lower().rstrip(".") for n in text.split()}

print("same" if names(os.environ["VISIBLE"]) == names(os.environ["ZONE_NS"]) else "different")
')" != same ]; then
  echo "main host: $MAIN_ID $(instance_state "$MAIN_ID")"
  echo "Delegation of $D not visible yet. Add these NS records at Cloudflare (deploy/edge.md), wait until dig +short NS $D @1.1.1.1 shows them, then run deploy/edge.sh $I again:"
  for name in $NAME_SERVERS; do
    echo "$name"
  done
  exit 4
fi

# Step 13: the certificate is issued.
attempt=0
while true; do
  status=$(aws acm describe-certificate --certificate-arn "$CERTIFICATE" --output json | json 'd["Certificate"]["Status"]')
  if [ "$status" = ISSUED ]; then
    break
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    fail "The certificate is $status after 30 minutes."
  fi
  pause 30
done

# Step 14: the HTTPS listener and its rules (AR-H-05, AR-S-09).
LISTENER=$(find_listener 443)
if [ -z "$LISTENER" ]; then
  listener_created=yes
  LISTENER=$(aws elbv2 create-listener --load-balancer-arn "$LB_ARN" --protocol HTTPS --port 443 \
    --certificates "CertificateArn=$CERTIFICATE" --ssl-policy ELBSecurityPolicy-TLS13-1-2-2021-06 \
    --default-actions '[{"Type":"fixed-response","FixedResponseConfig":{"StatusCode":"404","ContentType":"text/plain"}}]' \
    --output json | json 'd["Listeners"][0]["ListenerArn"]')
  line listener 443 created
else
  listener_created=no
  line listener 443 exists
fi
changes=$(aws elbv2 describe-rules --listener-arn "$LISTENER" --output json |
  python3 -c '
import json
import sys

work, domain, app, control = sys.argv[1:5]
not_found = [{"Type": "fixed-response", "FixedResponseConfig": {"StatusCode": "404", "ContentType": "text/plain"}}]


def host(name):
    return {"Field": "host-header", "HostHeaderConfig": {"Values": [name]}}


wanted = {
    10: ([host("demo." + domain), {"Field": "path-pattern", "PathPatternConfig": {"Values": ["/actuator*"]}}], not_found),
    20: ([host("demo." + domain)], [{"Type": "forward", "TargetGroupArn": app}]),
    30: ([host("control." + domain)], [{"Type": "forward", "TargetGroupArn": control}]),
}


def conditions(items):
    result = set()
    for item in items:
        values = item.get("Values") or []
        for key in ("HostHeaderConfig", "PathPatternConfig"):
            values = values or item.get(key, {}).get("Values", [])
        result.add((item["Field"], tuple(sorted(values))))
    return result


def actions(items):
    result = []
    for item in sorted(items, key=lambda a: a.get("Order", 0)):
        if item["Type"] == "forward":
            groups = item.get("ForwardConfig", {}).get("TargetGroups", [])
            arns = [item["TargetGroupArn"]] if item.get("TargetGroupArn") else [g["TargetGroupArn"] for g in groups]
            result.append(("forward", tuple(arns)))
        elif item["Type"] == "fixed-response":
            config = item["FixedResponseConfig"]
            result.append(("fixed", config["StatusCode"], config.get("ContentType"), config.get("MessageBody", "")))
        else:
            result.append((item["Type"],))
    return result


kept = set()
for rule in json.load(sys.stdin)["Rules"]:
    if rule["IsDefault"]:
        continue
    priority = int(rule["Priority"])
    if priority in wanted and priority not in kept:
        want = wanted[priority]
        if conditions(rule["Conditions"]) == conditions(want[0]) and actions(rule["Actions"]) == actions(want[1]):
            kept.add(priority)
            continue
    print("delete " + rule["RuleArn"])
for priority, (rule_conditions, rule_actions) in wanted.items():
    if priority not in kept:
        with open(f"{work}/conditions-{priority}.json", "w") as file:
            json.dump(rule_conditions, file)
        with open(f"{work}/actions-{priority}.json", "w") as file:
            json.dump(rule_actions, file)
        print(f"create {priority}")
' "$WORK" "$D" "$TG_APP" "$TG_CONTROL")
rules_result=exists
while read -r change value; do
  case "$change" in
    delete) aws elbv2 delete-rule --rule-arn "$value" ;;
    create)
      aws elbv2 create-rule --listener-arn "$LISTENER" --priority "$value" \
        --conditions "file://$WORK/conditions-$value.json" --actions "file://$WORK/actions-$value.json" > /dev/null
      ;;
    *) continue ;;
  esac
  rules_result="set"
done <<< "$changes"
if [ "$listener_created" = no ]; then
  line listener-rules 443 "$rules_result"
fi

# Step 15: address records (AR-H-05).
upsert_records demo-and-control "demo.$D alias $LB_ZONE $LB_DNS
control.$D alias $LB_ZONE $LB_DNS"

# Step 17: the summary.
summary() {
  local found
  for name in $NAME_SERVERS; do
    echo "name server $name"
  done
  echo "load balancer: $LB_DNS"
  echo "bucket: $BUCKET"
  echo "push role: $PUSH_ROLE_ARN"
  echo "main host: $MAIN_ID $(instance_state "$MAIN_ID")"
  found=$(find_instance "vigil-$I-gpu")
  if [ -n "$found" ]; then
    echo "gpu host: $found"
  else
    echo "gpu host: not created"
  fi
}

# Step 16: the GPU host (AR-H-02, BP-A-06).
GPU=$(find_instance "vigil-$I-gpu")
if [ -z "$GPU" ]; then
  HOST_KIND=GPU
  launch_instance "vigil-$I-gpu" g6.xlarge \
    /aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-24.04/latest/ami-id \
    10.40.1.20 "$SG_GPU" deploy/hosts/gpu-user-data.sh refusals
  case "$LAUNCH_ERROR" in
    VcpuLimitExceeded)
      echo "The GPU host was not created: VcpuLimitExceeded. See BP-OT-09 in deploy/edge.md and run deploy/edge.sh $I again later."
      summary
      exit 3
      ;;
    InsufficientInstanceCapacity)
      echo "The GPU host was not created: InsufficientInstanceCapacity. AWS has no g6.xlarge capacity now (AR-ASM-06); run deploy/edge.sh $I again later."
      summary
      exit 3
      ;;
  esac
  if ! wait_bootstrap "$LAUNCH_ID"; then
    echo "The GPU host has not finished its bootstrap; see /var/log/vigil-bootstrap.log on the host (Session Manager)."
    fail "The GPU host is still running: deploy/gpu.sh stop."
  fi
  aws ec2 stop-instances --instance-ids "$LAUNCH_ID" > /dev/null
  attempt=0
  while [ "$(instance_state "$LAUNCH_ID")" != stopped ]; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
      fail "The GPU host did not stop."
    fi
    pause 10
  done
  HOST_KIND=main
  line instance "vigil-$I-gpu" created
else
  line instance "vigil-$I-gpu" exists
fi

summary
exit 0
