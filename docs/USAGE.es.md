# Guía de uso

**Idiomas:** [English](USAGE.md) · [日本語](USAGE.ja.md) · [简体中文](USAGE.zh-Hans.md) · Español · [हिन्दी](USAGE.hi.md) · [العربية](USAGE.ar.md) · [한국어](USAGE.ko.md) · [Русский](USAGE.ru.md) · [Français](USAGE.fr.md)

Esta aplicación ejecuta un **nodo completo no validador** de Quantus Network (red principal de QTC) en un teléfono Android. Sincroniza la cadena, verifica cada bloque por sí mismo y retransmite datos a sus pares. **No** mina y **no** tiene billetera: nunca toca frases semilla ni claves de recompensa.

> Estado: pre-lanzamiento (Phase 1 MVP). Aún no hay una versión pública; las compilaciones de prueba las comparte directamente el mantenedor. Cuando comiencen las publicaciones, los APK firmados se publicarán en GitHub Releases con sumas de verificación SHA-256.

## Requisitos

- Android 12 o posterior, dispositivo arm64
- Espacio libre: la base de datos del nodo está podada (solo se conservan los últimos 256 bloques), por lo que está diseñada para mantenerse pequeña. El tamaño exacto en el dispositivo aún se está midiendo; como guía, mantén unos 10 GB libres para la primera sincronización
- Conexión a internet. Se recomienda encarecidamente Wi-Fi para la primera sincronización (ver más abajo)

Verificado hasta ahora: **Pixel 9a con GrapheneOS**. Se espera que otros dispositivos funcionen, pero no están verificados. Si lo pruebas en otro modelo, informa del resultado (funcione o no) en un Issue de GitHub: la lista de dispositivos verificados crece con esos informes.

## Instalación

1. Obtén el archivo APK (del mantenedor por ahora; de GitHub Releases cuando comiencen las publicaciones; verifica la suma SHA-256 publicada con la versión antes de instalar).
2. Abre el APK con tu aplicación de Archivos y permite la instalación desde esa fuente cuando Android lo pida.
3. Instala y abre **Quantus Node**.

## Primera ejecución y sincronización inicial

1. (Opcional) Configura primero el **nombre visible** y la opción de **telemetría** (ver Ajustes). Los cambios de configuración requieren reiniciar el nodo (Detener → Iniciar) para aplicarse.
2. Toca **Iniciar nodo**.
3. El estado mostrará **Sincronizando** y el mejor bloque subirá hacia el bloque más alto. En un Pixel 9a, la primera sincronización desde el génesis avanza a unos 50 bloques por segundo: calcula unas **2 horas**. Una vez que alcanzas la punta de la cadena, llega un bloque nuevo cada 12 segundos aproximadamente y el estado pasa a sincronizado.
4. Durante la primera sincronización, mantén el teléfono **cargando y con Wi-Fi** si puedes. Sincronizar con datos móviles funciona, pero descarga muchos datos y consume notablemente más batería (medido: alrededor de un 11 % en 30 minutos sincronizando con datos móviles). Cuando la sincronización termina, la carga continua es pequeña.

No necesitas mantener la aplicación abierta. El nodo funciona como un servicio en primer plano: sigue ejecutándose con la pantalla apagada o mientras usas otras aplicaciones, y muestra una notificación permanente mientras funciona. Si lo detienes y lo vuelves a iniciar, la sincronización continúa donde iba: la cadena descargada se conserva en el dispositivo.

## Qué muestra la pantalla

- **Estado** — Sincronizando / sincronizado, con una onda tranquila en la vista de pulso cada vez que llega un nuevo mejor bloque
- **Mejor bloque / Bloque más alto** — dónde está tu nodo frente a la punta de la red que conoce
- **Pares** — a cuántos nodos estás conectado
- **Trayectoria** — tu registro local: tiempo total de funcionamiento, nuevos bloques observados y el máximo de pares observados. Se guarda solo en este dispositivo; nunca se sube y no vale dinero: es simplemente tu propia huella de contribución
- **Quantum Watch** — el estado de sincronización de tu nodo y la salud de su conexión a la red (la información externa sobre amenazas aún no está implementada)

## Ajustes

- **Aparecer en la telemetría** (desactivado por defecto): cuando está activado, el nombre de tu nodo y su estado en vivo son visibles públicamente en la telemetría oficial en telemetry.quantus.com. Actívalo solo si quieres aparecer en la lista. Se aplica desde el siguiente inicio del nodo (Detener → Iniciar).
- **Nombre visible** — el nombre que usa tu nodo (también se muestra en la telemetría si está activada). Déjalo vacío para usar el nombre por defecto `quantus-android-node`. Se aplica desde el siguiente inicio del nodo.
- **Idioma** — sigue el ajuste del dispositivo o elige uno de 9 idiomas (japonés, inglés, chino simplificado, español, hindi, árabe, coreano, ruso, francés).
- **Compartir registro** — comparte el registro del nodo como texto. Es lo que debes adjuntar al informar de un problema. Antes de pegar un registro en un lugar público, revisa su contenido: puede incluir la dirección IP pública de tu nodo e información de los pares.
- **Bloquear ajustes** — bloquea los tres ajustes anteriores (telemetría, nombre visible e idioma) para que no se cambien por accidente mientras el teléfono queda ejecutando el nodo. Es solo una protección contra toques involuntarios: no hay PIN, y el inicio y la detención del nodo nunca se bloquean. Pulsa **Desbloquear ajustes** cuando quieras volver a cambiarlos.

Los ajustes nunca reinician el nodo por sí solos. Si cambias algo con el nodo en marcha, la pantalla te avisará de que es necesario reiniciar (Detener → Iniciar).

## Uso diario

- El nodo solo se detiene cuando tocas **Detener nodo**, fuerzas la detención de la aplicación o reinicias el teléfono.
- Tras un reinicio del teléfono, abre la aplicación y toca **Iniciar nodo** de nuevo: el inicio automático tras el arranque aún no está implementado.
- En algunas versiones de Android de ciertos fabricantes (Samsung, Xiaomi, etc.), el ahorro agresivo de batería puede detener los servicios en segundo plano. Si tu nodo se detiene solo, excluye esta aplicación de la optimización de batería en los ajustes del sistema.
- ¿Dejas un teléfono de repuesto enchufado las 24 horas? Revisa la batería de vez en cuando: las baterías de litio viejas pueden hincharse. Mantén el teléfono en un lugar ventilado, idealmente con un límite de carga si tu dispositivo lo ofrece.

## Si algo sale mal

- **El nodo se cierra enseguida** — toca **Compartir registro** en Ajustes y envía el registro con un Issue de GitHub (plantilla de informe de error). El registro muestra cada intento de inicio y por qué terminó.
- **Los pares se quedan en 0** — revisa la conexión y luego Detener → Iniciar una vez.
- **Tu nodo no aparece en la telemetría** — asegúrate de que la opción de telemetría está activada y de que reiniciaste el nodo después de activarla. (El comportamiento del listado aún se está verificando).
- **Dudas de almacenamiento** — la base de datos está podada a los últimos 256 bloques por diseño. Desinstalar la aplicación borra la base de datos y la clave de identidad del nodo; una reinstalación empieza una sincronización nueva con una identidad nueva.

Preguntas, informes de dispositivos e ideas son bienvenidos como Issues de GitHub. Los problemas de seguridad siguen [SECURITY.md](../SECURITY.md) en lugar de un Issue público.
