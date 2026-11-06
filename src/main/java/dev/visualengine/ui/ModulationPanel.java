package dev.visualengine.ui;

import dev.visualengine.app.Command;
import dev.visualengine.model.Settings;
import dev.visualengine.time.Modulation;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

public final class ModulationPanel extends JPanel {
    private final List<Modulation> mappings=new ArrayList<>();
    private final DefaultListModel<String> model=new DefaultListModel<>();
    public ModulationPanel(Consumer<Command> submit,Settings settings) {
        setLayout(new BorderLayout());settings.modulations.forEach(m->mappings.add(m.copy()));
        JList<String> list=new JList<>(model);add(new JScrollPane(list),BorderLayout.CENTER);
        JPanel fields=new JPanel(new GridLayout(0,2,4,4));
        JComboBox<String> target=new JComboBox<>(Settings.PARAMETERS.stream().map(Settings.Parameter::id).toArray(String[]::new));
        JComboBox<Modulation.Source> source=new JComboBox<>(Modulation.Source.values());JComboBox<Modulation.Mode> mode=new JComboBox<>(Modulation.Mode.values());
        JSpinner period=new JSpinner(new SpinnerNumberModel(8.,.1,3600.,.5)),low=new JSpinner(new SpinnerNumberModel(0.,-100.,100.,.1)),high=new JSpinner(new SpinnerNumberModel(1.,-100.,100.,.1));
        for(var pair:List.of(new Object[]{"Destination",target},new Object[]{"Source",source},new Object[]{"Combination",mode},new Object[]{"Period / duration (s)",period},new Object[]{"Low",low},new Object[]{"High",high})){fields.add(new JLabel((String)pair[0]));fields.add((Component)pair[1]);}
        JTextField keys=new JTextField("0:0, 0.3:1, 0.7:0.4, 1:1");fields.add(new JLabel("Keyframes time:value (0..1)"));fields.add(keys);
        JButton add=new JButton("Add mapping"),remove=new JButton("Remove selected"),apply=new JButton("Apply mappings");
        JLabel error=new JLabel("Mappings use simulation time; outputs are bounded by control ranges.");
        add.addActionListener(e->{try{String[] pairs=keys.getText().trim().split("[,\\s]+");double[] frames=new double[pairs.length*2];for(int i=0;i<pairs.length;i++){String[] pair=pairs[i].split(":");frames[i*2]=Double.parseDouble(pair[0]);frames[i*2+1]=Double.parseDouble(pair[1]);}
            Modulation mapping=new Modulation("mapping"+System.nanoTime(),(String)target.getSelectedItem(),(Modulation.Source)source.getSelectedItem(),(Modulation.Mode)mode.getSelectedItem(),((Number)period.getValue()).doubleValue(),((Number)low.getValue()).doubleValue(),((Number)high.getValue()).doubleValue(),0,frames);mapping.validate();mappings.add(mapping);refresh();}catch(Exception ex){error.setText(ex.getMessage());}});
        remove.addActionListener(e->{int i=list.getSelectedIndex();if(i>=0){mappings.remove(i);refresh();}});
        apply.addActionListener(e->{try{mappings.forEach(Modulation::validate);submit.accept(new Command("modulations",mappings.stream().map(Modulation::copy).toList()));error.setText("Mappings applied");}catch(Exception ex){error.setText(ex.getMessage());}});
        fields.add(add);fields.add(remove);fields.add(apply);JPanel bottom=new JPanel(new BorderLayout());bottom.add(fields,BorderLayout.CENTER);bottom.add(error,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);refresh();
    }
    private void refresh(){model.clear();for(var m:mappings)model.addElement(m.source()+" → "+m.destination()+" · "+m.mode()+" ["+m.low()+", "+m.high()+"]");}
    public void sync(Settings s){mappings.clear();s.modulations.forEach(m->mappings.add(m.copy()));refresh();}
}
