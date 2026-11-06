package dev.visualengine.authoring;

import dev.visualengine.presets.PresetIO;
import dev.visualengine.model.Settings;
import java.nio.file.*;
import java.io.*;

/** Portable self-contained data manifest; imports do not load code, DLLs or external dependencies. */
public final class EffectPackage {
    public int effectApiVersion=1;
    public String id="local.effect",version="1.0.0",author="",license="",coordinates="centered aspect-correct domain",time="seconds since reset; phase is bounded radians";
    public String authoring="expression",expression="sin(length(p) * frequency - phase)",statePolicy="restart";
    public EffectGraph graph;
    public java.util.Map<String,Double> parameters=new java.util.LinkedHashMap<>(new Settings().values);
    public void validate() {
        if(effectApiVersion!=1||id==null||!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}")||version==null||version.length()>32||author==null||license==null||author.length()>256||license.length()>1024)
            throw new IllegalArgumentException("Invalid effect manifest identity");
        if(!"restart".equals(statePolicy))throw new IllegalArgumentException("Effect state policy must be restart");
        if(parameters==null||parameters.size()>Settings.PARAMETERS.size())throw new IllegalArgumentException("Invalid effect parameters");
        Settings checked=new Settings();parameters.forEach(checked::set);
        if("graph".equals(authoring))GraphCompiler.compile(graph);
        else if("expression".equals(authoring)) {if(Formula.compile(expression).root().type()!=Formula.Type.FLOAT)throw new IllegalArgumentException("Field expression must be scalar");}
        else throw new IllegalArgumentException("Unsupported authoring mode");
    }
    public static EffectPackage load(Path path) throws IOException {
        if(Files.size(path)>1_048_576)throw new IOException("Effect package exceeds 1 MiB");
        EffectPackage effect=PresetIO.JSON.fromJson(Files.readString(path),EffectPackage.class);effect.validate();return effect;
    }
    public void apply(Settings s) {
        validate();s.expression=expression==null?"0":expression;s.graph=graph==null?new EffectGraph():graph.copy();s.graphEnabled="graph".equals(authoring);s.recipe=Settings.Recipe.CUSTOM;s.name=id+" "+version;parameters.forEach(s::set);resolveResources(s);
    }
    public static void resolveResources(Settings s) {
        if(s.recipe!=Settings.Recipe.CUSTOM)return;
        if(s.graphEnabled){var resources=GraphCompiler.compile(s.graph).resources();if(resources.contains("previousVisualColor"))s.feedbackEnabled=true;if(resources.contains("pigment"))s.pigmentEnabled=true;}
        else {var compiled=Formula.compile(s.expression);if(compiled.root().type()!=Formula.Type.FLOAT)throw new IllegalArgumentException("Field expression must be scalar");if(s.loopSeconds>0&&compiled.dependencies().contains("t"))throw new IllegalArgumentException("Analytic loops require phase instead of unbounded formula time");}
    }
    public void save(Path path) throws IOException {validate();PresetIO.atomicWrite(path,PresetIO.JSON.toJson(this));}
}
