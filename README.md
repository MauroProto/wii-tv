# wii-tv

El menú de canales de la Wii como pantalla de inicio de tu Android TV.

![wiiOS en una Philips 65PUD7906](docs/wii-menu.jpg)

wii-tv cambia la pantalla de inicio de un Android TV por un menú al estilo del Menú Wii: doce canales por página, el reloj de segmentos, la fecha, los botones redondos, la manito que sigue a lo que elegís y sonidos al moverte. Cada canal es una de tus apps, con su propio banner. Al prender la TV aparece una pantalla de aviso blanca, como en la consola, y con OK pasás al menú.

Lo mostré en X: [Le puse la interfaz de la Wii a mi TV](https://x.com/ProtoMauro/status/2112776289312555195).

English: [README.en.md](README.en.md)

## Aviso

wii-tv es un proyecto de fans. No está afiliado a Nintendo ni tiene su aprobación. "Wii" es una marca de Nintendo.

El repositorio no incluye archivos extraídos de Nintendo. La interfaz se dibuja por código (`WiiMenuView`, `WiiBase`, `WiiPalette`). Los 8 efectos y las 3 pistas musicales incluidos son remakes propios aportados por Soffizlly; ver [audios y créditos](tools/sounds/README.md).

## Qué trae

- **Canales**: 4 columnas por 3 filas por página. La primera columna de la página siguiente asoma a la derecha y las flechas cambian de página. Tus apps llenan los canales y los que sobran quedan vacíos. Al abrir un canal, la app crece desde su cuadro, como en la consola. Para cambiar un canal de lugar, mantené OK sobre él y movelo con las flechas; OK lo suelta y el orden queda guardado en la TV.
- **Reloj y fecha**: reloj de segmentos, de 12 horas con AM/PM o de 24 horas según el formato de hora de la TV, y la fecha corta en el idioma de la TV.
- **Botones de abajo**:
  - **wiiOS** (izquierda) abre los Ajustes de Android TV.
  - **SD** abre el reproductor multimedia de la TV (pendrive o disco USB).
  - **Sobre** (derecha) abre la lista de entradas HDMI y la TV en vivo.
- **Manito** que sigue al canal o botón elegido. Al volver de una app está donde la dejaste, aunque Android haya cerrado el menú para liberar memoria.
- **Canal Mii**: el primer canal. La plaza con tus Miis paseando sobre el piso de cuadrados, los botones de los costados (editar, crear, borrar, ayuda, desfile, mensajes), el silbato que los ordena por nombre, favoritos, color o género, y el editor para crearlos y cambiarlos. Los Miis se guardan en la TV. Sin nada más, todo se dibuja por código; con el paquete del canal usa las caras, los pelos, los íconos y los sonidos originales. El repositorio no trae esos archivos: [`tools/miipack`](tools/miipack/README.md) los toma de sus páginas públicas y arma el paquete en tu computadora.
- **Canal Juegos**: al lado del Canal Mii. Su cartel es una pradera pixelada con un personajito azul que salta piedras y junta gemas. Adentro, tus juegos son cartuchos en un estante: elegís uno, apretás **Jugar** y arranca en [RetroArch](https://www.retroarch.com) con mGBA (Game Boy, Game Boy Color y Game Boy Advance). El botón **RetroArch** abre sus ajustes. Los juegos no vienen con el repositorio: ver [Juegos](#juegos).
- **Sonidos** al moverte, al abrir algo, al cambiar de página, al abrir las entradas y al continuar desde el aviso. Usan el volumen de la TV.
- **Audio incluido**: 8 efectos y 3 pistas musicales para el menú, la Plaza Mii y el editor. El arranque suena primero y la música del menú comienza un segundo después. No hay que instalar audios por separado.
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

El canal Juegos arranca tus juegos en RetroArch. Usá solo juegos tuyos: copiá tus propios cartuchos con un lector de cartuchos (por ejemplo GB Operator o GBxCart RW).

1. **Instalá RetroArch en la TV.** La mayoría de los Android TV son de 32 bits y necesitan `RetroArch_ra32.apk`, de la [página de descargas de RetroArch](https://www.retroarch.com/?page=platforms). Abrilo una vez y bajá el núcleo: Actualizador en línea → Descargar núcleos → Nintendo - Game Boy Advance (mGBA). Salí con Home.
2. **Preparalo para el control de la TV** (una sola vez):
   ```sh
   TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py --setup
   ```
   Agrega el mapa de botones de `tools/games/retroarch/` y cambia algunos ajustes de RetroArch: OK elige y Atrás vuelve, mantener el botón rojo abre su menú y el juego se guarda cada 10 segundos. Antes guarda una copia de los ajustes en `retroarch.cfg.before-wii-tv`, en la carpeta de RetroArch de la TV. Si usás un joystick Bluetooth, en RetroArch elegí además Actualizador en línea → Actualizar perfiles de controles.
3. **Sumá tus juegos** (`.gba`, `.gb` o `.gbc`):
   ```sh
   TV_HOST=192.168.1.50 tools/venv/bin/python tools/games/add.py "Mi juego.gba" --title "Mi juego"
   ```
   Copia el archivo a `RetroArch/roms/` en la TV y lo pone en el estante. `--color "#E8432E"` elige el color de la etiqueta. `--list` muestra los juegos, y `--remove "Mi juego"` saca uno (con `--delete` también borra el archivo de la TV).

Con el control de la TV, durante el juego:

| Tecla | Botón |
| --- | --- |
| Flechas | Cruz |
| OK | A |
| Atrás | B |
| Rojo | Start. Mantenelo 2 segundos para el menú de RetroArch (guardar, cargar o cerrar el juego). |
| Verde | Select |
| Amarillo y azul | L y R |
| Home | Vuelve al Menú Wii. El juego queda en pausa y sigue si lo elegís otra vez. |

Si elegís otro juego, o el botón RetroArch del canal, RetroArch cierra el juego que estaba abierto. Lo que guardaste dentro del juego llega al archivo en 10 segundos como mucho, así que esperá un momento antes de cambiar de juego. El mapa de botones es para los controles de las TVs Philips (receptor "MStar Smart TV IR Receiver"); con otro control, configuralo en RetroArch → Ajustes → Entrada.

## Audio incluido

El APK ya incluye los 11 remakes de Soffizlly: 8 efectos y 3 pistas musicales. Funcionan desde la instalación, sin descargar archivos ni ejecutar herramientas adicionales.

Al cerrar el aviso inicial, suena el arranque y la música del menú comienza un segundo después, en bucle. La Plaza Mii y el editor tienen sus propias pistas. La música se pausa al salir de cada pantalla y respeta el foco de audio.

Los botones wiiOS, SD y el sobre usan `wii_big_buttons.ogg`. El aviso `wii_woop.ogg` suena una sola vez al mantener el cursor 0,5 segundos sobre un canal. Cambiar de canal, abrir un panel o salir del menú cancela la espera. `wii_hover_delay_ms` en `config.xml` cambia la demora; `0` lo desactiva.

Los archivos están en `launcher/app/src/main/res/raw/`: efectos y música OGG Vorbis. Consulta el [mapa de audios y créditos](tools/sounds/README.md). Si ya tienes música personalizada instalada con la herramienta opcional `tools/music/add.py`, esa música conserva la prioridad; al quitarla vuelve la pista incluida.

## Personalizar

Cambiá lo que quieras y volvé a correr `launcher/build-and-install.sh` para aplicarlo.

| Qué | Dónde |
| --- | --- |
| Qué apps van primero y en qué orden | `dock_apps` en `launcher/app/src/main/res/values/config.xml`. Son nombres de paquete; las demás apps siguen en orden alfabético. |
| Apps que no aparecen como canal | `hidden_apps`, en el mismo archivo. |
| Texto del botón izquierdo | `wii_settings_label` (por defecto `wiiOS`). |
| Sonidos sí o no | `wii_sounds`. |
| Música del menú sí o no | `wii_music`. |
| Volumen de la música | `wii_music_volume`, en porcentaje del volumen del archivo (100 por defecto). |
| App de TV en vivo de la lista de entradas | `live_tv_package`. |
| Textos de la pantalla de aviso | `wii_intro_title`, `wii_intro_body` y `wii_intro_continue` en `res/values*/strings.xml`. `values-es-rAR` usa voseo. |
| Archivos de audio incluidos | `launcher/app/src/main/res/raw/`; ver `tools/sounds/README.md`. El generador antiguo escribe alternativas en `tools/sounds/build/`. |
| Colores, tamaños y posiciones | Constantes al principio de `WiiMenuView.java`. El fondo y los paneles están en `WiiBase.java` y `WiiPalette.java`. |
| App del botón SD | `MEDIA_PLAYER` en `WiiMenuActivity.java`. |
| Canal Juegos sí o no | `games_channel` en `config.xml`. Con el canal, RetroArch no aparece como canal aparte. |

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
- **Un juego no arranca.** Fijate que RetroArch tenga el núcleo mGBA (paso 1 de [Juegos](#juegos)) y que el juego aparezca en `tools/games/add.py --list`.
- **No se escuchan los sonidos.** Suben y bajan con el volumen de la TV. Fijate que `wii_sounds` sea `true`.
- **No suena la música del menú.** Comprueba que `wii_music` sea `true`, que `wii_music_volume` sea mayor que cero y que el volumen multimedia de la TV esté activo. El APK ya incluye la música. Si otra app reproduce audio en segundo plano, el menú espera a que termine.
- **`tv.py` no conecta.** La TV y la computadora tienen que estar en la misma red, con la depuración activada, y tenés que aceptar el aviso en la pantalla de la TV.

## Cómo está hecho

- `launcher/` es una app Android en Java. `WiiMenuActivity` es la pantalla de inicio y `WiiMenuView` dibuja todo: canales, reloj, botones, manito, aviso y la lista de entradas. `WiiBase` y `WiiPalette` calculan el fondo rayado y los paneles píxel por píxel, en un hilo aparte. `WiiSounds` reproduce los efectos, `WiiMusic` las pistas incluidas y `MenuMusic` los reemplazos opcionales del menú.
- `GamesActivity` y `GamesView` son el canal Juegos; `PixelScene` dibuja la pradera del cartel y `PixelArt` los cartuchos y las letras. `GameList` lee `games.txt` y `RetroArch` arma el pedido para abrir cada juego.
- La app también trae el launcher de cleanyourtv (`MainActivity`). Ya no es la pantalla de inicio, pero sigue en el código.
- `tools/` tiene un cliente ADB por red en Python (`tv.py`, no hace falta instalar `adb`), los scripts para desactivar y restaurar apps, el respaldo, el generador de sonidos, `games/add.py` para los juegos y `music/add.py` para la música del menú.
- `tests/` prueba los scripts contra una TV simulada: `python3 -m unittest discover -s tests`.

## Créditos y licencia

- Basado en [cleanyourtv](https://github.com/Gathin23/cleanyourtv) de Gathin (licencia MIT): las herramientas ADB, los scripts para desactivar y restaurar apps y la base del launcher.
- Tipografía Outfit, con licencia SIL Open Font License 1.1 (`launcher/FONT-LICENSE-OFL.txt`).
- Remakes de audio: Soffizlly (2026), incluidos con autorización bajo la licencia MIT del repositorio.
- Licencia MIT: ver [LICENSE](LICENSE).
