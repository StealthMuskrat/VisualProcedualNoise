package dev.visualengine.model;

import java.util.*;

/** Complete resolved controls. IDs are persistence and uniform contracts. */
public final class Settings {
  public record Parameter(
      String id, String label, double minimum, double maximum, double initial, String group) {
    public double validate(double value) {
      if (!Double.isFinite(value) || value < minimum || value > maximum)
        throw new IllegalArgumentException(
            label + " must be between " + minimum + " and " + maximum);
      return value;
    }
  }

  public static final List<Parameter> PARAMETERS =
      List.of(
          new Parameter("scale", "Scale", .2, 12, 2.4, "Composition"),
          new Parameter("warp", "Broad warp", 0, 2, .65, "Composition"),
          new Parameter("detail", "Fine warp", 0, 1, .25, "Composition"),
          new Parameter("motion", "Motion rate", 0, 2, .22, "Time"),
          new Parameter("colorRate", "Color rate", 0, 1, .06, "Color"),
          new Parameter("height", "Surface height", 0, 2, .45, "Surface"),
          new Parameter("exposure", "Exposure", .1, 4, 1.1, "Finishing"),
          new Parameter("bloom", "Bloom", 0, 1, .15, "Finishing"),
          new Parameter("flow", "Flow speed (domain/s)", 0, 2, .35, "Simulation"),
          new Parameter("decay", "Pigment decay (/s)", 0, 3, .12, "Simulation"),
          new Parameter("injection", "Injection (/s)", 0, 8, 2, "Simulation"),
          new Parameter("feed", "Reaction feed", .01, .08, .0367, "Simulation"),
          new Parameter("kill", "Reaction kill", .03, .075, .0649, "Simulation"),
          new Parameter("feedback", "Feedback memory", 0, .98, .65, "Simulation"),
          new Parameter("radius", "Brush radius (domain)", .02, .5, .12, "Interaction"),
          new Parameter("role", "Generator role exchange", 0, 1, 0, "Composition"),
          new Parameter("historyMix", "History excavation", 0, .9, .35, "Composition"),
          new Parameter("frequency", "Formula frequency", .1, 20, 2, "Authoring"),
          new Parameter("drift", "Formula drift", 0, 2, .2, "Authoring"),
          new Parameter("stripeRate", "Formula stripe rate", .1, 30, 4, "Authoring"),
          new Parameter("agitation", "Agitation macro", 0, 1, .5, "Authoring"));

  public enum Recipe {
    LIQUID_OPAL,
    LIVING_CONTOURS,
    COSMIC_SILK,
    ALIEN_ECOSYSTEM,
    KALEIDOSCOPE,
    TEMPORAL_PAINTING,
    INTERNAL_WEATHER,
    PATTERNS_WITHIN_PATTERNS,
    TIME_EXCAVATION,
    CHANGING_METRIC,
    INTERFERENCE_GROWTH,
    FLUID_PALETTE,
    PORTALS,
    ROLE_EXCHANGE,
    LOCAL_CLOCKS,
    CUSTOM
  }

  public long seed = 173496285120L;
  public String name = "Liquid Opal";
  public Recipe recipe = Recipe.LIQUID_OPAL;
  public int palette = 0, debug = 0, grid = 256, particles = 16384, historyLayers = 8;
  public double speed = 1, displayScale = 1;
  public double loopSeconds;
  public boolean tileMode, tilePreview;
  public transient double motionClock, colorClock;
  public List<dev.visualengine.time.Modulation> modulations = new ArrayList<>();
  public boolean paused, feedbackEnabled, pigmentEnabled, reactionEnabled, particlesEnabled;
  public boolean graphEnabled;
  public dev.visualengine.authoring.EffectGraph graph =
      new dev.visualengine.authoring.EffectGraph();
  public String expression =
      "0.6 * noise3d(vec3(p * frequency, t * drift)) + 0.4 * sin(p.x * stripeRate + phase)";
  public final LinkedHashMap<String, Double> values = new LinkedHashMap<>();
  public final Set<String> locks = new HashSet<>();

  public Settings() {
    PARAMETERS.forEach(p -> values.put(p.id(), p.initial()));
  }

  public double get(String id) {
    return Objects.requireNonNull(values.get(id), id);
  }

  public void set(String id, double value) {
    var p =
        PARAMETERS.stream()
            .filter(x -> x.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown control: " + id));
    values.put(id, p.validate(value));
  }

  public Settings copy() {
    Settings s = new Settings();
    s.seed = seed;
    s.name = name;
    s.recipe = recipe;
    s.palette = palette;
    s.debug = debug;
    s.grid = grid;
    s.particles = particles;
    s.historyLayers = historyLayers;
    s.speed = speed;
    s.displayScale = displayScale;
    s.paused = paused;
    s.feedbackEnabled = feedbackEnabled;
    s.pigmentEnabled = pigmentEnabled;
    s.reactionEnabled = reactionEnabled;
    s.particlesEnabled = particlesEnabled;
    s.expression = expression;
    s.graphEnabled = graphEnabled;
    s.graph = graph == null ? null : graph.copy();
    s.loopSeconds = loopSeconds;
    s.tileMode = tileMode;
    s.tilePreview = tilePreview;
    s.motionClock = motionClock;
    s.colorClock = colorClock;
    s.modulations =
        modulations.stream()
            .map(dev.visualengine.time.Modulation::copy)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    s.values.putAll(values);
    s.locks.addAll(locks);
    return s;
  }

  public void validate() {
    if (graph == null || expression == null || name == null || recipe == null || locks == null)
      throw new IllegalArgumentException("Incomplete scene configuration");
    PARAMETERS.forEach(p -> p.validate(get(p.id())));
    if (palette < 0
        || palette > 3
        || debug < 0
        || debug > 10
        || grid < 32
        || grid > 1024
        || particles < 0
        || particles > 262144
        || historyLayers < 0
        || historyLayers > 32
        || !Double.isFinite(speed)
        || speed < 0
        || speed > 4
        || !Double.isFinite(displayScale)
        || displayScale < .25
        || displayScale > 1)
      throw new IllegalArgumentException("Invalid quality, palette, time or diagnostic settings");
    if (modulations == null || modulations.size() > 32)
      throw new IllegalArgumentException("At most 32 modulation mappings are allowed");
    modulations.forEach(dev.visualengine.time.Modulation::validate);
    if (!Double.isFinite(loopSeconds)
        || loopSeconds < 0
        || loopSeconds > 3600
        || loopSeconds > 0 && loopSeconds < 1)
      throw new IllegalArgumentException("Loop period must be zero or 1..3600 seconds");
    if ((tileMode || loopSeconds > 0)
        && (feedbackEnabled
            || pigmentEnabled
            || reactionEnabled
            || particlesEnabled
            || recipe == Recipe.LOCAL_CLOCKS
            || recipe == Recipe.TIME_EXCAVATION
            || recipe == Recipe.TEMPORAL_PAINTING
            || graphEnabled
            || !modulations.isEmpty()))
      throw new IllegalArgumentException(
          "Tile and analytic loop modes require an analytic scene without state, graph resources or"
              + " modulation");
  }
}
