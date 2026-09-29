package pzvr;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Offline stereo proof from a single color/depth capture, not a live VR renderer. */
public final class StereoPreview {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(dir.resolve("capture.properties"))) { p.load(r); }
        if (!Boolean.parseBoolean(p.getProperty("hasVaryingDepth"))) throw new IOException("Capture does not contain varying depth.");
        BufferedImage color = ImageIO.read(dir.resolve("color.png").toFile());
        int w = color.getWidth(), h = color.getHeight();
        byte[] bytes = Files.readAllBytes(dir.resolve("depth.f32"));
        if (bytes.length != w * h * 4) throw new IOException("Color/depth dimensions disagree.");
        FloatBuffer depth = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float low = Float.parseFloat(p.getProperty("depthPercentile01"));
        float high = Float.parseFloat(p.getProperty("depthPercentile99"));
        float center = depth.get((h / 2) * w + w / 2);
        float span = high - low;
        if (!Float.isFinite(center) || span <= 0) throw new IOException("Invalid depth range.");
        float maxShift = w / 120f; // <= 1.67% screen width total left/right disparity.
        BufferedImage left = warp(color, depth, center, span, maxShift, 1);
        BufferedImage right = warp(color, depth, center, span, maxShift, -1);
        ImageIO.write(left, "png", dir.resolve("left.png").toFile());
        ImageIO.write(right, "png", dir.resolve("right.png").toFile());
        BufferedImage full = pair(left, right, w, h, null);
        ImageIO.write(full, "png", dir.resolve("stereo-full-sbs.png").toFile());
        // Valve's file-upload interface documents a 1920x1080 maximum.
        int eyeW = Math.min(w, 960), eyeH = Math.round(eyeW * h / (float)w);
        ImageIO.write(pair(left, right, eyeW, eyeH, "DEPTH TEST  |  Paused scene"), "png", dir.resolve("stereo-preview.png").toFile());
        ImageIO.write(pair(color, color, eyeW, eyeH, "FLAT COMPARISON  |  Paused scene"), "png", dir.resolve("flat-preview.png").toFile());
        Files.writeString(dir.resolve("stereo-notes.txt"), "Offline single-frame depth reprojection. Lower raw depth treated as nearer.\nZero disparity raw depth: " + center + "\nMax per-eye shift: " + maxShift + " source pixels.\nForward splat with nearest-depth conflict resolution; horizontal background extension fills disocclusions.\nMissing furniture/rail depth and hidden surfaces cannot be recovered by this preview.\n");
        System.out.println("Stereo and flat comparison images created in " + dir);
    }

    private static BufferedImage warp(BufferedImage src, FloatBuffer depth, float center, float span, float max, int eye) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float[] z = new float[w];
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            Arrays.fill(z, Float.POSITIVE_INFINITY);
            for (int x = 0; x < w; x++) {
                float d = depth.get(y * w + x);
                if (!Float.isFinite(d) || d <= 0 || d >= 1) continue;
                float shift = Math.max(-max, Math.min(max, (center - d) / span * max * 2));
                int dest = Math.round(x + eye * shift);
                if (dest >= 0 && dest < w && d < z[dest]) { z[dest] = d; row[dest] = src.getRGB(x, y); }
            }
            int x = 0;
            while (x < w) {
                if (Float.isFinite(z[x])) { x++; continue; }
                int start = x;
                while (x < w && !Float.isFinite(z[x])) x++;
                int before = start - 1, after = x;
                int fill;
                if (before < 0 && after == w) fill = -1;
                else if (before < 0) fill = after;
                else if (after == w) fill = before;
                else fill = z[before] > z[after] ? before : after;
                for (int k = start; k < x; k++) row[k] = fill < 0 ? src.getRGB(k, y) : row[fill];
            }
            out.setRGB(0, y, w, 1, row, 0, w);
        }
        return out;
    }

    private static BufferedImage pair(BufferedImage a, BufferedImage b, int w, int h, String label) {
        BufferedImage image = new BufferedImage(w * 2, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(a, 0, 0, w, h, null); g.drawImage(b, w, 0, w, h, null);
        if (label != null) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            for (int eye = 0; eye < 2; eye++) {
                g.setColor(new Color(0, 0, 0, 180)); g.fillRect(eye * w + 16, 16, 370, 32);
                g.setColor(Color.WHITE); g.drawString(label, eye * w + 26, 38);
            }
        }
        g.dispose();
        return image;
    }
}
