package vigil.servicemap.api;

import java.util.List;

/**
 * The relation of two components and its path from the first to the second, both included (DM-R-07,
 * DM-E-29 {@code relation_path}); the path is empty for {@code NONE}.
 */
public record Relation(RelationKind kind, List<String> path) {

  public Relation {
    path = List.copyOf(path);
  }
}
