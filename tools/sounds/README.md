# Bundled audio / Audio incluido

The app includes the original remakes contributed by **Soffizlly (2026)** for this project, distributed under the repository MIT licence. These are contributor-provided remakes, not recordings extracted from Nintendo software. The APK needs no additional audio installation.

La app incluye los remakes propios aportados por **Soffizlly (2026)** para este proyecto, distribuidos bajo la licencia MIT del repositorio. El APK reproduce estos audios sin instalación adicional.

| Android resource | Use / Uso |
| --- | --- |
| `wii_move.ogg` | Cursor navigation / Mover el cursor |
| `wii_select.ogg` | Confirm and open / Confirmar y abrir |
| `wii_page.ogg` | Turn the page / Cambiar de página |
| `wii_back.ogg` | Back and close / Volver y cerrar |
| `wii_start.ogg` | Startup jingle (source: `WII-START.ogg`) / Arranque |
| `wii_big_buttons.ogg` | wiiOS, SD, envelope and Mii panels / wiiOS, SD, sobre y paneles Mii |
| `wii_woop.ogg` | Once after 500 ms on a channel / Una vez tras 0,5 s sobre un canal |
| `wii_menu_music.ogg` | Home loop, starts 1 s after the jingle / Música del menú, empieza 1 s después del arranque |
| `wii_music_plaza.webm` | Mii Plaza loop / Música de la Plaza Mii |
| `wii_music_editor.webm` | Mii editor loop / Música del editor Mii |

Assets live in `launcher/app/src/main/res/raw/`. The effects and the menu music are stereo OGG Vorbis at 44.1 kHz, supplied and individually compressed by Soffizlly. The Mii Plaza and editor tracks are Opus in WebM (VBR, 64 kbps nominal), re-encoded from his files to take less room; each holds one approximately 52.55-second loop. The audio assets total 1,968,443 bytes. Each effect remains below SoundPool’s 1 MB decoded limit. Android resource names use lowercase, hence `WII-START.ogg` becomes `wii_start.ogg`.

`make_sounds.py` is the legacy six-effect synthesizer. It writes to `tools/sounds/build/`, leaving bundled remakes untouched. The six additional synthesized Mii interaction effects remain in `MiiAudio.java`.
