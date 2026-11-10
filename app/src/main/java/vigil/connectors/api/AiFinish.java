package vigil.connectors.api;

/** How the provider ended a 2xx completion answer (AR-G-13). */
public enum AiFinish {
  COMPLETE,
  TRUNCATED,
  REFUSED
}
