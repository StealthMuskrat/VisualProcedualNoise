package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import java.util.*;

public final class Program implements AutoCloseable {
  public final int id;
  public final String sourceHash;
  private final Map<String, Integer> locations = new HashMap<>();

  public Program(String vertex, String fragment) {
    this(new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}, new String[] {vertex, fragment});
  }

  public static Program compute(String source) {
    return new Program(new int[] {GL_COMPUTE_SHADER}, new String[] {source});
  }

  public static String graphicsKey(String vertex, String fragment) {
    return fingerprint(
        new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}, new String[] {vertex, fragment});
  }

  private static String fingerprint(int[] stages, String[] sources) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      digest.update("glsl-source-v2\0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      for (int i = 0; i < stages.length; i++) {
        byte[] source = sources[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(
            java.nio.ByteBuffer.allocate(8).putInt(stages[i]).putInt(source.length).array());
        digest.update(source);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private Program(int[] stages, String[] sources) {
    for (String source : sources) ShaderSources.sourceBytes(source);
    sourceHash = fingerprint(stages, sources);
    int program = glCreateProgram();
    List<Integer> shaders = new ArrayList<>();
    boolean linked = false;
    try {
      for (int i = 0; i < stages.length; i++) {
        int s = glCreateShader(stages[i]);
        shaders.add(s);
        glShaderSource(s, sources[i]);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE)
          throw new IllegalArgumentException(
              "Shader stage " + stages[i] + ":\n" + glGetShaderInfoLog(s));
        glAttachShader(program, s);
      }
      glLinkProgram(program);
      if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE)
        throw new IllegalArgumentException("Shader link:\n" + glGetProgramInfoLog(program));
      id = program;
      linked = true;
    } catch (RuntimeException e) {
      glDeleteProgram(program);
      throw e;
    } finally {
      if (linked) shaders.forEach(s -> glDetachShader(program, s));
      shaders.forEach(s -> glDeleteShader(s));
    }
  }

  public void use() {
    glUseProgram(id);
  }

  private int loc(String n) {
    return locations.computeIfAbsent(n, k -> glGetUniformLocation(id, k));
  }

  public void f(String n, double v) {
    glUniform1f(loc(n), (float) v);
  }

  public void i(String n, int v) {
    glUniform1i(loc(n), v);
  }

  public void ui(String n, int v) {
    glUniform1ui(loc(n), v);
  }

  public void v2(String n, double x, double y) {
    glUniform2f(loc(n), (float) x, (float) y);
  }

  public void v4(String n, double x, double y, double z, double w) {
    glUniform4f(loc(n), (float) x, (float) y, (float) z, (float) w);
  }

  public void v3(String n, double x, double y, double z) {
    glUniform3f(loc(n), (float) x, (float) y, (float) z);
  }

  public void require(String... names) {
    for (String n : names)
      if (loc(n) < 0) throw new IllegalArgumentException("Required shader uniform missing: " + n);
  }

  @Override
  public void close() {
    glDeleteProgram(id);
  }
}
