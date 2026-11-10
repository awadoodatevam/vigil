package vigil.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiCostTest {

  @Test
  @DisplayName("AR-G-14 cost = units × price ÷ 1,000,000, rounded half-up to 6 places")
  void cost() {
    assertThat(AiGateway.cost(8969, 472, new BigDecimal("2.00"), new BigDecimal("10.00")))
        .isEqualTo(new BigDecimal("0.022658"));
    assertThat(AiGateway.cost(116, 0, new BigDecimal("0.02"), BigDecimal.ZERO))
        .isEqualTo(new BigDecimal("0.000002"));
    assertThat(AiGateway.cost(25, 0, new BigDecimal("0.02"), BigDecimal.ZERO))
        .isEqualTo(new BigDecimal("0.000001"));
    assertThat(AiGateway.cost(24, 0, new BigDecimal("0.02"), BigDecimal.ZERO))
        .isEqualTo(new BigDecimal("0.000000"));
    assertThat(AiGateway.cost(5704, 951, BigDecimal.ZERO, BigDecimal.ZERO))
        .isEqualByComparingTo("0");
  }
}
