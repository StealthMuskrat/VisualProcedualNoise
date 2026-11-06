package dev.visualengine.app;

import dev.visualengine.platform.Window;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.Playback;
import dev.visualengine.export.Capture;
import dev.visualengine.ui.ControlPanel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

public final class Engine implements AutoCloseable {
    private final Options options;
    private final Path data;
    private final ConcurrentLinkedQueue<Command> commands=new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private final ScheduledExecutorService watcher=Executors.newSingleThreadScheduledExecutor();
    private final Playback clock=new Playback();
    private final Deque<Settings> undo=new ArrayDeque<>();
    private Settings settings=new Settings();
    private Window window;
    private Renderer renderer;
    private ControlPanel controls;
    private boolean step,reset,fullscreen;
    private Path capture;
    private String device;
    private long sourceStamp;
    public Engine(Options options,Path data) { this.options=options;this.data=data; }
    public void run() throws Exception {
        if(options.preset!=null)settings=PresetIO.load(options.preset);
        window=new Window(options.width,options.height,options.headless);
        renderer=new Renderer(new ShaderSources(options.shaders));
        device=glGetString(GL_VENDOR)+" / "+glGetString(GL_RENDERER)+" / "+glGetString(GL_VERSION);
        if(!options.headless)controls=new ControlPanel(this::submit,settings,data);
        glfwSetKeyCallback(window.handle,(win,key,scan,action,mods)-> {
            if(action!=GLFW_PRESS)return;
            switch(key) {
                case GLFW_KEY_ESCAPE -> glfwSetWindowShouldClose(win,true);
                case GLFW_KEY_SPACE -> submit(new Command("pause",null));
                case GLFW_KEY_PERIOD -> submit(new Command("step",null));
                case GLFW_KEY_R -> submit(new Command("reset",null));
                case GLFW_KEY_P -> submit(new Command("randomPalette",null));
                case GLFW_KEY_F5 -> submit(new Command("reload",null));
                case GLFW_KEY_TAB -> {if(controls!=null)controls.show();}
                case GLFW_KEY_F11 -> {fullscreen=!fullscreen;window.fullscreen(fullscreen);}
                default -> {}
            }
        });
        if(options.shaders!=null) {
            sourceStamp=stamp(options.shaders);
            watcher.scheduleWithFixedDelay(()-> {try {long next=stamp(options.shaders);if(next!=sourceStamp){sourceStamp=next;submit(new Command("reload",null));}}catch(Exception e){status(e.getMessage());}},500,500,TimeUnit.MILLISECONDS);
        }
        double last=glfwGetTime();int rendered=0;double[] px={0},py={0};int[] ww={options.width},wh={options.height};
        while(!window.closing()&&(options.frames==0||rendered<options.frames)) {
            glfwPollEvents();window.resize();drain();double now=glfwGetTime(),elapsed=now-last;last=now;
            if(window.width==0||window.height==0){glfwWaitEventsTimeout(.1);continue;}
            if(reset){clock.reset();reset=false;}
            int steps=options.headless?2:clock.accumulate(elapsed,settings.speed,settings.paused);
            if(step){steps=1;step=false;}
            for(int j=0;j<steps;j++)clock.advance();
            glfwGetCursorPos(window.handle,px,py);glfwGetWindowSize(window.handle,ww,wh);
            double x=(px[0]/Math.max(1,ww[0])*2-1)*window.width/window.height,y=1-py[0]/Math.max(1,wh[0])*2;
            boolean brush=glfwGetWindowAttrib(window.handle,GLFW_FOCUSED)==GLFW_TRUE&&glfwGetMouseButton(window.handle,GLFW_MOUSE_BUTTON_LEFT)==GLFW_PRESS;
            renderer.render(settings,clock.seconds(),window.width,window.height,x,y,brush);
            if(capture!=null){capture(capture);capture=null;}
            if(options.capture!=null&&rendered+1==options.frames)capture(options.capture);
            glfwSwapBuffers(window.handle);rendered++;
            if(settings.paused&&!options.headless)glfwWaitEventsTimeout(.05);
        }
        System.out.println("Rendered "+rendered+" frames at tick "+clock.tick());
    }
    private static long stamp(Path root) throws java.io.IOException {
        try(var paths=Files.walk(root)){return paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.getLastModifiedTime(p).toMillis()^Files.size(p);}catch(Exception e){return 0;}}).sum();}
    }
    private void submit(Command c) {commands.add(c);glfwPostEmptyEvent();}
    private void status(String text) {System.out.println(text);if(controls!=null)controls.showStatus(text);}
    private void remember() {undo.push(settings.copy());while(undo.size()>64)undo.removeLast();}
    private void drain() {
        // Coalesce slider drags and reload notifications before touching GPU resources.
        Map<String,Command> edits=new LinkedHashMap<>();Command c;
        while((c=commands.poll())!=null) {
            String key=c.type().equals("parameter")?"parameter:"+((Command.Edit)c.value()).id():c.type();
            if(c.type().equals("parameter")||c.type().equals("reload"))edits.put(key,c);else apply(c);
        }
        edits.values().forEach(this::apply);
    }
    private void apply(Command c) {
        Settings before=settings.copy();
        try {
            switch(c.type()) {
                case "pause" -> settings.paused=!settings.paused;
                case "step" -> {settings.paused=true;step=true;}
                case "reset" -> reset=true;
                case "reload" -> {renderer.reload();status("Engine shaders reloaded");}
                case "parameter" -> {remember();var e=(Command.Edit)c.value();settings.set(e.id(),e.value());}
                case "palette" -> {remember();settings.palette=(int)c.value();}
                case "debug" -> settings.debug=(int)c.value();
                case "seed" -> {remember();settings.seed=(long)c.value();reset=true;}
                case "recipe" -> {remember();settings.recipe=(Settings.Recipe)c.value();reset=true;}
                case "feedbackEnabled" -> settings.feedbackEnabled=(boolean)c.value();
                case "pigmentEnabled" -> settings.pigmentEnabled=(boolean)c.value();
                case "reactionEnabled" -> settings.reactionEnabled=(boolean)c.value();
                case "particlesEnabled" -> settings.particlesEnabled=(boolean)c.value();
                case "grid" -> {settings.grid=((Number)c.value()).intValue();reset=true;}
                case "particles" -> {settings.particles=((Number)c.value()).intValue();reset=true;}
                case "historyLayers" -> {settings.historyLayers=((Number)c.value()).intValue();reset=true;}
                case "speed" -> settings.speed=((Number)c.value()).doubleValue();
                case "displayScale" -> settings.displayScale=((Number)c.value()).doubleValue();
                case "lock" -> {var entry=(Map.Entry<?,?>)c.value();if((boolean)entry.getValue())settings.locks.add((String)entry.getKey());else settings.locks.remove(entry.getKey());}
                case "undo" -> {if(!undo.isEmpty()){settings=undo.pop();if(controls!=null)controls.sync(settings);}}
                case "randomSeed","randomPalette","randomMotion","randomComposition" -> {
                    remember();Random random=new Random(System.nanoTime());
                    if(c.type().equals("randomSeed"))settings.seed=random.nextLong();
                    else if(c.type().equals("randomPalette"))settings.palette=random.nextInt(4);
                    else for(String id:c.type().equals("randomMotion")?List.of("motion","colorRate"):List.of("scale","warp","detail","role"))
                        if(!settings.locks.contains(id)){var p=Settings.PARAMETERS.stream().filter(d->d.id().equals(id)).findFirst().orElseThrow();settings.set(id,p.minimum()+(p.maximum()-p.minimum())*random.nextDouble());}
                    reset=c.type().equals("randomSeed");if(controls!=null)controls.sync(settings);
                }
                case "save" -> {Settings snapshot=settings.copy();long tick=clock.tick();enqueueIO(()->{PresetIO.save((Path)c.value(),snapshot,tick);status("Preset saved");});}
                case "load" -> enqueueIO(()->submit(new Command("loaded",PresetIO.load((Path)c.value()))));
                case "loaded" -> {remember();settings=(Settings)c.value();reset=true;if(controls!=null)controls.sync(settings);status("Preset loaded; state restarted");}
                case "capture" -> capture=(Path)c.value();
                default -> throw new IllegalArgumentException("Unknown command "+c.type());
            }
            settings.validate();
        } catch(Exception e){settings=before;if(controls!=null)controls.sync(settings);System.err.println(e);status(e.getMessage());}
    }
    @FunctionalInterface private interface IOAction {void run() throws Exception;}
    private void enqueueIO(IOAction action) {
        try{io.execute(()->{try{action.run();}catch(Exception e){System.err.println(e);status(e.getMessage());}});}
        catch(RejectedExecutionException e){status("File queue is full; wait for the current capture");}
    }
    private void capture(Path path) {
        var image=Capture.read(window.width,window.height);Settings snapshot=settings.copy();long tick=clock.tick();
        enqueueIO(()->{Capture.write(path,image,snapshot,tick,device);status("Captured "+path);});
    }
    @Override public void close() {
        watcher.shutdownNow();io.shutdown();
        try{if(!io.awaitTermination(30,TimeUnit.SECONDS))io.shutdownNow();}catch(InterruptedException e){Thread.currentThread().interrupt();io.shutdownNow();}
        if(controls!=null)controls.close();if(renderer!=null)renderer.close();if(window!=null)window.close();
    }
}
