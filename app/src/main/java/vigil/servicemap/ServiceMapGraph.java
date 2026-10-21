package vigil.servicemap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import vigil.servicemap.api.AffectedService;
import vigil.servicemap.api.ComponentKind;
import vigil.servicemap.api.ComponentView;
import vigil.servicemap.api.Criticality;
import vigil.servicemap.api.IdentityKey;
import vigil.servicemap.api.Match;
import vigil.servicemap.api.Relation;
import vigil.servicemap.api.RelationKind;
import vigil.servicemap.api.ServiceView;
import vigil.servicemap.api.SourceKind;

/**
 * The immutable graph of one generation: components, services, identity keys and the dependency
 * edges, listed and implied by {@code runs_on} (DM-E28-R2), and the answers of Section 6.6.
 */
final class ServiceMapGraph {

  /** An identity key of a component (DM-E-27). */
  record Identity(String componentKey, SourceKind sourceKind, IdentityKey pair) {}

  private final long generation;
  private final Map<String, ServiceView> services = new TreeMap<>();
  private final Map<String, ComponentView> components = new TreeMap<>();
  private final Map<SourceKind, Map<IdentityKey, String>> identities =
      new EnumMap<>(SourceKind.class);
  private final Map<String, SortedSet<String>> dependsOn = new HashMap<>();
  private final Map<String, SortedSet<String>> dependedOnBy = new HashMap<>();

  ServiceMapGraph(
      long generation,
      List<ServiceView> services,
      List<ComponentView> components,
      List<Identity> identities,
      List<List<String>> dependencies) {
    this.generation = generation;
    services.forEach(s -> this.services.put(s.serviceKey(), s));
    components.forEach(c -> this.components.put(c.componentKey(), c));
    identities.forEach(
        i ->
            this.identities
                .computeIfAbsent(i.sourceKind(), k -> new HashMap<>())
                .put(i.pair(), i.componentKey()));
    dependencies.forEach(d -> edge(d.get(0), d.get(1)));
    components.stream()
        .filter(c -> c.runsOnKey() != null)
        .forEach(c -> edge(c.componentKey(), c.runsOnKey()));
  }

  /** The graph of a file that read with no problem. */
  static ServiceMapGraph of(long generation, ServiceMapFile.Content file) {
    return new ServiceMapGraph(
        generation,
        file.services().stream()
            .map(
                s ->
                    new ServiceView(
                        s.serviceKey(),
                        s.name(),
                        Criticality.fromKey(s.criticality()).orElse(null),
                        s.ownerTeamKey(),
                        new TreeSet<>(s.componentKeys()).stream().toList()))
            .toList(),
        file.components().stream()
            .map(
                c ->
                    new ComponentView(
                        c.componentKey(),
                        c.name(),
                        ComponentKind.fromKey(c.kind()).orElseThrow(),
                        Criticality.fromKey(c.criticality()).orElse(null),
                        c.ownerTeamKey(),
                        c.runsOnKey(),
                        c.targetKey()))
            .toList(),
        file.identities().stream()
            .map(
                i ->
                    new Identity(
                        i.componentKey(),
                        SourceKind.fromKey(i.sourceKind()).orElseThrow(),
                        new IdentityKey(i.name(), i.value())))
            .toList(),
        file.dependencies().stream().map(d -> List.of(d.from(), d.to())).toList());
  }

  private void edge(String from, String to) {
    dependsOn.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
    dependedOnBy.computeIfAbsent(to, k -> new TreeSet<>()).add(from);
  }

  long generation() {
    return generation;
  }

  /** DM-R-05, DM-R-04: the first pair in the given order that is an identity key of the kind. */
  Match match(SourceKind sourceKind, List<IdentityKey> pairs) {
    Map<IdentityKey, String> keys = identities.getOrDefault(sourceKind, Map.of());
    for (IdentityKey pair : pairs) {
      if (pair != null && keys.containsKey(pair)) {
        return new Match(generation, keys.get(pair));
      }
    }
    return new Match(generation, null);
  }

  /** DM-R-07 with the shortest path, first by key order among equally short ones. */
  Relation relation(String a, String b) {
    if (a.equals(b)) {
      return new Relation(RelationKind.SAME_COMPONENT, List.of(a));
    }
    if (components.containsKey(a) && components.containsKey(b)) {
      List<String> path = shortestPath(a, b, dependsOn, dependedOnBy);
      if (!path.isEmpty()) {
        return new Relation(RelationKind.DEPENDS_ON, path);
      }
      path = shortestPath(a, b, dependedOnBy, dependsOn);
      if (!path.isEmpty()) {
        return new Relation(RelationKind.DEPENDED_ON_BY, path);
      }
    }
    return new Relation(RelationKind.NONE, List.of());
  }

  /** The components that depend on {@code componentKey} directly or indirectly, sorted. */
  List<String> dependents(String componentKey) {
    if (!components.containsKey(componentKey)) {
      return List.of();
    }
    Set<String> found = reachable(componentKey, dependedOnBy).keySet();
    found.remove(componentKey);
    return new TreeSet<>(found).stream().toList();
  }

  /** DM-R-19: services with at least one of the components, each with those it has. */
  List<AffectedService> affectedServices(Collection<String> componentKeys) {
    Set<String> keys = new HashSet<>();
    componentKeys.stream().filter(Objects::nonNull).forEach(keys::add);
    List<AffectedService> affected = new ArrayList<>();
    for (ServiceView service : services.values()) {
      List<String> via = service.componentKeys().stream().filter(keys::contains).toList();
      if (!via.isEmpty()) {
        affected.add(new AffectedService(service.serviceKey(), service.name(), via));
      }
    }
    return affected;
  }

  Optional<ComponentView> component(String componentKey) {
    return Optional.ofNullable(components.get(componentKey));
  }

  Optional<ServiceView> service(String serviceKey) {
    return Optional.ofNullable(services.get(serviceKey));
  }

  /** DM-E26-R1: a server's own target, otherwise the target of the server it runs on. */
  Optional<String> target(String componentKey) {
    ComponentView component = components.get(componentKey);
    if (component == null) {
      return Optional.empty();
    }
    if (component.kind() == ComponentKind.SERVER) {
      return Optional.ofNullable(component.targetKey());
    }
    return Optional.ofNullable(component.runsOnKey())
        .map(components::get)
        .map(ComponentView::targetKey);
  }

  List<ServiceView> services() {
    return List.copyOf(services.values());
  }

  List<ComponentView> components() {
    return List.copyOf(components.values());
  }

  /**
   * The shortest path from {@code from} to {@code to} along {@code forward} edges ({@code backward}
   * holds the same edges reversed), both included; of equally short paths, the one first when
   * compared key by key. With {@code from} equal to {@code to}: the shortest cycle. Empty when
   * there is none.
   */
  static List<String> shortestPath(
      String from,
      String to,
      Map<String, SortedSet<String>> forward,
      Map<String, SortedSet<String>> backward) {
    Map<String, Integer> distance = reachable(to, backward);
    int remaining;
    if (from.equals(to)) {
      remaining =
          forward.getOrDefault(from, new TreeSet<>()).stream()
              .filter(distance::containsKey)
              .mapToInt(next -> distance.get(next) + 1)
              .min()
              .orElse(0);
    } else {
      remaining = distance.getOrDefault(from, 0);
    }
    if (remaining == 0) {
      return List.of();
    }
    List<String> path = new ArrayList<>(List.of(from));
    String current = from;
    while (remaining > 0) {
      int wanted = --remaining;
      current =
          forward.get(current).stream()
              .filter(next -> distance.containsKey(next) && distance.get(next) == wanted)
              .findFirst()
              .orElseThrow();
      path.add(current);
    }
    return path;
  }

  /** Every key reachable from {@code start} along {@code edges}, with its distance (BFS). */
  private static Map<String, Integer> reachable(
      String start, Map<String, SortedSet<String>> edges) {
    Map<String, Integer> distance = new HashMap<>();
    distance.put(start, 0);
    Deque<String> queue = new ArrayDeque<>(List.of(start));
    while (!queue.isEmpty()) {
      String current = queue.poll();
      for (String next : edges.getOrDefault(current, new TreeSet<>())) {
        if (distance.putIfAbsent(next, distance.get(current) + 1) == null) {
          queue.add(next);
        }
      }
    }
    return distance;
  }

  /**
   * The strongly connected groups of two or more keys, in order of their smallest key (Kosaraju,
   * without recursion).
   */
  static List<SortedSet<String>> stronglyConnected(
      Map<String, SortedSet<String>> forward, Map<String, SortedSet<String>> backward) {
    Set<String> keys = new TreeSet<>(forward.keySet());
    keys.addAll(backward.keySet());
    List<String> finished = new ArrayList<>();
    Set<String> visited = new HashSet<>();
    for (String start : keys) {
      if (!visited.add(start)) {
        continue;
      }
      Deque<Map.Entry<String, Iterator<String>>> stack = new ArrayDeque<>();
      stack.push(Map.entry(start, forward.getOrDefault(start, new TreeSet<>()).iterator()));
      while (!stack.isEmpty()) {
        Map.Entry<String, Iterator<String>> top = stack.peek();
        if (top.getValue().hasNext()) {
          String next = top.getValue().next();
          if (visited.add(next)) {
            stack.push(Map.entry(next, forward.getOrDefault(next, new TreeSet<>()).iterator()));
          }
        } else {
          finished.add(stack.pop().getKey());
        }
      }
    }
    Set<String> assigned = new HashSet<>();
    List<SortedSet<String>> groups = new ArrayList<>();
    for (int i = finished.size() - 1; i >= 0; i--) {
      String start = finished.get(i);
      if (!assigned.add(start)) {
        continue;
      }
      SortedSet<String> group = new TreeSet<>(List.of(start));
      Deque<String> stack = new ArrayDeque<>(List.of(start));
      while (!stack.isEmpty()) {
        for (String next : backward.getOrDefault(stack.pop(), new TreeSet<>())) {
          if (assigned.add(next)) {
            group.add(next);
            stack.push(next);
          }
        }
      }
      if (group.size() >= 2) {
        groups.add(group);
      }
    }
    groups.sort((x, y) -> x.first().compareTo(y.first()));
    return groups;
  }
}
