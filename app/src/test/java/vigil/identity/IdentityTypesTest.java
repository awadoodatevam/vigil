package vigil.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.identity.api.Permission;

class IdentityTypesTest {

  @Test
  @DisplayName("DM-E06-R1 the 10 permissions in order")
  void permissions() {
    assertThat(Arrays.stream(Permission.values()).map(Permission::key))
        .containsExactly(
            "view",
            "acknowledge",
            "approve_action",
            "manage_setup",
            "manage_routing",
            "manage_ai_settings",
            "rerun_ai_investigation",
            "edit_workflow",
            "run_test_replay",
            "publish_workflow");
  }

  @Test
  @DisplayName(
      "AR-S-01 Argon2id with 19,456 KiB, 2 iterations, parallelism 1, 16-byte salt, 32-byte hash")
  void argon2Parameters() {
    String password = randomPassword();
    String hash = new Passwords().hash(password);
    assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    String[] fields = hash.split("\\$");
    assertThat(fields).hasSize(6);
    assertThat(Base64.getDecoder().decode(fields[4])).hasSize(16);
    assertThat(Base64.getDecoder().decode(fields[5])).hasSize(32);
    assertThat(new Passwords().matches(password, hash)).isTrue();
    assertThat(new Passwords().matches(password + "x", hash)).isFalse();
  }

  static String randomPassword() {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    SecureRandom random = new SecureRandom();
    StringBuilder password = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      password.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return password.toString();
  }
}
