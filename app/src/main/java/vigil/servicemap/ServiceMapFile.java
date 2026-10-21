package vigil.servicemap;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import vigil.servicemap.api.ComponentKind;
import vigil.servicemap.api.Counts;
import vigil.servicemap.api.Criticality;
import vigil.servicemap.api.SourceKind;

/**
 * The service map file (AR-B-06 with BP-A-24): stage 1 reads it, stopping at its first problem;
 * stage 2 checks the rules of DM-R-55 and collects every problem (texts of DC-TX-11).
 */
final class ServiceMapFile {

  static final int MAX_BYTES = 1_048_576;
  static final List<String> SECTIONS =
      List.of("services", "components", "identity_keys", "dependencies");
  private static final Map<String, List<String>> FIELDS =
      Map.of(
          "services",
          List.of("service_key", "name", "criticality", "owner_team_key", "component_keys"),
          "components",
          List.of(
              "component_key",
              "name",
              "kind",
              "criticality",
              "owner_team_key",
              "runs_on_key",
              "target_key"),
          "identity_keys",
          List.of("component_key", "source_kind", "name", "value"),
          "dependencies",
          List.of("from_component_key", "to_component_key"));
  private static final Set<String> OPTIONAL =
      Set.of("criticality", "owner_team_key", "runs_on_key", "target_key");
  private static final Set<String> EMPTY = Set.of("null", "Null", "NULL", "~", "");
  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final String ROOT =
      "the file must be a mapping of the sections services, components, identity_keys and"
          + " dependencies (line 1)";
  private static final Comparator<Node> DOCUMENT_ORDER =
      Comparator.comparingInt((Node node) -> mark(node).getLine())
          .thenComparingInt(node -> mark(node).getColumn());

  private ServiceMapFile() {}

  record Service(
      String serviceKey,
      String name,
      String criticality,
      String ownerTeamKey,
      List<String> componentKeys) {}

  record Component(
      String componentKey,
      String name,
      String kind,
      String criticality,
      String ownerTeamKey,
      String runsOnKey,
      String targetKey) {}

  record Identity(String componentKey, String sourceKind, String name, String value) {}

  record Dependency(String from, String to) {}

  /** The entries of a read file, values as written ({@code null} for an empty optional field). */
  record Content(
      List<Service> services,
      List<Component> components,
      List<Identity> identities,
      List<Dependency> dependencies) {

    Counts counts() {
      return new Counts(services.size(), components.size(), identities.size(), dependencies.size());
    }
  }

  /** The content of a file with no problem, or the problems in order. */
  record Read(Content content, List<String> problems) {}

  /** Reads {@code content}; no database. */
  static Read read(byte[] content) {
    if (content == null) {
      throw new IllegalArgumentException("content: required");
    }
    Content file;
    try {
      file = parse(content);
    } catch (Unreadable e) {
      return new Read(null, List.of("The file could not be read: " + e.getMessage() + "."));
    }
    List<String> problems = rules(file);
    return new Read(problems.isEmpty() ? file : null, problems);
  }

  /** A stage 1 problem: its parser message. */
  private static final class Unreadable extends RuntimeException {

    Unreadable(String message) {
      super(message, null, false, false);
    }
  }

  // Stage 1 — reading (BP-A-24).

  private static Content parse(byte[] bytes) {
    if (bytes.length > MAX_BYTES) {
      throw new Unreadable("the file is larger than 1 MiB");
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new Unreadable("the file is not UTF-8");
    }
    LoadSettings settings = LoadSettings.builder().build();
    List<Event> events = new ArrayList<>();
    List<Node> documents = new ArrayList<>();
    try {
      new Parse(settings).parseString(text).forEach(events::add);
      for (Event event : events) {
        if (event instanceof AliasEvent
            || (event instanceof NodeEvent node && node.getAnchor().isPresent())
            || (event instanceof ScalarEvent scalar && scalar.getTag().isPresent())
            || (event instanceof CollectionStartEvent start && start.getTag().isPresent())) {
          throw new Unreadable(
              "anchors, aliases and custom tags are not allowed (line "
                  + (event.getStartMark().orElseThrow().getLine() + 1)
                  + ")");
        }
      }
      new Compose(LoadSettings.builder().setAllowNonScalarKeys(true).build())
          .composeAllFromString(text)
          .forEach(documents::add);
    } catch (YamlEngineException e) {
      throw new Unreadable(parserMessage(e));
    }
    if (documents.size() != 1 || !(documents.getFirst() instanceof MappingNode root)) {
      throw new Unreadable(ROOT);
    }
    checkKeys(root);
    Map<String, SequenceNode> sections = sections(root);
    return content(sections);
  }

  private static String parserMessage(YamlEngineException e) {
    if (e instanceof MarkedYamlEngineException marked
        && marked.getProblem() != null
        && marked.getProblemMark().isPresent()) {
      Mark mark = marked.getProblemMark().get();
      return marked.getProblem()
          + " (line "
          + (mark.getLine() + 1)
          + ", column "
          + (mark.getColumn() + 1)
          + ")";
    }
    String message = String.valueOf(e.getMessage());
    int end = message.indexOf('\n');
    return end < 0 ? message : message.substring(0, end);
  }

  /** Item 6: every mapping key is a plain scalar; no key twice in one mapping. */
  private static void checkKeys(MappingNode root) {
    List<MappingNode> mappings = new ArrayList<>();
    collectMappings(root, mappings);
    first(
            mappings.stream()
                .flatMap(m -> m.getValue().stream())
                .map(NodeTuple::getKeyNode)
                .filter(key -> !(key instanceof ScalarNode scalar && scalar.isPlain())))
        .ifPresent(
            key -> {
              throw new Unreadable("a key must be a single word (line " + line(key) + ")");
            });
    List<Node> repeated = new ArrayList<>();
    for (MappingNode mapping : mappings) {
      Set<String> seen = new HashSet<>();
      for (NodeTuple tuple : mapping.getValue()) {
        if (!seen.add(word(tuple.getKeyNode()))) {
          repeated.add(tuple.getKeyNode());
        }
      }
    }
    first(repeated.stream())
        .ifPresent(
            key -> {
              throw new Unreadable("duplicate key '" + word(key) + "' (line " + line(key) + ")");
            });
  }

  private static void collectMappings(Node node, List<MappingNode> mappings) {
    if (node instanceof MappingNode mapping) {
      mappings.add(mapping);
      for (NodeTuple tuple : mapping.getValue()) {
        collectMappings(tuple.getKeyNode(), mappings);
        collectMappings(tuple.getValueNode(), mappings);
      }
    } else if (node instanceof SequenceNode sequence) {
      sequence.getValue().forEach(item -> collectMappings(item, mappings));
    }
  }

  /** Items 7 and 8: the four sections, each a list of mappings. */
  private static Map<String, SequenceNode> sections(MappingNode root) {
    Map<String, Node> values = new HashMap<>();
    for (NodeTuple tuple : root.getValue()) {
      String name = word(tuple.getKeyNode());
      if (!SECTIONS.contains(name)) {
        throw new Unreadable(
            "unknown section '" + name + "' (line " + line(tuple.getKeyNode()) + ")");
      }
      values.put(name, tuple.getValueNode());
    }
    for (String name : SECTIONS) {
      if (!values.containsKey(name)) {
        throw new Unreadable("missing section '" + name + "'");
      }
    }
    first(values.values().stream().filter(value -> !(value instanceof SequenceNode)))
        .ifPresent(
            value -> {
              String name = sectionOf(values, value);
              throw new Unreadable(
                  "section '" + name + "' must be a list (line " + line(value) + ")");
            });
    Map<String, SequenceNode> sections = new LinkedHashMap<>();
    SECTIONS.forEach(name -> sections.put(name, (SequenceNode) values.get(name)));
    first(
            sections.values().stream()
                .flatMap(section -> section.getValue().stream())
                .filter(entry -> !(entry instanceof MappingNode)))
        .ifPresent(
            entry -> {
              throw new Unreadable(
                  sectionOf(sections, entry)
                      + ": each entry must be a mapping (line "
                      + line(entry)
                      + ")");
            });
    return sections;
  }

  private static String sectionOf(Map<String, ? extends Node> sections, Node node) {
    for (Map.Entry<String, ? extends Node> section : sections.entrySet()) {
      if (section.getValue() == node
          || (section.getValue() instanceof SequenceNode list
              && list.getValue().stream().anyMatch(entry -> entry == node))) {
        return section.getKey();
      }
    }
    throw new IllegalStateException("node of no section");
  }

  /** Item 9 and the values: the entries in document order, each with its fields. */
  private static Content content(Map<String, SequenceNode> sections) {
    Map<Node, String> sectionOfEntry = new LinkedHashMap<>();
    sections.forEach(
        (name, section) -> section.getValue().forEach(entry -> sectionOfEntry.put(entry, name)));
    sectionOfEntry.keySet().stream()
        .sorted(DOCUMENT_ORDER)
        .forEach(entry -> checkEntry(sectionOfEntry.get(entry), (MappingNode) entry));
    List<Service> services = new ArrayList<>();
    for (Node node : sections.get("services").getValue()) {
      Map<String, Node> f = fields(node);
      List<String> keys = new ArrayList<>();
      ((SequenceNode) f.get("component_keys")).getValue().forEach(k -> keys.add(required(k)));
      services.add(
          new Service(
              required(f.get("service_key")),
              required(f.get("name")),
              optional(f.get("criticality")),
              optional(f.get("owner_team_key")),
              List.copyOf(new LinkedHashSet<>(keys))));
    }
    List<Component> components = new ArrayList<>();
    for (Node node : sections.get("components").getValue()) {
      Map<String, Node> f = fields(node);
      components.add(
          new Component(
              required(f.get("component_key")),
              required(f.get("name")),
              required(f.get("kind")),
              optional(f.get("criticality")),
              optional(f.get("owner_team_key")),
              optional(f.get("runs_on_key")),
              optional(f.get("target_key"))));
    }
    List<Identity> identities = new ArrayList<>();
    for (Node node : sections.get("identity_keys").getValue()) {
      Map<String, Node> f = fields(node);
      identities.add(
          new Identity(
              required(f.get("component_key")),
              required(f.get("source_kind")),
              required(f.get("name")),
              required(f.get("value"))));
    }
    List<Dependency> dependencies = new ArrayList<>();
    for (Node node : sections.get("dependencies").getValue()) {
      Map<String, Node> f = fields(node);
      dependencies.add(
          new Dependency(
              required(f.get("from_component_key")), required(f.get("to_component_key"))));
    }
    return new Content(services, components, identities, dependencies);
  }

  private static void checkEntry(String section, MappingNode entry) {
    List<String> fields = FIELDS.get(section);
    for (NodeTuple tuple : entry.getValue()) {
      String field = word(tuple.getKeyNode());
      Node value = tuple.getValueNode();
      if (!fields.contains(field)) {
        throw new Unreadable(
            section + ": unknown field '" + field + "' (line " + line(tuple.getKeyNode()) + ")");
      }
      if (field.equals("component_keys")) {
        if (!(value instanceof SequenceNode list
            && list.getValue().stream().allMatch(item -> item instanceof ScalarNode))) {
          throw new Unreadable(
              "services: component_keys must be a list (line " + line(value) + ")");
        }
      } else if (!(value instanceof ScalarNode)) {
        throw new Unreadable(
            section + ": field '" + field + "' must be a single value (line " + line(value) + ")");
      }
    }
    Map<String, Node> present = fields(entry);
    for (String field : fields) {
      if (!OPTIONAL.contains(field) && !present.containsKey(field)) {
        throw new Unreadable(
            section + ": missing field '" + field + "' (line " + line(entry) + ")");
      }
    }
  }

  private static Map<String, Node> fields(Node entry) {
    Map<String, Node> fields = new HashMap<>();
    for (NodeTuple tuple : ((MappingNode) entry).getValue()) {
      fields.put(word(tuple.getKeyNode()), tuple.getValueNode());
    }
    return fields;
  }

  /** A required value: its text; a plain empty or null scalar is the text {@code null}. */
  private static String required(Node node) {
    ScalarNode scalar = (ScalarNode) node;
    return scalar.isPlain() && EMPTY.contains(scalar.getValue()) ? "null" : scalar.getValue();
  }

  /** An optional value: absent, or a plain empty or null scalar, is empty ({@code null}). */
  private static String optional(Node node) {
    if (node == null) {
      return null;
    }
    ScalarNode scalar = (ScalarNode) node;
    return scalar.isPlain() && EMPTY.contains(scalar.getValue()) ? null : scalar.getValue();
  }

  private static String word(Node key) {
    return ((ScalarNode) key).getValue();
  }

  private static Optional<Node> first(Stream<? extends Node> nodes) {
    return nodes.map(Node.class::cast).min(DOCUMENT_ORDER);
  }

  private static Mark mark(Node node) {
    return node.getStartMark().orElseThrow();
  }

  private static int line(Node node) {
    return mark(node).getLine() + 1;
  }

  // Stage 2 — the rules (DM-R-55, DC-TX-11).

  private static List<String> rules(Content file) {
    Set<String> problems = new LinkedHashSet<>();
    valueForms(file, problems);
    for (String key : repeated(file.services().stream().map(Service::serviceKey))) {
      problems.add("services: key '" + key + "' appears more than once.");
    }
    for (String key : repeated(file.components().stream().map(Component::componentKey))) {
      problems.add("components: key '" + key + "' appears more than once.");
    }
    Map<String, Component> byKey = new LinkedHashMap<>();
    file.components().stream()
        .filter(c -> isKey(c.componentKey()))
        .forEach(c -> byKey.putIfAbsent(c.componentKey(), c));
    references(file, byKey.keySet(), problems);
    for (Component c : file.components()) {
      if (c.runsOnKey() != null && isKey(c.runsOnKey()) && isKind(c.kind())) {
        Component target = byKey.get(c.runsOnKey());
        if (c.kind().equals("server")) {
          problems.add(
              "components '" + c.componentKey() + "': a server cannot run on another component.");
        } else if (target != null && isKind(target.kind()) && !target.kind().equals("server")) {
          problems.add("components '" + c.componentKey() + "': runs_on must name a server.");
        }
      }
    }
    for (Component c : file.components()) {
      if (c.targetKey() != null
          && isKey(c.targetKey())
          && isKind(c.kind())
          && !c.kind().equals("server")) {
        problems.add(
            "components '" + c.componentKey() + "': target_key is allowed only on a server.");
      }
    }
    Set<List<String>> identities = new HashSet<>();
    for (Identity i : file.identities()) {
      if (SourceKind.fromKey(i.sourceKind()).isPresent()
          && isKey(i.name())
          && lengthBetween(i.value(), 200)
          && !identities.add(List.of(i.sourceKind(), i.name(), i.value()))) {
        problems.add(
            "identity_keys: "
                + i.sourceKind()
                + " "
                + i.name()
                + " = '"
                + i.value()
                + "' names more than one component.");
      }
    }
    dependencies(file, byKey, problems);
    for (Service s : file.services()) {
      if (s.componentKeys().isEmpty()) {
        problems.add("services '" + s.serviceKey() + "': needs at least one component.");
      }
    }
    return List.copyOf(problems);
  }

  /** Rule 1: keys, names, values and enumerations. */
  private static void valueForms(Content file, Set<String> problems) {
    for (Service s : file.services()) {
      Check check = new Check("services", s.serviceKey(), problems);
      check.key("service_key", s.serviceKey());
      check.name("name", s.name(), 100);
      check.criticality(s.criticality());
      check.optionalKey("owner_team_key", s.ownerTeamKey());
      s.componentKeys().forEach(k -> check.key("component_keys", k));
    }
    for (Component c : file.components()) {
      Check check = new Check("components", c.componentKey(), problems);
      check.key("component_key", c.componentKey());
      check.name("name", c.name(), 100);
      check.valid("kind", c.kind(), isKind(c.kind()));
      check.criticality(c.criticality());
      check.optionalKey("owner_team_key", c.ownerTeamKey());
      check.optionalKey("runs_on_key", c.runsOnKey());
      check.optionalKey("target_key", c.targetKey());
    }
    for (Identity i : file.identities()) {
      Check check = new Check("identity_keys", identityKey(i), problems);
      check.key("component_key", i.componentKey());
      check.valid("source_kind", i.sourceKind(), SourceKind.fromKey(i.sourceKind()).isPresent());
      check.key("name", i.name());
      check.name("value", i.value(), 200);
    }
    for (Dependency d : file.dependencies()) {
      Check check = new Check("dependencies", d.from() + " → " + d.to(), problems);
      check.key("from_component_key", d.from());
      check.key("to_component_key", d.to());
    }
  }

  /** The rule 1 text of one entry: "{section} '{key}': '{value}' is not a valid {field}." */
  private record Check(String section, String key, Set<String> problems) {

    void valid(String field, String value, boolean valid) {
      if (!valid) {
        problems.add(section + " '" + key + "': '" + value + "' is not a valid " + field + ".");
      }
    }

    void key(String field, String value) {
      valid(field, value, isKey(value));
    }

    void optionalKey(String field, String value) {
      if (value != null) {
        key(field, value);
      }
    }

    void name(String field, String value, int max) {
      valid(field, value, lengthBetween(value, max));
    }

    void criticality(String value) {
      if (value != null) {
        valid("criticality", value, Criticality.fromKey(value).isPresent());
      }
    }
  }

  /** Rule 3: every reference names a component of the file. */
  private static void references(Content file, Set<String> components, Set<String> problems) {
    for (Service s : file.services()) {
      s.componentKeys()
          .forEach(k -> reference("services", s.serviceKey(), k, components, problems));
    }
    for (Component c : file.components()) {
      if (c.runsOnKey() != null) {
        reference("components", c.componentKey(), c.runsOnKey(), components, problems);
      }
    }
    for (Identity i : file.identities()) {
      reference("identity_keys", identityKey(i), i.componentKey(), components, problems);
    }
    for (Dependency d : file.dependencies()) {
      String key = d.from() + " → " + d.to();
      reference("dependencies", key, d.from(), components, problems);
      reference("dependencies", key, d.to(), components, problems);
    }
  }

  private static void reference(
      String section, String key, String ref, Set<String> components, Set<String> problems) {
    if (isKey(ref) && !components.contains(ref)) {
      problems.add(section + " '" + key + "': component '" + ref + "' is not in the file.");
    }
  }

  /** Rule 7: no self dependency, no duplicate, no cycle over listed and implied edges. */
  private static void dependencies(
      Content file, Map<String, Component> byKey, Set<String> problems) {
    Set<List<String>> listed = new HashSet<>();
    Map<String, SortedSet<String>> forward = new TreeMap<>();
    Map<String, SortedSet<String>> backward = new TreeMap<>();
    for (Dependency d : file.dependencies()) {
      if (!isKey(d.from()) || !isKey(d.to())) {
        continue;
      }
      if (d.from().equals(d.to())) {
        problems.add("dependencies: '" + d.from() + "' cannot depend on itself.");
      }
      if (!listed.add(List.of(d.from(), d.to()))) {
        problems.add("dependencies: '" + d.from() + "' → '" + d.to() + "' appears more than once.");
      }
      edge(d.from(), d.to(), byKey, forward, backward);
    }
    byKey.forEach(
        (key, c) -> {
          if (c.runsOnKey() != null) {
            edge(key, c.runsOnKey(), byKey, forward, backward);
          }
        });
    for (SortedSet<String> group : ServiceMapGraph.stronglyConnected(forward, backward)) {
      String k1 = group.first();
      List<String> cycle = ServiceMapGraph.shortestPath(k1, k1, forward, backward);
      problems.add("dependencies: cycle " + String.join(" → ", cycle) + ".");
    }
  }

  private static void edge(
      String from,
      String to,
      Map<String, Component> byKey,
      Map<String, SortedSet<String>> forward,
      Map<String, SortedSet<String>> backward) {
    if (!from.equals(to) && byKey.containsKey(from) && byKey.containsKey(to)) {
      forward.computeIfAbsent(from, k -> new TreeSet<>()).add(to);
      backward.computeIfAbsent(to, k -> new TreeSet<>()).add(from);
    }
  }

  /** The values that appear more than once, in the order of their second appearance. */
  private static Set<String> repeated(Stream<String> keys) {
    Set<String> seen = new HashSet<>();
    Set<String> repeated = new LinkedHashSet<>();
    keys.filter(ServiceMapFile::isKey)
        .forEach(
            key -> {
              if (!seen.add(key)) {
                repeated.add(key);
              }
            });
    return repeated;
  }

  private static String identityKey(Identity i) {
    return i.sourceKind() + " " + i.name() + " = " + i.value();
  }

  static boolean isKey(String value) {
    return value != null && KEY.matcher(value).matches();
  }

  private static boolean isKind(String value) {
    return ComponentKind.fromKey(value).isPresent();
  }

  private static boolean lengthBetween(String value, int max) {
    if (value == null) {
      return false;
    }
    int length = value.codePointCount(0, value.length());
    return length >= 1 && length <= max;
  }
}
