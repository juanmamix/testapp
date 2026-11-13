# Teclado Adaptativo: notas para continuar

Teclado Android (IME) en español con zonas táctiles adaptativas al estilo iOS,
predicción, autocorrección, aprendizaje del usuario (palabras, orden y emojis) y UI
basada en el skill `.claude/skills/apple-design` (springs, feedback inmediato,
rubber band). Hablar con el usuario en español.

## Estado

- Se escribió en una sesión en la nube **sin Android SDK**. El módulo `core/` está
  compilado y probado (16 tests en verde). `app/` solo se comprobó compilando contra
  los jars del framework (robolectric android-all). **Nunca se ha compilado con AGP ni
  se ha ejecutado en un dispositivo o emulador.** Lo primero es compilarlo de verdad y
  probarlo.
- Rama de trabajo: `claude/android-keyboard-adaptive-prediction-6vddn6`.

## Requisitos en el PC

- JDK 17 (o el JBR de Android Studio).
- Android SDK con platform 35 y build-tools, más un emulador (AVD) con API 26 o
  superior. Lo más fácil es instalar Android Studio.
- `local.properties` con `sdk.dir=...` (Android Studio lo crea solo), o la variable
  `ANDROID_HOME`.
- Gradle wrapper 8.14.3, AGP 8.7.3 y Kotlin 2.0.21. Sin dependencias externas en
  runtime.

## Comandos

```bash
./gradlew :core:test                 # motor: predicción, corrección, zonas táctiles
./gradlew :app:installDebug          # instala en el emulador/dispositivo conectado
adb shell ime enable com.juanma.teclado/.TecladoService
adb shell ime set com.juanma.teclado/.TecladoService
adb logcat --pid=$(adb shell pidof com.juanma.teclado)
```

Para probar, abre la app «Teclado Adaptativo», que tiene un campo de prueba, o
cualquier campo de texto. El interruptor «Ver las zonas táctiles» dibuja los bordes
adaptativos.

## Arquitectura

- `core/` contiene Kotlin puro (sin Android), testeable en JVM:
  - `TouchModel`: resuelve qué tecla se tocó. Usa una gaussiana por tecla más el log
    de la probabilidad de la letra; el centro de cada tecla está protegido (`core`) y
    el crecimiento tiene un máximo (`maxReach`). Aprende el desvío por tecla.
  - `LanguageModel` + `Trie`: diccionario (`app/src/main/assets/dict_es.txt`, 40k
    palabras), probabilidad de la siguiente letra, n-gramas del usuario (bigramas y
    trigramas, `<s>` = inicio de frase) y asociaciones palabra → emoji.
  - `Suggester`: sugerencias, autocorrección (Damerau-Levenshtein ponderado por
    proximidad de teclas, tildes) y emoji en la barra.
  - `Layouts`, `EmojiData`, `TextUtil`.
- `app/`:
  - `TecladoService` (InputMethodService): texto en composición, autocorrección y
    deshacer, palabra bajo el cursor, aprendizaje y persistencia en `filesDir/*.tsv`.
  - `KeyboardView`: dibuja todo en Canvas, gestiona multitouch, tildes con pulsación
    larga, trackpad en el espacio y springs.
  - `EmojiPane`: panel de emojis dentro de `KeyboardView` (scroll con inercia y rubber
    band).
  - `Spring`: spring con damping/response que respeta la reducción de animaciones.
  - `SettingsActivity`: UI construida en código (no hay `R` ni layouts XML).

## Pendiente o a vigilar al probar

1. Que compile con AGP y que el IME aparezca y se active.
2. Cursor y texto en composición en apps reales (WhatsApp, Chrome): sincronización en
   `onUpdateSelection`, `resumeComposing`, y deshacer la autocorrección con backspace.
3. Ajustar el tacto: `TouchModel.sigma/priorWeight/core/maxReach` y los umbrales de
   `Suggester` (`AUTOCORRECT_THRESHOLD`, `COST_WEIGHT`).
4. Rendimiento: carga del diccionario en segundo plano (unos 120 ms en JVM) y coste de
   `computeZones` con la superposición activada.
5. Insets de la barra de navegación (targetSdk 34; revisar en Android 15).
6. Renderizado de emojis compuestos (banderas, ❤️‍🔥) en el panel.
7. La CI (`.github/workflows/build.yml`) genera el APK release firmado con la clave
   debug.
