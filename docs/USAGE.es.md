# Guía de uso

**Idiomas:** [English](USAGE.md) · [日本語](USAGE.ja.md) · [简体中文](USAGE.zh-Hans.md) · Español · [हिन्दी](USAGE.hi.md) · [العربية](USAGE.ar.md) · [한국어](USAGE.ko.md) · [Русский](USAGE.ru.md) · [Français](USAGE.fr.md)

Esta aplicación ejecuta un **nodo completo no validador** de Quantus Network (red principal de QTC) en un teléfono Android. Sincroniza la cadena, verifica cada bloque por sí mismo y retransmite datos a sus pares. **No** mina y **no** tiene billetera: nunca toca frases semilla ni claves de recompensa.

> Estado: v3.0 publicada (2026-11-11), la versión actual: la versión con integración DePIN. El APK firmado está disponible en [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) con suma de verificación SHA-256. Las compilaciones de prueba anteriores las compartía directamente el mantenedor.

## Requisitos

- Android 12 o posterior, dispositivo arm64
- Espacio libre: la base de datos del nodo está podada (solo se conservan los últimos 256 bloques), por lo que se mantiene pequeña. Medido en un Pixel 9a tras una sincronización completa (2026-11-05): 1,30 GB en total (96 MB de aplicación + 1,20 GB de datos del nodo). Como guía, mantén al menos unos 3 GB libres
- Conexión a internet. Se recomienda encarecidamente Wi-Fi para la primera sincronización (ver más abajo)

Verificado hasta ahora: **Pixel 9a con GrapheneOS**. Se espera que otros dispositivos funcionen, pero no están verificados. Si lo pruebas en otro modelo, informa del resultado (funcione o no) en un Issue de GitHub: la lista de dispositivos verificados crece con esos informes.

## Instalación

1. Obtén el archivo APK en [GitHub Releases](https://github.com/ogaworld369/quantus-android-node/releases) (verifica la suma SHA-256 publicada con la versión antes de instalar).
2. Abre el APK con tu aplicación de Archivos y permite la instalación desde esa fuente cuando Android lo pida.
3. Instala y abre **Quantus Node**.

## Primera ejecución y sincronización inicial

1. (Opcional) Configura primero el **nombre visible** y la opción de **telemetría** (ver Ajustes). Los cambios de configuración requieren reiniciar el nodo (Detener → Iniciar) para aplicarse.
2. Toca **Iniciar nodo**.
3. El estado mostrará **Sincronizando** y el mejor bloque subirá hacia el bloque más alto. En un Pixel 9a, la primera sincronización desde el génesis avanza a unos 50 bloques por segundo: calcula unas **2 horas**. Una vez que alcanzas la punta de la cadena, llega un bloque nuevo cada 12 segundos aproximadamente y el estado pasa a sincronizado.
4. Durante la primera sincronización, mantén el teléfono **cargando y con Wi-Fi** si puedes. Sincronizar con datos móviles funciona, pero descarga muchos datos y consume notablemente más batería (medido: alrededor de un 11 % en 30 minutos sincronizando con datos móviles). Cuando la sincronización termina, la carga continua es pequeña.

No necesitas mantener la aplicación abierta. El nodo funciona como un servicio en primer plano: sigue ejecutándose con la pantalla apagada o mientras usas otras aplicaciones, y muestra una notificación permanente mientras funciona. Si lo detienes y lo vuelves a iniciar, la sincronización continúa donde iba: la cadena descargada se conserva en el dispositivo.

## Qué muestran las pantallas

La aplicación tiene tres pestañas en la parte inferior: Inicio, Conexión y Tarjeta de miembro. A Detalles del nodo y a Ajustes se llega desde Inicio.

- **Inicio** — el pulso, el estado del nodo (Detenido / Iniciando / Sincronizando / Sincronizado / Error) y el botón principal. Mientras el nodo funciona, también muestra el número de pares conectados, el tiempo de funcionamiento de esta sesión y el precio actual de QTC. El pulso dibuja una sola onda tranquila solo mientras el nodo está sincronizado, en el momento en que se observa por primera vez un nuevo mejor bloque. Si un inicio falla, Inicio lo muestra como un error con un botón de reintento, en lugar de una detención normal; si un ajuste que cambiaste aún no ha entrado en vigor, Inicio muestra un enlace para revisarlo
- **Conexión** — una imagen viva del campo de red de tu nodo: los pares a los que está conectado y los ecos de sus propios puntos de control registrados. Muestra relaciones, no cifras para competir: no hay clasificación
- **Detalles del nodo** — mejor bloque / bloque más alto (dónde está tu nodo frente a la punta de la red que conoce), la brecha de sincronización, los pares y el tiempo de funcionamiento, y la sección **Diagnóstico**: código de salida, notas del servicio, el registro de cada intento de inicio y **Compartir registro** (ver más abajo). Mientras el nodo está detenido, esta pantalla muestra tu trayectoria acumulada en lugar de los valores de la sesión anterior
- **Tarjeta de miembro** — tu tarjeta de miembro: tu número de miembro (el ID de tu dispositivo) y las cifras principales de tu registro local: tiempo total de funcionamiento, nuevos bloques observados y el máximo de pares observados. El registro se guarda solo en este dispositivo; nunca se sube y no vale dinero: es simplemente tu propia huella de contribución, y mostrarla o no es decisión tuya
- **Ajustes** — los ajustes siguientes; la versión de la aplicación se muestra al final

## Ajustes

- **Aparecer en la telemetría** (desactivado por defecto): cuando está activado, el nombre de tu nodo y su estado en vivo son visibles públicamente en la telemetría oficial en telemetry.quantus.com. Actívalo solo si quieres aparecer en la lista. Se aplica desde el siguiente inicio del nodo (Detener → Iniciar).
- **Nombre visible** — el nombre que usa tu nodo (también se muestra en la telemetría si está activada), que se edita en un diálogo. Déjalo vacío para usar el nombre por defecto `quantus-android-node`. Se aplica desde el siguiente inicio del nodo.
- **Idioma** — sigue el ajuste del dispositivo o elige uno de 9 idiomas (japonés, inglés, chino simplificado, español, hindi, árabe, coreano, ruso, francés). Se aplica de inmediato.
- **Registrar mi actividad (DePIN)** (desactivado por defecto): cuando está activado, la aplicación guarda en este dispositivo un registro firmado del tiempo de funcionamiento y del trabajo de retransmisión del nodo (un punto de control cada 10 minutos). Nada se envía solo a ninguna parte; desde Ajustes puedes exportar el registro como un archivo de paquete de prueba y compartirlo tú mismo. Al desactivarlo el registro se detiene; lo ya registrado permanece en el dispositivo
- **Bloquear ajustes** — bloquea los ajustes anteriores (telemetría, nombre visible, idioma y registro de actividad) para que no se cambien por accidente mientras el teléfono queda ejecutando el nodo. Es solo una protección contra toques involuntarios: no hay PIN, y el inicio y la detención del nodo nunca se bloquean. Desactiva **Bloquear ajustes** cuando quieras volver a cambiarlos.

Los ajustes nunca reinician el nodo por sí solos. Si cambias la telemetría o el nombre visible con el nodo en marcha, el elemento cambiado se marca en Ajustes como aún no aplicado, e Inicio muestra un enlace para revisar los ajustes pendientes; el cambio entra en vigor en el siguiente inicio del nodo (Detener → Iniciar).

**Compartir registro** está en **Detalles del nodo → Diagnóstico**: comparte el registro del nodo como texto, y es lo que debes adjuntar al informar de un problema. Antes de pegar un registro en un lugar público, revisa su contenido: puede incluir la dirección IP pública de tu nodo e información de los pares. El diálogo para compartir puede enmascarar el nombre visible, las direcciones de las interfaces de este dispositivo y las rutas privadas de la aplicación (activado por defecto); la dirección exterior que el nodo conoce a través de sus pares no se enmascara.

## Uso diario

- El nodo solo se detiene cuando tocas **Detener nodo**, fuerzas la detención de la aplicación o reinicias el teléfono.
- Tras un reinicio del teléfono, abre la aplicación y toca **Iniciar nodo** de nuevo: el inicio automático tras el arranque aún no está implementado.
- En algunas versiones de Android de ciertos fabricantes (Samsung, Xiaomi, etc.), el ahorro agresivo de batería puede detener los servicios en segundo plano. Si tu nodo se detiene solo, excluye esta aplicación de la optimización de batería en los ajustes del sistema.
- ¿Dejas un teléfono de repuesto enchufado las 24 horas? Revisa la batería de vez en cuando: las baterías de litio viejas pueden hincharse. Mantén el teléfono en un lugar ventilado, idealmente con un límite de carga si tu dispositivo lo ofrece.

## Si algo sale mal

- **El nodo se cierra enseguida** — abre **Detalles del nodo → Diagnóstico → Compartir registro** y envía el registro con un Issue de GitHub (plantilla de informe de error). El registro muestra cada intento de inicio y por qué terminó.
- **Los pares se quedan en 0** — revisa la conexión y luego Detener → Iniciar una vez.
- **La sincronización se atasca (tu mejor bloque se detiene mientras la red avanza)** — la aplicación detecta esta situación y reinicia el nodo por sí sola (como máximo 3 veces en 24 horas). Si se repite, ayuda una conexión Wi-Fi estable; después, Detener → Iniciar una vez
- **Tu nodo no aparece en la telemetría** — asegúrate de que la opción de telemetría está activada y de que reiniciaste el nodo después de activarla. (El listado está verificado: el nodo del propio mantenedor aparece en la lista oficial).
- **Dudas de almacenamiento** — la base de datos está podada a los últimos 256 bloques por diseño. Desinstalar la aplicación borra la base de datos y la clave de identidad del nodo; una reinstalación empieza una sincronización nueva con una identidad nueva.

Preguntas, informes de dispositivos e ideas son bienvenidos como Issues de GitHub. Los problemas de seguridad siguen [SECURITY.md](../SECURITY.md) en lugar de un Issue público.
