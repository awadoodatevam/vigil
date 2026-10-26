package vigil.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.connectors.api.ConnectorImport;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.ConnectorInstancePart;
import vigil.connectors.api.ConnectorsPart.CredentialReferencePart;
import vigil.runner.api.PermissionKind;
import vigil.runner.api.RunRequest;
import vigil.runner.api.RunnerImport;
import vigil.runner.api.RunnerPart;
import vigil.runner.api.RunnerPart.GrantPart;
import vigil.runner.api.RunnerPart.OperationPart;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.testsupport.VigilTestApp;

/**
 * The runner fixture of P-18 spec Section 7: the demo part 4 as the adapter reads it, its changes,
 * the connectors part of the lab target, imports, requests and key secrets.
 */
public final class RunnerFixture {

  public static final JsonMapper JSON = JsonMapper.builder().build();
  static final Path OPERATIONS =
      Path.of("../docs/31-demo-data/04-operations-actions-recovery.yaml");

  private RunnerFixture() {}

  /** The file {@code 04-operations-actions-recovery.yaml} as a tree. */
  public static JsonNode operationsFile() {
    try {
      return YAMLMapper.builder()
          .build()
          .readTree(Files.readString(OPERATIONS, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The sections {@code operations} and {@code target_access_grants} as the adapter reads them. */
  public static RunnerPart demoPart() {
    JsonNode file = operationsFile();
    List<OperationPart> operations = new ArrayList<>();
    for (JsonNode o : file.get("operations")) {
      operations.add(
          new OperationPart(
              text(o, "operation_key"),
              text(o, "name"),
              text(o, "permission_kind"),
              text(o, "command_template"),
              o.get("parameters"),
              text(o, "output_parser"),
              text(o, "timeout_s")));
    }
    List<GrantPart> grants = new ArrayList<>();
    for (JsonNode g : file.get("target_access_grants")) {
      grants.add(
          new GrantPart(
              text(g, "target_key"),
              text(g, "connector_instance"),
              text(g, "permission_kind"),
              text(g, "credential_ref")));
    }
    return new RunnerPart(operations, grants);
  }

  /** The parameter definitions of the demo operation {@code key}. */
  public static JsonNode definitions(String key) {
    return demoPart().operations().stream()
        .filter(operation -> operation.operationKey().equals(key))
        .findFirst()
        .orElseThrow()
        .parameters();
  }

  /** The part with the operations appended. */
  public static RunnerPart withOperations(RunnerPart part, OperationPart... added) {
    List<OperationPart> operations = new ArrayList<>(part.operations());
    operations.addAll(List.of(added));
    return new RunnerPart(operations, part.grants());
  }

  /** The part with only the grants at those indices. */
  public static RunnerPart withGrants(RunnerPart part, int... kept) {
    List<GrantPart> grants = new ArrayList<>();
    for (int index : kept) {
      grants.add(part.grants().get(index));
    }
    return new RunnerPart(part.operations(), grants);
  }

  /** An operation named after its key. */
  public static OperationPart operation(
      String key,
      String kind,
      String command,
      String parametersJson,
      String parser,
      String timeout) {
    return new OperationPart(
        key, key, kind, command, JSON.readTree(parametersJson), parser, timeout);
  }

  /** The connectors part of the lab target: the credentials and instances of DD-F-07 for it. */
  public static ConnectorsPart connectorsPart(String address, int port, String fingerprint) {
    ObjectNode settings = JSON.createObjectNode();
    settings.put("port", port);
    settings.put("host_key_sha256", fingerprint);
    return new ConnectorsPart(
        List.of(
            new CredentialReferencePart(
                "cred-lab-db-01-diag", "target_diagnostic", "secret:lab-db-01-diag"),
            new CredentialReferencePart(
                "cred-lab-db-01-action", "target_action", "secret:lab-db-01-action"),
            new CredentialReferencePart("cred-zabbix-api", "external_api", "secret:zabbix-api")),
        List.of(
            new ConnectorInstancePart(
                "linux-lab-db-01",
                "Subscriber DB server (lab)",
                "linux_server",
                address,
                settings,
                null),
            new ConnectorInstancePart(
                "zabbix-lab",
                "Zabbix lab",
                "zabbix",
                "http://127.0.0.1:1",
                JSON.readTree("{\"api_path\": \"/api_jsonrpc.php\"}"),
                "cred-zabbix-api")));
  }

  /** The connectors part, then the runner part with its handles; returns the handles. */
  public static Map<String, UUID> importInto(
      VigilTestApp app,
      TenantId tenant,
      String address,
      int port,
      String fingerprint,
      RunnerPart part) {
    Map<String, UUID> handles =
        new LinkedHashMap<>(
            app.context()
                .getBean(ConnectorImport.class)
                .importPart(
                    tenant, connectorsPart(address, port, fingerprint), Instant.now(), Map.of()));
    app.context().getBean(RunnerImport.class).importPart(tenant, part, Instant.now(), handles);
    return handles;
  }

  /** A request for the target {@code lab-db-01}. */
  public static RunRequest request(
      String requestId, PermissionKind kind, String operationKey, String parametersJson) {
    return new RunRequest(requestId, kind, operationKey, "lab-db-01", parametersJson);
  }

  /** {@code vigil-actions} for {@code ACTION}, {@code vigil-diagnostics} for {@code DIAGNOSTIC}. */
  public static Actor actor(PermissionKind kind) {
    return Actor.module(kind == PermissionKind.ACTION ? "vigil-actions" : "vigil-diagnostics");
  }

  /** A JSON secret with a newly generated ed25519 key, as {@code LabTarget} makes them. */
  public static String keySecret(String username) {
    return secret(username, newKeyPair());
  }

  static String secret(String username, KeyPair key) {
    ObjectNode secret = JSON.createObjectNode();
    secret.put("username", username);
    secret.put("private_key", privateKeyText(key));
    return JSON.writeValueAsString(secret);
  }

  static KeyPair newKeyPair() {
    try {
      return KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, 256);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The private key in the OpenSSH format without passphrase. */
  static String privateKeyText(KeyPair key) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "vigil-test", null, out);
      return out.toString(StandardCharsets.UTF_8);
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The public key as one line {@code ssh-ed25519 {base64} {comment}}. */
  static String publicKeyLine(KeyPair key) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(key.getPublic(), "vigil-test", out);
      return out.toString(StandardCharsets.UTF_8).strip();
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.isString() ? value.stringValue() : value.asString();
  }
}
