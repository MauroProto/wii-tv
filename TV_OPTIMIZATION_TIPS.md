# Guía y Tips de Optimización (Debloat) de Android TV

Este documento recopila el contexto, la lista de paquetes y los comandos utilizados para optimizar el rendimiento del televisor (**TCL Smart TV Pro con Android TV 14**) donde se ejecuta y prueba este lanzador (**Wii TV** / `com.gathin.tvhome`).

---

## 1. Contexto del Entorno

* **Dispositivo:** TCL Smart TV Pro (Android TV 14, procesador MediaTek).
* **Limitación de Hardware:** El televisor cuenta con ~2.5 GB de RAM física. Con la configuración de fábrica, el sistema ocupaba más de 1.6 GB de RAM en reposo debido a telemetría de TCL, servicios redundantes de Google heredados de smartphones y apps preinstaladas, lo cual provocaba caídas de fotogramas o cierres inesperados (OOM Killer) al abrir emuladores (RetroArch) o animaciones pesadas en el lanzador.
* **Objetivo:** Liberar memoria RAM activa, reducir consumo de CPU en segundo plano y eliminar telemetría publicitaria sin romper las funciones nativas críticas del televisor (HDMI, audio, sintonizador ni mando).

---

## 2. Acciones Realizadas

Se desinstalaron para el perfil principal (`user 0`) los siguientes paquetes preinstalados:

### A. Bloatware y Telemetría de TCL
* `com.tcl.partnercustomizer`: Banners publicitarios y guías de operadores socios.
* `com.tcl.magiconnectfree`: MagiConnect / T-Cast con trackers publicitarios integrados.
* `com.tcl.smartalexa`: Demonio de integración para altavoces Amazon Alexa.
* `com.tcl.interactive`: Servicio interactivo de seguimiento publicitario (OAID).
* `com.tcl.tv.tclhome_passive`: Servicio de electrodomésticos inteligentes TCL Home.
* `com.tcl.smartlink.core`: Servicio de descarga e instalación remota de apps de TCL.
* `com.tcl.eva`: Asistente de gestos por cámara y voz propio de TCL.
* `com.tcl.usercenter`: Centro de cuentas y registro de usuario TCL.
* `com.tcl.factory.view`: Menú de pruebas de diagnóstico de fábrica.
* `com.tcl.gallery`: Galería multimedia básica de TCL.
* `com.tcl.ui_mediaCenter`: Reproductor Media Center USB de TCL.

### B. Servicios de Google / Android Innecesarios en una TV
* `com.google.android.syncadapters.calendar`: Sincronización continua de Google Calendar (consumía ~138 MB RSS en segundo plano).
* `com.android.providers.calendar`: Almacenamiento local de eventos de calendario.
* `com.android.printspooler`: Cola del servicio de impresión de Android.
* `com.android.providers.contacts`: Base de datos de contactos (`android.process.acore`, consumía ~82 MB RSS).
* `com.google.android.youtube.tv`: YouTube oficial (consumía ~99 MB RSS en recomendaciones; se utiliza SmartTube como alternativa limpia).
* `com.google.android.marvin.talkback`: Lector de pantalla para accesibilidad visual.

### C. Apps de Streaming Preinstaladas
* `com.amazon.amazonvideo.livingroom`: Amazon Prime Video.
* `com.netflix.ninja`: Cliente de Netflix.
* `com.netflix.tokenmanager`: Administrador de tokens de Netflix.

> **Nota:** La aplicación de notificaciones `com.notifytv.tv` (*Notifications for Android TV*) se mantuvo expresamente activa.

---

## 3. Tip Esencial: Reversibilidad de Apps del Sistema

Al utilizar el comando `pm uninstall -k --user 0 <paquete>`, Android elimina los datos y marca el paquete como desinstalado únicamente para el usuario actual, **pero el archivo APK original permanece intacto en la partición `/system` o `/product` del televisor**.

### Cómo Restaurar Cualquier Aplicación
Si en el futuro deseas recuperar o volver a usar cualquiera de las aplicaciones desinstaladas, no necesitas descargar ningún archivo APK de Internet. Basta con conectarte por ADB y ejecutar:

```powershell
# Sintaxis general:
adb -s 192.168.1.92:44723 shell pm install-existing --user 0 <nombre_del_paquete>

# Ejemplos específicos:
# Restaurar Prime Video:
adb -s 192.168.1.92:44723 shell pm install-existing --user 0 com.amazon.amazonvideo.livingroom

# Restaurar Netflix:
adb -s 192.168.1.92:44723 shell pm install-existing --user 0 com.netflix.ninja

# Restaurar YouTube oficial:
adb -s 192.168.1.92:44723 shell pm install-existing --user 0 com.google.android.youtube.tv

# Restaurar MagiConnect de TCL:
adb -s 192.168.1.92:44723 shell pm install-existing --user 0 com.tcl.magiconnectfree
```

---

## 4. Paquetes Críticos que NUNCA Deben Eliminarse

Para evitar fallos en el televisor (como pérdida de señal en puertos HDMI o problemas con el mando), **no** toques los siguientes paquetes:

| Paquete | Función Crítica |
| :--- | :--- |
| `com.tcl.suspension` | Provee el menú de selección de fuentes y puertos físicos (`VIEW_INPUTS` / HDMI 1, HDMI 2, etc.). |
| `com.tcl.tv` | Reproductor nativo de TV en vivo (`TVActivity`) y señal de entrada física. |
| `com.tcl.ttvs` | Administrador de actualizaciones del sistema (OTA) de TCL. |
| `com.tcl.inputmethod.international` | Teclado en pantalla nativo (solo cambiar si Gboard está configurado por defecto). |
| `com.tcl.gamebar` | Barra de ajustes para consolas y PC (VRR, ALLM, selector de tasa de refresco). |
| `com.tcl.autopair` | Emparejamiento Bluetooth del mando a distancia de TCL. |
| `com.tcl.dashboard` | Menú lateral rápido de ajustes de imagen y sonido. |

---

## 5. Comandos Útiles de Diagnóstico y Mantenimiento

```powershell
# Conectar por ADB al televisor:
adb connect 192.168.1.92:44723

# Ver uso de memoria RAM en tiempo real:
adb -s 192.168.1.92:44723 shell dumpsys meminfo

# Ver la app actualmente enfocada en pantalla:
adb -s 192.168.1.92:44723 shell dumpsys window | Select-String "mCurrentFocus"

# Listar paquetes deshabilitados o no instalados para el usuario 0:
adb -s 192.168.1.92:44723 shell pm list packages -d --user 0
adb -s 192.168.1.92:44723 shell pm list packages -u --user 0
```

