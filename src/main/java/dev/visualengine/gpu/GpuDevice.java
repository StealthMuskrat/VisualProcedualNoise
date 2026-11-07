package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.gpu.DeviceCapabilities.*;
import java.util.*;
import org.lwjgl.opengl.*;

/**
 * Snapshot queried once per owned context; ordinary allocation preflight performs no GL queries.
 */
public final class GpuDevice {
  private record Installed(GLCapabilities context, DeviceCapabilities capabilities) {}

  private static final ThreadLocal<Installed> CURRENT = new ThreadLocal<>();

  private GpuDevice() {}

  public static DeviceCapabilities install() {
    DeviceCapabilities capabilities = query();
    CURRENT.set(new Installed(GL.getCapabilities(), capabilities));
    return capabilities;
  }

  public static DeviceCapabilities current() {
    Installed installed = CURRENT.get();
    if (installed == null || installed.context() != GL.getCapabilities())
      throw new IllegalStateException("GPU capabilities require the initialized owning context");
    return installed.capabilities();
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static String textureName(int format) {
    return switch (format) {
      case GL_R32F -> "R32F";
      case GL_RG32F -> "RG32F";
      case GL_RGBA16F -> "RGBA16F";
      case GL_RGBA8 -> "RGBA8";
      case GL_RGBA32F -> "RGBA32F";
      default -> throw new IllegalArgumentException("Unsupported engine texture format: " + format);
    };
  }

  public static DeviceCapabilities query() {
    if (!GL.getCapabilities().OpenGL43)
      throw new IllegalStateException("OpenGL 4.3 capability queries require a 4.3 context");
    Map<String, Long> limits = new TreeMap<>();
    int[] names = {
      GL_MAX_TEXTURE_SIZE,
      GL_MAX_ARRAY_TEXTURE_LAYERS,
      GL_MAX_TEXTURE_IMAGE_UNITS,
      GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS,
      GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS,
      GL_MAX_IMAGE_UNITS,
      GL_MAX_COMPUTE_IMAGE_UNIFORMS,
      GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS,
      GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS,
      GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS,
      GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS,
      GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS,
      GL_MAX_FRAMEBUFFER_WIDTH,
      GL_MAX_FRAMEBUFFER_HEIGHT
    };
    String[] keys = {
      "GL_MAX_TEXTURE_SIZE",
      "GL_MAX_ARRAY_TEXTURE_LAYERS",
      "GL_MAX_TEXTURE_IMAGE_UNITS",
      "GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS",
      "GL_MAX_COMPUTE_TEXTURE_IMAGE_UNITS",
      "GL_MAX_IMAGE_UNITS",
      "GL_MAX_COMPUTE_IMAGE_UNIFORMS",
      "GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS",
      "GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS",
      "GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS",
      "GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS",
      "GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS",
      "GL_MAX_FRAMEBUFFER_WIDTH",
      "GL_MAX_FRAMEBUFFER_HEIGHT"
    };
    for (int i = 0; i < names.length; i++) limits.put(keys[i], (long) glGetInteger(names[i]));
    limits.put(
        "GL_MAX_SHADER_STORAGE_BLOCK_SIZE", glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE));
    int[] viewport = new int[2];
    glGetIntegerv(GL_MAX_VIEWPORT_DIMS, viewport);
    limits.put("GL_MAX_VIEWPORT_WIDTH", (long) viewport[0]);
    limits.put("GL_MAX_VIEWPORT_HEIGHT", (long) viewport[1]);
    String[] axes = {"X", "Y", "Z"};
    for (int i = 0; i < axes.length; i++) {
      limits.put(
          "GL_MAX_COMPUTE_WORK_GROUP_SIZE_" + axes[i],
          (long) glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_SIZE, i));
      limits.put(
          "GL_MAX_COMPUTE_WORK_GROUP_COUNT_" + axes[i],
          (long) glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_COUNT, i));
    }
    Map<String, Format> formats = new TreeMap<>();
    for (int format : new int[] {GL_R32F, GL_RG32F, GL_RGBA16F, GL_RGBA8, GL_RGBA32F})
      formats.put(textureName(format), format(GL_TEXTURE_2D, format));
    formats.put("RGBA16F_ARRAY", format(GL_TEXTURE_2D_ARRAY, GL_RGBA16F));
    int error = glGetError();
    if (error != GL_NO_ERROR)
      throw new IllegalStateException(
          "GPU capability query failed: GL error 0x" + Integer.toHexString(error));
    return new DeviceCapabilities(
        glGetString(GL_VENDOR),
        glGetString(GL_RENDERER),
        glGetString(GL_VERSION),
        glGetString(GL_SHADING_LANGUAGE_VERSION),
        limits,
        formats);
  }

  private static Format format(int target, int format) {
    return new Format(
        glGetInternalformati(target, format, GL_INTERNALFORMAT_SUPPORTED) == GL_TRUE,
        support(glGetInternalformati(target, format, GL_FRAMEBUFFER_RENDERABLE)),
        support(glGetInternalformati(target, format, GL_FILTER)),
        support(glGetInternalformati(target, format, GL_FRAGMENT_TEXTURE)),
        support(glGetInternalformati(target, format, GL_COMPUTE_TEXTURE)),
        support(glGetInternalformati(target, format, GL_SHADER_IMAGE_STORE)),
        support(glGetInternalformati(target, format, GL_FRAMEBUFFER_BLEND)),
        glGetInternalformati(target, format, GL_MAX_WIDTH),
        glGetInternalformati(target, format, GL_MAX_HEIGHT),
        target == GL_TEXTURE_2D_ARRAY ? glGetInternalformati(target, format, GL_MAX_LAYERS) : 1);
  }

  private static Support support(int value) {
    return switch (value) {
      case GL_TRUE, GL_FULL_SUPPORT -> Support.FULL;
      case GL_CAVEAT_SUPPORT -> Support.CAVEAT;
      default -> Support.NONE;
    };
  }
}
