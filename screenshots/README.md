# Captura de Pantalla en 1 Solo Comando

### 1. Conectar a la TV (si no está conectada)
```powershell
adb connect 192.168.1.92:40017
```

---

### 2. Tomar captura con fecha y hora (1 solo comando en PowerShell)

```powershell
cmd /c "adb -s 192.168.1.92:40017 exec-out screencap -p > screenshots\captura_tv_$(Get-Date -Format 'yyyyMMdd_HHmmss').png"
```

> **Nota:** Si tienes más de un dispositivo conectado en ADB, especifica la TV con `-s`:
> ```powershell
> cmd /c "adb -s 192.168.1.92:40017 exec-out screencap -p > screenshots\captura_tv_$(Get-Date -Format 'yyyyMMdd_HHmmss').png"
> ```
