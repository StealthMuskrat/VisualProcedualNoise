package dev.visualengine.gpu;

import java.util.*;
import static org.lwjgl.opengl.GL43C.*;

public final class PassScheduler {
    public enum Access { SAMPLER,IMAGE,STORAGE,VERTEX,INDIRECT }
    public record Pass(String id,Set<String> dependencies,Runnable execute,Set<Access> consumers) {}
    private PassScheduler() {}
    public static int barrierBits(Set<Access> access) {
        int bits=0;for(var a:access)bits|=switch(a){case SAMPLER->GL_TEXTURE_FETCH_BARRIER_BIT;case IMAGE->GL_SHADER_IMAGE_ACCESS_BARRIER_BIT;case STORAGE->GL_SHADER_STORAGE_BARRIER_BIT;case VERTEX->GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;case INDIRECT->GL_COMMAND_BARRIER_BIT;};return bits;
    }
    public static List<Pass> schedule(List<Pass> passes) {
        Map<String,Pass> remaining=new LinkedHashMap<>();for(var p:passes)if(remaining.putIfAbsent(p.id(),p)!=null)throw new IllegalArgumentException("Duplicate pass "+p.id());
        Set<String> done=new HashSet<>();List<Pass> result=new ArrayList<>();
        while(!remaining.isEmpty()) {
            Pass next=remaining.values().stream().filter(p->done.containsAll(p.dependencies())).findFirst().orElseThrow(()->new IllegalArgumentException("Missing pass dependency or same-step cycle"));
            result.add(next);done.add(next.id());remaining.remove(next.id());
        }
        return List.copyOf(result);
    }
    public static void execute(List<Pass> plan){for(var p:plan){p.execute().run();int bits=barrierBits(p.consumers());if(bits!=0)glMemoryBarrier(bits);}}
}
