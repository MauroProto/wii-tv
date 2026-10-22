# Bundled audio / Audio incluido

The app includes 11 original remakes contributed by **Soffizlly (2026)** for this project, distributed under the repository MIT licence. These are contributor-provided remakes, not recordings extracted from Nintendo software. The APK needs no additional audio installation.

La app incluye 11 remakes propios aportados por **Soffizlly (2026)** para este proyecto, distribuidos bajo la licencia MIT del repositorio. El APK reproduce estos audios sin instalación adicional.

| Android resource | Use / Uso |
| --- | --- |
| `wii_move.wav` | Cursor navigation / Mover el cursor |
| `wii_select.wav` | Confirm and open / Confirmar y abrir |
| `wii_page.wav` | Turn the page / Cambiar de página |
| `wii_back.wav` | Back and close / Volver y cerrar |
| `wii_menu.wav` | Internal Mii panels / Paneles internos Mii |
| `wii_start.wav` | Startup jingle (source: `WII-START.wav`) / Arranque |
| `wii_big_buttons.wav` | wiiOS, SD and envelope / wiiOS, SD y sobre |
| `wii_woop.wav` | Once after 500 ms on a channel / Una vez tras 0,5 s sobre un canal |
| `wii_menu_music.ogg` | Home loop, starts 1 s after the jingle / Música del menú, empieza 1 s después del arranque |
| `wii_music_plaza.ogg` | Mii Plaza loop / Música de la Plaza Mii |
| `wii_music_editor.ogg` | Mii editor loop / Música del editor Mii |

Assets live in `launcher/app/src/main/res/raw/`. Effects use stereo PCM16 WAV at 44.1 kHz (each below SoundPool’s 1 MB decoded limit). Music uses stereo OGG Vorbis at 44.1 kHz, converted from the supplied loopable WAVs without trimming. Android resource names use lowercase, hence `WII-START.wav` becomes `wii_start.wav`.

`make_sounds.py` is the legacy six-effect synthesizer. It writes to `tools/sounds/build/`, leaving bundled remakes untouched. The six additional synthesized Mii interaction effects remain in `MiiAudio.java`.
