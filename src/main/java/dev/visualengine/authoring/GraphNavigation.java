package dev.visualengine.authoring;

import java.util.*;

/** Search and transient canvas projection; neither operation edits an authored graph. */
public final class GraphNavigation {
  public static final int NODE_WIDTH = 150, NODE_HEIGHT = 76;
  private static final double PAINT_LIMIT = 1 << 20;

  private GraphNavigation() {}

  public record Choice(String id, EffectGraph.Kind kind) {
    @Override
    public String toString() {
      return id + " · " + kind;
    }
  }

  /** All query words must occur in a root node's identity or descriptive metadata. */
  public static List<Choice> choices(EffectGraph graph, String query) {
    String normalized = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    String[] words = normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
    List<Choice> matches = new ArrayList<>();
    for (var node : graph.nodes) {
      if (node == null || node.id() == null || node.kind() == null) continue;
      String text =
          String.join(
                  " ", node.id(), node.kind().name(), node.group(), node.comment(), node.template())
              .toLowerCase(Locale.ROOT);
      if (Arrays.stream(words).allMatch(text::contains))
        matches.add(new Choice(node.id(), node.kind()));
    }
    return List.copyOf(matches);
  }

  public record Position(int x, int y) {}

  public static final class View {
    private double anchorX, anchorY, offsetX, offsetY;

    /** Omit remote or invalid geometry before narrowing it for Java2D and hit testing. */
    public Optional<Position> position(EffectGraph.Node node) {
      double x = node.x() - anchorX + offsetX, y = node.y() - anchorY + offsetY;
      if (!Double.isFinite(x)
          || !Double.isFinite(y)
          || Math.abs(x) > PAINT_LIMIT
          || Math.abs(y) > PAINT_LIMIT) return Optional.empty();
      return Optional.of(new Position((int) x, (int) y));
    }

    public boolean focus(EffectGraph.Node node, int width, int height) {
      if (width <= 0 || height <= 0 || !Double.isFinite(node.x()) || !Double.isFinite(node.y()))
        return false;
      // Anchor the subtraction at the chosen node, retaining screen precision even near
      // Double.MAX_VALUE.
      anchorX = node.x();
      anchorY = node.y();
      offsetX = (width - NODE_WIDTH) / 2.0;
      offsetY = (height - NODE_HEIGHT) / 2.0;
      return true;
    }

    public void reset() {
      anchorX = anchorY = offsetX = offsetY = 0;
    }
  }
}
