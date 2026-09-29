package pzvr;

import com.sun.tools.attach.VirtualMachine;
import java.nio.file.Path;

public final class Attach {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Usage: Attach PID agent.jar output-directory game-instrument.dll");
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        // The game's launcher does not put its runtime bin directory on the DLL search path.
        // Use the game's own instrumentation library by absolute path.
        try { vm.loadAgentPath(Path.of(args[3]).toAbsolutePath().toString(), Path.of(args[1]).toAbsolutePath() + "=" + Path.of(args[2]).toAbsolutePath()); }
        finally { vm.detach(); }
        System.out.println("Agent loaded successfully.");
    }
}
