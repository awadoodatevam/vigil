package vigil.runner;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.PasswordIdentityProvider;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.loader.openssh.OpenSSHKeyPairResourceParser;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.Secrets;
import vigil.connectors.api.TargetConnector;
import vigil.connectors.api.TargetResult;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;

/**
 * The {@code linux_server} target connector (AR-K-03, AR-K-11 to AR-K-13): one SSH connection per
 * run with the grant's user and key, only the pinned host key, the command on one exec channel. The
 * operation's timeout bounds the whole call from the start of the connection attempt (AR-TM-01).
 */
@Component
class LinuxServerConnector implements TargetConnector {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final int OUTPUT_MAX = 65_536;
  private static final int ERROR_MAX = 1_000;
  private static final int MAX_PORT = 65_535;
  private static final AttributeKey<HostKeyCheck> HOST_KEY = new AttributeKey<>();
  private static final String NO_EXIT_STATUS = "the command ended without an exit status";

  private final Secrets secrets;
  private SshClient client;

  LinuxServerConnector(Secrets secrets) {
    this.secrets = secrets;
  }

  @Override
  public ConnectorKind kind() {
    return ConnectorKind.LINUX_SERVER;
  }

  @Override
  public TargetResult run(
      TenantId tenantId,
      ConnectorInstanceView instance,
      UUID credentialRefId,
      String command,
      Duration timeout) {
    TransactionGuard.assertNoTransaction();
    Runner.require(tenantId, instance, credentialRefId, command, timeout);
    if (instance.kind() != ConnectorKind.LINUX_SERVER) {
      throw new IllegalArgumentException(
          "connector instance " + instance.name() + " is not a linux_server instance");
    }
    JsonNode settings = instance.settings();
    JsonNode port = settings == null ? null : settings.get("port");
    JsonNode hostKey = settings == null ? null : settings.get("host_key_sha256");
    if (port == null
        || !port.isIntegralNumber()
        || !port.canConvertToInt()
        || port.intValue() < 1
        || port.intValue() > MAX_PORT
        || hostKey == null
        || !hostKey.isString()) {
      return new TargetResult.NotStarted(
          "connector instance " + instance.name() + " has no valid port or host_key_sha256");
    }
    Target target = new Target(instance.address(), port.intValue(), hostKey.stringValue());
    String secret;
    try {
      secret = secrets.read(tenantId, credentialRefId);
    } catch (RuntimeException e) {
      return new TargetResult.NotStarted(e.getMessage());
    }
    JsonNode credential = Parameters.readOrNull(secret);
    if (credential == null
        || !credential.isObject()
        || !credential.path("username").isString()
        || !credential.path("private_key").isString()) {
      return new TargetResult.NotStarted(
          "the credential for " + target + " is not a username and private key");
    }
    KeyPair key = privateKey(credential.get("private_key").stringValue());
    if (key == null) {
      return new TargetResult.NotStarted("the private key for " + target + " could not be read");
    }
    return connectAndRun(
        new Deadline(System.nanoTime(), timeout),
        target,
        credential.get("username").stringValue(),
        key,
        command);
  }

  private TargetResult connectAndRun(
      Deadline deadline, Target target, String username, KeyPair key, String command) {
    long connectEnd = Math.min(System.nanoTime() + CONNECT_TIMEOUT.toNanos(), deadline.end);
    HostKeyCheck check = new HostKeyCheck(target.hostKey());
    ClientSession session = null;
    try {
      InetAddress address = resolve(target.address(), connectEnd);
      if (address == null) {
        return notConnected(deadline, target);
      }
      SocketAddress socket =
          new InetSocketAddress(InetAddress.getByAddress(address.getAddress()), target.port());
      ConnectFuture connect =
          client()
              .connect(username, socket, AttributeRepository.ofKeyValuePair(HOST_KEY, check), null);
      if (!connect.await(millisUntil(connectEnd)) || !connect.isConnected()) {
        connect.cancel();
        return notConnected(deadline, target);
      }
      session = connect.getClientSession();
      session.addPublicKeyIdentity(key);
      Set<ClientSessionEvent> state =
          session.waitFor(
              EnumSet.of(ClientSessionEvent.WAIT_AUTH, ClientSessionEvent.CLOSED),
              millisUntil(connectEnd));
      if (check.received != null) {
        return new TargetResult.NotStarted(
            "host key " + check.received + " of " + target + " does not match host_key_sha256");
      }
      if (!state.contains(ClientSessionEvent.WAIT_AUTH)
          || state.contains(ClientSessionEvent.CLOSED)) {
        return notConnected(deadline, target);
      }
      AuthFuture auth = session.auth();
      if (!auth.await(millisUntil(deadline.end))) {
        return new TargetResult.NotStarted(deadline.noResult());
      }
      if (!auth.isSuccess()) {
        return new TargetResult.NotStarted(
            "authentication as " + username + " on " + target + " failed");
      }
      if (deadline.passed()) {
        return new TargetResult.NotStarted(deadline.noResult());
      }
      return execute(session, command, deadline);
    } catch (IOException | RuntimeException e) {
      return check.received != null
          ? new TargetResult.NotStarted(
              "host key " + check.received + " of " + target + " does not match host_key_sha256")
          : notConnected(deadline, target);
    } finally {
      if (session != null) {
        session.close(true);
      }
    }
  }

  /** Step (6): the command on one exec channel; from opening the channel on, it may have run. */
  private static TargetResult execute(ClientSession session, String command, Deadline deadline) {
    Collector output = new Collector(OUTPUT_MAX);
    Collector errors = new Collector(ERROR_MAX);
    try (ChannelExec channel = session.createExecChannel(command)) {
      channel.setOut(output);
      channel.setErr(errors);
      try {
        channel.open();
        Set<ClientChannelEvent> events =
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), millisUntil(deadline.end));
        if (!events.contains(ClientChannelEvent.CLOSED)) {
          channel.close(true);
          session.close(true);
          return new TargetResult.Interrupted(
              deadline.noResult(), output.text(), output.truncated());
        }
      } catch (IOException | RuntimeException e) {
        channel.close(true);
        return new TargetResult.Interrupted(NO_EXIT_STATUS, output.text(), output.truncated());
      }
      Integer exitStatus = channel.getExitStatus();
      if (exitStatus == null) {
        return new TargetResult.Interrupted(NO_EXIT_STATUS, output.text(), output.truncated());
      }
      return new TargetResult.Finished(
          exitStatus, output.text(), output.truncated(), errors.text());
    } catch (IOException e) {
      return new TargetResult.Interrupted(NO_EXIT_STATUS, output.text(), output.truncated());
    }
  }

  /** The address of the host within the connection's time; {@code null} when not found. */
  private static InetAddress resolve(String host, long end) {
    FutureTask<InetAddress> lookup = new FutureTask<>(() -> InetAddress.getByName(host));
    Thread.ofVirtual().start(lookup);
    try {
      return lookup.get(millisUntil(end), TimeUnit.MILLISECONDS);
    } catch (ExecutionException | TimeoutException e) {
      lookup.cancel(true);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      lookup.cancel(true);
      return null;
    }
  }

  private static TargetResult notConnected(Deadline deadline, Target target) {
    return new TargetResult.NotStarted(
        deadline.passed() ? deadline.noResult() : "could not connect to " + target);
  }

  /** The OpenSSH private key without passphrase; {@code null} when it cannot be read. */
  private static KeyPair privateKey(String text) {
    try {
      Collection<KeyPair> pairs =
          OpenSSHKeyPairResourceParser.INSTANCE.loadKeyPairs(
              null, NamedResource.ofName("credential"), FilePasswordProvider.EMPTY, text);
      return pairs == null || pairs.isEmpty() ? null : pairs.iterator().next();
    } catch (IOException | GeneralSecurityException | RuntimeException e) {
      return null;
    }
  }

  /** The fingerprint in the form {@code ssh-keygen -l -E sha256} prints (AR-B-07). */
  static String fingerprint(PublicKey key) {
    ByteArrayBuffer buffer = new ByteArrayBuffer();
    buffer.putRawPublicKey(key);
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(Arrays.copyOfRange(buffer.array(), 0, buffer.wpos()));
      return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Accepts only the pinned host key of the session's connection (AR-K-11). */
  private static boolean verifyHostKey(
      ClientSession session, SocketAddress remote, PublicKey serverKey) {
    AttributeRepository context = session.getConnectionContext();
    HostKeyCheck check = context == null ? null : context.getAttribute(HOST_KEY);
    if (check == null) {
      return false;
    }
    String received = fingerprint(serverKey);
    if (received.equals(check.expected)) {
      return true;
    }
    check.received = received;
    return false;
  }

  private synchronized SshClient client() {
    if (client == null) {
      SshClient created = SshClient.setUpDefaultClient();
      created.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
      created.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
      created.setPasswordIdentityProvider(PasswordIdentityProvider.EMPTY_PASSWORDS_PROVIDER);
      created.setUserAuthFactories(List.of(UserAuthPublicKeyFactory.INSTANCE));
      created.setAgentFactory(null);
      created.setServerKeyVerifier(LinuxServerConnector::verifyHostKey);
      created.start();
      client = created;
    }
    return client;
  }

  @PreDestroy
  synchronized void stop() {
    if (client != null) {
      client.stop();
      client = null;
    }
  }

  private static long millisUntil(long end) {
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime()));
  }

  /** The address, port and pinned host key of an instance. */
  private record Target(String address, int port, String hostKey) {

    @Override
    public String toString() {
      return address + ":" + port;
    }
  }

  /** The end of the whole call. */
  private static final class Deadline {

    private final long end;
    private final long seconds;

    Deadline(long start, Duration timeout) {
      this.end = start + timeout.toNanos();
      this.seconds = timeout.toSeconds();
    }

    boolean passed() {
      return System.nanoTime() - end >= 0;
    }

    String noResult() {
      return "no result within " + seconds + " s";
    }
  }

  /** The expected host key fingerprint of one connection and the one received when different. */
  private static final class HostKeyCheck {

    private final String expected;
    private volatile String received;

    HostKeyCheck(String expected) {
      this.expected = expected;
    }
  }

  /**
   * Collects a stream's bytes up to what {@code max} code points can take; decodes them as UTF-8
   * with malformed input and U+0000 replaced by U+FFFD, and keeps the first {@code max} code
   * points.
   */
  private static final class Collector extends OutputStream {

    private final int max;
    private final byte[] bytes;
    private int length;
    private boolean overflow;

    Collector(int max) {
      this.max = max;
      this.bytes = new byte[max * 4 + 4];
    }

    @Override
    public synchronized void write(int b) {
      write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] data, int offset, int count) {
      int kept = Math.min(count, bytes.length - length);
      System.arraycopy(data, offset, bytes, length, kept);
      length += kept;
      overflow |= kept < count;
    }

    synchronized String text() {
      String decoded = decoded();
      int count = decoded.codePointCount(0, decoded.length());
      return count <= max ? decoded : decoded.substring(0, decoded.offsetByCodePoints(0, max));
    }

    synchronized boolean truncated() {
      String decoded = decoded();
      return overflow || decoded.codePointCount(0, decoded.length()) > max;
    }

    private String decoded() {
      return new String(bytes, 0, length, StandardCharsets.UTF_8).replace('\u0000', '�');
    }
  }
}
