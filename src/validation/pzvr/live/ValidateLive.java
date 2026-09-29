package pzvr.live;

import java.nio.file.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.*;
import java.util.zip.ZipFile;
import pzvr.LiveBootstrap;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import zombie.iso.PlayerCamera;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/** Offline bytecode checks and an isolated hidden OpenGL window, without touching the game. */
public final class ValidateLive {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        try(ZipFile jar=new ZipFile(args[1])) {
            for(String name:List.of("zombie/core/SpriteRenderer$RingBuffer$StateRun","org/lwjglx/opengl/Display")) {
                byte[] original=jar.getInputStream(jar.getEntry(name+".class")).readAllBytes();
                byte[] result=LiveBootstrap.transformBytes(name,original);
                var errors=ClassFile.of().verify(result);
                if(!errors.isEmpty()) throw new AssertionError(errors);
                if(Arrays.equals(result,original)) throw new AssertionError("Missing hook "+name);
                System.out.println("Verified bytecode: "+name);
                if(name.equals(UiCaptureHook.BATCH)) {
                    result=UiCaptureHook.transformBytes(result);
                    errors=ClassFile.of().verify(result);
                    if(!errors.isEmpty()) throw new AssertionError(errors);
                    var method=ClassFile.of().parse(result).methods().stream().filter(m->m.methodName().equalsString("render")).findFirst().orElseThrow();
                    int captures=0,draws=0;
                    boolean capturePending=false;
                    for(var element:method.code().orElseThrow()) if(element instanceof InvokeInstruction call) {
                        if(call.owner().asInternalName().equals("pzvr/LiveBridge") && call.name().equalsString("beforeBatch")) { captures++; capturePending=true; }
                        else {
                            boolean draw=call.owner().asInternalName().equals("zombie/core/SpriteRenderer$RingBuffer") && call.name().equalsString("drawElements");
                            if(capturePending && !draw) throw new AssertionError("Capture is not immediately before the sprite draw.");
                            if(draw) { if(!capturePending) throw new AssertionError("Sprite draw lacks capture hook."); draws++; capturePending=false; }
                        }
                    }
                    if(captures!=1 || draws!=1 || capturePending) throw new AssertionError("Invalid UI capture hook count.");
                    System.out.println("UI capture verified immediately before the sprite draw, after state-only returns.");
                }
            }
        }
        if(!glfwInit()) throw new IllegalStateException("GLFW init failed.");
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        long window=glfwCreateWindow(64,64,"PZVR validation",0,0);
        if(window==0) throw new IllegalStateException("Hidden GL context failed.");
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            int vs=compile(GL_VERTEX_SHADER,Files.readString(root.resolve("live-shaders/fullscreen.vert")));
            int fs=compile(GL_FRAGMENT_SHADER,Files.readString(root.resolve("live-shaders/stereo.frag")));
            int program=glCreateProgram(); glAttachShader(program,vs); glAttachShader(program,fs); glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==0) throw new AssertionError(glGetProgramInfoLog(program));
            System.out.println("Stereo shaders linked on "+glGetString(GL_RENDERER));
            glViewport(3,5,41,37); glEnable(GL_BLEND); glEnable(GL_DEPTH_TEST); glDepthMask(true);
            int texture=glGenTextures(); glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D,texture);
            glUseProgram(program);
            try(GlState saved=new GlState()) { glViewport(0,0,64,64); glActiveTexture(GL_TEXTURE0); glUseProgram(0); }
            int[] viewport=new int[4]; glGetIntegerv(GL_VIEWPORT,viewport);
            if(!Arrays.equals(viewport,new int[]{3,5,41,37}) || !glIsEnabled(GL_BLEND) || !glIsEnabled(GL_DEPTH_TEST)
                || !glGetBoolean(GL_DEPTH_WRITEMASK) || glGetInteger(GL_CURRENT_PROGRAM)!=program
                || glGetInteger(GL_ACTIVE_TEXTURE)!=GL_TEXTURE2 || glGetInteger(GL_TEXTURE_BINDING_2D)!=texture)
                throw new AssertionError("OpenGL state was not restored.");
            int error=glGetError(); if(error!=GL_NO_ERROR) throw new AssertionError("GL error "+error);
            glUseProgram(0); glDeleteProgram(program); glDeleteShader(vs); glDeleteShader(fs); glDeleteTextures(texture);
            System.out.println("OpenGL state restoration passed.");
            validateZoomDepth();
        } finally { glfwMakeContextCurrent(0); glfwDestroyWindow(window); glfwTerminate(); }
    }
    private static int compile(int type,String source) {
        int shader=glCreateShader(type); glShaderSource(shader,source); glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==0) throw new AssertionError(glGetShaderInfoLog(shader));
        return shader;
    }

    private static void validateZoomDepth() {
        try(GlState saved=new GlState()) {
            // Reproduce the game's padded allocation with valid depth only in the
            // screen-sized viewport. Four distinct depths catch crop, scale and flip errors.
            int source=glGenFramebuffers(), sourceDepth=glGenRenderbuffers();
            int target=glGenFramebuffers(), targetDepth=glGenRenderbuffers();
            try {
                attachDepth(source,sourceDepth,2048,2048);
                for(int[] size:new int[][]{{1920,1080},{1280,720}}) {
                    int w=size[0],h=size[1];
                    glBindFramebuffer(GL_FRAMEBUFFER,source);
                    glDisable(GL_SCISSOR_TEST); glDepthMask(true); glClearDepth(1); glClear(GL_DEPTH_BUFFER_BIT);
                    glEnable(GL_SCISSOR_TEST);
                    for(int y=0;y<2;y++) for(int x=0;x<2;x++) {
                        glScissor(x*w/2,y*h/2,w/2,h/2);
                        glClearDepth(0.2+0.1*(x+y*2)); glClear(GL_DEPTH_BUFFER_BIT);
                    }
                    glDisable(GL_SCISSOR_TEST);
                    attachDepth(target,targetDepth,w,h);
                    var camera=new PlayerCamera(0); camera.width=w; camera.height=h;
                    var pixels=MemoryUtil.memAllocFloat(w*h);
                    try {
                        for(float zoom:new float[]{0.5f,0.75f,1f,1.25f,1.5f,2f,2.5f}) {
                            camera.zoom=zoom; camera.offscreenWidth=(int)(w*zoom); camera.offscreenHeight=(int)(h*zoom);
                            glBindFramebuffer(GL_FRAMEBUFFER,target); glClearDepth(0); glClear(GL_DEPTH_BUFFER_BIT);
                            if(!LiveSession.copyWorldDepth(source,2048,2048,camera,target,w,h)) throw new AssertionError("Viewport rejected.");
                            glBindFramebuffer(GL_READ_FRAMEBUFFER,target);
                            glReadPixels(0,0,w,h,GL_DEPTH_COMPONENT,GL_FLOAT,pixels);
                            for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                                float expected=0.2f+0.1f*((x>=w/2?1:0)+(y>=h/2?2:0));
                                if(Math.abs(pixels.get(y*w+x)-expected)>0.000001f)
                                    throw new AssertionError("Zoom "+zoom+", "+w+"x"+h+": depth misaligned at "+x+","+y);
                            }
                        }
                    } finally { MemoryUtil.memFree(pixels); }
                    if(LiveSession.copyWorldDepth(source,w-1,h,camera,target,w,h)) throw new AssertionError("Partial viewport accepted.");
                }
                int error=glGetError(); if(error!=GL_NO_ERROR) throw new AssertionError("Zoom test GL error "+error);
                System.out.println("Depth coverage/alignment passed: every pixel at 7 zoom levels (50%-250%), 2 resolutions, padded framebuffer; partial viewport rejected.");
            } finally {
                glDeleteFramebuffers(source); glDeleteFramebuffers(target);
                glDeleteRenderbuffers(sourceDepth); glDeleteRenderbuffers(targetDepth);
            }
        }
    }
    private static void attachDepth(int fbo,int depth,int w,int h) {
        glBindFramebuffer(GL_FRAMEBUFFER,fbo); glBindRenderbuffer(GL_RENDERBUFFER,depth);
        glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH24_STENCIL8,w,h);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,depth);
        glDrawBuffer(GL_NONE); glReadBuffer(GL_NONE);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Test depth FBO incomplete.");
    }
}
