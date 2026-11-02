# Reglas y Directrices para Agentes

- **No simular pruebas por ADB:** No ejecutes broadcasts, intents o comandos ADB que inyecten eventos simulados o estados ficticios de hardware (como simulación de conexiones HDMI u otros dispositivos) en el entorno del dispositivo/TV del usuario.
- **Validación con el usuario:** Siempre compila, instala y despliega la aplicación de forma limpia, y solicita al usuario que pruebe directamente las funciones o correcciones solicitadas en el televisor o dispositivo físico.

- **Commits y subida a GitHub automáticos:** Cada vez que el usuario solicite algún cambio, corrección o mejora al proyecto, realiza un commit explicando detalladamente los cambios realizados y súbelos (push) automáticamente al repositorio de GitHub en la rama correspondiente.
