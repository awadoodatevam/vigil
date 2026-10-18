package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.shared.TenantId;
import vigil.testsupport.TestSecrets;

class ConstantTimeMatchTest {

  @Test
  @DisplayName("AR-S-05 secrets are compared by their SHA-256 digests in constant time")
  void constantTime() {
    String token = TestSecrets.randomToken();
    assertThat(ConstantTimeMatch.matches(token, new String(token))).isTrue();
    char first = token.charAt(0) == 'A' ? 'B' : 'A';
    char last = token.charAt(42) == 'A' ? 'B' : 'A';
    assertThat(ConstantTimeMatch.matches(token, first + token.substring(1))).isFalse();
    assertThat(ConstantTimeMatch.matches(token, token.substring(0, 42) + last)).isFalse();
    assertThat(ConstantTimeMatch.matches(token, token + "A")).isFalse();
    assertThat(ConstantTimeMatch.matches(token, "")).isFalse();
    assertThat(ConstantTimeMatch.matches(null, token)).isFalse();
    assertThat(ConstantTimeMatch.matches(token, null)).isFalse();

    JavaClasses production =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("vigil.connectors");
    JavaClass match = production.get(ConstantTimeMatch.class);
    JavaClass secrets = production.get(FileSecrets.class);
    assertThat(match.getMethod("matches", String.class, String.class).getMethodCallsFromSelf())
        .anyMatch(call -> calls(call, MessageDigest.class, "isEqual", byte[].class, byte[].class));
    assertThat(
            secrets
                .getMethod("verify", TenantId.class, UUID.class, String.class)
                .getMethodCallsFromSelf())
        .anyMatch(
            call -> calls(call, ConstantTimeMatch.class, "matches", String.class, String.class));
    assertThat(secrets.getMethod("verifyInstallationToken", String.class).getMethodCallsFromSelf())
        .anyMatch(
            call -> calls(call, ConstantTimeMatch.class, "matches", String.class, String.class));
    for (JavaClass type : Set.of(match, secrets)) {
      assertThat(type.getMethodCallsFromSelf())
          .as(type.getSimpleName())
          .noneMatch(
              call ->
                  (call.getTargetOwner().isEquivalentTo(String.class)
                          && Set.of("equals", "contentEquals").contains(call.getName()))
                      || (call.getTargetOwner().isEquivalentTo(Arrays.class)
                          && call.getName().equals("equals")));
    }
  }

  private static boolean calls(
      JavaCall<?> call, Class<?> owner, String name, Class<?>... parameters) {
    return call.getTargetOwner().isEquivalentTo(owner)
        && call.getName().equals(name)
        && call.getTarget().getParameterTypes().stream()
            .map(type -> type.toErasure().getName())
            .toList()
            .equals(Arrays.stream(parameters).map(Class::getName).toList());
  }
}
