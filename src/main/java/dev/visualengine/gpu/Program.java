package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import java.util.*;

public final class Program implements AutoCloseable {
  public final int id;
  private final Map<String, Integer> locations = new HashMap<>();

  public Program(String vertex, String fragment) {
    this(new int[] {GL_VERTEX_SHADER, GL_FRAGMENT_SHADER}, new String[] {vertex, fragment});
  }

  public static Program compute(String source) {
    return new Program(new int[] {GL_COMPUTE_SHADER}, new String[] {source});
  }

  private Program(int[] stages, String[] sources) {
    int program = glCreateProgram();
    List<Integer> shaders = new ArrayList<>();
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
    } catch (RuntimeException e) {
      glDeleteProgram(program);
      throw e;
    } finally {
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

  public void require(String... names) {
    for (String n : names)
      if (loc(n) < 0) throw new IllegalArgumentException("Required shader uniform missing: " + n);
  }

  @Override
  public void close() {
    glDeleteProgram(id);
  }
}
