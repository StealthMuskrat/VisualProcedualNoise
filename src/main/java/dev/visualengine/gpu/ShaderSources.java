package dev.visualengine.gpu;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Root-bounded include resolver; #line source numbers appear in driver diagnostics. */
public final class ShaderSources {
    private final Path root;
    public ShaderSources(Path root) { this.root=root==null?null:root.toAbsolutePath().normalize(); }
    public String read(String name) { return expand(name,new LinkedHashSet<>(),new LinkedHashMap<>()); }
    private String expand(String name, Set<String> active, Map<String,Integer> ids) {
        Path relative=Path.of(name).normalize();
        if(relative.isAbsolute() || relative.startsWith("..") || active.size()>16 || !active.add(relative.toString()))
            throw new IllegalArgumentException("Invalid or cyclic shader include: "+name);
        try {
            String text;
            if(root!=null) {
                Path p=root.resolve(relative).normalize();
                if(!p.startsWith(root) || !p.toRealPath().startsWith(root.toRealPath())) throw new IOException("Include escapes shader root");
                text=Files.readString(p);
            } else try(var in=ShaderSources.class.getResourceAsStream("/shaders/"+relative.toString().replace('\\','/'))) {
                if(in==null)throw new IOException("Missing shader "+name);
                text=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }
            if(text.length()>262144)throw new IOException("Shader source exceeds limit");
            int id=ids.computeIfAbsent(name,k->ids.size()+1), line=0;
            StringBuilder out=new StringBuilder();
            for(String l:text.split("\n",-1)) {
                line++;
                Matcher m=Pattern.compile("\\s*#include \\\"([^\\\"]+)\\\"\\s*").matcher(l);
                if(m.matches()) {
                    String child=relative.getParent()==null?m.group(1):relative.getParent().resolve(m.group(1)).normalize().toString();
                    out.append("\n// ").append(child).append('\n').append(expand(child,active,ids));
                    out.append("\n#line ").append(line+1).append(' ').append(id).append('\n');
                } else out.append(l).append('\n');
            }
            active.remove(relative.toString()); return out.toString();
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
}
