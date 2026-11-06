package dev.visualengine.app;

import java.nio.file.*;
import java.io.*;

public final class Main {
    public static void main(String[] args) {
        Path data=Path.of(System.getenv().getOrDefault("LOCALAPPDATA",System.getProperty("user.home")),"ProceduralVisualEngine");
        try {
            Files.createDirectories(data);
            try(var log=new PrintStream(Files.newOutputStream(data.resolve("engine.log")),true,java.nio.charset.StandardCharsets.UTF_8)) {
                System.setErr(log);
                try(Engine engine=new Engine(Options.parse(args),data)) {engine.run();}
            }
        } catch(Exception e) {
            e.printStackTrace();
            if(!java.awt.GraphicsEnvironment.isHeadless()) javax.swing.JOptionPane.showMessageDialog(null,e.getMessage()+"\nLog: "+data,"Visual Engine error",javax.swing.JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
