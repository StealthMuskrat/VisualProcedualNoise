package dev.visualengine;

import dev.visualengine.app.Command;
import dev.visualengine.authoring.*;
import dev.visualengine.model.Settings;
import dev.visualengine.ui.AuthoringPanel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class GraphEditUiChecks {
  private static int count;
  private static void check(boolean ok, String text) { count++; if (!ok) throw new AssertionError(text); }
  private static Component find(Container root,String name) {
    for (Component child : root.getComponents()) {
      if (("authoring-"+name).equals(child.getName())) return child;
      if (child instanceof Container container) { Component result = find(container,name); if (result != null) return result; }
    }
    return null;
  }
  private static void click(Container root,String name) { ((JButton)find(root,name)).doClick(); }
  private static void mouse(Component canvas,int kind,int x,int y) {
    canvas.dispatchEvent(new MouseEvent(canvas,kind,1,0,x,y,1,false,MouseEvent.BUTTON1));
  }
  private static void select(Component canvas,int x,int y) {
    mouse(canvas,MouseEvent.MOUSE_PRESSED,x,y); mouse(canvas,MouseEvent.MOUSE_RELEASED,x,y);
  }
  private static void layout(Container root) {
    root.doLayout(); for (Component child : root.getComponents()) if (child instanceof Container container) layout(container);
  }
  private static Settings settings() {
    Settings result = new Settings(); result.graph = AuthoringChecks.noiseGraph();
    result.graph.nodes.set(0,result.graph.nodes.get(0).moved(40,60));
    result.graph.nodes.set(1,result.graph.nodes.get(1).moved(40,180));
    result.graph.nodes.set(2,result.graph.nodes.get(2).moved(250,60));
    result.graph.nodes.set(3,result.graph.nodes.get(3).moved(460,60));
    return result;
  }
  private static EffectGraph submitted(List<Command> commands) { return (EffectGraph)commands.getLast().value(); }
  public static int run() throws Exception {
    count = 0;
    SwingUtilities.invokeAndWait(() -> {
      Settings settings = settings(); List<Command> commands = new ArrayList<>();
      AuthoringPanel panel = new AuthoringPanel(commands::add,settings);
      ((JTabbedPane)find(panel,"tabs")).setSelectedIndex(1);
      panel.setSize(780,900); layout(panel);
      Component canvas = find(panel,"canvas");
      var id = (JTextField)find(panel,"node-id");
      var output = (JTextField)find(panel,"output");
      var comment = (JTextField)find(panel,"node-comment");
      var status = (JTextArea)find(panel,"status");
      select(canvas,50,70); id.setText("domain"); click(panel,"update"); click(panel,"apply-graph");
      check(submitted(commands).nodes.get(2).inputs().getFirst().equals("domain") && id.getText().equals("domain"),
          "editor rename rewires consumers and selects the renamed node");
      var snapshot = submitted(commands);
      click(panel,"undo"); click(panel,"apply-graph");
      check(id.getText().equals("coord") && GraphEdits.same(submitted(commands),settings.graph), "undo restores the pre-rename graph and original selection");
      check(snapshot.nodes.getFirst().id().equals("domain"), "undo preserves previously submitted command snapshots");
      select(canvas,470,70); id.setText("heightField"); click(panel,"update");
      check(output.getText().equals("heightField"), "renaming selected output updates its output field");
      click(panel,"undo"); check(id.getText().equals("field") && output.getText().equals("field"), "output rename undo restores selection and output");
      select(canvas,50,70); comment.setText("draft marker"); click(panel,"update");
      id.setText("time"); click(panel,"update");
      check(status.getText().contains("already exists") && "time".equals(id.getSelectedText()), "duplicate IDs are rejected with the editable name selected");
      click(panel,"apply-graph");
      check(submitted(commands).nodes.getFirst().id().equals("coord") && submitted(commands).nodes.getFirst().comment().equals("draft marker"),
          "duplicate rename leaves the last valid draft and links intact");
      click(panel,"undo");
      check(comment.getText().equals(settings.graph.nodes.getFirst().comment()), "failed rename does not hide the preceding successful edit in undo");
      id.setText("bad id"); click(panel,"update");
      check(status.getText().contains("Node ID must"), "invalid identifier syntax is rejected before draft mutation");
      click(panel,"apply-graph"); check(GraphEdits.same(submitted(commands),settings.graph), "invalid rename preserves the complete draft");
      select(canvas,50,190); click(panel,"preview");
      check(output.getText().equals("time") && submitted(commands).output.equals("time"), "scalar preview submits the selected output");
      click(panel,"undo"); check(output.getText().equals("field") && id.getText().equals("time"), "preview output changes participate in draft undo");
      select(canvas,260,70); int before = commands.size(); click(panel,"preview");
      check(commands.size() == before && output.getText().equals("field") && status.getText().contains("must be scalar"),
          "invalid vector preview leaves model output and scene submission unchanged");
      output.setText("absent"); click(panel,"apply-graph");
      check(commands.size() == before && status.getText().contains("Missing graph output"), "invalid manual output is rejected without submitting");
      output.setText("field"); click(panel,"apply-graph"); check(GraphEdits.same(submitted(commands),settings.graph), "correcting output retains the original valid graph");
      panel.sync(settings); ((JTabbedPane)find(panel,"tabs")).setSelectedIndex(1);
      select(canvas,50,70); comment.setText("one edit"); click(panel,"update");
      for (int i=0;i<40;i++) select(canvas,50,190);
      click(panel,"undo");
      check(id.getText().equals("coord") && comment.getText().equals(settings.graph.nodes.getFirst().comment()),
          "selection clicks consume no undo slots and restore the edited selection");
      panel.sync(settings); select(canvas,50,70);
      mouse(canvas,MouseEvent.MOUSE_PRESSED,50,70);
      mouse(canvas,MouseEvent.MOUSE_DRAGGED,70,90);
      mouse(canvas,MouseEvent.MOUSE_DRAGGED,90,110);
      mouse(canvas,MouseEvent.MOUSE_RELEASED,90,110);
      click(panel,"apply-graph");
      check(submitted(commands).nodes.getFirst().x() == 80 && submitted(commands).nodes.getFirst().y() == 100,
          "drag applies its final coordinates");
      click(panel,"undo"); click(panel,"apply-graph");
      check(GraphEdits.same(submitted(commands),settings.graph), "one undo restores the complete drag");
      select(canvas,470,70); click(panel,"delete");
      check(id.getText().isEmpty() && status.getText().contains("Missing graph output"), "deletion clears stale fields while reporting a missing output");
      click(panel,"undo");
      check(id.getText().equals("field") && output.getText().equals("field"), "undo deletion restores its selection and output");
      panel.sync(settings); select(canvas,50,70);
      for (int i=0;i<33;i++) { comment.setText("revision "+i); click(panel,"update"); }
      for (int i=0;i<33;i++) click(panel,"undo");
      check(comment.getText().equals("revision 0"), "draft history remains bounded at 32 edits");
      Settings full = new Settings(); full.graph.nodes.clear(); full.graph.output="n0";
      for (int i=0;i<64;i++) full.graph.nodes.add(new EffectGraph.Node("n"+i,EffectGraph.Kind.FORMULA,List.of(),"0",40+(i%3)*210,60+(i/3)*100,false,"",""));
      panel.sync(full); before=commands.size(); click(panel,"add");
      check(status.getText().contains("at most 64"), "adding at the root-node bound is rejected before mutation");
      click(panel,"apply-graph"); check(commands.size()==before+1 && submitted(commands).nodes.size()==64, "bounded add preserves all existing nodes");
      panel.sync(settings); select(canvas,50,70); id.setText("domain"); click(panel,"update");
      panel.setSize(780,900); layout(panel);
      BufferedImage image = new BufferedImage(780,900,BufferedImage.TYPE_INT_RGB);
      var graphics=image.createGraphics(); panel.printAll(graphics); graphics.dispose();
      try { Path path=Path.of("build/reports/ui/graph-edits.png"); Files.createDirectories(path.getParent()); ImageIO.write(image,"png",path.toFile()); }
      catch(Exception e) { throw new RuntimeException(e); }
    });
    return count;
  }
}
