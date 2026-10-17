"""Fake of the AWS CLI for the tests of the deploy/ scripts (P-05 spec Section 7). Run as deploy/tests/bin/aws.

It answers exactly the calls the tested scripts make, in the JSON forms of the AWS CLI, and keeps its state in the
JSON file named by FAKE_AWS_STATE, which each test seeds (complete list of keys, all optional):

- "account": the account ID;
- "parameters": {name: {"Value": ..., "Type": ...}};
- "instances": {instance id: {"Name": ..., "State": ..., "Online": true or false}};
- "images": {repository: [tags]};
- "objects": {"s3://bucket/key": base64 content};
- "errors": {"{service} {operation}": error code}: the call then exits 254 with the CLI's error text;
- "calls" (written): every call with its arguments and region;
- "commands" (written): every send-command with its parameters, output and exit code.

A start sets an instance "pending" and a stop "stopping"; the next describe-instances call returns "running" or
"stopped". A running instance is Online in describe-instance-information unless seeded otherwise. ssm send-command
runs the command locally with sh (the same environment, as the test's user). Every call whose region (--region or
AWS_REGION) is not us-east-1 is refused with exit 255.
"""

import base64
import json
import os
import secrets
import subprocess
import sys
from pathlib import Path

FLAGS = {"--with-decryption", "--recursive", "--quiet", "--only-show-errors"}


class Fail(Exception):
    def __init__(self, code, message="fake error"):
        super().__init__(code)
        self.code = code
        self.message = message


def parse(arguments):
    """Options ({name: [values] or True}) and positional arguments."""
    options = {}
    positional = []
    index = 0
    while index < len(arguments):
        argument = arguments[index]
        index += 1
        if argument in FLAGS:
            options[argument[2:]] = True
        elif argument.startswith("--"):
            values = []
            while index < len(arguments) and not arguments[index].startswith("--"):
                values.append(arguments[index])
                index += 1
            options[argument[2:]] = values
        else:
            positional.append(argument)
    return options, positional


def one(options, name):
    return options[name][0]


def text(value):
    """A parameter value as the AWS CLI reads it: file:// names a file."""
    if value.startswith("file://"):
        return Path(value[len("file://") :]).read_text()
    return value


def shorthand(value):
    """Key=Path,Option=OneLevel,Values=/vigil/demo -> {"Key": "Path", ...}; Values is a list."""
    result = {}
    for part in value.split(","):
        key, equals, item = part.partition("=")
        if not equals:
            result.setdefault("Values", []).append(part)
        elif key == "Values":
            result.setdefault("Values", []).append(item)
        else:
            result[key] = item
    return result


def operation_name(operation):
    return "".join(word.capitalize() for word in operation.split("-"))


def instance_json(instance_id, instance):
    return {
        "InstanceId": instance_id,
        "State": {"Name": instance["State"]},
        "Tags": [{"Key": "Name", "Value": instance["Name"]}],
    }


def describe_instances(state, options):
    for instance in state.get("instances", {}).values():
        if instance["State"] == "pending":
            instance["State"] = "running"
        elif instance["State"] == "stopping":
            instance["State"] = "stopped"
    found = []
    for instance_id, instance in sorted(state.get("instances", {}).items()):
        if "instance-ids" in options and instance_id not in options["instance-ids"]:
            continue
        keep = True
        for item in options.get("filters", []):
            wanted = shorthand(item)
            values = wanted.get("Values", [])
            if wanted["Name"] == "tag:Name":
                keep = keep and instance["Name"] in values
            elif wanted["Name"] == "instance-state-name":
                keep = keep and instance["State"] in values
        if keep:
            found.append(instance_json(instance_id, instance))
    if "instance-ids" in options and not found:
        raise Fail("InvalidInstanceID.NotFound")
    return {"Reservations": [{"Instances": [instance]} for instance in found]}


def change_states(state, options, new_state, key):
    changed = []
    for instance_id in options["instance-ids"]:
        instance = state["instances"][instance_id]
        previous = instance["State"]
        instance["State"] = new_state
        changed.append(
            {"InstanceId": instance_id, "CurrentState": {"Name": new_state}, "PreviousState": {"Name": previous}}
        )
    return {key: changed}


def describe_instance_information(state, options):
    ids = []
    for item in options.get("filters", []):
        ids += shorthand(item).get("Values", [])
    found = []
    for instance_id in ids:
        instance = state.get("instances", {}).get(instance_id)
        if instance and instance["State"] == "running":
            online = instance.get("Online", True)
            found.append({"InstanceId": instance_id, "PingStatus": "Online" if online else "ConnectionLost"})
    return {"InstanceInformationList": found}


def send_command(state, options, state_path):
    parameters = json.loads(text(one(options, "parameters")))
    instance_id = one(options, "instance-ids")
    command_id = "fake-" + secrets.token_hex(8)
    save(state_path, state)
    process = subprocess.run(
        ["sh", "-c", "\n".join(parameters["commands"])],
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        check=False,
    )
    state.clear()
    state.update(load(state_path))
    state.setdefault("commands", {})[command_id] = {
        "InstanceId": instance_id,
        "Parameters": parameters,
        "Status": "Success" if process.returncode == 0 else "Failed",
        "ResponseCode": process.returncode,
        "StandardOutputContent": process.stdout[:24000],
        "StandardErrorContent": process.stderr[:8000],
    }
    return {"Command": {"CommandId": command_id, "InstanceIds": [instance_id], "Status": "Pending"}}


def get_command_invocation(state, options):
    command = state.get("commands", {}).get(one(options, "command-id"))
    if command is None or command["InstanceId"] != one(options, "instance-id"):
        raise Fail("InvocationDoesNotExist")
    return {
        "CommandId": one(options, "command-id"),
        "InstanceId": command["InstanceId"],
        "Status": command["Status"],
        "StatusDetails": command["Status"],
        "ResponseCode": command["ResponseCode"],
        "StandardOutputContent": command["StandardOutputContent"],
        "StandardErrorContent": command["StandardErrorContent"],
    }


def describe_parameters(state, options):
    paths = []
    for item in options.get("parameter-filters", []):
        wanted = shorthand(item)
        if wanted.get("Key") == "Path":
            paths += wanted.get("Values", [])
    found = []
    for name, parameter in sorted(state.get("parameters", {}).items()):
        if not paths or name.rsplit("/", 1)[0] in paths:
            found.append({"Name": name, "Type": parameter.get("Type", "SecureString"), "Version": 1})
    return {"Parameters": found}


def put_parameter(state, options):
    name = one(options, "name")
    parameters = state.setdefault("parameters", {})
    if name in parameters and "overwrite" not in options:
        raise Fail("ParameterAlreadyExists")
    parameters[name] = {
        "Value": text(one(options, "value")),
        "Type": one(options, "type"),
        "Tier": options.get("tier", ["Standard"])[0],
        "KeyId": options.get("key-id", ["alias/aws/ssm"])[0],
    }
    return {"Version": 1, "Tier": parameters[name]["Tier"]}


def get_parameter(state, options):
    name = one(options, "name")
    parameter = state.get("parameters", {}).get(name)
    if parameter is None:
        raise Fail("ParameterNotFound")
    value = parameter["Value"] if options.get("with-decryption") else "AQICAHfakeencryptedvalue"
    return {"Parameter": {"Name": name, "Type": parameter.get("Type", "SecureString"), "Value": value, "Version": 1}}


def describe_images(state, options):
    repository = one(options, "repository-name")
    if repository not in state.get("images", {}):
        raise Fail("RepositoryNotFoundException")
    tags = [shorthand(item).get("imageTag") for item in options.get("image-ids", [])]
    details = []
    for tag in tags:
        if tag not in state["images"][repository]:
            raise Fail("ImageNotFoundException")
        details.append({"repositoryName": repository, "imageTags": [tag], "imageDigest": "sha256:" + "0" * 64})
    return {"imageDetails": details}


def s3_copy(state, options, positional):
    source, target = positional
    objects = state.setdefault("objects", {})
    if source.startswith("s3://") and options.get("recursive"):
        prefix = source if source.endswith("/") else source + "/"
        for key, content in sorted(objects.items()):
            if key.startswith(prefix):
                path = Path(target) / key[len(prefix) :]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(base64.b64decode(content))
    elif target.startswith("s3://"):
        objects[target] = base64.b64encode(Path(source).read_bytes()).decode()
    else:
        raise Fail("UnsupportedCall", f"fake aws: s3 cp {source} {target}")
    return None


def answer(state, service, operation, options, positional, state_path):
    """The output of a call (None for no output)."""
    if (service, operation) == ("sts", "get-caller-identity"):
        account = state.get("account", "123456789012")
        return {"UserId": "AIDAFAKE", "Account": account, "Arn": f"arn:aws:iam::{account}:user/operator"}
    if service == "ec2":
        if operation == "describe-instances":
            return describe_instances(state, options)
        if operation == "start-instances":
            return change_states(state, options, "pending", "StartingInstances")
        if operation == "stop-instances":
            return change_states(state, options, "stopping", "StoppingInstances")
    if service == "ssm":
        handlers = {
            "describe-instance-information": describe_instance_information,
            "get-command-invocation": get_command_invocation,
            "describe-parameters": describe_parameters,
            "put-parameter": put_parameter,
            "get-parameter": get_parameter,
        }
        if operation in handlers:
            return handlers[operation](state, options)
        if operation == "send-command":
            return send_command(state, options, state_path)
    if (service, operation) == ("ecr", "describe-images"):
        return describe_images(state, options)
    if (service, operation) == ("ecr", "get-login-password"):
        return secrets.token_urlsafe(32)
    if (service, operation) == ("s3", "cp"):
        return s3_copy(state, options, positional)
    raise Fail("UnsupportedCall", f"fake aws: no answer for {service} {operation}")


def load(path):
    return json.loads(Path(path).read_text())


def save(path, state):
    temporary = Path(str(path) + ".tmp")
    temporary.write_text(json.dumps(state, indent=1))
    temporary.replace(path)


def main(arguments):
    state_path = os.environ["FAKE_AWS_STATE"]
    state = load(state_path)
    service, operation = (arguments + ["", ""])[:2]
    options, positional = parse(arguments[2:])
    region = options.get("region", [None])[0] or os.environ.get("AWS_REGION") or os.environ.get("AWS_DEFAULT_REGION")
    state.setdefault("calls", []).append({"args": arguments, "region": region})
    if region != "us-east-1":
        save(state_path, state)
        sys.stderr.write(f"fake aws: region {region} is not us-east-1\n")
        return 255
    try:
        seeded = state.get("errors", {}).get(f"{service} {operation}")
        if seeded:
            raise Fail(seeded)
        result = answer(state, service, operation, options, positional, state_path)
    except Fail as failure:
        save(state_path, state)
        sys.stderr.write(
            f"\nAn error occurred ({failure.code}) when calling the {operation_name(operation)} operation: "
            f"{failure.message}\n"
        )
        return 254
    save(state_path, state)
    if isinstance(result, str):
        sys.stdout.write(result + "\n")
    elif result is not None:
        sys.stdout.write(json.dumps(result, indent=4) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
