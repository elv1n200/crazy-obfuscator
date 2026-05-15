package dev.crazy.obf;

import dev.crazy.obf.cli.Main;
import dev.crazy.obf.gui.ObfuscatorGui;

/**
 * Single entry point for the packaged app.
 *   - launched with arguments  -> command-line mode (picocli)
 *   - launched with no args    -> opens the GUI window
 *
 * So double-clicking the installed app shows a real window instead of a
 * console that flashes and closes.
 */
public final class Launcher {
    public static void main(String[] args) {
        if (args.length > 0) {
            Main.main(args);
        } else {
            ObfuscatorGui.launch();
        }
    }
}
