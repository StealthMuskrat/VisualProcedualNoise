package dev.visualengine.time;

import java.nio.charset.StandardCharsets;

public final class Seeds {
    private Seeds() {}
    public static long derive(long root, String node, String channel) {
        long h=0xcbf29ce484222325L ^ root;
        for(byte b:(node+"\0"+channel).getBytes(StandardCharsets.UTF_8)) { h^=b&255; h*=0x100000001b3L; }
        h=(h^(h>>>30))*0xbf58476d1ce4e5b9L;
        h=(h^(h>>>27))*0x94d049bb133111ebL;
        return h^(h>>>31);
    }
}
