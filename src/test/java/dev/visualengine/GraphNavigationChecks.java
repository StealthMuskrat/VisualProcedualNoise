package dev.visualengine;

import dev.visualengine.authoring.*;
import java.util.*;

public final class GraphNavigationChecks {
  private static int count;

  private static void check(boolean ok, String text) {
    count++;
    if (!ok) throw new AssertionError(text);
  }

  private static List<String> ids(EffectGraph graph, String query) {
    return GraphNavigation.choices(graph, query).stream().map(GraphNavigation.Choice::id).toList();
  }

  public static int run() {
    count = 0;
    EffectGraph graph = AuthoringChecks.noiseGraph();
    var field = graph.nodes.getLast();
    graph.nodes.set(
        3,
        new EffectGraph.Node(
            field.id(),
            field.kind(),
            field.inputs(),
            field.expression(),
            field.x(),
            field.y(),
            false,
            "Cloud layers",
            "Fine detail",
            "local.cloud@1.2.0",
            field.seedKey()));
    var before = graph.copy();
    var source = GraphCompiler.compile(graph).function();
    check(
        ids(graph, null).equals(List.of("coord", "time", "point", "field")),
        "blank search retains authored order");
    check(
        ids(graph, "  FiElD  ").equals(List.of("field")),
        "node identity search ignores case and outer whitespace");
    check(
        ids(graph, "noise cloud DETAIL").equals(List.of("field")),
        "query words match operation, group and comment together");
    check(
        ids(graph, "local.cloud@1.2.0").equals(List.of("field")),
        "pinned template revisions are searchable");
    check(ids(graph, "cloud missing").isEmpty(), "all search words are required");
    check(ids(graph, "   \t  ").size() == 4, "whitespace-only search lists all nodes");
    check(
        GraphNavigation.choices(graph, "noise").getFirst().toString().equals("field · NOISE"),
        "choices distinguish name and operation");
    boolean immutable = false;
    try {
      GraphNavigation.choices(graph, "").clear();
    } catch (UnsupportedOperationException expected) {
      immutable = true;
    }
    check(immutable, "search results cannot mutate graph membership");
    Locale prior = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      check(ids(graph, "TIME").equals(List.of("time")), "search uses locale-independent casing");
    } finally {
      Locale.setDefault(prior);
    }
    var invalid = graph.copy();
    invalid.nodes.set(
        3,
        new EffectGraph.Node(
            "field",
            EffectGraph.Kind.FORMULA,
            List.of("absent"),
            "sin(",
            0,
            0,
            false,
            "",
            "repair me"));
    check(
        ids(invalid, "repair").equals(List.of("field")),
        "incomplete drafts remain searchable without compilation");
    var view = new GraphNavigation.View();
    check(
        view.position(field)
            .orElseThrow()
            .equals(new GraphNavigation.Position((int) field.x(), (int) field.y())),
        "initial view retains authored canvas coordinates");
    var remote = field.moved(-45000, 87000);
    check(view.focus(remote, 800, 600), "finite distant node can be focused");
    check(
        view.position(remote).orElseThrow().equals(new GraphNavigation.Position(325, 262)),
        "focus centers the selected rectangle");
    var extreme = field.moved(Double.MAX_VALUE, -Double.MAX_VALUE);
    check(
        view.focus(extreme, 800, 600)
            && view.position(extreme).orElseThrow().equals(new GraphNavigation.Position(325, 262)),
        "extreme finite layouts retain screen precision");
    check(
        view.position(field).isEmpty()
            && view.position(extreme.moved(-Double.MAX_VALUE, Double.MAX_VALUE)).isEmpty(),
        "remote and overflowing projections are omitted before painting");
    check(
        !view.focus(field.moved(Double.NaN, 0), 800, 600) && !view.focus(field, 0, 600),
        "invalid focus requests are rejected");
    check(
        view.position(extreme).orElseThrow().x() == 325,
        "rejected focus leaves the accepted view unchanged");
    view.reset();
    check(
        view.position(field).orElseThrow().x() == (int) field.x()
            && view.position(extreme).isEmpty(),
        "reset restores the original view without clipping the model");
    check(
        GraphEdits.same(graph, before) && GraphCompiler.compile(graph).function().equals(source),
        "search and view changes preserve graph and generated source");
    return count;
  }
}
