package vigil.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchTextsTest {

  @Test
  @DisplayName("DM-R-51 the search text pattern with sorted, de-duplicated keys")
  void pattern() {
    assertThat(
            SearchTexts.of(
                List.of("disk_full", "balance_api_errors", "disk_full"),
                List.of("svc-sms-notifications", "svc-balance-check", "svc-balance-check"),
                "srv-db-01"))
        .isEqualTo(
            "alert types: balance_api_errors, disk_full;"
                + " services: svc-balance-check, svc-sms-notifications; component: srv-db-01");
    assertThat(SearchTexts.of(List.of("disk_full"), List.of(), null))
        .isEqualTo("alert types: disk_full; services: ; component: ");
  }
}
