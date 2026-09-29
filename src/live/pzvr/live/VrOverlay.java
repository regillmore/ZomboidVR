package pzvr.live;

import com.sun.jna.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

final class VrOverlay implements AutoCloseable {
    private NativeLibrary dll;
    private final Map<String,Function> overlay = new HashMap<>(), system = new HashMap<>();
    private final Memory pose = new Memory(80), transform = new Memory(48), texture = new Memory(16), bounds = new Memory(16);
    private long handle;
    private boolean initialized, positioned, shown;

    void initialize(Path root, String dllPath) throws Exception {
        String header=Files.readString(root.resolve("vendor/openvr/openvr_capi.h"));
        dll=NativeLibrary.getInstance(dllPath);
        try(Memory error=new Memory(4)) {
            error.clear(); dll.getFunction("VR_InitInternal").invokeLong(new Object[]{error,2});
            if(error.getInt(0)!=0) throw new IllegalStateException("SteamVR init error " + error.getInt(0));
            initialized=true;
            loadInterface(header,"IVROverlay",overlay); loadInterface(header,"IVRSystem",system);
        }
        try(Memory out=new Memory(8)) {
            out.clear(); check(call("CreateOverlay","local.zomboidvr.live","ZomboidVR live stereo",out)); handle=out.getLong(0);
        }
        check(call("SetOverlayFlag",handle,1024,(byte)1));
        check(call("SetOverlayWidthInMeters",handle,2.6f));
        check(call("SetOverlayTexelAspect",handle,1f));
        // SteamVR performs the OpenGL convention conversion. Keep normal overlay bounds.
        bounds.setFloat(0,0); bounds.setFloat(4,0); bounds.setFloat(8,1); bounds.setFloat(12,1);
        check(call("SetOverlayTextureBounds",handle,bounds));
    }
    private void loadInterface(String header,String name,Map<String,Function> result) {
        Matcher version=Pattern.compile(name+"_Version = \"([^\"]+)\"").matcher(header);
        if(!version.find()) throw new IllegalStateException("Missing SDK interface "+name);
        Pointer table;
        try(Memory error=new Memory(4)) {
            error.clear(); table=dll.getFunction("VR_GetGenericInterface").invokePointer(new Object[]{"FnTable:"+version.group(1),error});
            if(error.getInt(0)!=0 || table==null) throw new IllegalStateException("Unsupported SteamVR interface "+version.group(1)+": "+error.getInt(0));
        }
        Matcher body=Pattern.compile("struct VR_"+name+"_FnTable\\s*\\{(.*?)\\n\\};",Pattern.DOTALL).matcher(header);
        if(!body.find()) throw new IllegalStateException("Missing SDK table "+name);
        Matcher names=Pattern.compile("OPENVR_FNTABLE_CALLTYPE \\*(\\w+)\\)").matcher(body.group(1));
        int index=0;
        while(names.find()) result.put(names.group(1),Function.getFunction(table.getPointer((long)index++*Native.POINTER_SIZE),Function.ALT_CONVENTION));
    }
    private int call(String name,Object... args) { return overlay.get(name).invokeInt(args); }
    private void check(int code) {
        if(code!=0) throw new IllegalStateException("OpenVR "+code+": "+overlay.get("GetOverlayErrorNameFromEnum").invokeString(new Object[]{code},false));
    }
    boolean recenter(float width,float distance) {
        pose.clear(); system.get("GetDeviceToAbsoluteTrackingPose").invokeVoid(new Object[]{1,0f,pose,1});
        if(pose.getByte(76)==0 || pose.getByte(77)==0) return false;
        float fx=-pose.getFloat(8), fz=-pose.getFloat(40), length=(float)Math.hypot(fx,fz);
        if(length<0.1f) return false;
        fx/=length; fz/=length;
        float[] matrix={-fz,0,-fx,pose.getFloat(12)+fx*distance, 0,1,0,pose.getFloat(28), fx,0,-fz,pose.getFloat(44)+fz*distance};
        transform.write(0,matrix,0,12);
        check(call("SetOverlayWidthInMeters",handle,width));
        check(call("SetOverlayTransformAbsolute",handle,1,transform));
        positioned=true;
        return true;
    }
    void submit(int textureId) {
        texture.setPointer(0,Pointer.createConstant(Integer.toUnsignedLong(textureId)));
        texture.setInt(8,1); texture.setInt(12,1); // OpenGL, gamma-encoded color
        check(call("SetOverlayTexture",handle,texture));
        if(positioned && !shown) { check(call("ShowOverlay",handle)); shown=true; }
    }
    boolean positioned() { return positioned; }
    void hide() { if(shown) { check(call("HideOverlay",handle)); shown=false; } }
    @Override public void close() {
        if(handle!=0) { call("HideOverlay",handle); call("ClearOverlayTexture",handle); call("DestroyOverlay",handle); handle=0; }
        if(initialized) { dll.getFunction("VR_ShutdownInternal").invokeVoid(new Object[]{}); initialized=false; }
        pose.close(); transform.close(); texture.close(); bounds.close();
        // NativeLibrary is cached by JNA: do not unload a shared runtime DLL from the game process.
    }
}
