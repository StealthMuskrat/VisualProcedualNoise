package dev.visualengine;

import dev.visualengine.authoring.*;
import dev.visualengine.model.*;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import dev.visualengine.gpu.PassScheduler;
import java.util.*;

/** Dependency-free contract checks runnable with a JDK 25 on any platform. */
public final class CoreChecks {
    private static int count;
    private static void check(boolean condition,String label){count++;if(!condition)throw new AssertionError(label);}
    private static void rejects(Runnable action,String label){try{action.run();}catch(IllegalArgumentException e){count++;return;}throw new AssertionError("Accepted "+label);}
    public static void main(String[] args) {
        var f=Formula.compile("sin(p.x * frequency) + cos(p.y)");
        check(f.root().type()==Formula.Type.FLOAT&&f.analyticGradient(),"smooth scalar AD");
        check(Formula.compile("noise3d(vec3(p, t))").dependencies().contains("t"),"time dependency");
        check(!Formula.compile("sin(length(p))").analyticGradient(),"complete-field fallback for unsupported gradient");
        check(Formula.compile("2 + 3 * 4").expression().equals("14.0"),"constant folding with precedence");
        rejects(()->Formula.compile("vec2(1) + vec3(1)"),"vector mismatch");
        rejects(()->Formula.compile("javaExec(p)"),"unknown function");
        rejects(()->Formula.compile("sin(p.x); discard"),"source injection");
        rejects(()->Formula.compile("(".repeat(40)+"1"+")".repeat(40)),"excess nesting");
        rejects(()->Formula.compile("p.z"),"invalid swizzle");
        rejects(()->Formula.compile("1e999"),"nonfinite literal");
        EffectGraph graph=new EffectGraph();graph.nodes.add(new EffectGraph.Node("unused",EffectGraph.Kind.FORMULA,List.of(),"2",0,0,false,"",""));
        check(!GraphCompiler.compile(graph).order().contains("unused"),"unreachable execution removed");
        graph.nodes=new ArrayList<>(List.of(node("a",EffectGraph.Kind.FORMULA,List.of(),"sin(p.x)"),node("b",EffectGraph.Kind.FORMULA,List.of(),"sin(p.x)"),node("sum",EffectGraph.Kind.ADD,List.of("a","b"),"0")));graph.output="sum";
        String source=GraphCompiler.compile(graph).function();check(source.indexOf("sin(")==source.lastIndexOf("sin("),"pure expression shared");
        EffectGraph cycle=new EffectGraph();cycle.nodes=new ArrayList<>(List.of(node("a",EffectGraph.Kind.ADD,List.of("b","b"),"0"),node("b",EffectGraph.Kind.ADD,List.of("a","a"),"0")));cycle.output="a";
        rejects(()->GraphCompiler.compile(cycle),"same-step cycle");
        EffectGraph missing=new EffectGraph();missing.output="missing";rejects(()->GraphCompiler.compile(missing),"missing output");
        Settings s=new Settings();s.seed=Long.MIN_VALUE;s.recipe=Settings.Recipe.CUSTOM;s.graph=graph;s.graphEnabled=true;s.set("warp",1.25);s.locks.add("warp");
        Settings decoded=PresetIO.parse(PresetIO.JSON.toJson(PresetIO.document(s,1234)));
        check(decoded.seed==Long.MIN_VALUE&&decoded.get("warp")==1.25&&decoded.graph.nodes.equals(s.graph.nodes)&&decoded.locks.contains("warp"),"lossless preset roundtrip");
        var legacy=PresetIO.document(s,0);legacy.addProperty("schemaVersion",1);for(String id:List.of("frequency","drift","stripeRate"))legacy.getAsJsonObject("settings").getAsJsonObject("values").remove(id);
        check(PresetIO.parse(legacy.toString()).get("frequency")==2,"explicit v1 authoring migration");
        var bad=PresetIO.document(s,0);bad.getAsJsonObject("settings").getAsJsonObject("values").addProperty("feed",.9);
        rejects(()->PresetIO.parse(bad.toString()),"invalid solver range");
        check(Seeds.derive(7,"flow","x")!=Seeds.derive(7,"flow","y"),"independent stable channels");
        long seed=Seeds.derive(7,"flow","x");Seeds.derive(7,"new node","x");check(seed==Seeds.derive(7,"flow","x"),"node addition leaves seed stable");
        Playback clock=new Playback();check(clock.accumulate(.1,1,false)==8,"bounded interactive catchup");check(clock.accumulate(10,1,true)==0,"paused accumulator");
        for(int i=0;i<120;i++)clock.advance();check(Math.abs(clock.seconds()-1)<1e-12,"integer-tick time");
        rejects(()->PassScheduler.schedule(List.of(new PassScheduler.Pass("a",Set.of("b"),()->{},Set.of()),new PassScheduler.Pass("b",Set.of("a"),()->{},Set.of()))),"pass cycle");
        System.out.println("Core checks passed: "+count);
    }
    private static EffectGraph.Node node(String id,EffectGraph.Kind kind,List<String> inputs,String expression){return new EffectGraph.Node(id,kind,inputs,expression,0,0,false,"","");}
}
