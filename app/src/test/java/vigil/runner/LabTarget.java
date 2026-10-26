package vigil.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Volume;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * The lab target of P-18 spec Section 7: {@code lab-db-01} of the local image {@code
 * vigil/demo-lab:local} (built by {@code deploy/images.sh --test}, never pulled or built here) in
 * the {@code test} profile, started once per test JVM with three ed25519 key pairs generated at
 * start (BP-R-06, BP-R-14).
 */
public final class LabTarget {

  static final String IMAGE = "vigil/demo-lab:local";
  private static final int SSH_PORT = 22;
  private static final int MODE_0400 = 0400;
  private static final String SECRETS = "/run/secrets/vigil/";
  private static final String INJECT =
      "set -euo pipefail; cd /data/txlog_archive; mib=1048576;"
          + " free=$(df -B1 --output=avail /data | tail -n 1 | tr -dc '0-9'); n=0;"
          + " while [ $((free - (n + 1) * 256 * mib)) -ge $((320 * mib)) ]; do n=$((n + 1)); done;"
          + " last=$(( (free - n * 256 * mib - 64 * mib) / 4096 * 4096 ));"
          + " for i in $(seq 1 \"$n\"); do fallocate -l $((256 * mib))"
          + " \"$(printf 'txlog_%06d.arc' \"$i\")\"; done;"
          + " fallocate -l \"$last\" \"$(printf 'txlog_%06d.arc' $((n + 1)))\"; now=$(date +%s);"
          + " for i in $(seq 1 $((n + 1))); do touch -m -d"
          + " \"@$((now - 47 * 3600 + (i - 1) * 21 * 3600 / (n > 0 ? n : 1)))\""
          + " \"$(printf 'txlog_%06d.arc' \"$i\")\"; done;"
          + " echo \"$((n + 1)) $((n * 256 * mib + last))\"";

  private static LabTarget shared;

  private final GenericContainer<?> container;
  private final String fingerprint;
  private final Map<String, String> secrets = new LinkedHashMap<>();

  /** An injection like SL-F-1: the number of files and their bytes. */
  public record Injection(int files, long bytes) {}

  private LabTarget() {
    DockerClient docker = DockerClientFactory.instance().client();
    try {
      docker.inspectImageCmd(IMAGE).exec();
    } catch (NotFoundException e) {
      throw new IllegalStateException(IMAGE + " is missing: run deploy/images.sh --test", e);
    }
    KeyPair host = RunnerFixture.newKeyPair();
    KeyPair diag = RunnerFixture.newKeyPair();
    KeyPair action = RunnerFixture.newKeyPair();
    String hostLine = RunnerFixture.publicKeyLine(host);
    fingerprint = fingerprintOf(hostLine);
    secrets.put("lab-db-01-diag", RunnerFixture.secret("vigil-diag", diag));
    secrets.put("lab-db-01-action", RunnerFixture.secret("vigil-action", action));
    container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withEnv("LAB_ROLE", "lab-db-01")
            .withEnv("LAB_PROFILE", "test")
            .withPrivilegedMode(true)
            .withCreateContainerCmdModifier(cmd -> cmd.withVolumes(new Volume("/var/lib/lab-data")))
            .withExposedPorts(SSH_PORT)
            .withCopyToContainer(
                file(RunnerFixture.privateKeyText(host)), SECRETS + "lab-db-01-host-key")
            .withCopyToContainer(
                file(RunnerFixture.publicKeyLine(diag)), SECRETS + "lab-db-01-diag-pub")
            .withCopyToContainer(
                file(RunnerFixture.publicKeyLine(action)), SECRETS + "lab-db-01-action-pub")
            .waitingFor(
                Wait.forSuccessfulCommand("test -f /run/lab/ready")
                    .withStartupTimeout(Duration.ofSeconds(120)));
    container.start();
    Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
  }

  /** The lab target of this test JVM, started at the first call. */
  public static synchronized LabTarget shared() {
    if (shared == null) {
      shared = new LabTarget();
    }
    return shared;
  }

  public String host() {
    return container.getHost();
  }

  /** The mapped port of 22. */
  public int port() {
    return container.getMappedPort(SSH_PORT);
  }

  /** The host key's fingerprint, computed from the generated public key (AR-B-07). */
  public String fingerprint() {
    return fingerprint;
  }

  /** The secrets {@code lab-db-01-diag} and {@code lab-db-01-action} (AR-S-13). */
  public Map<String, String> secrets() {
    return Map.copyOf(secrets);
  }

  /** Runs {@code bash -c} in the container as root; a non-zero exit fails the test. */
  public String exec(String bash) {
    try {
      ExecResult result = container.execInContainer("bash", "-c", bash);
      assertThat(result.getExitCode()).as("%s: %s", bash, result.getStderr()).isZero();
      return result.getStdout();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** The fingerprint {@code ssh-keygen} prints for the installed host key. */
  public String sshKeygenFingerprint() {
    return exec("ssh-keygen -l -E sha256 -f /etc/ssh/ssh_host_ed25519_key.pub | cut -d ' ' -f 2")
        .strip();
  }

  /** Deletes the archived transaction logs. */
  public void clear() {
    exec("rm -f /data/txlog_archive/txlog_*.arc");
  }

  /** {@link #clear}, then an injection like SL-F-1 that leaves 64 MiB free. */
  public Injection inject() {
    clear();
    String[] line = exec(INJECT).strip().split(" ");
    return new Injection(Integer.parseInt(line[0]), Long.parseLong(line[1]));
  }

  /** SIGTERM to PID 1, at most 10 s for the exit (so {@code /data} is unmounted), then removal. */
  private void stop() {
    try {
      DockerClient docker = DockerClientFactory.instance().client();
      String id = container.getContainerId();
      docker.killContainerCmd(id).withSignal("TERM").exec();
      docker.waitContainerCmd(id).start().awaitCompletion(10, TimeUnit.SECONDS);
    } catch (RuntimeException e) {
      // Best effort at JVM exit.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      container.stop();
    }
  }

  private static Transferable file(String content) {
    return Transferable.of(content.getBytes(StandardCharsets.UTF_8), MODE_0400);
  }

  /** {@code SHA256:} and the unpadded base64 of the SHA-256 of the key blob of the line. */
  private static String fingerprintOf(String publicKeyLine) {
    byte[] blob = Base64.getDecoder().decode(publicKeyLine.split(" ")[1]);
    try {
      return "SHA256:"
          + Base64.getEncoder()
              .withoutPadding()
              .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
