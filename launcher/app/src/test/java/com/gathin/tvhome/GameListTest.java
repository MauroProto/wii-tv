package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class GameListTest {

    @Test
    public void readsTitleRomColourAndCore() {
        List<GameList.Game> games = GameList.parse(
                "# title | rom | colour | core\n"
                        + "Fire Red | /storage/emulated/0/RetroArch/roms/gba/firered.gba | #E8432E\n"
                        + "  Tetris|/sdcard/RetroArch/roms/gb/tetris.gb||gambatte  \r\n");
        assertEquals(2, games.size());
        GameList.Game a = games.get(0);
        assertEquals("Fire Red", a.title);
        assertEquals("/storage/emulated/0/RetroArch/roms/gba/firered.gba", a.rom);
        assertEquals(0xFFE8432E, a.color);
        assertEquals("mgba", a.core);
        GameList.Game b = games.get(1);
        assertEquals("Tetris", b.title);
        assertEquals("gambatte", b.core);
        assertTrue(contains(GameList.PALETTE, b.color));
    }

    @Test
    public void skipsLinesItCannotLaunch() {
        List<GameList.Game> games = GameList.parse(
                "\n# comment\n"
                        + "no rom here\n"
                        + "Relative | roms/gba/x.gba\n"
                        + "Unknown system | /sdcard/roms/x.iso\n"
                        + "Bad core | /sdcard/roms/x.gba | | ../../evil\n"
                        + "Good | /sdcard/roms/ok.gba\n");
        assertEquals(1, games.size());
        assertEquals("Good", games.get(0).title);
    }

    @Test
    public void emptyTitleFallsBackToTheFileName() {
        GameList.Game g = GameList.parse(" | /sdcard/RetroArch/roms/gba/Mi Juego.v2.gba").get(0);
        assertEquals("Mi Juego.v2", g.title);
    }

    @Test
    public void colourIsStableAndOnlyHexIsAccepted() {
        assertEquals(0, GameList.parseColor("red"));
        assertEquals(0, GameList.parseColor("#FFF"));
        assertEquals(0xFF00FF80, GameList.parseColor("#00ff80"));
        int first = GameList.parse("Same | /x/a.gba | nope").get(0).color;
        int again = GameList.parse("Same | /y/b.gba").get(0).color;
        assertEquals(first, again);
    }

    @Test
    public void defaultCoreCoversTheHandheldsNesAndSnes() {
        assertEquals("mgba", GameList.defaultCore("/a/B.GBA"));
        assertEquals("mgba", GameList.defaultCore("/a/b.gbc"));
        assertEquals("fceumm", GameList.defaultCore("/a/b.NES"));
        assertEquals("snes9x2010", GameList.defaultCore("/a/b.sfc"));
        assertEquals("snes9x2010", GameList.defaultCore("/a/b.smc"));
        assertNull(GameList.defaultCore("/a/b.zip"));
    }

    @Test
    public void neoGeoLinesNameTheirCore() {
        List<GameList.Game> games = GameList.parse(
                "Metal Slug | /storage/emulated/0/RetroArch/roms/neogeo/mslug.zip | | fbneo\n"
                        + "No core | /storage/emulated/0/RetroArch/roms/neogeo/kof98.zip\n");
        assertEquals(1, games.size());
        assertEquals("fbneo", games.get(0).core);
        assertEquals("Neo Geo", GameList.system(games.get(0).rom));
    }

    @Test
    public void systemIsNamedByTheExtensionOrFolder() {
        assertEquals("Game Boy Advance", GameList.system("/r/Juego.GBA"));
        assertEquals("Game Boy Color", GameList.system("/r/a.gbc"));
        assertEquals("Game Boy", GameList.system("/r/a.gb"));
        assertEquals("NES", GameList.system("/r/nes/a.nes"));
        assertEquals("Super Nintendo", GameList.system("/r/snes/a.SMC"));
        assertNull(GameList.system("/r/arcade/a.zip"));
        assertNull(GameList.system("/r/a.iso"));
    }

    @Test
    public void segaCartridgesLaunchWithoutAnExplicitCore() {
        String[][] files = {{"md", "Mega Drive"}, {"GEN", "Mega Drive"}, {"smd", "Mega Drive"},
                {"SMS", "Master System"}, {"gg", "Game Gear"}};
        for (String[] file : files) {
            List<GameList.Game> games = GameList.parse("Sega game | /roms/game." + file[0]);
            assertEquals(file[0], 1, games.size());
            assertEquals("genesis_plus_gx", games.get(0).core);
            assertEquals(file[1], GameList.system(games.get(0).rom));
        }
        assertTrue(GameList.parse("Ambiguous | /roms/game.bin\nDisc | /roms/game.cue").isEmpty());
    }

    @Test
    public void explicitSegaCoreStillOverridesTheDefault() {
        GameList.Game game = GameList.parse("Sega game | /roms/game.md | | picodrive").get(0);
        assertEquals("picodrive", game.core);
        assertEquals("Mega Drive", GameList.system(game.rom));
    }

    @Test
    public void segaLegendMatchesGenesisPlusGxButtons() {
        assertArrayEquals(new String[] {"C", "B"}, GameList.buttons("/roms/game.MD"));
        assertArrayEquals(new String[] {"2", "1"}, GameList.buttons("/roms/game.sms"));
        assertArrayEquals(new String[] {"2", "1"}, GameList.buttons("/roms/game.GG"));
        assertArrayEquals(new String[] {"A", "B"}, GameList.buttons("/roms/game.gba"));
        assertArrayEquals(new String[] {"A", "B"}, GameList.buttons("/roms/game.sfc"));
        assertArrayEquals(new String[] {"A", "B"}, GameList.buttons("/roms/neogeo/game.zip"));
        assertArrayEquals(new String[] {"Start", "Mode", "A", "Y"}, GameList.pad("/roms/game.gen", "Coin"));
        assertArrayEquals(new String[] {"Pause", null, null, null}, GameList.pad("/roms/game.sms", "Coin"));
        assertArrayEquals(new String[] {"Start", null, null, null}, GameList.pad("/roms/game.gg", "Coin"));
    }

    @Test
    public void padNamesTheColourButtonsPerConsole() {
        assertArrayEquals(new String[] {"Start", "Select", "L", "R"}, GameList.pad("/r/gba/a.gba", "Coin"));
        assertArrayEquals(new String[] {"Start", "Select", null, null}, GameList.pad("/r/nes/a.nes", "Coin"));
        assertArrayEquals(new String[] {"Start", "Select", "Y", "X"}, GameList.pad("/r/snes/a.sfc", "Coin"));
        assertArrayEquals(new String[] {"Start", "Coin", "C", "D"}, GameList.pad("/r/neogeo/mslug.zip", "Coin"));
    }

    private static boolean contains(int[] list, int v) {
        for (int x : list) if (x == v) return true;
        return false;
    }
}
