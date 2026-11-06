package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.model.Settings;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/** A separate native inspector leaves the entire GLFW viewport available to artwork and input. */
public final class ControlPanel implements AutoCloseable {
    private JFrame frame;
    private final Consumer<Command> submit;
    private final Path data;
    private final JLabel status=new JLabel("Ready");
    private final Map<String,JSlider> sliders=new HashMap<>();
    private final Map<String,JCheckBox> flags=new HashMap<>();
    private JComboBox<Settings.Recipe> recipes;
    private JComboBox<String> palette,debug;
    private boolean syncing;
    public ControlPanel(Consumer<Command> submit,Settings initial,Path data) {
        this.submit=submit; this.data=data;
        try { SwingUtilities.invokeAndWait(()->build(initial)); }
        catch(Exception e) { throw new IllegalStateException("Could not open controls",e); }
    }
    private JPanel column() { JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setBorder(BorderFactory.createEmptyBorder(10,12,10,12));return p; }
    private void button(JPanel p,String label,String type) { JButton b=new JButton(label);b.addActionListener(e->submit.accept(new Command(type,null)));p.add(b); }
    private void build(Settings s) {
        frame=new JFrame("Visual Engine Controls");frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        JPanel scene=column();
        recipes=new JComboBox<>(Settings.Recipe.values()); recipes.addActionListener(e->{if(!syncing)submit.accept(new Command("recipe",recipes.getSelectedItem()));});scene.add(recipes);
        palette=new JComboBox<>(new String[]{"Opal","Botanical","Ember","Glacier"});palette.addActionListener(e->{if(!syncing)submit.accept(new Command("palette",palette.getSelectedIndex()));});scene.add(palette);
        JTextField seed=new JTextField(Long.toString(s.seed),20); JButton applySeed=new JButton("Apply seed and reset");
        applySeed.addActionListener(e->{try{submit.accept(new Command("seed",Long.parseLong(seed.getText().trim())));}catch(NumberFormatException ex){showStatus("Seed must be a signed 64-bit integer");}}); scene.add(seed);scene.add(applySeed);
        for(String[] b:new String[][]{{"Play / pause","pause"},{"Single tick","step"},{"Reset simulation","reset"},{"Undo controls","undo"},{"Randomize seed","randomSeed"},{"Randomize palette","randomPalette"},{"Randomize motion","randomMotion"},{"Randomize composition","randomComposition"},{"Reload engine resources","reload"}})button(scene,b[0],b[1]);
        for(String[] f:new String[][]{{"Feedback","feedbackEnabled"},{"Pigment transport","pigmentEnabled"},{"Reaction diffusion","reactionEnabled"},{"Particles and trails","particlesEnabled"}}) {
            JCheckBox box=new JCheckBox(f[0]); box.addActionListener(e->{if(!syncing)submit.accept(new Command(f[1],box.isSelected()));});flags.put(f[1],box);scene.add(box);
        }
        JPanel params=column();String group="";
        for(var p:Settings.PARAMETERS) {
            if(!p.group().equals(group)) {group=p.group();params.add(new JLabel(group));}
            JLabel label=new JLabel(p.label());params.add(label);
            JSlider slider=new JSlider(0,1000); slider.addChangeListener(e->{
                double value=p.minimum()+(p.maximum()-p.minimum())*slider.getValue()/1000.;
                label.setText(p.label()+"  "+String.format(Locale.ROOT,"%.3f",value));
                if(!syncing)submit.accept(new Command("parameter",new Command.Edit(p.id(),value)));
            });sliders.put(p.id(),slider);params.add(slider);
            JCheckBox lock=new JCheckBox("Lock for randomization");lock.addActionListener(e->submit.accept(new Command("lock",Map.entry(p.id(),lock.isSelected()))));params.add(lock);
        }
        JPanel quality=column();
        debug=new JComboBox<>(new String[]{"Artwork","Raw noise","Cell ID","Flow","Chemical A","Chemical B","Pigment","Age","Normals","History validity","Linear HDR"});
        debug.addActionListener(e->{if(!syncing)submit.accept(new Command("debug",debug.getSelectedIndex()));});quality.add(debug);
        spinner(quality,"Simulation grid (resets state)","grid",s.grid,32,1024,32);
        spinner(quality,"Particle count (resets state)","particles",s.particles,0,262144,1024);
        spinner(quality,"History layers (resets state)","historyLayers",s.historyLayers,0,32,1);
        spinner(quality,"Playback speed","speed",s.speed,0.,4.,.1);
        spinner(quality,"Display scale","displayScale",s.displayScale,.25,1.,.25);
        JPanel files=column();
        fileButton(files,"Save configuration preset","save","study.json",true);
        fileButton(files,"Load configuration preset (restart)","load","study.json",false);
        fileButton(files,"Capture artwork PNG","capture","artwork.png",true);
        files.add(new JLabel("Presets restart state. Captures record the reference tick."));
        JTabbedPane tabs=new JTabbedPane();tabs.addTab("Scene",new JScrollPane(scene));tabs.addTab("Controls",new JScrollPane(params));tabs.addTab("Quality",new JScrollPane(quality));tabs.addTab("Files",new JScrollPane(files));
        frame.add(tabs,BorderLayout.CENTER);frame.add(status,BorderLayout.SOUTH);frame.setSize(480,820);frame.setLocationByPlatform(true);syncNow(s);frame.setVisible(true);
    }
    private void spinner(JPanel p,String label,String id,Number initial,Comparable<?> min,Comparable<?> max,Number step) {
        p.add(new JLabel(label)); JSpinner control=new JSpinner(new SpinnerNumberModel(initial,(Comparable)min,(Comparable)max,step));
        control.addChangeListener(e->{if(!syncing)submit.accept(new Command(id,control.getValue()));});p.add(control);
    }
    private void fileButton(JPanel p,String label,String type,String filename,boolean save) {
        JButton b=new JButton(label);b.addActionListener(e->{JFileChooser chooser=new JFileChooser(data.toFile());chooser.setSelectedFile(data.resolve(filename).toFile());
            if((save?chooser.showSaveDialog(frame):chooser.showOpenDialog(frame))==JFileChooser.APPROVE_OPTION)submit.accept(new Command(type,chooser.getSelectedFile().toPath()));});p.add(b);
    }
    private void syncNow(Settings s) {
        syncing=true;
        try {
            recipes.setSelectedItem(s.recipe);palette.setSelectedIndex(s.palette);debug.setSelectedIndex(s.debug);
            for(var p:Settings.PARAMETERS)sliders.get(p.id()).setValue((int)Math.round(1000*(s.get(p.id())-p.minimum())/(p.maximum()-p.minimum())));
            flags.get("feedbackEnabled").setSelected(s.feedbackEnabled);flags.get("pigmentEnabled").setSelected(s.pigmentEnabled);
            flags.get("reactionEnabled").setSelected(s.reactionEnabled);flags.get("particlesEnabled").setSelected(s.particlesEnabled);
        } finally { syncing=false; }
    }
    public void sync(Settings s) { Settings copy=s.copy();SwingUtilities.invokeLater(()->syncNow(copy)); }
    public void showStatus(String text) { SwingUtilities.invokeLater(()->status.setText(text.replace('\n',' '))); }
    public void show() { SwingUtilities.invokeLater(()->frame.setVisible(true)); }
    @Override public void close() { SwingUtilities.invokeLater(()->frame.dispose()); }
}
