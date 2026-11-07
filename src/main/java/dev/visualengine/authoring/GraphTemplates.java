package dev.visualengine.authoring;

import java.util.*;

/** Embedded, revision-pinned graph definitions expanded into the existing typed compiler. */
public final class GraphTemplates {
  public record Input(String name, GraphCompiler.Port type) {}

  public record Exposure(String nodeId, String name) {
    @Override
    public String toString() {
      return name + " ← " + nodeId;
    }
  }

  public static void validateExposures(List<Exposure> inputs) {
    if (inputs == null || inputs.size() > 8)
      throw new IllegalArgumentException("A group can expose at most eight inputs");
    Set<String> names = new HashSet<>(), sources = new HashSet<>();
    for (var input : inputs) {
      if (input == null
          || input.name() == null
          || !input.name().matches("[A-Za-z][A-Za-z0-9_]{0,47}"))
        throw new IllegalArgumentException(
            "Input names need 1..48 letters, digits or underscores, starting with a letter");
      if (!names.add(input.name()))
        throw new IllegalArgumentException("Group input name already exists: " + input.name());
      if (input.nodeId() == null || !sources.add(input.nodeId()))
        throw new IllegalArgumentException("Expose each source node only once");
    }
  }

  public record Group(
      String id,
      String version,
      List<Input> inputs,
      List<EffectGraph.Node> nodes,
      String output,
      GraphCompiler.Port outputType) {
    public Group {
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    public String key() {
      return id + "@" + version;
    }
  }

  public record Expanded(EffectGraph graph, Map<String, List<String>> nodePaths) {}

  private final Map<String, List<String>> nodePaths = new LinkedHashMap<>();
  private final Map<String, Group> definitions = new LinkedHashMap<>();
  private final List<EffectGraph.Node> expanded = new ArrayList<>();

  private GraphTemplates() {}

  public static EffectGraph expand(EffectGraph graph) {
    return expandWithOrigins(graph).graph();
  }

  public static Expanded expandWithOrigins(EffectGraph graph) {
    if (graph == null
        || graph.nodes == null
        || graph.groups == null
        || graph.nodes.size() > 64
        || graph.groups.size() > 16)
      throw new IllegalArgumentException(
          "Graph requires at most 64 root nodes and 16 group revisions");
    GraphTemplates t = new GraphTemplates();
    for (var group : graph.groups) {
      if (group == null
          || group.id() == null
          || !group.id().matches("[A-Za-z][A-Za-z0-9_.-]{0,95}")
          || group.version() == null
          || group.version().length() > 32
          || !group.version().matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?")
          || group.outputType() == null
          || group.nodes().isEmpty()
          || group.nodes().size() > 32
          || group.inputs().size() > 8
          || t.definitions.putIfAbsent(group.key(), group) != null)
        throw new IllegalArgumentException("Invalid or duplicate group revision");
      Set<String> names = new HashSet<>();
      for (var input : group.inputs())
        if (input == null
            || input.type() == null
            || input.name() == null
            || !input.name().matches("[A-Za-z][A-Za-z0-9_]{0,47}")
            || !names.add(input.name())) throw new IllegalArgumentException("Invalid group input");
      validateNodes(group.nodes(), group.output(), List.of(group.key()));
      for (var node : group.nodes())
        if (node.kind() == EffectGraph.Kind.INPUT
            && (!names.contains(node.expression()) || !node.inputs().isEmpty()))
          throw problem(
              List.of(group.key(), node.id()), "Undeclared group input: " + node.expression());
    }
    validateNodes(graph.nodes, graph.output, List.of());
    for (String key : t.definitions.keySet()) t.checkReferences(key, new LinkedHashSet<>(), 0);
    t.expandNodes(graph.nodes, "", Map.of(), 0, List.of());
    EffectGraph result = graph.copy();
    result.nodes = new ArrayList<>(t.expanded);
    result.groups = new ArrayList<>();
    return new Expanded(result, Map.copyOf(t.nodePaths));
  }

  private static void validateNodes(
      List<EffectGraph.Node> nodes, String output, List<String> path) {
    Set<String> ids = new HashSet<>();
    for (var node : nodes)
      if (node == null
          || node.id() == null
          || !node.id().matches("[A-Za-z][A-Za-z0-9_]{0,47}")
          || node.kind() == null
          || !ids.add(node.id()))
        throw problem(
            append(path, node == null ? "<missing>" : String.valueOf(node.id())),
            "Invalid or duplicate graph node ID");
    if (!ids.contains(output))
      throw problem(append(path, String.valueOf(output)), "Missing graph output: " + output);
    for (var node : nodes)
      for (String input : node.inputs())
        if (!ids.contains(input))
          throw problem(append(path, node.id()), "Missing graph input: " + input);
  }

  private void checkReferences(String key, Set<String> active, int depth) {
    if (depth > 4 || !active.add(key))
      throw new IllegalArgumentException(
          "Recursive group or nesting beyond four revisions: " + key);
    Group group = definitions.get(key);
    if (group == null) throw new IllegalArgumentException("Missing group revision: " + key);
    for (var node : group.nodes())
      if (node.kind() == EffectGraph.Kind.GROUP)
        checkReferences(node.template(), active, depth + 1);
    active.remove(key);
  }

  private static List<String> append(List<String> path, String id) {
    List<String> result = new ArrayList<>(path);
    result.add(id);
    return List.copyOf(result);
  }

  private static AuthoringDiagnostic problem(List<String> path, String text) {
    return AuthoringDiagnostic.inNodes(path, new IllegalArgumentException(text));
  }

  private void add(EffectGraph.Node node, List<String> path) {
    if (expanded.size() >= 256)
      throw new IllegalArgumentException("Expanded graph exceeds 256 nodes");
    expanded.add(node);
    nodePaths.put(node.id(), path);
  }

  private void expandNodes(
      List<EffectGraph.Node> nodes,
      String prefix,
      Map<String, String> bindings,
      int depth,
      List<String> parentPath) {
    if (depth > 4) throw new IllegalArgumentException("Group nesting exceeds four instances");
    for (var n : nodes) {
      List<String> path = append(parentPath, n.id());
      try {
        String id = prefix + n.id();
        List<String> inputs = n.inputs().stream().map(x -> prefix + x).toList();
        if (n.kind() == EffectGraph.Kind.INPUT) {
          String bound = bindings.get(n.expression());
          if (bound == null)
            throw new IllegalArgumentException("INPUT is only valid inside a group definition");
          add(
              new EffectGraph.Node(
                  id,
                  EffectGraph.Kind.ALIAS,
                  List.of(bound),
                  "0",
                  n.x(),
                  n.y(),
                  false,
                  n.group(),
                  n.comment(),
                  "",
                  n.seedKey()),
              path);
          continue;
        }
        if (n.kind() == EffectGraph.Kind.GROUP && !n.bypass()) {
          Group group = definitions.get(n.template());
          if (group == null)
            throw new IllegalArgumentException("Missing group revision: " + n.template());
          if (group.inputs().size() != inputs.size())
            throw new IllegalArgumentException(
                "Group " + n.template() + " requires " + group.inputs().size() + " inputs");
          String inner =
              "g"
                  + Integer.toUnsignedString(id.hashCode(), 16)
                  + "_"
                  + Long.toUnsignedString(
                      dev.visualengine.time.Seeds.derive(0, id, "namespace"), 16)
                  + "_";
          Map<String, String> args = new LinkedHashMap<>();
          for (int i = 0; i < inputs.size(); i++) {
            var input = group.inputs().get(i);
            String checked = inner + "arg_" + i;
            add(
                new EffectGraph.Node(
                    checked,
                    EffectGraph.Kind.CHECK_SOURCE,
                    List.of(inputs.get(i)),
                    input.type().name(),
                    0,
                    0,
                    false,
                    "",
                    ""),
                path);
            args.put(input.name(), checked);
          }
          expandNodes(group.nodes(), inner + "node_", args, depth + 1, path);
          add(
              new EffectGraph.Node(
                  id,
                  EffectGraph.Kind.CHECK_SOURCE,
                  List.of(inner + "node_" + group.output()),
                  group.outputType().name(),
                  n.x(),
                  n.y(),
                  false,
                  n.group(),
                  n.comment(),
                  "",
                  n.seedKey()),
              path);
        } else
          add(
              new EffectGraph.Node(
                  id,
                  n.kind(),
                  inputs,
                  n.expression(),
                  n.x(),
                  n.y(),
                  n.bypass(),
                  n.group(),
                  n.comment(),
                  n.template(),
                  n.seedKey()),
              path);
      } catch (IllegalArgumentException e) {
        throw AuthoringDiagnostic.inNodes(path, e);
      }
    }
  }

  /** Copy one pinned instance into the root draft while retaining explicit type boundaries. */
  public static EffectGraph unpack(
      EffectGraph graph, String selected, List<EffectControl> controls) {
    Objects.requireNonNull(graph);
    var instance =
        graph.nodes.stream().filter(n -> n.id().equals(selected)).findFirst().orElse(null);
    if (instance == null || instance.kind() != EffectGraph.Kind.GROUP || instance.bypass())
      throw problem(
          List.of(String.valueOf(selected)), "Select a non-bypassed GROUP instance to unpack");
    GraphCompiler.compile(graph, controls);
    var definition =
        graph.groups.stream()
            .filter(g -> g.key().equals(instance.template()))
            .findFirst()
            .orElseThrow();
    if (graph.nodes.size() + definition.inputs().size() + definition.nodes().size() > 64)
      throw problem(List.of(selected), "Unpacking would exceed 64 root nodes");
    Set<String> used = new HashSet<>();
    graph.nodes.forEach(n -> used.add(n.id()));
    Map<String, String> ports = new LinkedHashMap<>(), names = new LinkedHashMap<>();
    List<EffectGraph.Node> additions = new ArrayList<>();
    for (int i = 0; i < definition.inputs().size(); i++) {
      var port = definition.inputs().get(i);
      String name = freshRootName(instance.id() + "_input" + i, used);
      ports.put(port.name(), name);
      additions.add(
          new EffectGraph.Node(
              name,
              EffectGraph.Kind.CHECK_SOURCE,
              List.of(instance.inputs().get(i)),
              port.type().name(),
              instance.x(),
              instance.y(),
              false,
              "",
              "Input " + port.name()));
    }
    for (var node : definition.nodes())
      names.put(node.id(), freshRootName(instance.id() + "_" + node.id(), used));
    for (var node : definition.nodes()) {
      boolean input = node.kind() == EffectGraph.Kind.INPUT;
      additions.add(
          new EffectGraph.Node(
              names.get(node.id()),
              input ? EffectGraph.Kind.ALIAS : node.kind(),
              input
                  ? List.of(ports.get(node.expression()))
                  : node.inputs().stream().map(names::get).toList(),
              input ? "0" : node.expression(),
              node.x(),
              node.y(),
              input ? false : node.bypass(),
              node.group(),
              node.comment(),
              input ? "" : node.template(),
              node.seedKey()));
    }
    EffectGraph next = graph.copy();
    next.nodes.set(
        next.nodes.indexOf(instance),
        new EffectGraph.Node(
            instance.id(),
            EffectGraph.Kind.CHECK_SOURCE,
            List.of(names.get(definition.output())),
            definition.outputType().name(),
            instance.x(),
            instance.y(),
            false,
            instance.group(),
            instance.comment(),
            "",
            instance.seedKey()));
    next.nodes.addAll(additions);
    GraphCompiler.compile(next, controls);
    return next;
  }

  private static String freshRootName(String base, Set<String> used) {
    String name = base.substring(0, Math.min(48, base.length()));
    int counter = 1;
    while (!used.add(name)) {
      String suffix = "_" + (++counter);
      name = base.substring(0, Math.min(48 - suffix.length(), base.length())) + suffix;
    }
    return name;
  }

  public static EffectGraph capture(
      EffectGraph graph, String id, String version, List<EffectControl> controls) {
    return capture(graph, id, version, controls, List.of());
  }

  /** Capture a revision, replacing selected internal sources with typed named boundaries. */
  public static EffectGraph capture(
      EffectGraph graph,
      String id,
      String version,
      List<EffectControl> controls,
      List<Exposure> exposed) {
    validateExposures(exposed);
    var compiled = GraphCompiler.compile(graph, controls);
    Map<String, EffectGraph.Node> original = new LinkedHashMap<>();
    graph.nodes.forEach(node -> original.put(node.id(), node));
    Map<String, Exposure> bindings = new LinkedHashMap<>();
    List<Input> ports = new ArrayList<>();
    for (var input : exposed) {
      var type = compiled.types().get(input.nodeId());
      if (!original.containsKey(input.nodeId()) || type == null)
        throw new IllegalArgumentException(
            "Input source must contribute to the active output: " + input.nodeId());
      bindings.put(input.nodeId(), input);
      ports.add(new Input(input.name(), type));
    }
    List<EffectGraph.Node> body = new ArrayList<>();
    for (var node : graph.nodes) {
      var binding = bindings.get(node.id());
      body.add(
          binding == null
              ? node
              : new EffectGraph.Node(
                  node.id(),
                  EffectGraph.Kind.INPUT,
                  List.of(),
                  binding.name(),
                  node.x(),
                  node.y(),
                  false,
                  node.group(),
                  node.comment(),
                  "",
                  node.seedKey()));
    }
    EffectGraph next = graph.copy();
    Group definition = new Group(id, version, ports, body, graph.output, GraphCompiler.Port.SCALAR);
    if (next.groups.stream().anyMatch(g -> g.key().equals(definition.key())))
      throw new IllegalArgumentException(
          "That group revision already exists; choose a new version");
    next.groups.add(definition);
    Set<String> retained = new LinkedHashSet<>();
    Deque<String> pending = new ArrayDeque<>(bindings.keySet());
    while (!pending.isEmpty()) {
      String source = pending.removeFirst();
      if (retained.add(source)) pending.addAll(original.get(source).inputs());
    }
    String instance = "instance";
    int suffix = 1;
    while (retained.contains(instance)) instance = "instance" + (++suffix);
    next.nodes = new ArrayList<>();
    for (var node : graph.nodes) if (retained.contains(node.id())) next.nodes.add(node);
    var output = original.get(graph.output);
    next.nodes.add(
        new EffectGraph.Node(
            instance,
            EffectGraph.Kind.GROUP,
            exposed.stream().map(Exposure::nodeId).toList(),
            "0",
            exposed.isEmpty() ? 70 : output.x(),
            exposed.isEmpty() ? 70 : output.y(),
            false,
            "",
            "",
            definition.key()));
    next.output = instance;
    GraphCompiler.compile(next, controls);
    return next;
  }
}
