package dev.visualengine.gpu;

import static org.lwjgl.opengl.GL43C.*;

/** Results are collected only when available; a full ring skips measuring. */
public final class GpuTimer implements AutoCloseable {
    private final int[] queries=new int[8];private final boolean[] pending=new boolean[8];
    private int cursor;private boolean active;private double milliseconds;
    public GpuTimer(){for(int i=0;i<queries.length;i++)queries[i]=glGenQueries();}
    public void begin(){poll();active=!pending[cursor];if(active)glBeginQuery(GL_TIME_ELAPSED,queries[cursor]);}
    public void end(){if(active){glEndQuery(GL_TIME_ELAPSED);pending[cursor]=true;cursor=(cursor+1)%queries.length;active=false;}}
    public void poll(){for(int i=0;i<queries.length;i++)if(pending[i]&&glGetQueryObjecti(queries[i],GL_QUERY_RESULT_AVAILABLE)!=0){milliseconds=glGetQueryObjectui64(queries[i],GL_QUERY_RESULT)/1e6;pending[i]=false;}}
    public double milliseconds(){return milliseconds;}
    @Override public void close(){for(int q:queries)glDeleteQueries(q);}
}
