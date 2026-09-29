package pzvr;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.instrument.*;
import java.net.*;
import java.nio.file.*;
import java.security.ProtectionDomain;
import java.util.*;
import zombie.core.opengl.RenderThread;

public final class LiveBootstrap {
    private static final String BATCH = "zombie/core/SpriteRenderer$RingBuffer$StateRun";
    private static final String DISPLAY = "org/lwjglx/opengl/Display";
    private static Instrumentation instrumentation;
    private static Hook hook;
    private static URLClassLoader runtimeLoader;
    private static LiveBridge.Driver active;
    private static Class<?>[] targets;
    private static Path root;

    public static synchronized void agentmain(String args, Instrumentation inst) throws Exception {
        if (active != null) throw new IllegalStateException("Live stereo is already installed. Stop it before loading a new build.");
        root = Path.of(args).toAbsolutePath();
        Files.createDirectories(root.resolve("live-control"));
        instrumentation = inst;
        if (!inst.isRetransformClassesSupported()) throw new IllegalStateException("JVM cannot retransform classes.");
        String runtime = Files.readString(root.resolve("build/live-runtime-path.txt")).trim();
        Path runtimePath = Path.of(runtime).toAbsolutePath().normalize();
        if (!runtimePath.startsWith(root.resolve("build"))) throw new IllegalArgumentException("Runtime must be built within this project.");
        runtimeLoader = new URLClassLoader(new URL[]{runtimePath.toUri().toURL()}, LiveBootstrap.class.getClassLoader());
        try {
            active = (LiveBridge.Driver) runtimeLoader.loadClass("pzvr.live.LiveSession").getConstructor(Path.class).newInstance(root);
            targets = new Class<?>[]{Class.forName(BATCH.replace('/', '.')), Class.forName(DISPLAY.replace('/', '.'))};
            for (Class<?> target : targets) if (!inst.isModifiableClass(target)) throw new IllegalStateException("Cannot hook " + target);
            hook = new Hook();
            inst.addTransformer(hook, true);
            inst.retransformClasses(targets);
            if (!hook.done.containsAll(Set.of(BATCH, DISPLAY))) throw new IllegalStateException("Render hooks were not both installed: " + hook.error);
            LiveBridge.driver = active;
            active.start();
        } catch (Throwable error) {
            stop();
            throw error;
        }
    }

    public static synchronized void stop() {
        LiveBridge.driver = null;
        LiveBridge.Driver old = active;
        active = null;
        try {
            if (old != null) RenderThread.invokeOnRenderContext(old::close);
        } catch (Throwable e) { e.printStackTrace(); }
        try {
            if (hook != null) {
                instrumentation.removeTransformer(hook);
                instrumentation.retransformClasses(targets);
                hook = null;
            }
            if (root != null) Files.writeString(root.resolve("live-control/hooks.txt"), "removed");
        } catch (Throwable e) {
            e.printStackTrace();
            try { Files.writeString(root.resolve("live-control/hooks.txt"), "disabled but removal failed: " + e); } catch (Exception ignored) { }
        }
        try { if (runtimeLoader != null) runtimeLoader.close(); } catch (Exception ignored) { }
        runtimeLoader = null;
    }

    public static byte[] transformBytes(String name, byte[] bytes) {
        String method = name.equals(BATCH) ? "render" : "swapBuffers";
        ClassDesc bridge = ClassDesc.of("pzvr.LiveBridge");
        ClassDesc texture = ClassDesc.of("zombie.core.textures.Texture");
        ClassModel model = ClassFile.of().parse(bytes);
        long matches = model.methods().stream().filter(m -> m.methodName().equalsString(method) && m.methodType().equalsString("()V")).count();
        if (matches != 1) throw new IllegalStateException("Expected one " + name + "." + method + "()V, found " + matches);
        return ClassFile.of().transformClass(model, ClassTransform.transformingMethodBodies(
            m -> m.methodName().equalsString(method) && m.methodType().equalsString("()V"),
            new CodeTransform() {
                @Override public void atStart(CodeBuilder code) {
                    if (name.equals(BATCH)) code.aload(0).getfield(ClassDesc.of(BATCH.replace('/', '.')), "texture0", texture)
                        .invokestatic(bridge, "beforeBatch", MethodTypeDesc.of(ConstantDescs.CD_void, texture));
                    else code.invokestatic(bridge, "beforeSwap", MethodTypeDesc.of(ConstantDescs.CD_void));
                }
                @Override public void accept(CodeBuilder code, CodeElement element) { code.with(element); }
            }));
    }

    private static final class Hook implements ClassFileTransformer {
        final Set<String> done = new HashSet<>();
        Throwable error;
        @Override public byte[] transform(ClassLoader loader, String name, Class<?> clazz, ProtectionDomain domain, byte[] bytes) {
            if (!BATCH.equals(name) && !DISPLAY.equals(name)) return null;
            try { byte[] transformed = transformBytes(name, bytes); done.add(name); return transformed; }
            catch (Throwable e) { error = e; return null; }
        }
    }
}
