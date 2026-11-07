package dev.visualengine.platform;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import dev.visualengine.platform.WindowPlacement.*;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

public final class Window implements AutoCloseable {
  public final long handle;
  private final GLFWErrorCallback error;
  private org.lwjgl.system.Callback debug;
  private final boolean headless;
  private Presentation presentation;
  private dev.visualengine.gpu.DeviceCapabilities capabilities;
  private final DesktopInput input = new DesktopInput();
  private boolean timingReset;
  private WindowedRestore windowedRestore;

  private record WindowedRestore(Bounds bounds, Insets frame, boolean maximized) {}

  public int width, height;

  public Window(int w, int h, boolean headless) {
    this(w, h, headless, Presentation.DEFAULT);
  }

  public Window(int w, int h, boolean headless, Presentation requested) {
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
      capabilities = dev.visualengine.gpu.GpuDevice.install();
      System.err.println(
          "GPU capabilities: " + dev.visualengine.presets.PresetIO.JSON.toJson(capabilities));
      capabilities.requireBaseline();
      applyPresentation(requested);
      if (!headless) {
        input.focus(glfwGetWindowAttrib(handle, GLFW_FOCUSED) == GLFW_TRUE);
        glfwSetWindowFocusCallback(
            handle,
            (win, focused) -> {
              input.focus(focused);
              timingReset = true;
            });
        glfwSetMouseButtonCallback(
            handle,
            (win, button, action, mods) -> {
              if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE) input.release();
            });
        glfwSetWindowIconifyCallback(handle, (win, iconified) -> interruptInput());
        glfwSetFramebufferSizeCallback(handle, (win, width, height) -> timingReset = true);
        glfwSetWindowContentScaleCallback(handle, (win, x, y) -> interruptInput());
      }
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

  public dev.visualengine.gpu.DeviceCapabilities capabilities() {
    return capabilities;
  }

  public void resize() {
    // GLFW switches an unplugged fullscreen window to windowed mode itself. Query the actual
    // attachment, then restore using freshly enumerated monitors; never retain a monitor handle.
    if (!headless && windowedRestore != null && !fullscreen()) restoreWindowed();
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

  public boolean iconified() {
    return !headless && glfwGetWindowAttrib(handle, GLFW_ICONIFIED) == GLFW_TRUE;
  }

  public boolean consumeTimingReset() {
    boolean result = timingReset;
    timingReset = false;
    return result;
  }

  public void interruptInput() {
    input.cancel();
    timingReset = true;
  }

  public DesktopInput.Pointer pointer() {
    if (headless) return DesktopInput.NONE;
    input.focus(glfwGetWindowAttrib(handle, GLFW_FOCUSED) == GLFW_TRUE);
    try (var stack = MemoryStack.stackPush()) {
      var x = stack.mallocDouble(1);
      var y = stack.mallocDouble(1);
      var w = stack.mallocInt(1);
      var h = stack.mallocInt(1);
      glfwGetCursorPos(handle, x, y);
      glfwGetWindowSize(handle, w, h);
      return input.sample(
          x.get(0),
          y.get(0),
          w.get(0),
          h.get(0),
          width,
          height,
          glfwGetMouseButton(handle, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS);
    }
  }

  public boolean tickBrush(boolean requested) {
    return headless ? requested : input.tickBrush(requested);
  }

  /** Native attachment is authoritative, including after monitor removal. */
  public boolean fullscreen() {
    return !headless && glfwGetWindowMonitor(handle) != NULL;
  }

  public boolean toggleFullscreen() {
    if (headless) return false;
    if (fullscreen()) {
      restoreWindowed();
    } else {
      if (windowedRestore != null) restoreWindowed();
      Bounds visible = bounds();
      boolean maximized = glfwGetWindowAttrib(handle, GLFW_MAXIMIZED) == GLFW_TRUE;
      if (maximized) glfwRestoreWindow(handle);
      // Restoring can dispatch native events. Enumerate after it, before using a monitor handle.
      Monitor monitor = WindowPlacement.choose(visible, monitors());
      if (monitor == null) {
        if (maximized) glfwMaximizeWindow(handle);
        throw new IllegalStateException("No connected monitor has a video mode");
      }
      windowedRestore = new WindowedRestore(bounds(), insets(), maximized);
      interruptInput();
      glfwSetWindowMonitor(
          handle,
          monitor.id(),
          0,
          0,
          monitor.bounds().width(),
          monitor.bounds().height(),
          monitor.refreshRate());
      if (glfwGetWindowMonitor(handle) != monitor.id()) {
        restoreWindowed();
        throw new IllegalStateException("The requested fullscreen monitor could not be attached");
      }
      applyPresentation(presentation);
    }
    resize();
    return fullscreen();
  }

  private void restoreWindowed() {
    WindowedRestore saved = windowedRestore;
    if (saved == null) throw new IllegalStateException("No windowed placement to restore");
    Monitor monitor = WindowPlacement.choose(saved.bounds(), monitors());
    Bounds target =
        monitor == null
            ? saved.bounds()
            : WindowPlacement.fit(saved.bounds(), monitor.workArea(), saved.frame());
    interruptInput();
    if (fullscreen()) {
      glfwSetWindowMonitor(
          handle, NULL, target.x(), target.y(), target.width(), target.height(), GLFW_DONT_CARE);
      if (fullscreen())
        throw new IllegalStateException("The fullscreen window could not be restored");
    }
    windowedRestore = null;
    // A DPI/monitor change can change decoration sizes. Fit again after returning to windowed mode.
    if (monitor != null) target = WindowPlacement.fit(saved.bounds(), monitor.workArea(), insets());
    glfwSetWindowSize(handle, target.width(), target.height());
    glfwSetWindowPos(handle, target.x(), target.y());
    if (saved.maximized()) glfwMaximizeWindow(handle);
    applyPresentation(presentation);
  }

  private Bounds bounds() {
    try (var stack = MemoryStack.stackPush()) {
      var x = stack.mallocInt(1);
      var y = stack.mallocInt(1);
      var w = stack.mallocInt(1);
      var h = stack.mallocInt(1);
      glfwGetWindowPos(handle, x, y);
      glfwGetWindowSize(handle, w, h);
      return new Bounds(x.get(0), y.get(0), Math.max(1, w.get(0)), Math.max(1, h.get(0)));
    }
  }

  private Insets insets() {
    try (var stack = MemoryStack.stackPush()) {
      var l = stack.mallocInt(1);
      var t = stack.mallocInt(1);
      var r = stack.mallocInt(1);
      var b = stack.mallocInt(1);
      glfwGetWindowFrameSize(handle, l, t, r, b);
      return new Insets(l.get(0), t.get(0), r.get(0), b.get(0));
    }
  }

  private static List<Monitor> monitors() {
    List<Monitor> result = new ArrayList<>();
    var handles = glfwGetMonitors();
    if (handles == null) return result;
    try (var stack = MemoryStack.stackPush()) {
      var x = stack.mallocInt(1);
      var y = stack.mallocInt(1);
      var w = stack.mallocInt(1);
      var h = stack.mallocInt(1);
      for (int i = 0; i < handles.limit(); i++) {
        long id = handles.get(i);
        var mode = glfwGetVideoMode(id);
        if (mode == null || mode.width() <= 0 || mode.height() <= 0) continue;
        glfwGetMonitorPos(id, x, y);
        Bounds bounds = new Bounds(x.get(0), y.get(0), mode.width(), mode.height());
        glfwGetMonitorWorkarea(id, x, y, w, h);
        Bounds work =
            w.get(0) > 0 && h.get(0) > 0
                ? new Bounds(x.get(0), y.get(0), w.get(0), h.get(0))
                : bounds;
        result.add(new Monitor(id, bounds, work, mode.refreshRate()));
      }
    }
    return result;
  }

  @Override
  public void close() {
    dev.visualengine.gpu.GpuDevice.clear();
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
