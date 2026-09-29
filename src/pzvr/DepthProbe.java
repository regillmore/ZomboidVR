package pzvr;

import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;
import zombie.core.Core;
import zombie.core.opengl.RenderThread;
import zombie.core.textures.TextureFBO;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.opengl.GL30.*;

/** One-shot color/depth readback. Does not transform classes or change game files. */
public final class DepthProbe {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    public static void agentmain(String output, Instrumentation unused) throws Exception {
        Path dir = Path.of(output).toAbsolutePath();
        Files.createDirectories(dir);
        if (!BUSY.compareAndSet(false, true)) {
            Files.writeString(dir.resolve("error.txt"), "Another depth capture is still running.");
            return;
        }
        Thread worker = new Thread(() -> {
            AtomicReference<Capture> capture = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            AtomicBoolean expired = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(1);
            try {
                Files.writeString(dir.resolve("status.txt"), "Waiting for the game render thread.");
                RenderThread.queueInvokeOnRenderContext(() -> {
                    try {
                        if (!expired.get()) capture.set(read());
                    } catch (Throwable e) { error.set(e); }
                    finally { done.countDown(); }
                });
                if (!done.await(20, TimeUnit.SECONDS)) {
                    expired.set(true);
                    // If a read is already in progress, let it finish before freeing its memory.
                    throw new IOException("Render thread did not complete within 20 seconds. Bring the game to the foreground and retry.");
                }
                if (error.get() != null) throw new IOException("GPU capture failed", error.get());
                if (capture.get() == null) throw new IOException("No capture returned.");
                save(capture.get(), dir);
                Files.writeString(dir.resolve("status.txt"), "Complete");
            } catch (Throwable e) {
                try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(dir.resolve("error.txt")))) {
                    e.printStackTrace(writer);
                } catch (IOException ignored) { }
            } finally {
                // Free only after the GL callback has finished. A late callback owns its cleanup.
                if (done.getCount() == 0 && capture.get() != null) capture.get().close();
                else if (done.getCount() != 0) {
                    try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    if (done.getCount() == 0 && capture.get() != null) capture.get().close();
                }
                BUSY.set(false);
            }
        }, "PZVR one-shot depth probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static Capture read() throws IOException {
        Core core = Core.getInstance();
        TextureFBO fbo = core.offscreenBuffer.fboRendered;
        if (fbo == null || fbo.isDestroyed()) fbo = core.getOffscreenBuffer();
        if (fbo == null || fbo.isDestroyed()) throw new IOException("No world framebuffer. Load a single-player scene, pause, and retry.");
        // Build 42 zoom changes projection extent, not the occupied framebuffer pixels.
        int activeW = core.getScreenWidth(), activeH = core.getScreenHeight();
        if (activeW > fbo.getWidth() || activeH > fbo.getHeight()) throw new IOException("World framebuffer does not cover the screen.");
        int w = Math.min(activeW, 3840), h = Math.min(activeH, 2160);
        if (w < 1 || h < 1) throw new IOException("Invalid framebuffer dimensions.");
        int x = (activeW - w) / 2, y = (activeH - h) / 2;
        Capture c = new Capture(w, h);
        c.info.put("capturedAtUTC", Instant.now().toString());
        c.info.put("framebuffer", Integer.toString(fbo.getBufferId()));
        c.info.put("framebufferSize", fbo.getWidth() + "x" + fbo.getHeight());
        c.info.put("activeSize", activeW + "x" + activeH);
        c.info.put("projectionSize", core.getOffscreenWidth(0) + "x" + core.getOffscreenHeight(0));
        c.info.put("cropBottomLeft", x + "," + y);
        c.info.put("captureSize", w + "x" + h);
        c.info.put("zoom", Float.toString(core.getZoom(0)));
        c.info.put("screenSize", core.getScreenWidth() + "x" + core.getScreenHeight());
        c.info.put("gpu", glGetString(GL_RENDERER));
        c.info.put("depthEncoding", "raw GL_DEPTH_COMPONENT floats, little endian, top-down rows; no perspective linearization");
        int oldFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int oldPackBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] packKeys = { GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS, GL_PACK_SWAP_BYTES, GL_PACK_LSB_FIRST };
        int[] pack = new int[packKeys.length];
        for (int i = 0; i < pack.length; i++) pack[i] = glGetInteger(packKeys[i]);
        int targetRead = -1;
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo.getBufferId());
            targetRead = glGetInteger(GL_READ_BUFFER);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw new IOException("World framebuffer is incomplete.");
            int kind = glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            c.info.put("depthAttachmentType", Integer.toString(kind));
            if (kind == GL_NONE) throw new IOException("World framebuffer has no depth attachment.");
            c.info.put("depthAttachment", Integer.toString(glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)));
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            for (int i = 0; i < packKeys.length; i++) glPixelStorei(packKeys[i], i == 0 ? 1 : 0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            long start = System.nanoTime();
            glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, c.rgba);
            glReadPixels(x, y, w, h, GL_DEPTH_COMPONENT, GL_FLOAT, c.depth);
            c.info.put("readbackMilliseconds", String.format(Locale.ROOT, "%.2f", (System.nanoTime() - start) / 1e6));
            return c;
        } catch (Throwable e) {
            c.close();
            throw e;
        } finally {
            if (targetRead != -1) glReadBuffer(targetRead);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, oldFbo);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, oldPackBuffer);
            for (int i = 0; i < pack.length; i++) glPixelStorei(packKeys[i], pack[i]);
        }
    }

    private static void save(Capture c, Path dir) throws IOException {
        int count = c.w * c.h;
        FloatBuffer depth = c.depth.asFloatBuffer();
        float[] sample = new float[(count + 15) / 16];
        int n = 0;
        long zero = 0, one = 0, invalid = 0;
        for (int i = 0; i < count; i++) {
            float d = depth.get(i);
            if (!Float.isFinite(d) || d < 0 || d > 1) invalid++;
            else if (d == 0) zero++;
            else if (d == 1) one++;
            else if (i % 16 == 0) sample[n++] = d;
        }
        Arrays.sort(sample, 0, n);
        float low = n > 0 ? sample[(int)((n - 1) * 0.01)] : 0;
        float high = n > 0 ? sample[(int)((n - 1) * 0.99)] : 1;
        c.info.put("nonClearDepthSamples", Integer.toString(n));
        c.info.put("zeroPixels", Long.toString(zero));
        c.info.put("onePixels", Long.toString(one));
        c.info.put("invalidPixels", Long.toString(invalid));
        c.info.put("depthPercentile01", Float.toString(low));
        c.info.put("depthPercentile99", Float.toString(high));
        c.info.put("depthSampleMinimum", n > 0 ? Float.toString(sample[0]) : "none");
        c.info.put("depthSampleMaximum", n > 0 ? Float.toString(sample[n - 1]) : "none");
        c.info.put("hasVaryingDepth", Boolean.toString(n > 100 && high > low));
        BufferedImage color = new BufferedImage(c.w, c.h, BufferedImage.TYPE_INT_RGB);
        BufferedImage grey = new BufferedImage(c.w, c.h, BufferedImage.TYPE_INT_RGB);
        ByteBuffer row = ByteBuffer.allocate(c.w * 4).order(ByteOrder.LITTLE_ENDIAN);
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(dir.resolve("depth.f32")))) {
            for (int y = 0; y < c.h; y++) {
                row.clear();
                for (int x = 0; x < c.w; x++) {
                    int i = (c.h - 1 - y) * c.w + x, offset = i * 4;
                    int rgb = ((c.rgba.get(offset) & 255) << 16) | ((c.rgba.get(offset + 1) & 255) << 8) | (c.rgba.get(offset + 2) & 255);
                    color.setRGB(x, y, rgb);
                    float d = depth.get(i);
                    row.putFloat(d);
                    int g = high > low ? Math.round(Math.max(0, Math.min(1, (d - low) / (high - low))) * 255) : 0;
                    grey.setRGB(x, y, d == 0 || d == 1 || !Float.isFinite(d) ? 0xB000B0 : g * 0x010101);
                }
                raw.write(row.array());
            }
        }
        ImageIO.write(color, "png", dir.resolve("color.png").toFile());
        ImageIO.write(grey, "png", dir.resolve("depth.png").toFile());
        try (Writer writer = Files.newBufferedWriter(dir.resolve("capture.properties"))) { c.info.store(writer, "PZVR depth probe; depth.png is percentile-normalized, magenta means clear/invalid"); }
    }

    private static final class Capture implements AutoCloseable {
        final int w, h;
        final ByteBuffer rgba, depth;
        final Properties info = new Properties();
        Capture(int w, int h) {
            this.w = w; this.h = h;
            rgba = MemoryUtil.memAlloc(w * h * 4);
            depth = MemoryUtil.memAlloc(w * h * 4).order(ByteOrder.nativeOrder());
        }
        public void close() { MemoryUtil.memFree(rgba); MemoryUtil.memFree(depth); }
    }
}
