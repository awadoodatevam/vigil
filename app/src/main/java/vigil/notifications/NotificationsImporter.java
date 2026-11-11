package vigil.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.CredentialPurpose;
import vigil.connectors.api.CredentialReferenceView;
import vigil.notifications.api.Channel;
import vigil.notifications.api.DeliveryAddressView;
import vigil.notifications.api.NotificationsImport;
import vigil.notifications.api.NotificationsPart;
import vigil.notifications.api.NotificationsPart.AddressPart;
import vigil.notifications.api.RecipientKind;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 15: the delivery addresses (DM-E-77, AR-B-11), checked and refused as a
 * whole (DM-R-57; P-22 spec Section 6.11). It writes no audit event and publishes no event.
 */
@Service
class NotificationsImporter implements NotificationsImport {

  private static final String AT = "delivery_addresses[";
  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final int ADDRESS_MAX = 500;

  private final NotificationRecords records;
  private final ConnectorService connectors;
  private final Clock clock;

  NotificationsImporter(NotificationRecords records, ConnectorService connectors, Clock clock) {
    this.records = records;
    this.connectors = connectors;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId,
      NotificationsPart part,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    if (tenantId == null
        || part == null
        || part.addresses() == null
        || handles == null
        || part.addresses().stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("a required argument is missing");
    }
    records.lock(tenantId);
    List<String> problems = new ArrayList<>();
    List<DeliveryAddressView> addresses = new ArrayList<>();
    if (records.anyAddress(tenantId)) {
      problems.add("delivery addresses already exist");
    } else {
      Map<String, Integer> seen = new LinkedHashMap<>();
      for (int i = 0; i < part.addresses().size(); i++) {
        check(tenantId, part.addresses().get(i), i, handles, seen, addresses, problems);
      }
    }
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID,
          "Part notifications refused.",
          Map.of("problems", List.copyOf(problems)));
    }
    Instant now = Timestamps.now(clock);
    addresses.forEach(address -> records.insertAddress(tenantId, address, now));
    return Map.of();
  }

  /** The checks of one entry; adds the address to {@code addresses} when it has no problem. */
  private void check(
      TenantId tenantId,
      AddressPart entry,
      int i,
      Map<String, UUID> handles,
      Map<String, Integer> seen,
      List<DeliveryAddressView> addresses,
      List<String> problems) {
    String at = AT + i + "]";
    int before = problems.size();
    Optional<RecipientKind> kind = RecipientKind.fromKey(entry.subjectKind());
    if (kind.isEmpty()) {
      problems.add(at + ".subject_kind: one of team, user");
    }
    boolean key = entry.subjectKey() != null && KEY.matcher(entry.subjectKey()).matches();
    if (!key) {
      problems.add(at + ".subject_key: not a key");
    }
    Optional<Channel> channel = Channel.fromKey(entry.channel());
    if (channel.isEmpty()) {
      problems.add(at + ".channel: one of teams, email");
    }
    String address = entry.address();
    boolean addressLength =
        address != null
            && !address.isEmpty()
            && address.codePointCount(0, address.length()) <= ADDRESS_MAX;
    if (!addressLength) {
      problems.add(at + ".address: 1 to 500 characters");
    }
    UUID credential = null;
    if (channel.orElse(null) == Channel.TEAMS) {
      if (entry.credentialRefHandle() == null) {
        problems.add(at + ".credential_ref: required for teams");
      } else {
        UUID id = handles.get(entry.credentialRefHandle());
        Optional<CredentialReferenceView> reference =
            id == null ? Optional.empty() : connectors.credentialReference(tenantId, id);
        if (reference.isEmpty()) {
          problems.add(at + ".credential_ref: not a credential reference of part 3");
        } else if (reference.get().purpose() != CredentialPurpose.WEBHOOK_URL) {
          problems.add(at + ".credential_ref: purpose must be webhook_url");
        } else {
          credential = id;
        }
      }
    } else if (channel.orElse(null) == Channel.EMAIL) {
      if (entry.credentialRefHandle() != null) {
        problems.add(at + ".credential_ref: not allowed for email");
      }
      if (addressLength && !email(address)) {
        problems.add(at + ".address: not an email address");
      }
    }
    if (kind.isPresent() && key && channel.isPresent()) {
      String subject = kind.get().key() + " " + entry.subjectKey() + " " + channel.get().key();
      Integer earlier = seen.putIfAbsent(subject, i);
      if (earlier != null) {
        problems.add(at + ": the same subject and channel as " + AT + earlier + "]");
      }
    }
    if (problems.size() == before) {
      addresses.add(
          new DeliveryAddressView(
              Ids.newId(),
              kind.get(),
              entry.subjectKey(),
              channel.get(),
              address,
              credential,
              true));
    }
  }

  /** One {@code @} with text on both sides and no white space (CTX-D-071 (11)). */
  private static boolean email(String address) {
    int at = address.indexOf('@');
    return at > 0
        && at == address.lastIndexOf('@')
        && at < address.length() - 1
        && address.codePoints().noneMatch(Character::isWhitespace);
  }
}
