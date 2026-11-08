package dev.visualengine.app;

/** Resource and native-loading verification shared by lightweight startup and acceptance runs. */
public final class Installation {
  private Installation() {}

  public static String verify() {
    var settings = new dev.visualengine.model.Settings();
    dev.visualengine.presets.PresetIO.parse(
        dev.visualengine.presets.PresetIO.JSON.toJson(
            dev.visualengine.presets.PresetIO.document(settings, 0)));
    dev.visualengine.authoring.Formula.compile(settings.expression);
    new dev.visualengine.gpu.ShaderSources(null).read("render/material.frag");
    if (Installation.class.getResource("/META-INF/licenses/LWJGL-BSD.txt") == null)
      throw new IllegalStateException("Packaged license resources are missing");
    return "Installation verified | Java "
        + System.getProperty("java.version")
        + " | GLFW "
        + org.lwjgl.glfw.GLFW.glfwGetVersionString();
  }
}
