package dev.visualengine.app;

import dev.visualengine.platform.Window;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.time.Playback;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.io.*;
import static org.lwjgl.glfw.GLFW.*;

public final class Main {
    public static void main(String[] args) {
        Path data=Path.of(System.getenv().getOrDefault("LOCALAPPDATA",System.getProperty("user.home")),"ProceduralVisualEngine");
        try {
            Files.createDirectories(data);
            try(var log=new PrintStream(Files.newOutputStream(data.resolve("engine.log")),true,java.nio.charset.StandardCharsets.UTF_8)) {
                System.setErr(log); run(args);
            }
        } catch(Exception e) {
            e.printStackTrace();
            if(!java.awt.GraphicsEnvironment.isHeadless()) javax.swing.JOptionPane.showMessageDialog(null,e.getMessage()+"\nLog: "+data,"Visual Engine startup error",javax.swing.JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
    private static void run(String[] args) {
        boolean headless=java.util.Arrays.asList(args).contains("--headless");
        int frames=0,w=1280,h=800; Path shaderRoot=null;
        for(int i=0;i<args.length;i++) switch(args[i]) {
            case "--frames" -> frames=Integer.parseInt(args[++i]);
            case "--size" -> { var size=args[++i].split("x"); w=Integer.parseInt(size[0]); h=Integer.parseInt(size[1]); }
            case "--shaders" -> shaderRoot=Path.of(args[++i]);
            case "--headless" -> {}
            default -> throw new IllegalArgumentException("Unknown option: "+args[i]);
        }
        if(headless&&frames<=0)throw new IllegalArgumentException("Headless mode requires --frames > 0");
        Settings settings=new Settings(); Playback clock=new Playback();
        try(Window window=new Window(w,h,headless); Renderer renderer=new Renderer(new ShaderSources(shaderRoot))) {
            boolean[] single={false},fullscreen={false};
            glfwSetKeyCallback(window.handle,(win,key,scan,action,mods)-> {
                if(action!=GLFW_PRESS)return;
                switch(key) {
                    case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win,true);
                    case GLFW_KEY_SPACE -> settings.paused=!settings.paused;
                    case GLFW_KEY_PERIOD -> single[0]=true;
                    case GLFW_KEY_R -> clock.reset();
                    case GLFW_KEY_P -> settings.palette=(settings.palette+1)%4;
                    case GLFW_KEY_F11 -> { fullscreen[0]=!fullscreen[0]; window.fullscreen(fullscreen[0]); }
                    case GLFW_KEY_F5 -> { try { renderer.reload(); } catch(RuntimeException e) { System.err.println(e.getMessage()); } }
                    default -> {}
                }
            });
            double last=GLFW.glfwGetTime(); int rendered=0;
            double[] px={0},py={0}; int[] ww={w},wh={h};
            while(!window.closing() && (frames==0||rendered<frames)) {
                glfwPollEvents(); window.resize(); double now=glfwGetTime(),elapsed=now-last; last=now;
                if(window.width==0||window.height==0) { glfwWaitEventsTimeout(.1); continue; }
                int steps=headless?2:clock.accumulate(elapsed,settings.speed,settings.paused);
                if(single[0]) { steps=1; single[0]=false; }
                for(int j=0;j<steps;j++)clock.advance();
                glfwGetCursorPos(window.handle,px,py); glfwGetWindowSize(window.handle,ww,wh);
                double x=(px[0]/Math.max(1,ww[0])*2-1)*window.width/window.height;
                double y=1-py[0]/Math.max(1,wh[0])*2;
                boolean brush=glfwGetWindowAttrib(window.handle,GLFW_FOCUSED)==GLFW_TRUE && glfwGetMouseButton(window.handle,GLFW_MOUSE_BUTTON_LEFT)==GLFW_PRESS;
                renderer.render(settings,clock.seconds(),window.width,window.height,x,y,brush);
                glfwSwapBuffers(window.handle); rendered++;
                if(settings.paused&&!headless)glfwWaitEventsTimeout(.1);
            }
            System.out.println("Rendered "+rendered+" frames at tick "+clock.tick());
        }
    }
}
