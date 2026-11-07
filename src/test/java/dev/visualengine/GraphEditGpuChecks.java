package dev.visualengine;

import static org.lwjgl.opengl.GL43C.*;

import dev.visualengine.authoring.*;
import dev.visualengine.export.Capture;
import dev.visualengine.gpu.*;
import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import dev.visualengine.time.*;
import java.nio.file.*;
import java.util.*;

public final class GraphEditGpuChecks {
  private static int count;
  private static void check(boolean ok,String text) { count++; if(!ok)throw new AssertionError(text); }
  private static int[] image() { return Capture.read(96,64).getRGB(0,0,96,64,null,0,96); }
  private static boolean same(GpuSnapshot a,GpuSnapshot b) {
    return a.blobs().keySet().equals(b.blobs().keySet()) && a.blobs().entrySet().stream().allMatch(e->Arrays.equals(e.getValue(),b.blobs().get(e.getKey())))
        && PresetIO.JSON.toJsonTree(a.simulation()).equals(PresetIO.JSON.toJsonTree(b.simulation()));
  }
  public static int run() throws Exception {
    count=0;
    var fixture=com.google.gson.JsonParser.parseString(Files.readString(Path.of("build/reports/graph-edit-fixture.json"))).getAsJsonObject();
    for(String prefix:List.of("","group")) {
      Settings original=PresetIO.parse(fixture.get(prefix.isEmpty()?"original":"groupOriginal").toString());
      Settings renamed=PresetIO.parse(fixture.get(prefix.isEmpty()?"renamed":"groupRenamed").toString());
      ControlSignals signals=new ControlSignals();
      try(Renderer renderer=new Renderer(new ShaderSources(null))) {
        renderer.reset(original); Settings active=null;
        for(int tick=1;tick<=16;tick++) { active=signals.tick(original,tick*Playback.DT); renderer.tick(active,tick*Playback.DT,1.5,.2,-.1,true); }
        renderer.render(active,16*Playback.DT,96,64,.2,-.1,true);
        int[] pixels=image(); var state=renderer.snapshot(active,16); long memory=renderer.bytes(); int program=renderer.materialProgramId();
        String signature=renderer.programSignature(); var controlState=signals.snapshot(original);
        Settings renamedActive=signals.frame(renamed);
        renderer.render(renamedActive,16*Playback.DT,96,64,.2,-.1,true);
        check(!signature.equals(renderer.programSignature()) && Arrays.equals(pixels,image()), "renamed "+prefix+" graph changes source identity while preserving every pixel");
        check(same(state,renderer.snapshot(renamedActive,16)) && memory==renderer.bytes() && controlState.equals(signals.snapshot(renamed)),
            "renamed "+prefix+" graph preserves every GPU byte, storage and phase controls");
        try(Renderer baseline=renderer.stageRestore(active,state,16,96,64)) {
          active=signals.tick(original,17*Playback.DT); renamedActive=signals.frame(renamed);
          renderer.tick(renamedActive,17*Playback.DT,1.5,.2,-.1,true); renderer.render(renamedActive,17*Playback.DT,96,64,.2,-.1,true);
          int[] continued=image();
          baseline.tick(active,17*Playback.DT,1.5,.2,-.1,true); baseline.render(active,17*Playback.DT,96,64,.2,-.1,true);
          check(Arrays.equals(continued,image()) && same(renderer.snapshot(renamedActive,17),baseline.snapshot(active,17)),
              "renamed "+prefix+" graph continues with exactly the original image and simulation");
          renderer.render(active,17*Playback.DT,96,64,.2,-.1,true);
          check(renderer.materialProgramId()==program && Arrays.equals(continued,image()), "undoing "+prefix+" rename reuses the original material and restores its output");
        }
      }
    }
    check(glGetError()==GL_NO_ERROR,"graph rename and continuation leave no GL errors");
    return count;
  }
}
