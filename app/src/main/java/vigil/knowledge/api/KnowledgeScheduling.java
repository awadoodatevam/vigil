package vigil.knowledge.api;

/** The scheduled work of vigil-knowledge: the re-embedding task per active tenant. */
public interface KnowledgeScheduling {

  /** AR-R-19, AR-R-23, AR-R-24 (6). */
  void matchActiveTenants();
}
