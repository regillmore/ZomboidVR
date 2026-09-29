package pzvr;

import zombie.core.textures.Texture;

/** Stable call targets in the game's loader; replaceable implementation lives in a child loader. */
public final class LiveBridge {
    public interface Driver {
        void beforeUi(Texture texture);
        void frame() throws Exception;
        void failed(Throwable error);
        void start() throws Exception;
        void close();
    }
    public static volatile Driver driver;
    public static void beforeBatch(Texture texture) {
        Driver d = driver;
        if (d != null) try { d.beforeUi(texture); } catch (Throwable e) { driver = null; d.failed(e); }
    }
    public static void beforeSwap() {
        Driver d = driver;
        if (d != null) try { d.frame(); } catch (Throwable e) { driver = null; d.failed(e); }
    }
}
