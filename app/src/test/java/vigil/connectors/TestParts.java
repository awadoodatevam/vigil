package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.ConnectorInstancePart;
import vigil.connectors.api.ConnectorsPart.CredentialReferencePart;
import vigil.shared.VigilError;

/** Seed parts 3 for the tests: the DD-F-07 part, changed copies of it, and small parts. */
final class TestParts {

  private static final Path DD_F_07 = Path.of("../docs/31-demo-data/07-settings.yaml");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]*)}");
  private static final List<String> MAILBOXES =
      List.of(
          "noc-l1",
          "db-ops",
          "vas-service-owners",
          "it-apps",
          "noc-shift-lead",
          "noc-management",
          "customer-care",
          "dbops-engineer",
          "dbops-lead");

  /** The host key fingerprint of this test run. */
  static final String HOST_KEY = hostKey();

  private TestParts() {}

  private static String hostKey() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The DD-F-07 part with the test's deployment values. */
  @SuppressWarnings("unchecked")
  static ConnectorsPart ddF07() {
    Map<String, String> values = new HashMap<>();
    values.put("demo_domain", "vigil.test");
    values.put("endpoint.jira", "http://jira.test:8000");
    values.put("endpoint.smtp", "smtp://smtp.test:1025");
    values.put("smtp_starttls", "false");
    values.put("endpoint.claude", "http://claude.test:8000");
    values.put("endpoint.openai", "http://openai.test:8000");
    values.put("endpoint.local_embedding", "http://embed.test:11434");
    values.put("gpu_host_ip", "gpu.test");
    values.put("lab_db_host_key_sha256", HOST_KEY);
    MAILBOXES.forEach(key -> values.put("mailbox." + key, key + "@vigil.test"));
    String text;
    try {
      text = Files.readString(DD_F_07, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Matcher matcher = PLACEHOLDER.matcher(text);
    StringBuilder filled = new StringBuilder();
    while (matcher.find()) {
      String value = values.get(matcher.group(1));
      if (value == null) {
        fail("no test value for placeholder " + matcher.group(1));
      }
      matcher.appendReplacement(filled, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(filled);
    Map<String, Object> data = new Yaml().load(filled.toString());
    List<CredentialReferencePart> refs = new ArrayList<>();
    for (Map<String, Object> ref : (List<Map<String, Object>>) data.get("credential_references")) {
      refs.add(
          new CredentialReferencePart(
              (String) ref.get("handle"),
              (String) ref.get("purpose"),
              (String) ref.get("locator")));
    }
    List<ConnectorInstancePart> instances = new ArrayList<>();
    for (Map<String, Object> instance :
        (List<Map<String, Object>>) data.get("connector_instances")) {
      instances.add(
          new ConnectorInstancePart(
              (String) instance.get("handle"),
              (String) instance.get("name"),
              (String) instance.get("kind"),
              (String) instance.get("address"),
              Recordings.JSON.valueToTree(instance.get("settings")),
              (String) instance.get("credential_ref")));
    }
    return new ConnectorsPart(refs, instances);
  }

  /** The part with the credential reference {@code handle} changed. */
  static ConnectorsPart ref(
      ConnectorsPart part, String handle, UnaryOperator<CredentialReferencePart> change) {
    List<CredentialReferencePart> refs = new ArrayList<>();
    boolean found = false;
    for (CredentialReferencePart ref : part.credentialReferences()) {
      boolean match = !found && handle.equals(ref.handle());
      found |= match;
      refs.add(match ? change.apply(ref) : ref);
    }
    assertThat(found).as("credential reference %s", handle).isTrue();
    return new ConnectorsPart(refs, part.connectorInstances());
  }

  /** The part with the instance {@code handle} changed. */
  static ConnectorsPart instance(
      ConnectorsPart part, String handle, UnaryOperator<ConnectorInstancePart> change) {
    List<ConnectorInstancePart> instances = new ArrayList<>();
    boolean found = false;
    for (ConnectorInstancePart instance : part.connectorInstances()) {
      boolean match = !found && handle.equals(instance.handle());
      found |= match;
      instances.add(match ? change.apply(instance) : instance);
    }
    assertThat(found).as("instance %s", handle).isTrue();
    return new ConnectorsPart(part.credentialReferences(), instances);
  }

  static CredentialReferencePart withHandle(CredentialReferencePart ref, String handle) {
    return new CredentialReferencePart(handle, ref.purpose(), ref.locator());
  }

  static CredentialReferencePart withPurpose(CredentialReferencePart ref, String purpose) {
    return new CredentialReferencePart(ref.handle(), purpose, ref.locator());
  }

  static CredentialReferencePart withLocator(CredentialReferencePart ref, String locator) {
    return new CredentialReferencePart(ref.handle(), ref.purpose(), locator);
  }

  static ConnectorInstancePart withHandle(ConnectorInstancePart i, String handle) {
    return new ConnectorInstancePart(
        handle, i.name(), i.kind(), i.address(), i.settings(), i.credentialRef());
  }

  static ConnectorInstancePart withName(ConnectorInstancePart i, String name) {
    return new ConnectorInstancePart(
        i.handle(), name, i.kind(), i.address(), i.settings(), i.credentialRef());
  }

  static ConnectorInstancePart withKind(ConnectorInstancePart i, String kind) {
    return new ConnectorInstancePart(
        i.handle(), i.name(), kind, i.address(), i.settings(), i.credentialRef());
  }

  static ConnectorInstancePart withAddress(ConnectorInstancePart i, String address) {
    return new ConnectorInstancePart(
        i.handle(), i.name(), i.kind(), address, i.settings(), i.credentialRef());
  }

  /** The instance with the settings given as JSON text ({@code null} for a Java {@code null}). */
  static ConnectorInstancePart withSettings(ConnectorInstancePart i, String settings) {
    return new ConnectorInstancePart(
        i.handle(),
        i.name(),
        i.kind(),
        i.address(),
        settings == null ? null : Recordings.tree(settings),
        i.credentialRef());
  }

  static ConnectorInstancePart withCredentialRef(ConnectorInstancePart i, String credentialRef) {
    return new ConnectorInstancePart(
        i.handle(), i.name(), i.kind(), i.address(), i.settings(), credentialRef);
  }

  static CredentialReferencePart credential(String handle, String purpose, String locator) {
    return new CredentialReferencePart(handle, purpose, locator);
  }

  /** An instance named as its handle with settings given as JSON text. */
  static ConnectorInstancePart connector(
      String handle, String kind, String address, String settings, String credentialRef) {
    return new ConnectorInstancePart(
        handle, handle, kind, address, Recordings.tree(settings), credentialRef);
  }

  /** The problems of an import refusal. */
  @SuppressWarnings("unchecked")
  static List<String> problems(VigilError error) {
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part connectors refused.");
    return (List<String>) error.details().get("problems");
  }
}
