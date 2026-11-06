package dev.visualengine.app;

import java.nio.file.*;
import java.io.*;

public final class Main {
    public static void main(String[] args) {
        if(java.util.Arrays.asList(args).contains("--help")) {
            System.out.println("Procedural Visual Engine 0.1.0\nJava 25 / OpenGL 4.3\n"
                +"--preset file.json --recipe NAME --seed INTEGER --grid 32..1024\n"
                +"--expression FORMULA --shaders DIRECTORY\n"
                +"--headless --frames N --size WIDTHxHEIGHT --capture artwork.png\n"
                +"--export DIRECTORY --frames N --fps 24|30|60|120 --warmup TICKS\n"
                +"--replay take.replay.json --list-recipes\n"
                +"Interactive: Space pause, period step, R reset, F5 reload, F11 fullscreen, Tab controls, Escape exit");return;
        }
        if(java.util.Arrays.asList(args).contains("--list-recipes")){for(var recipe:dev.visualengine.model.Settings.Recipe.values())System.out.println(recipe.name().toLowerCase().replace('_','-'));return;}
        Path data=Path.of(System.getenv().getOrDefault("LOCALAPPDATA",System.getProperty("user.home")),"ProceduralVisualEngine");
        PrintStream console=System.err;
        try {
            Files.createDirectories(data);
            try(var log=new PrintStream(Files.newOutputStream(data.resolve("engine.log")),true,java.nio.charset.StandardCharsets.UTF_8)) {
                System.setErr(log);
                try(Engine engine=new Engine(Options.parse(args),data)) {engine.run();}
                catch(Exception e){e.printStackTrace(log);throw e;}
                finally{System.setErr(console);}
            }
        } catch(Exception e) {
            e.printStackTrace();
            if(!java.awt.GraphicsEnvironment.isHeadless()) javax.swing.JOptionPane.showMessageDialog(null,e.getMessage()+"\nLog: "+data,"Visual Engine error",javax.swing.JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
