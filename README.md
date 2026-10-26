# wii-tv

El menú de canales de la Wii como pantalla de inicio de tu Android TV.

![wiiOS en una Philips 65PUD7906](docs/wii-menu.jpg)

wii-tv cambia la pantalla de inicio de un Android TV por un menú al estilo del Menú Wii: doce canales por página, el reloj de segmentos, la fecha, los botones redondos, la manito que sigue a lo que elegís y sonidos al moverte. Cada canal es una de tus apps, con su propio banner. Al prender la TV aparece una pantalla de aviso blanca, como en la consola, y con OK pasás al menú.

Lo mostré en X: [Le puse la interfaz de la Wii a mi TV](https://x.com/ProtoMauro/status/2112776289312555195).

English: [README.en.md](README.en.md)

## Aviso

wii-tv es un proyecto de fans. No está afiliado a Nintendo ni tiene su aprobación. "Wii" es una marca de Nintendo.

El repositorio no incluye archivos extraídos de Nintendo. La interfaz se dibuja por código (`WiiMenuView`, `WiiBase`, `WiiPalette`). Los 7 efectos y las 3 músicas que trae son remakes que hizo Soffizlly para este proyecto: ver [audios y créditos](tools/sounds/README.md).

## Qué trae

- **Canales**: 4 columnas por 3 filas por página. La primera columna de la página siguiente asoma a la derecha y las flechas cambian de página. Tus apps llenan los canales y los que sobran quedan vacíos. Al abrir un canal, la app crece desde su cuadro, como en la consola. Para cambiar un canal de lugar, mantené OK sobre él y movelo con las flechas; OK lo suelta y el orden queda guardado en la TV.
- **Reloj y fecha**: reloj de segmentos, de 12 horas con AM/PM o de 24 horas según el formato de hora de la TV, y la fecha corta en el idioma de la TV.
- **Botones de abajo**:
  - **wiiOS** (izquierda) abre los Ajustes de Android TV.
  - **SD** abre el reproductor multimedia de la TV (pendrive o disco USB).
  - **Sobre** (derecha) abre la lista de entradas HDMI y la TV en vivo.
- **Manito** que sigue al canal o botón elegido. Al volver de una app está donde la dejaste, aunque Android haya cerrado el menú para liberar memoria.
- **Canal Mii**: el primer canal. La plaza con tus Miis paseando sobre el piso de cuadrados, los botones de los costados (editar, crear, borrar, ayuda, desfile, mensajes), el silbato que los ordena por nombre, favoritos, color o género, y el editor para crearlos y cambiarlos. Los Miis se guardan en la TV. Sin nada más, todo se dibuja por código; con el paquete del canal usa las caras, los pelos, los íconos y los sonidos originales. El repositorio no trae esos archivos: [`tools/miipack`](tools/miipack/README.md) los toma de sus páginas públicas y arma el paquete en tu computadora.
- **Canal Juegos**: al lado del Canal Mii. Su cartel es una pradera pixelada con un personajito azul que salta piedras y junta gemas. Adentro, tus juegos son cartuchos en un estante: elegís uno, apretás **Jugar** y arranca en [RetroArch](https://www.retroarch.com) (Game Boy, Game Boy Color, Game Boy Advance, NES, Super Nintendo, Neo Geo, Mega Drive, Master System y Game Gear). El botón **RetroArch** abre sus ajustes. Los juegos no vienen con el repositorio: ver [Juegos](#juegos).
- **Canal Wiimote**: al lado del Canal Juegos. Convierte tu celu en el control de Wii, sin instalar nada: escaneás el código QR con la cámara, tocás **Empezar** en la página que se abre y la manito sigue al celu cuando lo movés (o cuando arrastrás el dedo en su panel). A abre, B vuelve, la cruz hace de flechas, + y − pasan de página y HOME vuelve al menú. Hasta 4 celus a la vez, cada uno con su luz de jugador. El código QR sirve solo mientras el canal está abierto; después el celu se acuerda de la TV. Se encuentran a través de un servidor chiquito en Cloudflare ([`wiimote/`](wiimote)), así que los dos necesitan internet.
- **Sonidos** al moverte, al abrir algo, al cambiar de página, al abrir las entradas y al continuar desde el aviso. Usan el volumen de la TV.
- **Audio incluido**: 7 efectos y 3 músicas, para el menú, la Plaza Mii y el editor. Primero suena el arranque y la música del menú entra un segundo después. No hay que instalar nada aparte.
- **Pantalla de aviso** al prender la TV. La cierran OK o las flechas, o se va sola a los 12 segundos. No vuelve a aparecer cuando salís de una app.
- **Siempre arranca en el menú**: al prender la TV vuelve al menú aunque hayas dejado otra app abierta.
- Protector de pantalla con reloj (opcional).

Probado en una Philips 65PUD7906/77 (Android TV 11, 1920 x 1080). Debería andar en cualquier Android TV 8.0 o posterior con pantalla 16:9.

![Pantalla de aviso al prender la TV](docs/wii-intro.jpg)

## Qué necesitás

- Una computadora con macOS o Linux en la misma red que la TV. En Windows también se puede instalar (ver el paso 5).
- Python 3.10 o posterior.
- JDK 17 y el Android SDK con `platforms;android-34` y `build-tools;34.0.0`. Android Studio trae los dos. En macOS también sirve `brew install openjdk@17 android-commandlinetools`.
- La depuración activada en la TV (paso 1).

## Instalación paso a paso

Corré todos los comandos desde la carpeta del repositorio. Cambiá `192.168.1.50` por la IP de tu TV.

### 1. Activar la depuración en la TV

1. Ajustes → Preferencias del dispositivo → Información: tocá **Compilación** siete veces, hasta que diga que ya sos desarrollador.
2. Ajustes → Preferencias del dispositivo → Opciones para desarrolladores: activá **Depuración por USB**, y también **Depuración por red** o **ADB por red** si tu TV lo muestra.
3. Anotá la IP de la TV: Ajustes → Red e Internet → tu red.

### 2. Preparar las herramientas

```sh
git clone https://github.com/MauroProto/wii-tv.git
cd wii-tv
python3 -m venv tools/venv
tools/venv/bin/pip install -r tools/requirements.txt
TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py --probe
```

La primera vez la TV pregunta si permitís la depuración. Marcá "Permitir siempre desde esta computadora" y aceptá. `--probe` muestra el modelo y la versión de Android.

La clave que autoriza a tu computadora queda en `tools/adbkey/`. Git la ignora; no la compartas, porque da acceso a la TV.

### 3. Hacer un respaldo

```sh
TV_HOST=192.168.1.50 tools/backup.sh
```

Guarda en `backups/<modelo>/<fecha>/` la lista de apps (activas, desactivadas, de sistema y las que instalaste vos), cuál es la pantalla de inicio, los ajustes y una captura de pantalla. Git ignora esa carpeta: tiene el número de serie y los nombres de red de tu TV.

### 4. (Opcional) Desactivar apps del fabricante

Las apps se **desactivan**, no se desinstalan, y se pueden volver a activar (ver "Deshacer todo").

```sh
TV_HOST=192.168.1.50 tools/disable.sh examples/philips-65pud7906/debloat.txt
```

- `examples/philips-65pud7906/debloat.txt` es la lista que se usó en la Philips 65PUD7906. Revisala y poné `#` adelante de lo que quieras conservar.
- Para otras marcas y modelos, [cleanyourtv](https://github.com/Gathin23/cleanyourtv/tree/main/examples) tiene más de cien listas probadas. Copiá la de tu TV a `examples/<tu-tv>/debloat.txt`.
- `disable.sh` guarda lo necesario para deshacer en `~/.cleanyourtv/`, y no desactiva la app dueña de tu red Wi-Fi.

### 5. Instalar el menú

```sh
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

El script:

1. compila la app de `launcher/`,
2. la instala y comprueba que abre,
3. desactiva el launcher original (`com.google.android.tvlauncher` en Android TV, `com.google.android.apps.tv.launcherx` en Google TV),
4. deja **wiiOS** como pantalla de inicio y verifica que Home lo abra. Si algo sale mal, vuelve a activar el launcher original.

Apretá Home en el control para verlo. Si preferís no desactivar el launcher original, usá `STOCK_LAUNCHER=none`. Ojo: en muchas TVs el launcher original tiene prioridad, y mientras siga activo Home lo va a seguir abriendo.

En Windows, con PowerShell:

```powershell
python -m venv tools\venv
tools\venv\Scripts\pip install -r tools\requirements.txt
powershell -ExecutionPolicy Bypass -File launcher\build-and-install.ps1 -TvHost 192.168.1.50
```

### 6. Cerrar la depuración al terminar

Mientras la depuración esté activada, cualquier computadora que ya autorizaste puede manejar la TV por la red. Cuando termines:

1. Ajustes → Opciones para desarrolladores: desactivá la depuración por USB y por red. Si querés, tocá también "Revocar autorizaciones de depuración".
2. Reiniciá la TV.
3. Comprobá que quedó cerrada: `tools/venv/bin/python tools/adb-closed.py 192.168.1.50`

## Usarlo con el control

| Tecla | Qué hace |
| --- | --- |
| Flechas | Mueven la manito. Desde la última columna, la flecha derecha pasa de página. Desde la fila de abajo, la flecha abajo baja a los botones. |
| CH+ / CH- | Pasan de página (también avance y retroceso rápido). |
| OK | Abre el canal o el botón elegido. |
| Mantener OK | Agarra el canal para moverlo: las flechas lo cambian de lugar con el de al lado, y OK o Atrás lo sueltan. |
| Atrás | Cierra la lista de entradas o la pantalla de aviso. |
| Home | Vuelve al menú. Si ya estás en el menú, vuelve a la primera página. |

## Juegos

El canal Juegos arranca tus juegos en RetroArch. Usá solo juegos tuyos: copiá tus propios cartuchos con un lector de cartuchos (por ejemplo GB Operator, GBxCart RW o el Open Source Cartridge Reader).

| Consola | Archivos | Núcleo de RetroArch |
| --- | --- | --- |
| Game Boy, Game Boy Color y Game Boy Advance | `.gb`, `.gbc`, `.gba` | Nintendo - Game Boy Advance (mGBA) |
| NES | `.nes` | Nintendo - NES / Famicom (FCEUmm) |
| Super Nintendo | `.sfc`, `.smc` | Nintendo - SNES / SFC (Snes9x 2010) |
| Neo Geo | `.zip` | Arcade (FinalBurn Neo) |
| Mega Drive / Genesis | `.md`, `.gen`, `.smd` | Sega - MS/GG/MD/CD (Genesis Plus GX) |
| Master System | `.sms` | Sega - MS/GG/MD/CD (Genesis Plus GX) |
| Game Gear | `.gg` | Sega - MS/GG/MD/CD (Genesis Plus GX) |

1. **Instalá RetroArch en la TV.** La mayoría de los Android TV son de 32 bits y necesitan `RetroArch_ra32.apk`, de la [página de descargas de RetroArch](https://www.retroarch.com/?page=platforms). Abrilo una vez y bajá los núcleos de tus consolas en Actualizador en línea → Descargar núcleos. Salí con Home.
2. **Preparalo para el control de la TV** (una sola vez):
   ```sh
   TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --setup
   ```
   Agrega el mapa de botones de `tools/games/retroarch/` y cambia algunos ajustes de RetroArch: OK elige y Atrás vuelve, mantener el botón rojo abre su menú y el juego se guarda cada 10 segundos. Antes guarda una copia de los ajustes en `retroarch.cfg.before-wii-tv`, en la carpeta de RetroArch de la TV. Si usás un joystick Bluetooth, en RetroArch elegí además Actualizador en línea → Actualizar perfiles de controles. Si ya lo habías preparado con una versión anterior de wii-tv, corrélo otra vez: cambió el mapa de botones.
3. **Sumá tus juegos**:
   ```sh
   TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py "Mi juego.gba" --title "Mi juego"
   ```
   Copia el archivo a `RetroArch/roms/` en la TV y lo pone en el estante. `--color "#E8432E"` elige el color de la etiqueta. `--list` muestra los juegos, y `--remove "Mi juego"` saca uno (con `--delete` también borra el archivo de la TV).

**Neo Geo.** Cada juego es un set `.zip` de FinalBurn Neo (por ejemplo `mslug.zip`) y necesita el BIOS de la Neo Geo, `neogeo.zip`, que wii-tv no trae. Los sets tienen que ser de la versión de FinalBurn Neo que tenés en RetroArch; si no, el juego no arranca.

```sh
TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --bios neogeo.zip
TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py mslug.zip --system neogeo --title "Metal Slug"
```

**Cartuchos de Sega.** Se agregan con el mismo comando, por ejemplo `TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py "Mi juego.md" --title "Mi juego"`. El núcleo Genesis Plus GX se elige solo; los cartuchos no necesitan BIOS. Se conserva su mapa de botones: en Mega Drive, OK es C, Atrás es B, amarillo es A, rojo es Start, verde es Mode y azul es Y. Mode e Y corresponden al control de seis botones; los juegos que usan X y Z necesitan un joystick. En Master System y Game Gear, OK es 2 y Atrás es 1; rojo es Pause en Master System y Start en Game Gear. El estante muestra los botones de la consola elegida. Los archivos `.bin` son ambiguos y siguen necesitando un núcleo explícito; la detección automática cubre las extensiones de cartuchos de la tabla, no Sega CD ni 32X.

Con el control de la TV, durante el juego (los botones de Sega están descritos arriba):

| Tecla | Botón |
| --- | --- |
| Flechas | Cruz |
| OK | A |
| Atrás | B |
| Rojo | Start. Mantenelo 2 segundos para el menú de RetroArch (guardar, cargar o cerrar el juego). |
| Verde | Select. En Neo Geo, moneda. |
| Amarillo y azul | Y y X. En Game Boy Advance son L y R; en Neo Geo, C y D. |
| Home | Vuelve al Menú Wii. El juego queda en pausa y sigue si lo elegís otra vez. |

Los juegos de Super Nintendo que usan L y R necesitan un joystick. Si elegís otro juego, o el botón RetroArch del canal, RetroArch cierra el juego que estaba abierto. Lo que guardaste dentro del juego llega al archivo en 10 segundos como mucho, así que esperá un momento antes de cambiar de juego. El mapa de botones es para los controles de las TVs Philips (receptor "MStar Smart TV IR Receiver"); con otro control, configuralo en RetroArch → Ajustes → Entrada.

## Audio incluido

El APK trae los remakes de Soffizlly: 7 efectos y 3 músicas. Suenan apenas lo instalás, sin bajar nada más.

Al cerrar el aviso suena el arranque, y la música del menú entra un segundo después, en bucle. La Plaza Mii y el editor tienen su propia música. La música se pausa cuando salís de cada pantalla, y espera si otra app está reproduciendo algo.

Los botones wiiOS, SD y el sobre, y los paneles del Canal Mii, usan `wii_big_buttons.ogg`. `wii_woop.ogg` suena una sola vez cuando dejás la manito medio segundo sobre un canal; si antes te movés, abrís un panel o salís del menú, no suena. `wii_hover_delay_ms` en `config.xml` cambia la demora, y `0` lo apaga.

Los archivos están en `launcher/app/src/main/res/raw/`: los efectos y la música del menú en OGG Vorbis, y las de la Plaza y el editor en Opus. Mirá el [mapa de audios y créditos](tools/sounds/README.md). Para usar tu propia música, mirá [Tu propia música](#tu-propia-música).

### Tu propia música

En lugar de la música incluida, el menú puede tocar la tuya. Cargala con `tools/music/add.py`, que necesita [ffmpeg](https://ffmpeg.org) en la computadora:

```sh
TV_HOST=192.168.1.50 tools/venv/bin/python tools/music/add.py musica.flac --start arranque.flac --loop 12.5 48
```

- `--loop INICIO FIN`, en segundos: la música suena hasta FIN y sigue desde INICIO, una y otra vez. El último segundo y medio antes de FIN se funde con lo que suena justo antes de INICIO, así no se nota el salto (`--crossfade` cambia cuánto dura). Sin `--loop` se repite el archivo entero.
- `--start` es opcional: un sonido de arranque que reemplaza al del menú. La música entra un segundo después, como con la incluida.
- Lee los formatos que lee ffmpeg (mp3, flac, ogg, m4a, wav) y los guarda en la TV como WAV, en la carpeta de wiiOS. Con `adb` instalado (o `ADB=/ruta/a/adb`), la copia tarda un segundo.
- `--remove` saca tu música y vuelve la incluida, y `--out carpeta` solo arma los archivos, sin tocar la TV.

## Personalizar

Cambiá lo que quieras y volvé a correr `launcher/build-and-install.sh` para aplicarlo.

| Qué | Dónde |
| --- | --- |
| Qué apps van primero y en qué orden | `dock_apps` en `launcher/app/src/main/res/values/config.xml`. Son nombres de paquete; las demás apps siguen en orden alfabético. |
| Apps que no aparecen como canal | `hidden_apps`, en el mismo archivo. |
| Texto del botón izquierdo | `wii_settings_label` (por defecto `wiiOS`). |
| Sonidos sí o no | `wii_sounds`. |
| Música sí o no (menú, Plaza Mii y editor) | `wii_music`. |
| Volumen de la música | `wii_music_volume`, en porcentaje del volumen del archivo (100 por defecto). La Plaza y el editor suenan al 40 % de eso. |
| App de TV en vivo de la lista de entradas | `live_tv_package`. |
| Textos de la pantalla de aviso | `wii_intro_title`, `wii_intro_body` y `wii_intro_continue` en `res/values*/strings.xml`. `values-es-rAR` usa voseo. |
| Archivos de audio incluidos | `launcher/app/src/main/res/raw/`; ver `tools/sounds/README.md`. El generador antiguo escribe alternativas en `tools/sounds/build/`. |
| Colores, tamaños y posiciones | Constantes al principio de `WiiMenuView.java`. El fondo y los paneles están en `WiiBase.java` y `WiiPalette.java`. |
| App del botón SD | `MEDIA_PLAYER` en `WiiMenuActivity.java`. |
| Canal Juegos sí o no | `games_channel` en `config.xml`. Con el canal, RetroArch no aparece como canal aparte. |
| Canal Wiimote sí o no, y su servidor | `wiimote_channel` y `wiimote_url` en `config.xml`. El servidor se sube con `cd wiimote && npx wrangler deploy`, con tu dominio en `wiimote/wrangler.jsonc`. Con `wiimote_url` vacío, el celu no se usa en ningún lado. |

Para ver los nombres de paquete de tus apps:

```sh
TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'pm list packages -3'
```

Protector de pantalla con reloj:

```sh
TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'settings put secure screensaver_components com.gathin.tvhome/.ClockDream'
```

## Deshacer todo

Para reactivar las apps que desactivaste y volver al launcher original, pasá la misma lista que usaste:

```sh
TV_HOST=192.168.1.50 tools/restore.sh examples/philips-65pud7906/debloat.txt com.google.android.tvlauncher
```

En Google TV el último argumento es `com.google.android.apps.tv.launcherx`. Después podés desinstalar wiiOS:

```sh
TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'pm uninstall com.gathin.tvhome'
```

Si solo querés volver al launcher original, sin tocar las demás apps:

```sh
TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'pm enable --user 0 com.google.android.tvlauncher'
TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'cmd package set-home-activity --user 0 com.google.android.tvlauncher/.MainActivity'
```

## Problemas comunes

- **Home sigue abriendo el launcher viejo.** En Google TV, el asistente de configuración (`com.google.android.tungsten.setupwraith`) también puede reclamar Home. Mirá la [guía de problemas de cleanyourtv](https://github.com/Gathin23/cleanyourtv/blob/main/docs/TROUBLESHOOTING.md) antes de desactivarlo: en algunas TVs es el dueño del Wi-Fi.
- **Falta una app.** El menú muestra las apps hechas para TV. Fijate que no esté en `hidden_apps`.
- **El botón SD dice "No disponible".** Tu TV no tiene el reproductor de MediaTek; poné el de tu TV en `MEDIA_PLAYER`.
- **Un juego no arranca.** Fijate que RetroArch tenga el núcleo de esa consola (paso 1 de [Juegos](#juegos)) y que el juego aparezca en `tools/games/add.py --list`. En Neo Geo, que `neogeo.zip` esté en la TV y que el set sea de tu versión de FinalBurn Neo.
- **No se escuchan los sonidos.** Suben y bajan con el volumen de la TV. Fijate que `wii_sounds` sea `true`.
- **No suena la música del menú.** Fijate que `wii_music` sea `true`, que `wii_music_volume` sea mayor que cero y que el volumen de la TV no esté en cero. La música ya viene en el APK. Si otra app está reproduciendo algo en segundo plano, el menú espera a que termine.
- **`tv.py` no conecta.** La TV y la computadora tienen que estar en la misma red, con la depuración activada, y tenés que aceptar el aviso en la pantalla de la TV.

## Cómo está hecho

- `launcher/` es una app Android en Java. `WiiMenuActivity` es la pantalla de inicio y `WiiMenuView` dibuja todo: canales, reloj, botones, manito, aviso y la lista de entradas. `WiiBase` y `WiiPalette` calculan el fondo rayado y los paneles píxel por píxel, en un hilo aparte. `WiiSounds` reproduce los efectos, `WiiMusic` las pistas incluidas y `MenuMusic` los reemplazos opcionales del menú.
- `GamesActivity` y `GamesView` son el canal Juegos; `PixelScene` dibuja la pradera del cartel y `PixelArt` los cartuchos y las letras. `GameList` lee `games.txt` y `RetroArch` arma el pedido para abrir cada juego.
- `Wiimote` conecta la pantalla que está al frente con el servidor (`WiimoteLink`, un WebSocket sin librerías) y convierte los botones del celu en teclas del control; `WiimoteActivity` muestra el código QR. `wiimote/` es el servidor (un Worker de Cloudflare con un Durable Object por TV) y la página del celu.
- La app también trae el launcher de cleanyourtv (`MainActivity`). Ya no es la pantalla de inicio, pero sigue en el código.
- `tools/` tiene un cliente ADB por red en Python (`tv.py`, no hace falta instalar `adb`), los scripts para desactivar y restaurar apps, el respaldo, el generador de sonidos, `games/add.py` para los juegos y `music/add.py` para la música del menú.
- `tests/` prueba los scripts contra una TV simulada: `python3 -m unittest discover -s tests`.

## Créditos y licencia

- Basado en [cleanyourtv](https://github.com/Gathin23/cleanyourtv) de Gathin (licencia MIT): las herramientas ADB, los scripts para desactivar y restaurar apps y la base del launcher.
- Tipografía Outfit, con licencia SIL Open Font License 1.1 (`launcher/FONT-LICENSE-OFL.txt`).
- Remakes de audio: Soffizlly (2026), incluidos con autorización bajo la licencia MIT del repositorio.
- Licencia MIT: ver [LICENSE](LICENSE).
