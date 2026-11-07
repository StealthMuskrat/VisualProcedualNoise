package dev.visualengine.authoring;

import dev.visualengine.model.Settings;
import java.util.*;

/** A data-only scalar/vector control. Values are separate from shader structure. */
public record EffectControl(
    String id,
    String label,
    Formula.Type type,
    String units,
    List<Double> initial,
    double minimum,
    double maximum,
    double step,
    Scale scale,
    Update update) {
  public enum Scale {
    LINEAR,
    LOGARITHMIC
  }

  public enum Update {
    UNIFORM,
    SMOOTHED,
    RESET
  }

  public EffectControl {
    initial = initial == null ? List.of() : List.copyOf(initial);
  }

  public void validate() {
    if (id == null
        || !id.matches("[A-Za-z][A-Za-z0-9_]{0,47}")
        || Formula.SYMBOLS.containsKey(id)
        || id.matches("input[0-3]")
        || Settings.PARAMETERS.stream().anyMatch(p -> p.id().equals(id))
        || label == null
        || label.isBlank()
        || label.length() > 80
        || type == null
        || units == null
        || units.length() > 32
        || scale == null
        || update == null
        || !Double.isFinite(minimum)
        || !Double.isFinite(maximum)
        || minimum >= maximum
        || Math.abs(minimum) > 1e6
        || Math.abs(maximum) > 1e6
        || !Double.isFinite(step)
        || step <= 0
        || step > maximum - minimum
        || scale == Scale.LOGARITHMIC && minimum <= 0)
      throw new IllegalArgumentException("Invalid effect control descriptor: " + id);
    validateValue(initial);
  }

  public List<Double> validateValue(List<Double> value) {
    if (type == null || value == null || value.size() != type.width)
      throw new IllegalArgumentException(
          "Control "
              + id
              + " requires "
              + (type == null ? "a known type" : type.width + " components"));
    for (Double v : value)
      if (v == null || !Double.isFinite(v) || v < minimum || v > maximum)
        throw new IllegalArgumentException(
            "Control " + id + " is outside [" + minimum + ", " + maximum + "]");
    return List.copyOf(value);
  }

  public static void validateSchema(List<EffectControl> controls) {
    if (controls == null || controls.size() > 16)
      throw new IllegalArgumentException("At most 16 effect controls are allowed");
    Set<String> ids = new HashSet<>();
    for (var control : controls) {
      if (control == null) throw new IllegalArgumentException("Null effect control");
      control.validate();
      if (!ids.add(control.id()))
        throw new IllegalArgumentException("Duplicate effect control: " + control.id());
    }
  }

  public static Map<String, Formula.Type> symbols(List<EffectControl> controls) {
    validateSchema(controls);
    Map<String, Formula.Type> symbols = new LinkedHashMap<>(Formula.SYMBOLS);
    controls.forEach(c -> symbols.put(c.id(), c.type()));
    return Map.copyOf(symbols);
  }

  public static Formula.Compiled compile(String expression, List<EffectControl> controls) {
    return Formula.compile(expression, symbols(controls));
  }

  public static Formula.Compiled compileField(String expression, List<EffectControl> controls) {
    return requireScalar(expression, compile(expression, controls));
  }

  public static Formula.Compiled compileFieldValue(
      String expression, List<EffectControl> controls) {
    return requireScalar(expression, Formula.compileValue(expression, symbols(controls)));
  }

  private static Formula.Compiled requireScalar(String expression, Formula.Compiled compiled) {
    if (compiled.root().type() != Formula.Type.FLOAT)
      throw AuthoringDiagnostic.at(
          expression, 0, expression.length(), "Field expression must be scalar");
    return compiled;
  }

  public static String declarations(List<EffectControl> controls) {
    validateSchema(controls);
    return String.join(
            "\n",
            controls.stream()
                .map(c -> "uniform " + c.type().glsl + " user_" + c.id() + ";")
                .toList())
        + "\n";
  }
}
