package dev.visualengine.export;

import dev.visualengine.model.Settings;
import dev.visualengine.presets.PresetIO;
import org.lwjgl.system.MemoryUtil;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.io.*;
import static org.lwjgl.opengl.GL43C.*;

public final class Capture {
    private Capture() {}
    /** Explicit still capture may synchronize; encoding and filesystem work happen on the IO worker. */
    public static BufferedImage read(int width,int height) {
        if(width<1||height<1||(long)width*height>33_554_432L)throw new IllegalArgumentException("Capture dimensions exceed 32 megapixels");
        var pixels=MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width,height),4));
        try {
            glPixelStorei(GL_PACK_ALIGNMENT,1); glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                int p=((height-1-y)*width+x)*4;
                image.setRGB(x,y,((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255));
            }
            return image;
        } finally { MemoryUtil.memFree(pixels); }
    }
    public static void write(Path path,BufferedImage image,Settings settings,long tick,String device) throws IOException {
        Path target=path.toAbsolutePath(); Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),".capture-",".png");
        try {
            if(!ImageIO.write(image,"png",temp.toFile()))throw new IOException("PNG encoder unavailable");
            Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
        var doc=PresetIO.document(settings,tick); doc.addProperty("device",device);
        doc.addProperty("captureEncoding","sRGB SDR opaque RGB top-left origin");
        doc.addProperty("width",image.getWidth()); doc.addProperty("height",image.getHeight());
        PresetIO.atomicWrite(Path.of(target+".json"),PresetIO.JSON.toJson(doc));
    }
}
