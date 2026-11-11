package vigil.setup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.identity.api.IdentityService;
import vigil.notifications.api.DeliveryAddressView;
import vigil.notifications.api.NotificationsService;
import vigil.notifications.api.RecipientKind;
import vigil.shared.Key;
import vigil.shared.TenantId;

/**
 * DM-R-60 "team keys in notification addresses; usernames in user delivery addresses": the subjects
 * of part 15's addresses exist in part 2.
 */
@Component
class NotificationsTeamCheck implements SeedKeyCheck {

  private final NotificationsService notifications;
  private final IdentityService identity;

  NotificationsTeamCheck(NotificationsService notifications, IdentityService identity) {
    this.notifications = notifications;
    this.identity = identity;
  }

  @Override
  public int referringPart() {
    return 15;
  }

  @Override
  public int targetPart() {
    return 2;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    List<String> problems = new ArrayList<>();
    for (DeliveryAddressView address : notifications.deliveryAddresses(tenantId)) {
      Key key = new Key(address.subjectKey());
      if (address.subjectKind() == RecipientKind.TEAM) {
        if (identity.team(tenantId, key).isEmpty()) {
          problems.add(
              "delivery address "
                  + address.channel().key()
                  + ": team '"
                  + address.subjectKey()
                  + "' does not exist");
        }
      } else if (identity.userByUsername(tenantId, key).isEmpty()) {
        problems.add(
            "delivery address "
                + address.channel().key()
                + ": user '"
                + address.subjectKey()
                + "' does not exist");
      }
    }
    return problems;
  }
}
