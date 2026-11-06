package dev.visualengine.platform;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

public final class Window implements AutoCloseable {
  public final long handle;
  private final GLFWErrorCallback error;
  private org.lwjgl.system.Callback debug;
  private final boolean headless;
  private Presentation presentation;
  public int width, height;

  public Window(int w, int h, boolean headless) {
    this.headless = headless;
    error = GLFWErrorCallback.createPrint(System.err);
    error.set();
    boolean nullEgl =
        headless
            && System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux");
    glfwInitHint(GLFW_PLATFORM, nullEgl ? GLFW_PLATFORM_NULL : GLFW_ANY_PLATFORM);
    if (!glfwInit()) {
      glfwSetErrorCallback(null);
      error.free();
      throw new IllegalStateException(
          "GLFW initialization failed. Install a current graphics driver.");
    }
    glfwDefaultWindowHints();
    glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
    glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
    glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
    glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
    if (nullEgl) glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);
    if (headless) glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
    handle = glfwCreateWindow(w, h, "Procedural Visual Engine", NULL, NULL);
    if (handle == NULL) {
      glfwTerminate();
      glfwSetErrorCallback(null);
      error.free();
      throw new IllegalStateException(
          "OpenGL 4.3 core is required. GLFW could not create a context.");
    }
    try {
      glfwMakeContextCurrent(handle);
      var caps = GL.createCapabilities();
      if (!caps.OpenGL43) {
        throw new IllegalStateException("The active graphics driver does not provide OpenGL 4.3.");
      }
      debug = GLUtil.setupDebugMessageCallback(System.err);
      applyPresentation(Presentation.DEFAULT);
      resize();
      System.out.println(
          "GPU: "
              + GL11.glGetString(GL11.GL_VENDOR)
              + " / "
              + GL11.glGetString(GL11.GL_RENDERER)
              + " / "
              + GL11.glGetString(GL11.GL_VERSION));
    } catch (RuntimeException e) {
      close();
      throw e;
    }
  }

  public void applyPresentation(Presentation requested) {
    if (glfwGetCurrentContext() != handle)
      throw new IllegalStateException("Presentation changes require the owning current context");
    presentation = headless ? Presentation.OFFLINE : java.util.Objects.requireNonNull(requested);
    glfwSwapInterval(presentation.vsync() ? 1 : 0);
  }

  /** Application request; driver settings may override swap synchronization. */
  public Presentation presentation() {
    return presentation;
  }

  public void resize() {
    try (var stack = MemoryStack.stackPush()) {
      var w = stack.mallocInt(1);
      var h = stack.mallocInt(1);
      glfwGetFramebufferSize(handle, w, h);
      width = w.get(0);
      height = h.get(0);
    }
  }

  public boolean down(int key) {
    return glfwGetKey(handle, key) == GLFW_PRESS;
  }

  public boolean closing() {
    return glfwWindowShouldClose(handle);
  }

  public void fullscreen(boolean enabled) {
    long monitor = glfwGetPrimaryMonitor();
    var mode = glfwGetVideoMode(monitor);
    if (mode == null) return;
    glfwSetWindowMonitor(
        handle,
        enabled ? monitor : NULL,
        enabled ? 0 : 100,
        enabled ? 0 : 100,
        enabled ? mode.width() : 1280,
        enabled ? mode.height() : 800,
        enabled ? mode.refreshRate() : GLFW_DONT_CARE);
  }

  @Override
  public void close() {
    if (debug != null) debug.free();
    if (handle != NULL) {
      Callbacks.glfwFreeCallbacks(handle);
      glfwDestroyWindow(handle);
    }
    glfwTerminate();
    glfwSetErrorCallback(null);
    error.free();
    GL.setCapabilities(null);
  }
}
