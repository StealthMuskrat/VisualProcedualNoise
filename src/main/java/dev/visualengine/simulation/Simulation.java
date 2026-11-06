package dev.visualengine.simulation;

import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.time.Playback;
import static org.lwjgl.opengl.GL43C.*;

/** Explicit pass order: flow -> pigment -> (later chemical/particles) -> visual feedback. */
public final class Simulation implements AutoCloseable {
    public final int grid;
    public final Texture flow;
    public final PingPong pigment,feedback;
    private Program flowProgram,pigmentProgram,feedbackProgram;
    private final ShaderSources sources;
    private double previousX,previousY;
    public Simulation(ShaderSources sources,int grid) {
        this.sources=sources;this.grid=grid;
        Texture f=null;PingPong p=null,b=null;
        try {
            f=new Texture(grid,grid,GL_RG32F);p=new PingPong(grid,grid,GL_RGBA16F);b=new PingPong(grid,grid,GL_RGBA16F);
            flow=f;pigment=p;feedback=b;reload();
        } catch(RuntimeException e){if(f!=null)f.close();if(p!=null)p.close();if(b!=null)b.close();throw e;}
    }
    public void reload() {
        Program f=null,p=null,b=null;
        try {
            f=Program.compute(sources.read("simulation/flow.comp"));p=Program.compute(sources.read("simulation/pigment.comp"));b=Program.compute(sources.read("simulation/feedback.comp"));
            f.require("flow","seed");p.require("dt","previousState");b.require("dt","previousState");
        } catch(RuntimeException e){if(f!=null)f.close();if(p!=null)p.close();if(b!=null)b.close();throw e;}
        if(flowProgram!=null)flowProgram.close();if(pigmentProgram!=null)pigmentProgram.close();if(feedbackProgram!=null)feedbackProgram.close();
        flowProgram=f;pigmentProgram=p;feedbackProgram=b;
    }
    public void reset(){flow.clear(0,0,0,0);pigment.clear(0,0,0,0);feedback.clear(0,0,0,0);previousX=previousY=0;}
    private void dispatch(){glDispatchCompute((grid+7)/8,(grid+7)/8,1);glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);}
    public void update(Settings s,double time,double aspect,double x,double y,boolean brush) {
        flowProgram.use();Renderer.uniforms(flowProgram,s,time,grid,grid,x,y,brush);flowProgram.f("aspect",aspect);flow.image(0);dispatch();
        if(s.pigmentEnabled) {
            pigmentProgram.use();Renderer.uniforms(pigmentProgram,s,time,grid,grid,x,y,brush);
            pigmentProgram.f("dt",Playback.DT);pigmentProgram.f("aspect",aspect);
            pigmentProgram.v2("previousPointer",previousX,previousY);pigment.read().bind(0);flow.bind(1);pigment.write().image(0);dispatch();pigment.swap();
        }
        previousX=x;previousY=y;
    }
    public void visual(Settings s,Texture marks,double time,double aspect) {
        if(!s.feedbackEnabled)return;
        feedbackProgram.use();feedbackProgram.f("dt",Playback.DT);feedbackProgram.f("memory",s.get("feedback"));feedbackProgram.f("aspect",aspect);
        feedback.read().bind(0);marks.bind(1);flow.bind(2);feedback.write().image(0);dispatch();feedback.swap();
    }
    public void bind() {flow.bind(1);pigment.read().bind(2);feedback.read().bind(3);}
    public long bytes(){return flow.bytes()+pigment.bytes()+feedback.bytes();}
    @Override public void close() {
        flow.close();pigment.close();feedback.close();
        if(flowProgram!=null)flowProgram.close();if(pigmentProgram!=null)pigmentProgram.close();if(feedbackProgram!=null)feedbackProgram.close();
    }
}
