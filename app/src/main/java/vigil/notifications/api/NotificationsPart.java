package vigil.notifications.api;

import java.util.List;

/** Seed part 15 (DM-R-56 (15)): the delivery addresses as read from the bundle. */
public record NotificationsPart(List<AddressPart> addresses) {

  /**
   * One delivery address; values as written, {@code credentialRefHandle} {@code null} if absent.
   */
  public record AddressPart(
      String subjectKind,
      String subjectKey,
      String channel,
      String address,
      String credentialRefHandle) {}
}
