"""tools/games/add.py: the games.txt and retroarch.cfg edits it makes, without a TV.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import importlib.util
import unittest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("games_add", ROOT / "tools" / "games" / "add.py")
add = importlib.util.module_from_spec(spec)
spec.loader.exec_module(add)


class GamesListTest(unittest.TestCase):
    def test_first_game_gets_the_header(self):
        text = add.upsert("", "Mi juego", "/storage/emulated/0/RetroArch/roms/gba/mi.gba")
        self.assertEqual(text, add.HEADER + "\nMi juego | /storage/emulated/0/RetroArch/roms/gba/mi.gba\n")

    def test_same_rom_replaces_its_line(self):
        text = add.upsert("", "Old", "/r/gba/a.gba")
        text = add.upsert(text, "Other", "/r/gba/b.gba", core="mgba")
        text = add.upsert(text, "New", "/r/gba/a.gba", color="#E8432E")
        self.assertEqual(add.entries(text), [("Other", "/r/gba/b.gba"), ("New", "/r/gba/a.gba")])
        self.assertIn("Other | /r/gba/b.gba |  | mgba", text)
        self.assertIn("New | /r/gba/a.gba | #E8432E", text)

    def test_title_cannot_break_the_columns(self):
        text = add.upsert("", "A | B", "/r/gba/a.gba")
        self.assertEqual(add.entries(text), [("A - B", "/r/gba/a.gba")])

    def test_remove_by_title_or_file(self):
        text = add.upsert(add.upsert("", "Uno", "/r/gba/uno.gba"), "Dos", "/r/gba/dos.gba")
        left, gone = add.remove(text, "uno")
        self.assertEqual(gone, [("Uno", "/r/gba/uno.gba")])
        left, gone = add.remove(left, "DOS.gba")
        self.assertEqual(gone, [("Dos", "/r/gba/dos.gba")])
        self.assertEqual(add.entries(left), [])
        self.assertEqual(add.remove(left, "tres")[1], [])


class FileNamesTest(unittest.TestCase):
    def test_folders(self):
        self.assertEqual(add.folder("x.GBA"), "gba")
        self.assertEqual(add.folder("x.gbc"), "gb")
        self.assertEqual(add.folder("x.nes"), "nes")
        self.assertEqual(add.folder("x.SMC"), "snes")
        self.assertEqual(add.folder("mslug.zip", system="neogeo"), "neogeo")
        self.assertIsNone(add.folder("x.iso"))
        self.assertEqual(add.folder("x.md", core="genesis_plus_gx"), "md")

    def test_sega_cartridges_share_a_folder_per_console(self):
        for extension, console in (("md", "md"), ("GEN", "md"), ("smd", "md"),
                                   ("SMS", "sms"), ("gg", "gg")):
            with self.subTest(extension=extension):
                self.assertEqual(add.folder("game." + extension), console)
        self.assertIsNone(add.folder("game.bin"))
        self.assertIsNone(add.folder("game.cue"))
        self.assertEqual(add.folder("game.bin", core="genesis_plus_gx"), "bin")

    def test_names_are_safe_for_the_shell(self):
        self.assertEqual(add.safe_name("/home/me/Mi juego (USA).gba"), "Mi juego (USA).gba")
        self.assertEqual(add.safe_name("a'b;$(rm).gba"), "a_b__(rm).gba")
        self.assertEqual(add.safe_name("../.."), "game")

    def test_default_title_drops_dump_tags(self):
        self.assertEqual(add.default_title("Mi_Juego (USA, Europe) [!].gba"), "Mi Juego")
        self.assertEqual(add.default_title("(Demo).gba"), "(Demo)")


class RetroArchConfigTest(unittest.TestCase):
    def test_changes_and_adds_keys(self):
        text = 'a = "1"\nmenu_swap_ok_cancel_buttons = "true"\nb = "2"\n'
        out = add.set_cfg(text, {"menu_swap_ok_cancel_buttons": "false", "autosave_interval": "10"})
        self.assertEqual(out, 'a = "1"\nmenu_swap_ok_cancel_buttons = "false"\nb = "2"\nautosave_interval = "10"\n')
        self.assertEqual(add.cfg_value(out, "b"), "2")
        self.assertIsNone(add.cfg_value(out, "c"))



class RemapTest(unittest.TestCase):
    def test_sega_keeps_the_core_button_layout_and_uses_player_one(self):
        remap = add.CORE_REMAPS["Genesis Plus GX"]
        for port in range(2, 5):
            self.assertEqual(add.cfg_value(remap, f"input_remap_port_p{port}"), "0")
        self.assertNotIn("input_player", remap)

    def test_every_core_plays_the_remote_as_player_one(self):
        for lib, rmp in add.CORE_REMAPS.items():
            self.assertIn('input_remap_port_p2 = "0"', rmp, lib)

    def test_yellow_and_blue_are_y_and_x_and_l_and_r_on_the_gba(self):
        profile = (ROOT / "tools" / "games" / "retroarch" / "MStar_Smart_TV_IR_Receiver.cfg").read_text()
        self.assertEqual(add.cfg_value(profile, "input_y_btn"), "185")
        self.assertEqual(add.cfg_value(profile, "input_x_btn"), "186")
        self.assertIn('input_player1_btn_y = "10"', add.CORE_REMAPS["mGBA"])
        self.assertIn('input_player4_btn_x = "11"', add.CORE_REMAPS["mGBA"])

    def test_ok_is_a_on_the_neo_geo(self):
        rmp = add.CORE_REMAPS["FinalBurn Neo"]
        self.assertIn('input_player1_btn_a = "0"', rmp)
        self.assertIn('input_player1_btn_b = "8"', rmp)


if __name__ == "__main__":
    unittest.main()
