package dev.visualengine.authoring;

import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import java.io.*;
import java.nio.file.*;

/**
 * Portable self-contained data manifest; imports do not load code, DLLs or external dependencies.
 */
public final class EffectPackage {
  public int effectApiVersion = 2;
  public java.util.List<EffectControl> controls = new java.util.ArrayList<>();
  public java.util.Map<String, java.util.List<Double>> controlValues =
      new java.util.LinkedHashMap<>();
  public String definitionHash = "";
  public boolean feedbackEnabled, pigmentEnabled, reactionEnabled, particlesEnabled;
  public String id = "local.effect",
      version = "1.0.0",
      author = "",
      license = "",
      coordinates = "centered aspect-correct domain",
      time = "seconds since reset; phase is bounded radians";
  public String authoring = "expression",
      expression = "sin(length(p) * frequency - phase)",
      statePolicy = "restart";
  public EffectGraph graph;
  public java.util.Map<String, Double> parameters =
      new java.util.LinkedHashMap<>(new Settings().values);

  public void validate() {
    if ((effectApiVersion != 1 && effectApiVersion != 2)
        || id == null
        || !id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")
        || version == null
        || version.length() > 32
        || author == null
        || license == null
        || author.length() > 256
        || license.length() > 1024)
      throw new IllegalArgumentException("Invalid effect manifest identity");
    if (!"centered aspect-correct domain".equals(coordinates)
        || !"seconds since reset; phase is bounded radians".equals(time))
      throw new IllegalArgumentException("Unsupported coordinate or time contract");
    if (!"restart".equals(statePolicy))
      throw new IllegalArgumentException("Effect state policy must be restart");
    if (parameters == null || parameters.size() > Settings.PARAMETERS.size())
      throw new IllegalArgumentException("Invalid effect parameters");
    EffectControl.validateSchema(controls);
    if (effectApiVersion == 1 && !controls.isEmpty())
      throw new IllegalArgumentException("Custom control schemas require effect API 2");
    if (controlValues == null) throw new IllegalArgumentException("Missing effect values");
    if (definitionHash == null
        || !definitionHash.isEmpty() && !definitionHash.equals(contentHash()))
      throw new IllegalArgumentException("Effect definition hash does not match its content");
    Settings checked = new Settings();
    checked.installEffectControls(controls, controlValues);
    parameters.forEach(checked::set);
    if ("graph".equals(authoring)) GraphCompiler.compile(graph, controls);
    else if ("expression".equals(authoring)) {
      EffectControl.compileField(expression, controls);
    } else throw new IllegalArgumentException("Unsupported authoring mode");
  }

  public static EffectPackage load(Path path) throws IOException {
    if (Files.size(path) > 1_048_576) throw new IOException("Effect package exceeds 1 MiB");
    var raw = com.google.gson.JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    String supplied = raw.has("definitionHash") ? raw.get("definitionHash").getAsString() : "";
    raw.remove("definitionHash");
    if (!supplied.isEmpty() && !supplied.equals(hash(raw)))
      throw new IllegalArgumentException("Effect definition hash does not match its content");
    EffectPackage effect = PresetIO.JSON.fromJson(raw, EffectPackage.class);
    effect.definitionHash = "";
    effect.validate();
    if (!supplied.isEmpty()) effect.definitionHash = effect.contentHash();
    return effect;
  }

  public void apply(Settings s) {
    validate();
    s.modulations.clear();
    s.installEffectControls(controls, controlValues);
    s.tileMode = false;
    s.tilePreview = false;
    s.loopSeconds = 0;
    s.feedbackEnabled = feedbackEnabled;
    s.pigmentEnabled = pigmentEnabled;
    s.reactionEnabled = reactionEnabled;
    s.particlesEnabled = particlesEnabled;
    s.expression = expression == null ? "0" : expression;
    s.graph = graph == null ? new EffectGraph() : graph.copy();
    s.graphEnabled = "graph".equals(authoring);
    s.recipe = Settings.Recipe.CUSTOM;
    s.name = id + " " + version;
    parameters.forEach(s::set);
    resolveResources(s);
  }

  public static void resolveResources(Settings s) {
    if (s.recipe != Settings.Recipe.CUSTOM) return;
    if (s.graphEnabled) {
      var resources = GraphCompiler.compile(s.graph, s.effectControls).resources();
      if (resources.contains("previousVisualColor")) s.feedbackEnabled = true;
      if (resources.contains("pigment")) s.pigmentEnabled = true;
    } else {
      var compiled = EffectControl.compileField(s.expression, s.effectControls);
      if (s.loopSeconds > 0 && compiled.dependencies().contains("t"))
        throw new IllegalArgumentException(
            "Analytic loops require phase instead of unbounded formula time");
    }
  }

  public String contentHash() {
    var json = PresetIO.JSON.toJsonTree(this).getAsJsonObject();
    json.remove("definitionHash");
    return hash(json);
  }

  private static String hash(com.google.gson.JsonElement json) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(canonical(json).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static String canonical(com.google.gson.JsonElement value) {
    if (value.isJsonObject()) {
      var object = value.getAsJsonObject();
      return "{"
          + String.join(
              ",",
              new java.util.TreeSet<>(object.keySet())
                  .stream()
                      .map(k -> PresetIO.JSON.toJson(k) + ":" + canonical(object.get(k)))
                      .toList())
          + "}";
    }
    if (value.isJsonArray())
      return "["
          + String.join(
              ",",
              java.util.stream.StreamSupport.stream(value.getAsJsonArray().spliterator(), false)
                  .map(EffectPackage::canonical)
                  .toList())
          + "]";
    return value.toString();
  }

  public void save(Path path) throws IOException {
    validate();
    definitionHash = contentHash();
    PresetIO.atomicWrite(path, PresetIO.JSON.toJson(this));
  }
}
