package vigil.connectors;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vigil.connectors.ConnectorRecords.StoredCredential;
import vigil.connectors.api.CredentialPurpose;
import vigil.connectors.api.Secrets;
import vigil.shared.TenantId;
import vigil.shared.VigilError;

/**
 * The secret store of AR-S-04: one file per secret in the folder {@code vigil.secrets.dir}, read
 * again at each use. The module's only reader of secret files. Logs names only, never a value.
 */
@Component
class FileSecrets implements Secrets {

  private static final Logger LOG = LoggerFactory.getLogger(FileSecrets.class);
  private static final Pattern NAME = Pattern.compile("^[a-z0-9-]{1,64}$");
  private static final String SEED_PASSWORD_PREFIX = "seed-password-";
  private static final String INSTALLATION_TOKEN = "installation-token";

  private final Path folder;
  private final ConnectorRecords records;

  FileSecrets(@Value("${vigil.secrets.dir}") Path folder, ConnectorRecords records) {
    this.folder = folder;
    this.records = records;
  }

  @Override
  public boolean verify(TenantId tenantId, UUID credentialRefId, String presented) {
    required(tenantId, "tenantId");
    required(credentialRefId, "credentialRefId");
    if (presented == null || presented.isEmpty()) {
      return false;
    }
    Optional<StoredCredential> credential = records.credential(tenantId, credentialRefId);
    if (credential.isEmpty() || credential.get().purpose() != CredentialPurpose.WEBHOOK_TOKEN) {
      return false;
    }
    Optional<String> secret = readNamed(credential.get().secretName());
    return secret.isPresent() && ConstantTimeMatch.matches(secret.get(), presented);
  }

  @Override
  public String read(TenantId tenantId, UUID credentialRefId) {
    required(tenantId, "tenantId");
    required(credentialRefId, "credentialRefId");
    StoredCredential credential =
        records
            .credential(tenantId, credentialRefId)
            .orElseThrow(
                () ->
                    new VigilError(
                        VigilError.Code.NOT_FOUND,
                        "Credential reference " + credentialRefId + " not found."));
    if (credential.purpose() != CredentialPurpose.TARGET_DIAGNOSTIC
        && credential.purpose() != CredentialPurpose.TARGET_ACTION) {
      throw new IllegalArgumentException(
          "credential reference " + credentialRefId + " is not a target credential");
    }
    return available(credential.secretName());
  }

  @Override
  public String readSeedPassword(String username) {
    required(username, "username");
    String name = SEED_PASSWORD_PREFIX + username;
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("username " + username + " cannot name a secret");
    }
    return available(name);
  }

  @Override
  public boolean verifyInstallationToken(String presented) {
    if (presented == null || presented.isEmpty()) {
      return false;
    }
    Optional<String> secret = readNamed(INSTALLATION_TOKEN);
    return secret.isPresent() && ConstantTimeMatch.matches(secret.get(), presented);
  }

  /**
   * The content of the secret file {@code name}, read now as strict UTF-8; empty when the file is
   * missing, unreadable, empty or not UTF-8 (logged at WARN with the name).
   */
  Optional<String> readNamed(String name) {
    if (name == null || !NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("not a secret name: " + name);
    }
    try {
      byte[] bytes = Files.readAllBytes(folder.resolve(name));
      if (bytes.length > 0) {
        return Optional.of(
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString());
      }
    } catch (CharacterCodingException e) {
      // Not UTF-8: not available.
    } catch (IOException e) {
      // Missing or unreadable: not available.
    }
    LOG.warn("Secret {} is not available.", name);
    return Optional.empty();
  }

  private String available(String name) {
    return readNamed(name)
        .orElseThrow(
            () ->
                new VigilError(
                    VigilError.Code.DEPENDENCY_FAILED, "Secret " + name + " is not available."));
  }

  private static void required(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + ": required");
    }
  }
}
