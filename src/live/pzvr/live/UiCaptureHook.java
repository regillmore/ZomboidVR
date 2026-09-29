package pzvr.live;

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.*;
import java.lang.instrument.*;
import java.lang.reflect.Field;
import java.security.ProtectionDomain;
import pzvr.LiveBootstrap;

/** Places capture immediately before the real UI sprite draw, excluding state-only runs. */
final class UiCaptureHook implements ClassFileTransformer,AutoCloseable {
    static final String BATCH="zombie/core/SpriteRenderer$RingBuffer$StateRun";
    private Instrumentation instrumentation;
    private boolean installed, transformed;
    private Throwable failure;

    void install() throws Exception {
        // The stable bootstrap is already loaded in the running game. Reuse its own
        // instrumentation handle so this correction can be loaded without a game restart.
        Field field=LiveBootstrap.class.getDeclaredField("instrumentation");
        field.setAccessible(true); instrumentation=(Instrumentation)field.get(null);
        if(instrumentation==null) throw new IllegalStateException("Missing live bootstrap instrumentation.");
        instrumentation.addTransformer(this,true); installed=true;
        instrumentation.retransformClasses(Class.forName(BATCH.replace('/','.')));
        if(!transformed) throw new IllegalStateException("UI draw hook was not installed.",failure);
    }
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
        if(!BATCH.equals(name)) return null;
        try { byte[] result=transformBytes(bytes); transformed=true; return result; }
        catch(Throwable error) { failure=error; return null; }
    }
    static byte[] transformBytes(byte[] bytes) {
        int[] count=new int[2];
        ClassDesc bridge=ClassDesc.of("pzvr.LiveBridge"), texture=ClassDesc.of("zombie.core.textures.Texture");
        ClassFile cf=ClassFile.of();
        byte[] result=cf.transformClass(cf.parse(bytes),ClassTransform.transformingMethodBodies(
            m->m.methodName().equalsString("render") && m.methodType().equalsString("()V"),
            (code,element)-> {
                if(element instanceof InvokeInstruction call) {
                    String owner=call.owner().asInternalName(),name=call.name().stringValue();
                    if(owner.equals("pzvr/LiveBridge") && name.equals("beforeBatch")) {
                        // Discard the texture passed by the old method-entry hook.
                        code.pop(); count[0]++; return;
                    }
                    if(owner.equals("zombie/core/SpriteRenderer$RingBuffer") && name.equals("drawElements")
                        && call.type().equalsString("(IIII)V")) {
                        code.aload(0).getfield(ClassDesc.of(BATCH.replace('/','.')),"texture0",texture)
                            .invokestatic(bridge,"beforeBatch",MethodTypeDesc.of(ConstantDescs.CD_void,texture));
                        count[1]++;
                    }
                }
                code.with(element);
            }));
        if(count[0]!=1 || count[1]!=1) throw new IllegalStateException("Expected one entry hook and one sprite draw, found "+count[0]+", "+count[1]);
        return result;
    }
    @Override public void close() {
        if(installed) { instrumentation.removeTransformer(this); installed=false; }
        // LiveBootstrap.stop removes its transformer next and restores both original methods.
    }
}
