package dev.visualengine.presets;

import com.google.gson.*;
import dev.visualengine.model.Settings;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class PresetIO {
    public static final String REVISION="gradient-v1-simulation-v1";
    public static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private PresetIO() {}
    public static JsonObject document(Settings settings,long tick) {
        settings.validate(); JsonObject root=new JsonObject();
        root.addProperty("schemaVersion",3); root.addProperty("engineVersion","0.1.0");
        root.addProperty("algorithmRevision",REVISION); root.addProperty("rootSeed",Long.toString(settings.seed));
        root.addProperty("statePolicy","restart"); root.addProperty("referenceTick",tick);
        root.addProperty("fixedTickSeconds",1./120); root.add("settings",JSON.toJsonTree(settings));
        root.addProperty("referenceMotionClock",settings.motionClock);root.addProperty("referenceColorClock",settings.colorClock);
        // Root seed is deliberately lossless even with JSON consumers using doubles.
        root.getAsJsonObject("settings").remove("seed");
        return root;
    }
    public static Settings parse(String text) {
        if(text.length()>1_048_576)throw new IllegalArgumentException("Preset exceeds 1 MiB");
        JsonObject root=JsonParser.parseString(text).getAsJsonObject();
        if(!root.has("schemaVersion") || root.get("schemaVersion").getAsInt()<1 || root.get("schemaVersion").getAsInt()>3)
            throw new IllegalArgumentException("Unsupported preset schema. This build accepts versions 1..3.");
        if(!root.has("algorithmRevision") || !REVISION.equals(root.get("algorithmRevision").getAsString()))
            throw new IllegalArgumentException("Preset algorithm revision is incompatible; explicit migration is required.");
        Settings s=JSON.fromJson(root.get("settings"),Settings.class);
        if(root.get("schemaVersion").getAsInt()==1)for(String id:List.of("frequency","drift","stripeRate"))s.values.putIfAbsent(id,new Settings().get(id));
        if(root.get("schemaVersion").getAsInt()<3)s.values.putIfAbsent("agitation",.5);
        s.seed=Long.parseLong(root.get("rootSeed").getAsString());
        if(s.values==null || s.locks==null || s.recipe==null || s.expression==null || s.name==null
                || !s.values.keySet().equals(new Settings().values.keySet())) throw new IllegalArgumentException("Preset controls are incomplete");
        if(s.name.length()>256 || s.expression.length()>8192)throw new IllegalArgumentException("Preset text exceeds limit");
        s.validate(); return s;
    }
    public static Settings load(Path path) throws IOException {
        if(Files.size(path)>1_048_576)throw new IOException("Preset exceeds 1 MiB");
        return parse(Files.readString(path));
    }
    public static void save(Path path,Settings s,long tick) throws IOException {
        String text=JSON.toJson(document(s,tick)); parse(text); atomicWrite(path,text);
    }
    public static void atomicWrite(Path path,String text) throws IOException {
        Path target=path.toAbsolutePath(); Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),".visual-engine-",".tmp");
        try {
            Files.writeString(temp,text);
            try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
