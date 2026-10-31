# Instrucciones para Copiar Archivos MP3 a la TV mediante ADB

Esta guía explica los pasos para transferir música manualmente desde tu computadora hacia el almacenamiento del Android TV usando comandos `adb`.

---

## 1. Conectar a la TV vía ADB

Asegúrate de que la TV tenga activada la **Depuración por red / Depuración inalámbrica** en las *Opciones de Desarrollador*.

Ejecuta en tu terminal (PowerShell o CMD):

```bash
adb connect <IP_DE_LA_TV>:<PUERTO>
```

> **Ejemplo:**
> ```bash
> adb connect 192.168.1.92:44723
> ```

Verifica que el dispositivo aparezca conectado:

```bash
adb devices
```

---

## 2. Copiar un Archivo Específico

Para transferir una canción concreta a la carpeta `/sdcard/Music/`:

```bash
adb -s <IP_DE_LA_TV>:<PUERTO> push "nombre del archivo.mp3" /sdcard/Music/
```

> **Ejemplo:**
> ```bash
> adb -s 192.168.1.92:44723 push "mario galaxy The Star Festival.mp3" /sdcard/Music/
> ```

---

## 3. Copiar Todos los MP3 (PowerShell)

Para transferir todos los archivos `.mp3` de esta carpeta a la vez:

```powershell
Get-ChildItem -Filter *.mp3 | ForEach-Object {
    adb -s 192.168.1.92:44723 push $_.FullName /sdcard/Music/
}
```

### Copiar todos excepto uno en particular (Ejemplo: excluir 'smash'):

```powershell
Get-ChildItem -Filter *.mp3 | Where-Object { $_.Name -notmatch "smash" } | ForEach-Object {
    adb -s 192.168.1.92:44723 push $_.FullName /sdcard/Music/
}
```

---

## 4. Verificar los Archivos en la TV

Para listar las canciones presentes en la carpeta de música de la TV:

```bash
adb -s 192.168.1.92:44723 shell ls -la /sdcard/Music
```

---

## 5. Eliminar un Archivo de la TV (Opcional)

Si necesitas remover alguna canción del televisor:

```bash
adb -s 192.168.1.92:44723 shell rm "/sdcard/Music/nombre_archivo.mp3"
```

