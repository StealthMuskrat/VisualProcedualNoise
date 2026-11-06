package dev.visualengine.gpu;

import dev.visualengine.model.Settings;
import dev.visualengine.time.Playback;
import static org.lwjgl.opengl.GL43C.*;

public final class Renderer implements AutoCloseable {
    private Program material;
    private final ShaderSources sources;
    private final int vao;
    public Renderer(ShaderSources sources) {
        this.sources=sources; vao=glGenVertexArrays(); glBindVertexArray(vao);
        try { reload(); } catch(RuntimeException e) { glDeleteVertexArrays(vao); throw e; }
    }
    public void reload() {
        Program candidate=new Program(sources.read("render/fullscreen.vert"),sources.read("render/material.frag"));
        try { candidate.require("viewport","seed","scale"); }
        catch(RuntimeException e) { candidate.close(); throw e; }
        Program old=material; material=candidate; if(old!=null)old.close();
    }
    public void render(Settings s,double time,int w,int h,double x,double y,boolean brush) {
        glBindFramebuffer(GL_FRAMEBUFFER,0); glViewport(0,0,w,h); glDisable(GL_FRAMEBUFFER_SRGB);
        material.use(); uniforms(material,s,time,w,h,x,y,brush);
        glBindVertexArray(vao); glDrawArrays(GL_TRIANGLES,0,3);
    }
    public static void uniforms(Program p,Settings s,double time,int w,int h,double x,double y,boolean brush) {
        p.v2("viewport",w,h); p.ui("seed",(int)(s.seed^(s.seed>>>32)));
        p.v2("motionPhase",Playback.phase(time,s.get("motion")),Playback.phase(time,s.get("motion")*.37));
        p.f("colorPhase",Playback.phase(time,s.get("colorRate")));
        p.f("time",time); p.i("recipe",s.recipe.ordinal()); p.i("paletteIndex",s.palette); p.i("debugView",s.debug);
        p.v4("pointer",x,y,s.get("radius"),brush?1:0);
        s.values.forEach(p::f);
    }
    @Override public void close() { if(material!=null)material.close(); glDeleteVertexArrays(vao); }
}
