package pzvr;

import java.lang.instrument.Instrumentation;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.zip.CRC32;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;
import zombie.core.opengl.RenderThread;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;

/** Read-only diagnostic of our own live textures; no game code transformation. */
public final class LayerProbe {
    public static void agentmain(String args,Instrumentation unused) throws Exception {
        Path out=Path.of(args); Files.createDirectories(out);
        Thread worker=new Thread(()-> {
            try {
                StringBuilder report=new StringBuilder("sample,frames,worldCRC,finalCRC,uiCRC,depthCRC,stereoCRC,medianDepth,uiComposeMAE,uiFlipMAE\n");
                for(int i=0;i<12;i++) {
                    Map<String,Pixels> layers=new LinkedHashMap<>();
                    long[] frames=new long[1];
                    RenderThread.invokeOnRenderContext(()-> {
                        try {
                            Object driver=Class.forName("pzvr.LiveBridge").getField("driver").get(null);
                            if(driver==null) throw new IllegalStateException("Live driver not running.");
                            Field f=driver.getClass().getDeclaredField("frames"); f.setAccessible(true); frames[0]=f.getLong(driver);
                            for(String name:List.of("worldTexture","finalTexture","savedUiId","depthTexture","stereoTexture")) {
                                f=driver.getClass().getDeclaredField(name); f.setAccessible(true);
                                layers.put(name,read(f.getInt(driver),name.equals("depthTexture")));
                            }
                        } catch(Exception e) { throw new RuntimeException(e); }
                    });
                    Pixels depth=layers.get("depthTexture"), world=layers.get("worldTexture"), ui=layers.get("savedUiId"), fin=layers.get("finalTexture");
                    float[] values=new float[9];
                    for(int j=0;j<9;j++) values[j]=depth.depth((int)((0.5+(j%3-1)*0.065)*depth.w),(int)((0.5+(j/3-1)*0.065)*depth.h));
                    Arrays.sort(values);
                    report.append(i).append(',').append(frames[0]);
                    for(Pixels p:layers.values()) { CRC32 crc=new CRC32(); crc.update(p.data); report.append(',').append(crc.getValue()); }
                    report.append(',').append(values[4]).append(',').append(composeError(world,ui,fin,false)).append(',').append(composeError(world,ui,fin,true)).append('\n');
                    if(i==0 || i==11) for(var entry:layers.entrySet()) {
                        String name=String.format("%02d-%s",i,entry.getKey());
                        Pixels p=entry.getValue();
                        if(entry.getKey().equals("depthTexture")) Files.write(out.resolve(name+".f32"),p.data);
                        else ImageIO.write(p.image(),"png",out.resolve(name+".png").toFile());
                    }
                    Files.writeString(out.resolve("samples.csv"),report.toString());
                    Thread.sleep(100);
                }
                Files.writeString(out.resolve("status.txt"),"complete");
            } catch(Throwable e) {
                try(var writer=new java.io.PrintWriter(Files.newBufferedWriter(out.resolve("error.txt")))) { e.printStackTrace(writer); } catch(Exception ignored) { }
            }
        },"PZVR layer diagnostic"); worker.setDaemon(true); worker.start();
    }
    private static Pixels read(int id,boolean depth) {
        int active=glGetInteger(GL_ACTIVE_TEXTURE); glActiveTexture(GL_TEXTURE0);
        int texture=glGetInteger(GL_TEXTURE_BINDING_2D), pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        glPushClientAttrib(GL_CLIENT_PIXEL_STORE_BIT);
        try {
            glBindTexture(GL_TEXTURE_2D,id); glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            glPixelStorei(GL_PACK_ALIGNMENT,1); glPixelStorei(GL_PACK_ROW_LENGTH,0); glPixelStorei(GL_PACK_SKIP_ROWS,0); glPixelStorei(GL_PACK_SKIP_PIXELS,0); glPixelStorei(GL_PACK_SWAP_BYTES,0);
            int w=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_WIDTH),h=glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_HEIGHT);
            if(w<=0 || h<=0) throw new IllegalStateException("Missing texture "+id);
            ByteBuffer b=MemoryUtil.memAlloc(w*h*4);
            try { glGetTexImage(GL_TEXTURE_2D,0,depth?GL_DEPTH_COMPONENT:GL_RGBA,depth?GL_FLOAT:GL_UNSIGNED_BYTE,b); byte[] data=new byte[b.remaining()]; b.get(data); return new Pixels(w,h,data); }
            finally { MemoryUtil.memFree(b); }
        } finally { glPopClientAttrib(); glBindBuffer(GL_PIXEL_PACK_BUFFER,pack); glBindTexture(GL_TEXTURE_2D,texture); glActiveTexture(active); }
    }
    private static double composeError(Pixels world,Pixels ui,Pixels fin,boolean flip) {
        double error=0; int count=0;
        for(int y=0;y<world.h;y+=3) for(int x=0;x<world.w;x+=3) {
            int i=(y*world.w+x)*4, u=((flip?world.h-1-y:y)*ui.w+x)*4;
            double a=(ui.data[u+3]&255)/255.0;
            for(int c=0;c<3;c++) { error+=Math.abs((fin.data[i+c]&255)-((world.data[i+c]&255)*(1-a)+(ui.data[u+c]&255))); count++; }
        }
        return error/count;
    }
    private record Pixels(int w,int h,byte[] data) {
        float depth(int x,int y) { return ByteBuffer.wrap(data).order(ByteOrder.nativeOrder()).getFloat((y*w+x)*4); }
        BufferedImage image() {
            var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB); int[] row=new int[w];
            for(int y=0;y<h;y++) { for(int x=0;x<w;x++) { int i=((h-1-y)*w+x)*4; row[x]=((data[i+3]&255)<<24)|((data[i]&255)<<16)|((data[i+1]&255)<<8)|(data[i+2]&255); } image.setRGB(0,y,w,1,row,0,w); }
            return image;
        }
    }
}
