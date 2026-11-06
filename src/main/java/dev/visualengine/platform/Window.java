package dev.visualengine.platform;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class Window implements AutoCloseable {
    public final long handle;
    private final GLFWErrorCallback error;
    private org.lwjgl.system.Callback debug;
    public int width, height;
    public Window(int w, int h, boolean headless) {
        error=GLFWErrorCallback.createPrint(System.err); error.set();
        if(headless) glfwInitHint(GLFW_PLATFORM, GLFW_PLATFORM_NULL);
        if(!glfwInit()) throw new IllegalStateException("GLFW initialization failed. Install a current graphics driver.");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT,GLFW_TRUE);
        if(headless) { glfwWindowHint(GLFW_CONTEXT_CREATION_API,GLFW_EGL_CONTEXT_API); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE); }
        handle=glfwCreateWindow(w,h,"Procedural Visual Engine",NULL,NULL);
        if(handle==NULL) { glfwTerminate(); error.free(); throw new IllegalStateException("OpenGL 4.3 core is required. GLFW could not create a context."); }
        glfwMakeContextCurrent(handle);
        var caps=GL.createCapabilities();
        if(!caps.OpenGL43) { close(); throw new IllegalStateException("The active graphics driver does not provide OpenGL 4.3."); }
        debug=GLUtil.setupDebugMessageCallback(System.err);
        glfwSwapInterval(headless?0:1); resize();
        System.out.println("GPU: "+GL11.glGetString(GL11.GL_VENDOR)+" / "+GL11.glGetString(GL11.GL_RENDERER)+" / "+GL11.glGetString(GL11.GL_VERSION));
    }
    public void resize() {
        try(var stack=MemoryStack.stackPush()) {
            var w=stack.mallocInt(1); var h=stack.mallocInt(1);
            glfwGetFramebufferSize(handle,w,h); width=w.get(0); height=h.get(0);
        }
    }
    public boolean down(int key) { return glfwGetKey(handle,key)==GLFW_PRESS; }
    public boolean closing() { return glfwWindowShouldClose(handle); }
    public void fullscreen(boolean enabled) {
        long monitor=glfwGetPrimaryMonitor(); var mode=glfwGetVideoMode(monitor);
        if(mode==null)return;
        glfwSetWindowMonitor(handle, enabled?monitor:NULL, enabled?0:100, enabled?0:100,
            enabled?mode.width():1280, enabled?mode.height():800, enabled?mode.refreshRate():GLFW_DONT_CARE);
    }
    @Override public void close() {
        if(debug!=null)debug.free();
        if(handle!=NULL) { Callbacks.glfwFreeCallbacks(handle); glfwDestroyWindow(handle); }
        glfwTerminate(); error.free();
    }
}
