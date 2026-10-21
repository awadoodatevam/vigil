package vigil.servicemap.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/**
 * The service map (PM-M07). Every question is answered from the tenant's current generation, its
 * highest; a key that is not in it has no dependencies, no services and no target (DM-R-06).
 */
public interface ServiceMapService {

  Optional<GenerationView> currentGeneration(TenantId tenantId);

  /** DM-R-05: the first pair, in the given order, that equals an identity key of the kind. */
  Match match(TenantId tenantId, SourceKind sourceKind, List<IdentityKey> pairs);

  /** DM-R-07: the relation of {@code a} to {@code b} with its path. */
  Relation relation(TenantId tenantId, String a, String b);

  /** The components that depend on the component directly or indirectly, sorted. */
  List<String> dependents(TenantId tenantId, String componentKey);

  /** DM-R-19: the services with at least one of the components, by service key. */
  List<AffectedService> affectedServices(TenantId tenantId, Collection<String> componentKeys);

  Optional<ComponentView> component(TenantId tenantId, String componentKey);

  Optional<ServiceView> service(TenantId tenantId, String serviceKey);

  /** DM-E26-R1: the target server of the component. */
  Optional<String> target(TenantId tenantId, String componentKey);

  /** Every service of the current generation by service key; empty without a generation. */
  List<ServiceView> services(TenantId tenantId);

  /** Every component of the current generation by component key; empty without a generation. */
  List<ComponentView> components(TenantId tenantId);

  /**
   * PM-M07-01: reads the file and stores it as the next generation, with its audit event and {@link
   * ServiceMapImported}; refuses a file with problems with {@code INVALID} "The file was not
   * imported." and the list of problems (DM-E24-R1).
   */
  ImportResult importFile(TenantId tenantId, String fileName, byte[] content, Actor importedBy);
}
