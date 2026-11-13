# Teclado Adaptativo (Android)

Un teclado para Android en español con:

- **Zonas táctiles adaptativas (como el iPhone).** Las teclas que ves no cambian, pero
  mientras escribes la zona invisible de cada letra se agranda si es probable que sea la
  siguiente y se encoge si no lo es. Después de «qu», por ejemplo, la `e` y la `i` captan
  los toques dudosos de sus vecinas. Para que nunca resulte raro:
  - si tocas el centro de una tecla, siempre sale esa tecla;
  - el espacio, el borrado, el shift, etc. conservan su forma exacta;
  - además aprende hacia dónde sueles desviarte en cada tecla.

  Activa *Mostrar zonas táctiles* en los ajustes para ver los bordes reales (líneas azules).
- **Predicción** de la palabra que estás escribiendo y de la siguiente (aprende tus parejas
  de palabras).
- **Corrección** al pulsar espacio o un signo de puntuación: errores de dedo según la
  distancia entre teclas, letras cambiadas de orden y tildes («cancion» → «canción»).
  Si borras justo después de una corrección, se deshace y se aprende tu palabra.
- **Corrección de la palabra seleccionada**: pon el cursor sobre cualquier palabra ya
  escrita y la barra te propone alternativas; al tocar una, se sustituye.
- Mantén pulsada una vocal para ver las tildes (á, ü…) y la `n` para la `ñ`.
- Desliza sobre el espacio para mover el cursor. Doble espacio = punto.
- Funciona sin conexión a internet y nunca aprende de los campos de contraseña.

## Instalar

1. En GitHub, abre **Actions → Build APK →** la última ejecución y descarga el artefacto
   `teclado-adaptativo-apk`, o compílalo tú con `./gradlew :app:assembleRelease`.
2. Instala el APK (habilita «instalar apps desconocidas»).
3. Abre *Teclado Adaptativo*, pulsa **Activar** y luego **Elegir**.

## Estructura

- `core/` es Kotlin puro, sin Android, y se puede probar en cualquier máquina:
  - `TouchModel.kt` decide qué tecla has tocado (gaussiana por tecla × probabilidad de
    la letra).
  - `LanguageModel.kt` + `Trie.kt` contienen el diccionario, la probabilidad de la
    siguiente letra y lo que aprende de ti.
  - `Suggester.kt` se encarga de las sugerencias y la autocorrección.
  - Los tests se ejecutan con `./gradlew :core:test`.
- `app/` contiene el servicio de teclado (`TecladoService`), la vista (`KeyboardView`) y
  los ajustes.

Diccionario: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (Hermit Dave),
basado en OpenSubtitles 2018, CC BY-SA 4.0. Se ha filtrado y se han quitado las variantes
sin tilde que son faltas de ortografía.
