package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The games the Games channel shows, one per line of games.txt in the launcher's own external files folder
 * (Android/data/&lt;package&gt;/files), which adb can write and the launcher reads without a storage permission:
 *
 * <pre>
 *   # title | ROM path | label colour | libretro core
 *   Fire Red | /storage/emulated/0/RetroArch/roms/gba/firered.gba | #E8432E
 * </pre>
 *
 * The colour and the core are optional: Game Boy and GBA files default to mGBA, NES to FCEUmm and SNES to Snes9x
 * 2010, and Mega Drive, Master System and Game Gear to Genesis Plus GX. Neo Geo sets are .zip files, so their
 * lines name fbneo (FinalBurn Neo). tools/games/add.py
 * copies a ROM to the TV and writes its line, through ThemeProvider where the shell cannot reach Android/data.
 */
final class GameList {
    private GameList() {}

    static final String FILE = "games.txt";

    /** Label colours for games that do not name one, picked by the title so a game keeps its colour. */
    static final int[] PALETTE = {0xFFE8432E, 0xFF2F7DE1, 0xFF3FA34D, 0xFFF29D16, 0xFF8E5BD8, 0xFF16A9A4,
            0xFFE5508C};

    static final class Game {
        final String title;
        final String rom;
        final int color;
        final String core;

        Game(String title, String rom, int color, String core) {
            this.title = title;
            this.rom = rom;
            this.color = color;
            this.core = core;
        }
    }

    static List<Game> load(Context context) {
        File dir = Config.dir(context);
        if (dir == null) return Collections.emptyList();
        File f = new File(dir, FILE);
        if (!f.isFile()) return Collections.emptyList();
        try {
            return parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (IOException | SecurityException e) {
            return Collections.emptyList();
        }
    }

    static List<Game> parse(String text) {
        List<Game> games = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", -1);
            if (parts.length < 2) continue;
            String rom = parts[1].trim();
            if (!rom.startsWith("/")) continue;
            String core = parts.length > 3 && !parts[3].trim().isEmpty() ? parts[3].trim() : defaultCore(rom);
            if (core == null || !core.matches("[A-Za-z0-9_]+")) continue;
            String title = parts[0].trim();
            if (title.isEmpty()) title = stem(rom);
            int color = parts.length > 2 ? parseColor(parts[2].trim()) : 0;
            if (color == 0) color = PALETTE[Math.floorMod(title.hashCode(), PALETTE.length)];
            games.add(new Game(title, rom, color, core));
        }
        return games;
    }

    /** The core for a ROM's extension, or null when the line has to name one (Neo Geo sets are plain .zip files). */
    static String defaultCore(String rom) {
        String name = rom.toLowerCase(Locale.ROOT);
        if (name.endsWith(".gba") || name.endsWith(".gb") || name.endsWith(".gbc") || name.endsWith(".sgb")) {
            return "mgba";
        }
        if (name.endsWith(".nes")) return "fceumm";
        if (name.endsWith(".sfc") || name.endsWith(".smc")) return "snes9x2010";
        if (name.endsWith(".md") || name.endsWith(".gen") || name.endsWith(".smd")
                || name.endsWith(".sms") || name.endsWith(".gg")) return "genesis_plus_gx";
        return null;
    }

    /** The console a ROM is for, by its extension or its roms/ folder, or null for one the channel has no name for. */
    static String system(String rom) {
        String name = rom.toLowerCase(Locale.ROOT);
        if (name.endsWith(".gba")) return "Game Boy Advance";
        if (name.endsWith(".gbc")) return "Game Boy Color";
        if (name.endsWith(".gb") || name.endsWith(".sgb")) return "Game Boy";
        if (name.endsWith(".nes")) return "NES";
        if (name.endsWith(".sfc") || name.endsWith(".smc")) return "Super Nintendo";
        if (name.endsWith(".md") || name.endsWith(".gen") || name.endsWith(".smd")) return "Mega Drive";
        if (name.endsWith(".sms")) return "Master System";
        if (name.endsWith(".gg")) return "Game Gear";
        if (name.endsWith(".zip") && name.contains("/neogeo/")) return "Neo Geo";
        return null;
    }

    /**
     * The remote's red, green, yellow and blue buttons in a ROM's game, as tools/games/add.py --setup maps them;
     * null for a button the console lacks. coin names the Neo Geo's Select.
     */
    static String[] pad(String rom, String coin) {
        String system = system(rom);
        if (system == null) return new String[] {"Start", "Select", "Y", "X"};
        switch (system) {
            case "Game Boy Advance":
                return new String[] {"Start", "Select", "L", "R"};
            case "Super Nintendo":
                return new String[] {"Start", "Select", "Y", "X"};
            case "Neo Geo":
                return new String[] {"Start", coin, "C", "D"};
            case "Mega Drive":
                return new String[] {"Start", "Mode", "A", "Y"};
            case "Master System":
                return new String[] {"Pause", null, null, null};
            case "Game Gear":
                return new String[] {"Start", null, null, null};
            default:
                return new String[] {"Start", "Select", null, null};
        }
    }

    static String[] buttons(String rom) {
        String system = system(rom);
        if ("Mega Drive".equals(system)) return new String[] {"C", "B"};
        if ("Master System".equals(system) || "Game Gear".equals(system)) return new String[] {"2", "1"};
        return new String[] {"A", "B"};
    }

    /** "#RRGGBB" as an opaque colour; 0 for anything else. */
    static int parseColor(String s) {
        if (!s.matches("#[0-9A-Fa-f]{6}")) return 0;
        return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
    }

    static String stem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
