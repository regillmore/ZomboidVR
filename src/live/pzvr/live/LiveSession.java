package pzvr.live;

import pzvr.LiveBridge;
import pzvr.LiveBootstrap;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.*;
import zombie.ui.UIManager;
import zombie.iso.PlayerCamera;
import org.lwjglx.opengl.Display;
import org.lwjglx.input.Mouse;
import org.lwjgl.system.MemoryUtil;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.*;

public final class LiveSession implements LiveBridge.Driver {
    private final Path root, control;
    private final AtomicBoolean running=new AtomicBoolean(true);
    private volatile boolean started, stop, recenter=true, snapshot;
    private volatile String failure="", mode="starting";
    private volatile float strength=0.7f, convergence=0, screenWidth=2.6f, screenDistance=2.5f;
    private volatile int maxWidth=1600, fpsLimit=60;
    private volatile long frames, worldFrames, lastFrameNanos;
    private volatile double averageCpuMs, averageGpuMs, outputFps;
    private volatile int width,height,outWidth,outHeight,worldWidth,worldHeight,projectionWidth,projectionHeight,uiId,depthFormat;
    private volatile float zoom=1;
    private VrOverlay vr;
    private final UiCaptureHook uiCaptureHook=new UiCaptureHook();
    private int worldTexture, finalTexture, depthTexture, depthFbo, stereoTexture, stereoFbo, program, vao;
    private final int[] queries=new int[12];
    private final boolean[] queryPending=new boolean[6];
    private int queryIndex;
    private boolean captured, disposed;
    private int savedUiId;
    private float uiScaleX=1,uiScaleY=1;
    private long nextOutput, captureCpu, reportStart=System.nanoTime(), reportFrames;
    private double cpuSum,gpuSum;
    private int gpuSamples;
    private final Map<String,Integer> uniforms=new HashMap<>();

    public LiveSession(Path root) { this.root=root; control=root.resolve("live-control"); }
    @Override public void start() throws Exception {
        Files.createDirectories(control);
        Files.deleteIfExists(control.resolve("stop")); Files.deleteIfExists(control.resolve("snapshot"));
        uiCaptureHook.install();
        Files.writeString(control.resolve("hooks.txt"),"installed");
        started=true;
        Thread worker=new Thread(this::monitor,"PZVR live controls"); worker.setDaemon(true); worker.start();
    }
    private void monitor() {
        try {
            while(running.get() && !stop) {
                if(Files.exists(control.resolve("stop"))) break;
                Path config=control.resolve("settings.properties");
                if(Files.exists(config)) {
                    Properties p=new Properties(); try(Reader r=Files.newBufferedReader(config)) { p.load(r); }
                    strength=value(p,"strength",0.7f,0,1.5f); convergence=value(p,"convergence",0,-0.5f,0.5f);
                    screenWidth=value(p,"screenWidthMeters",2.6f,1,4); screenDistance=value(p,"distanceMeters",2.5f,1.5f,4);
                    maxWidth=(int)value(p,"eyeWidth",1600,640,1920); fpsLimit=(int)value(p,"fpsLimit",60,20,90);
                }
                if(Files.deleteIfExists(control.resolve("recenter"))) recenter=true;
                if(Files.deleteIfExists(control.resolve("snapshot"))) snapshot=true;
                writeStatus(); Thread.sleep(500);
            }
        } catch(Throwable e) { failure=stack(e); }
        finally {
            stop=true;
            LiveBootstrap.stop();
            mode=failure.isEmpty()?"stopped":"error";
            try { writeStatus(); } catch(Exception ignored) { }
        }
    }
    private static float value(Properties p,String key,float fallback,float low,float high) {
        try { float v=Float.parseFloat(p.getProperty(key,"")); return Float.isFinite(v)?Math.max(low,Math.min(high,v)):fallback; }
        catch(Exception e) { return fallback; }
    }
    private void writeStatus() throws IOException {
        Properties p=new Properties();
        p.setProperty("state",mode); p.setProperty("updatedUTC",Instant.now().toString());
        p.setProperty("gamePid",Long.toString(ProcessHandle.current().pid()));
        p.setProperty("framesSubmitted",Long.toString(frames)); p.setProperty("framesWithWorldDepth",Long.toString(worldFrames));
        p.setProperty("sourceSize",width+"x"+height); p.setProperty("perEyeSize",outWidth+"x"+outHeight);
        p.setProperty("worldRegion",worldWidth+"x"+worldHeight); p.setProperty("zoom",Float.toString(zoom));
        p.setProperty("projectionSize",projectionWidth+"x"+projectionHeight);
        p.setProperty("capturePoint","before_ui_draw");
        p.setProperty("uiTextureId",Integer.toString(uiId)); p.setProperty("depthFormat",Integer.toString(depthFormat));
        p.setProperty("strength",Float.toString(strength));
        p.setProperty("outputFps",String.format(Locale.ROOT,"%.2f",outputFps));
        p.setProperty("meanHookCpuMs",String.format(Locale.ROOT,"%.3f",averageCpuMs));
        p.setProperty("meanStereoGpuMs",String.format(Locale.ROOT,"%.3f",averageGpuMs));
        p.setProperty("frameAgeSeconds",String.format(Locale.ROOT,"%.2f",lastFrameNanos==0?0:(System.nanoTime()-lastFrameNanos)/1e9));
        p.setProperty("error",failure);
        Path temp=control.resolve("status.tmp"); try(Writer writer=Files.newBufferedWriter(temp)) { p.store(writer,"ZomboidVR live stereo"); }
        Files.move(temp,control.resolve("status.properties"),StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public void failed(Throwable error) { failure=stack(error); stop=true; }
    private static String stack(Throwable e) { StringWriter s=new StringWriter(); e.printStackTrace(new PrintWriter(s)); return s.toString(); }

    @Override public void beforeUi(Texture texture) {
        if(!started || stop || captured || texture==null || UIManager.uiFbo==null || !UIManager.useUiFbo || !zombie.GameWindow.isIngameState()) return;
        if(UIManager.uiFbo.getTexture()!=texture || glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=0) return;
        SpriteRenderState state=SpriteRenderer.instance.getRenderingState();
        if(state==null || state.fbo==null || state.fbo.isDestroyed()) return;
        int w=Display.getWidth(),h=Display.getHeight();
        PlayerCamera camera=state.playerCamera[0];
        if(camera==null || w<1 || h<1) return;
        // Split-screen needs per-player rectangles and independent convergence; reject for now.
        if(camera.width!=w || camera.height!=h) { mode="waiting_for_single_player_view"; return; }
        long start=System.nanoTime();
        try(GlState saved=new GlState()) {
            int format=readDepthFormat(state.fbo);
            if(format==0) return;
            ensureResources(w,h,format);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,0); glReadBuffer(GL_BACK);
            bind(0,worldTexture); glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,w,h);
            worldWidth=camera.width; worldHeight=camera.height;
            projectionWidth=camera.offscreenWidth; projectionHeight=camera.offscreenHeight;
            zoom=camera.zoom;
            if(!copyWorldDepth(state.fbo.getBufferId(),state.fbo.getWidth(),state.fbo.getHeight(),camera,depthFbo,w,h)) return;
            savedUiId=texture.getID(); uiId=savedUiId;
            uiScaleX=w/(float)texture.getWidthHW(); uiScaleY=h/(float)texture.getHeightHW();
            captured=true;
        }
        captureCpu=System.nanoTime()-start;
    }

    static boolean copyWorldDepth(int source,int sourceWidth,int sourceHeight,PlayerCamera camera,int destination,int w,int h) {
        // Build 42 renders into a fixed pixel viewport. offscreenWidth/Height describe
        // the zoom-scaled projection, NOT the occupied pixels in the padded world FBO.
        // MultiTextureFBO2.render/rendershader2 also sample camera.width x camera.height.
        // Refuse a partial rectangle instead of stretching an incomplete depth image.
        if(camera.width<=0 || camera.height<=0 || w<=0 || h<=0
            || camera.width>sourceWidth || camera.height>sourceHeight) return false;
        glBindFramebuffer(GL_READ_FRAMEBUFFER,source);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER,destination);
        glBlitFramebuffer(0,0,camera.width,camera.height,0,0,w,h,GL_DEPTH_BUFFER_BIT,GL_NEAREST);
        return true;
    }

    private int readDepthFormat(TextureFBO fbo) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo.getBufferId());
        int type=glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int id=glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if(type==GL_RENDERBUFFER) { glBindRenderbuffer(GL_RENDERBUFFER,id); return glGetRenderbufferParameteri(GL_RENDERBUFFER,GL_RENDERBUFFER_INTERNAL_FORMAT); }
        if(type==GL_TEXTURE) { bind(0,id); return glGetTexLevelParameteri(GL_TEXTURE_2D,0,GL_TEXTURE_INTERNAL_FORMAT); }
        return 0;
    }

    @Override public void frame() throws Exception {
        if(!started || stop) return;
        long now=System.nanoTime();
        if(now<nextOutput) { captured=false; captureCpu=0; return; }
        long begin=now;
        try(GlState saved=new GlState()) {
            if(vr==null) {
                Path dll=Path.of(Files.readString(root.resolve("build/steamvr-dll-path.txt")).trim());
                if(!dll.isAbsolute() || !Files.isRegularFile(dll)) throw new IOException("SteamVR library path is missing or invalid. Run Start-Live.ps1 again.");
                vr=new VrOverlay(); vr.initialize(root,dll.toString());
            }
            if(recenter || !vr.positioned()) recenter=!vr.recenter(screenWidth,screenDistance);
            int w=Display.getWidth(),h=Display.getHeight();
            if(w<1 || h<1) return;
            if(width!=w || height!=h || stereoTexture==0) { captured=false; ensureResources(w,h,depthFormat==0?GL_DEPTH24_STENCIL8:depthFormat); }
            glBindFramebuffer(GL_READ_FRAMEBUFFER,0); glReadBuffer(GL_BACK);
            bind(3,finalTexture); glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,w,h);
            int slot=queryIndex%6;
            if(queryPending[slot] && glGetQueryObjecti(queries[slot*2+1],GL_QUERY_RESULT_AVAILABLE)!=0) {
                long a=glGetQueryObjectui64(queries[slot*2],GL_QUERY_RESULT), b=glGetQueryObjectui64(queries[slot*2+1],GL_QUERY_RESULT);
                gpuSum+=(b-a)/1e6; gpuSamples++; queryPending[slot]=false;
            }
            boolean timing=!queryPending[slot];
            if(timing) glQueryCounter(queries[slot*2],GL_TIMESTAMP);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,stereoFbo); glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glUseProgram(program); glBindVertexArray(vao);
            bind(0,worldTexture); bind(1,depthTexture); bind(2,captured?savedUiId:worldTexture); bind(3,finalTexture);
            glUniform1i(u("worldColor"),0); glUniform1i(u("worldDepth"),1); glUniform1i(u("uiLayer"),2); glUniform1i(u("finalColor"),3);
            glUniform2f(u("uiScale"),uiScaleX,uiScaleY); glUniform2f(u("sourceSize"),w,h);
            glUniform1f(u("strength"),captured?strength:0); glUniform1f(u("convergence"),convergence);
            // Keep disparity calibration tied to the logical view extent, independently of its pixel coverage.
            glUniform1f(u("depthSpan"),Math.max(0.001f,0.0492f*projectionHeight/1080f));
            glUniform1i(u("hasWorld"),captured?1:0);
            glUniform2f(u("cursor"),Mouse.getX()/(float)w,Mouse.getY()/(float)h);
            glUniform1f(u("cursorVisible"),zombie.core.opengl.RenderThread.isCursorVisible()?1:0);
            for(int eye=0;eye<2;eye++) {
                glViewport(eye*outWidth,0,outWidth,outHeight); glUniform1f(u("eye"),eye==0?1:-1); glDrawArrays(GL_TRIANGLES,0,3);
            }
            if(timing) { glQueryCounter(queries[slot*2+1],GL_TIMESTAMP); queryPending[slot]=true; queryIndex++; }
            glFlush();
            vr.submit(stereoTexture);
            mode=vr.positioned()?(strength<=0?"live_flat":captured?"live_stereo":"flat_fallback_no_world_or_ui"):"waiting_for_headset_tracking";
            frames++; if(captured) worldFrames++; lastFrameNanos=now;
            cpuSum+=(System.nanoTime()-begin+captureCpu)/1e6;
            reportFrames++;
            if(now-reportStart>2_000_000_000L) {
                outputFps=reportFrames*1e9/(now-reportStart); averageCpuMs=cpuSum/reportFrames;
                if(gpuSamples>0) averageGpuMs=gpuSum/gpuSamples;
                cpuSum=0; reportFrames=0; gpuSum=0; gpuSamples=0; reportStart=now;
            }
            if(snapshot) { snapshot=false; saveSnapshot(); if(captured) saveDepthSnapshot(); }
        } finally {
            captured=false; captureCpu=0;
            nextOutput=Math.max(nextOutput+1_000_000_000L/fpsLimit,now);
        }
    }
    private void bind(int unit,int texture) { glActiveTexture(GL_TEXTURE0+unit); glBindTexture(GL_TEXTURE_2D,texture); }
    private int u(String name) { return uniforms.computeIfAbsent(name,n->glGetUniformLocation(program,n)); }
    private void ensureResources(int w,int h,int format) {
        int ow=Math.min(w,maxWidth),oh=Math.max(1,Math.round(h*ow/(float)w));
        if(worldTexture!=0 && width==w && height==h && depthFormat==format && outWidth==ow) return;
        releaseTextures(); width=w; height=h; outWidth=ow; outHeight=oh; depthFormat=format;
        worldTexture=colorTexture(w,h); finalTexture=colorTexture(w,h); stereoTexture=colorTexture(ow*2,oh);
        depthTexture=glGenTextures(); bind(0,depthTexture);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_COMPARE_MODE,GL_NONE);
        boolean stencil=format==GL_DEPTH24_STENCIL8 || format==GL_DEPTH32F_STENCIL8;
        int external=stencil?GL_DEPTH_STENCIL:GL_DEPTH_COMPONENT;
        int dataType=stencil?(format==GL_DEPTH24_STENCIL8?GL_UNSIGNED_INT_24_8:GL_FLOAT_32_UNSIGNED_INT_24_8_REV):GL_FLOAT;
        glTexImage2D(GL_TEXTURE_2D,0,format,w,h,0,external,dataType,0L);
        depthFbo=glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER,depthFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_TEXTURE_2D,depthTexture,0);
        glDrawBuffer(GL_NONE); glReadBuffer(GL_NONE); checkFbo("depth");
        stereoFbo=glGenFramebuffers(); glBindFramebuffer(GL_FRAMEBUFFER,stereoFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,stereoTexture,0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0); glReadBuffer(GL_COLOR_ATTACHMENT0); checkFbo("stereo");
        if(program==0) {
            try { program=link(Files.readString(root.resolve("live-shaders/fullscreen.vert")),Files.readString(root.resolve("live-shaders/stereo.frag"))); }
            catch(IOException e) { throw new UncheckedIOException(e); }
            vao=glGenVertexArrays(); for(int i=0;i<queries.length;i++) queries[i]=glGenQueries();
        }
    }
    private int colorTexture(int w,int h) {
        int id=glGenTextures(); bind(0,id);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,0L); return id;
    }
    private static void checkFbo(String name) { int status=glCheckFramebufferStatus(GL_FRAMEBUFFER); if(status!=GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException(name+" FBO incomplete "+status); }
    private static int compile(int type,String source) {
        int shader=glCreateShader(type); glShaderSource(shader,source); glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE) { String error=glGetShaderInfoLog(shader); glDeleteShader(shader); throw new IllegalStateException(error); }
        return shader;
    }
    private static int link(String vertex,String fragment) {
        int vs=compile(GL_VERTEX_SHADER,vertex),fs=0,program=0;
        try {
            fs=compile(GL_FRAGMENT_SHADER,fragment); program=glCreateProgram(); glAttachShader(program,vs); glAttachShader(program,fs); glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE) throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch(Throwable e) { if(program!=0) glDeleteProgram(program); throw e; }
        finally { glDeleteShader(vs); if(fs!=0) glDeleteShader(fs); }
    }
    private void saveSnapshot() {
        int w=outWidth*2,h=outHeight;
        ByteBuffer pixels=MemoryUtil.memAlloc(w*h*4);
        try {
            glPixelStorei(GL_PACK_ALIGNMENT,1); glPixelStorei(GL_PACK_ROW_LENGTH,0); glPixelStorei(GL_PACK_SKIP_ROWS,0); glPixelStorei(GL_PACK_SKIP_PIXELS,0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,stereoFbo); glReadBuffer(GL_COLOR_ATTACHMENT0);
            glReadPixels(0,0,w,h,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
            byte[] copy=new byte[w*h*4]; pixels.get(copy);
            Thread writer=new Thread(()-> {
                try {
                    BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                    int[] row=new int[w];
                    for(int y=0;y<h;y++) { for(int x=0;x<w;x++) { int i=((h-1-y)*w+x)*4; row[x]=((copy[i]&255)<<16)|((copy[i+1]&255)<<8)|(copy[i+2]&255); } image.setRGB(0,y,w,1,row,0,w); }
                    ImageIO.write(image,"png",control.resolve("live-stereo.png").toFile());
                } catch(Exception e) { e.printStackTrace(); }
            },"PZVR snapshot writer"); writer.setDaemon(true); writer.start();
        } finally { MemoryUtil.memFree(pixels); }
    }
    private void saveDepthSnapshot() {
        int w=width,h=height;
        FloatBuffer pixels=MemoryUtil.memAllocFloat(w*h);
        float[] values=new float[w*h];
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,depthFbo);
            glReadPixels(0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,pixels);
            pixels.get(values);
        } finally { MemoryUtil.memFree(pixels); }
        Properties info=new Properties();
        info.setProperty("capturedAtUTC",Instant.now().toString());
        info.setProperty("zoom",Float.toString(zoom));
        info.setProperty("worldRegion",worldWidth+"x"+worldHeight);
        info.setProperty("projectionSize",projectionWidth+"x"+projectionHeight);
        Thread writer=new Thread(()-> {
            try {
                float low=1,high=0;
                int invalid=0,topInvalid=0,rightInvalid=0;
                int band=Math.min(32,Math.min(w,h));
                for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                    float d=values[y*w+x];
                    if(!Float.isFinite(d) || d<=0 || d>=1) {
                        invalid++; if(y>=h-band) topInvalid++; if(x>=w-band) rightInvalid++;
                    } else { low=Math.min(low,d); high=Math.max(high,d); }
                }
                info.setProperty("clearOrInvalidPixels",Integer.toString(invalid));
                info.setProperty("totalPixels",Integer.toString(w*h));
                info.setProperty("topBandClearOrInvalidFraction",Float.toString(topInvalid/(float)(w*band)));
                info.setProperty("rightBandClearOrInvalidFraction",Float.toString(rightInvalid/(float)(h*band)));
                info.setProperty("minimumDepth",Float.toString(low)); info.setProperty("maximumDepth",Float.toString(high));
                BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
                int[] row=new int[w];
                for(int y=0;y<h;y++) {
                    for(int x=0;x<w;x++) {
                        float d=values[(h-1-y)*w+x];
                        int g=high>low?Math.round(Math.max(0,Math.min(1,(d-low)/(high-low)))*255):0;
                        row[x]=!Float.isFinite(d) || d<=0 || d>=1?0xB000B0:g*0x010101;
                    }
                    image.setRGB(0,y,w,1,row,0,w);
                }
                ImageIO.write(image,"png",control.resolve("live-depth.png").toFile());
                try(Writer out=Files.newBufferedWriter(control.resolve("live-depth.properties"))) { info.store(out,"Actual depth texture used by live stereo; magenta marks clear/invalid depth"); }
            } catch(Exception e) { e.printStackTrace(); }
        },"PZVR depth snapshot writer"); writer.setDaemon(true); writer.start();
    }
    private void releaseTextures() {
        for(int id:new int[]{worldTexture,finalTexture,depthTexture,stereoTexture}) if(id!=0) glDeleteTextures(id);
        for(int id:new int[]{depthFbo,stereoFbo}) if(id!=0) glDeleteFramebuffers(id);
        worldTexture=finalTexture=depthTexture=stereoTexture=depthFbo=stereoFbo=0;
    }
    @Override public void close() {
        if(disposed) return; disposed=true; running.set(false);
        uiCaptureHook.close();
        try(GlState saved=new GlState()) {
            if(vr!=null) { vr.close(); vr=null; }
            releaseTextures(); if(program!=0) glDeleteProgram(program); if(vao!=0) glDeleteVertexArrays(vao);
            for(int id:queries) if(id!=0) glDeleteQueries(id);
        }
    }
}
