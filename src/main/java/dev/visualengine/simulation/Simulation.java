package dev.visualengine.simulation;

import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.time.Playback;
import static org.lwjgl.opengl.GL43C.*;

/** Every globally dependent update is a separate dispatch with explicit next-consumer barriers. */
public final class Simulation implements AutoCloseable {
    public final int grid;
    public final Texture flow;
    public final PingPong pigment,feedback,chemicals,age;
    public final Particles particles;
    public final History history;
    private Program flowProgram,pigmentProgram,feedbackProgram,reactionProgram,ageProgram;
    private final ShaderSources sources;
    private double previousX,previousY;
    private boolean chemicalInitialized;
    public Simulation(ShaderSources sources,Settings settings) {
        this.sources=sources;this.grid=settings.grid;
        Texture f=null;PingPong p=null,b=null,c=null,a=null;Particles pt=null;History h=null;
        try {
            f=new Texture(grid,grid,GL_RG32F);p=new PingPong(grid,grid,GL_RGBA16F);b=new PingPong(grid,grid,GL_RGBA16F);
            c=new PingPong(grid,grid,GL_RG32F);a=new PingPong(grid,grid,GL_R32F);
            pt=new Particles(sources,settings.particles,grid);h=new History(settings.historyLayers,Math.min(grid,256));
            flow=f;pigment=p;feedback=b;chemicals=c;age=a;particles=pt;history=h;reload();reset();
        }catch(RuntimeException e){if(f!=null)f.close();if(p!=null)p.close();if(b!=null)b.close();if(c!=null)c.close();if(a!=null)a.close();if(pt!=null)pt.close();if(h!=null)h.close();throw e;}
    }
    public void reload() {
        Program[] next=new Program[5];
        try {
            String[] names={"flow","pigment","feedback","reaction","age"};
            for(int i=0;i<names.length;i++)next[i]=Program.compute(sources.read("simulation/"+names[i]+".comp"));
            next[0].require("flow","seed");next[1].require("dt","previousState");next[2].require("dt","previousState");next[3].require("dt","previousState");next[4].require("dt","previousState");
            particles.reload(sources);
        }catch(RuntimeException e){for(var program:next)if(program!=null)program.close();throw e;}
        closePrograms();flowProgram=next[0];pigmentProgram=next[1];feedbackProgram=next[2];reactionProgram=next[3];ageProgram=next[4];
    }
    public void reset(){flow.clear(0,0,0,0);pigment.clear(0,0,0,0);feedback.clear(0,0,0,0);chemicals.clear(1,0,0,0);age.clear(0,0,0,0);particles.reset();history.reset();chemicalInitialized=false;previousX=previousY=0;}
    private void dispatch(){glDispatchCompute((grid+7)/8,(grid+7)/8,1);glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT|GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);}
    public void update(Settings s,double time,double aspect,double x,double y,boolean brush) {
        if(s.pigmentEnabled||s.feedbackEnabled||s.particlesEnabled||s.debug==3||s.recipe==Settings.Recipe.INTERNAL_WEATHER) {
            flowProgram.use();Renderer.uniforms(flowProgram,s,time,grid,grid,x,y,brush);flowProgram.f("aspect",aspect);flow.image(0);dispatch();
        }
        if(s.pigmentEnabled) {
            pigmentProgram.use();Renderer.uniforms(pigmentProgram,s,time,grid,grid,x,y,brush);
            pigmentProgram.f("dt",Playback.DT);pigmentProgram.f("aspect",aspect);pigmentProgram.v2("previousPointer",previousX,previousY);
            pigment.read().bind(0);flow.bind(1);pigment.write().image(0);dispatch();pigment.swap();
        }
        if(s.reactionEnabled) {
            if(!chemicalInitialized){chemical(s,time,aspect,x,y,false,true);chemical(s,time,aspect,x,y,false,true);chemicalInitialized=true;}
            for(int sub=0;sub<4;sub++)chemical(s,time,aspect,x,y,brush,false);
        }
        if(s.recipe==Settings.Recipe.LOCAL_CLOCKS||s.debug==7) {
            ageProgram.use();Renderer.uniforms(ageProgram,s,time,grid,grid,x,y,brush);ageProgram.f("dt",Playback.DT);ageProgram.f("aspect",aspect);
            age.read().bind(0);age.write().image(0);dispatch();age.swap();
        }
        if(s.particlesEnabled)particles.tick(s,flow,time,aspect);
        previousX=x;previousY=y;
    }
    private void chemical(Settings s,double time,double aspect,double x,double y,boolean brush,boolean initialize) {
        reactionProgram.use();Renderer.uniforms(reactionProgram,s,time,grid,grid,x,y,brush);reactionProgram.f("aspect",aspect);
        reactionProgram.f("dt",30*Playback.DT);reactionProgram.i("initialize",initialize?1:0);
        chemicals.read().bind(0);chemicals.write().image(0);dispatch();chemicals.swap();
    }
    public void visual(Settings s,Texture marks,double time,double aspect) {
        if(!s.feedbackEnabled)return;
        feedbackProgram.use();feedbackProgram.f("dt",Playback.DT);feedbackProgram.f("memory",s.get("feedback"));feedbackProgram.f("aspect",aspect);
        feedback.read().bind(0);marks.bind(1);flow.bind(2);feedback.write().image(0);dispatch();feedback.swap();
    }
    public void bind() {flow.bind(1);pigment.read().bind(2);feedback.read().bind(3);history.bind(4);chemicals.read().bind(5);age.read().bind(6);particles.trails.read().bind(7);}
    public void materialUniforms(Program p,Settings s) {
        p.i("reactionEnabled",s.reactionEnabled?1:0);p.i("particlesEnabled",s.particlesEnabled?1:0);
        p.i("historyCount",history.count());p.i("historyHead",history.head());p.i("historyLayers",Math.max(1,history.layers));
    }
    public long bytes(){return flow.bytes()+pigment.bytes()+feedback.bytes()+chemicals.bytes()+age.bytes()+particles.bytes()+history.bytes();}
    private void closePrograms(){if(flowProgram!=null)flowProgram.close();if(pigmentProgram!=null)pigmentProgram.close();if(feedbackProgram!=null)feedbackProgram.close();if(reactionProgram!=null)reactionProgram.close();if(ageProgram!=null)ageProgram.close();}
    @Override public void close(){flow.close();pigment.close();feedback.close();chemicals.close();age.close();particles.close();history.close();closePrograms();}
}
