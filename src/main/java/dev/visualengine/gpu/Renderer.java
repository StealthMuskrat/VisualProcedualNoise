package dev.visualengine.gpu;

import dev.visualengine.model.Settings;
import dev.visualengine.time.Playback;
import dev.visualengine.simulation.Simulation;
import static org.lwjgl.opengl.GL43C.*;

public final class Renderer implements AutoCloseable {
    private Program material,finish,bloom;
    private final ShaderSources sources;
    private final int vao;
    private Texture scene,glow,marks;
    private Simulation simulation;
    private final GpuTimer timer=new GpuTimer();
    public Renderer(ShaderSources sources) {
        this.sources=sources;vao=glGenVertexArrays();glBindVertexArray(vao);
        try{reload();}catch(RuntimeException e){close();throw e;}
    }
    public void reload() {
        Program m=null,f=null,b=null;
        try {
            String vertex=sources.read("render/fullscreen.vert");
            m=new Program(vertex,sources.read("render/material.frag"));m.require("viewport","seed","scale");
            f=new Program(vertex,sources.read("post/finish.frag"));f.require("scene","exposure");
            b=new Program(vertex,sources.read("post/bloom.frag"));b.require("scene");
            if(simulation!=null)simulation.reload();
        }catch(RuntimeException e){if(m!=null)m.close();if(f!=null)f.close();if(b!=null)b.close();throw e;}
        if(material!=null)material.close();if(finish!=null)finish.close();if(bloom!=null)bloom.close();material=m;finish=f;bloom=b;
    }
    public void configure(Settings settings) {
        settings.validate();
        if(simulation==null||simulation.grid!=settings.grid||simulation.particles.count!=settings.particles||simulation.history.layers!=settings.historyLayers) {
            Simulation candidate=null;Texture nextMarks=null;
            try {candidate=new Simulation(sources,settings);nextMarks=new Texture(settings.grid,settings.grid,GL_RGBA16F);}
            catch(RuntimeException e){if(candidate!=null)candidate.close();if(nextMarks!=null)nextMarks.close();throw e;}
            if(simulation!=null)simulation.close();if(marks!=null)marks.close();simulation=candidate;marks=nextMarks;
        }
    }
    public void reset(Settings settings){configure(settings);simulation.reset();marks.clear(0,0,0,0);}
    public void tick(Settings s,double time,double aspect,double x,double y,boolean brush) {
        configure(s);simulation.update(s,time,aspect,x,y,brush);
        if(s.feedbackEnabled||needsHistory(s)) {
            marks.target();material.use();uniforms(material,s,time,(int)(marks.height*aspect),marks.height,x,y,brush);
            simulation.materialUniforms(material,s);material.i("feedbackEnabled",0);material.i("pigmentEnabled",s.pigmentEnabled?1:0);simulation.bind();triangle();
            simulation.visual(s,marks,time,aspect);
            if(needsHistory(s))simulation.history.capture(s.feedbackEnabled?simulation.feedback.read():marks,Math.round(time/Playback.DT));
        }
    }
    private static boolean needsHistory(Settings s){return s.recipe==Settings.Recipe.TIME_EXCAVATION||s.recipe==Settings.Recipe.TEMPORAL_PAINTING||s.debug==9;}
    private void ensureOutput(int w,int h) {
        if(scene!=null&&scene.width==w&&scene.height==h)return;
        Texture a=null,b=null;
        try{a=new Texture(w,h,GL_RGBA16F);b=new Texture(Math.max(1,w/4),Math.max(1,h/4),GL_RGBA16F);}
        catch(RuntimeException e){if(a!=null)a.close();if(b!=null)b.close();throw e;}
        if(scene!=null)scene.close();if(glow!=null)glow.close();scene=a;glow=b;
    }
    private void triangle(){glBindVertexArray(vao);glDrawArrays(GL_TRIANGLES,0,3);}
    public void render(Settings s,double time,int w,int h,double x,double y,boolean brush) {
        configure(s);ensureOutput(Math.max(1,(int)(w*s.displayScale)),Math.max(1,(int)(h*s.displayScale)));timer.begin();
        scene.target();glDisable(GL_FRAMEBUFFER_SRGB);glDisable(GL_BLEND);
        material.use();uniforms(material,s,time,w,h,x,y,brush);
        simulation.materialUniforms(material,s);material.i("feedbackEnabled",s.feedbackEnabled?1:0);material.i("pigmentEnabled",s.pigmentEnabled?1:0);simulation.bind();triangle();
        glow.target();bloom.use();scene.bind(0);triangle();
        glBindFramebuffer(GL_FRAMEBUFFER,0);glViewport(0,0,w,h);finish.use();scene.bind(0);glow.bind(1);
        finish.f("exposure",s.get("exposure"));finish.f("bloom",s.get("bloom"));finish.i("debugView",s.debug);triangle();timer.end();
    }
    public double gpuMilliseconds(){return timer.milliseconds();}
    public long bytes(){return (scene==null?0:scene.bytes()+glow.bytes())+(simulation==null?0:simulation.bytes()+marks.bytes());}
    public static void uniforms(Program p,Settings s,double time,int w,int h,double x,double y,boolean brush) {
        p.v2("viewport",w,h);p.ui("seed",(int)(s.seed^(s.seed>>>32)));
        p.v2("motionPhase",Playback.phase(time,s.get("motion")),Playback.phase(time,s.get("motion")*.37));
        p.f("colorPhase",Playback.phase(time,s.get("colorRate")));p.f("time",time);
        p.i("recipe",s.recipe.ordinal());p.i("paletteIndex",s.palette);p.i("debugView",s.debug);
        p.v4("pointer",x,y,s.get("radius"),brush?1:0);s.values.forEach(p::f);
    }
    @Override public void close() {
        if(material!=null)material.close();if(finish!=null)finish.close();if(bloom!=null)bloom.close();
        if(simulation!=null)simulation.close();if(marks!=null)marks.close();if(scene!=null)scene.close();if(glow!=null)glow.close();
        timer.close();glDeleteVertexArrays(vao);
    }
}
