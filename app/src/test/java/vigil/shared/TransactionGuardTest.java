package vigil.shared;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class TransactionGuardTest {

  @AfterEach
  void reset() {
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  @Test
  @DisplayName("AR-R-09 the guard throws inside a transaction and passes outside one")
  void guard() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(TransactionGuard::assertNoTransaction)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("external call inside a transaction");

    TransactionSynchronizationManager.setActualTransactionActive(false);
    assertThatCode(TransactionGuard::assertNoTransaction).doesNotThrowAnyException();
  }
}
