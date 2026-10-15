package vigil.shared;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Stops an external call made inside a database transaction (AR-R-09). */
public final class TransactionGuard {

  private TransactionGuard() {}

  /** Throws when a database transaction is active on this thread. */
  public static void assertNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("external call inside a transaction");
    }
  }
}
