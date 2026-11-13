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
- **Aprende cómo escribes tú.** Aprende:
  - Tus palabras: entran en el diccionario al usarlas dos veces, o a la primera si
    deshaces una corrección.
  - El **orden** en que las usas: qué viene después de cada palabra y de cada pareja de
    palabras (por ejemplo, «gracias de → nada» pero «es de → verdad»).
  - Con qué palabras empiezas las frases.

  Esto se usa para predecir la siguiente palabra, ordenar sugerencias y correcciones, y
  hasta para agrandar las teclas de la palabra que sueles escribir a continuación.
- **Emojis.** Tiene un panel de emojis (tecla ☺) con la sección *Usados con frecuencia*.
  Recuerda qué emoji pones después de qué palabras («jaja» → 😂, «buenas noches» → 🌙) y
  te lo ofrece en la barra. Al principio usa una lista básica en español, y tus hábitos
  siempre tienen prioridad.
- **Corrección** al pulsar espacio o un signo de puntuación: errores de dedo según la
  distancia entre teclas, letras cambiadas de orden y tildes («cancion» → «canción»).
  Si borras justo después de una corrección, se deshace y se aprende tu palabra.
- **Corrección de la palabra seleccionada**: pon el cursor sobre cualquier palabra ya
  escrita y la barra te propone alternativas; al tocar una, se sustituye.
- Mantén pulsada una vocal para ver las tildes (á, ü…) y la `n` para la `ñ`.
- Desliza sobre el espacio para mover el cursor. Doble espacio = punto.
- Funciona sin conexión a internet y nunca aprende de los campos de contraseña.

## Diseño

La interfaz sigue el skill [`apple-design`](https://github.com/emilkowalski/skills) de Emil
Kowalski (MIT), importado en `.claude/skills/apple-design/`, aplicado a Android:

- Las teclas se iluminan y vibran en el mismo fotograma en que las tocas, y la letra se
  escribe al soltar.
- Las animaciones usan springs con *damping* y *response*, no duraciones fijas. Así se
  pueden interrumpir o invertir en cualquier momento y siempre parten del valor que hay en
  pantalla.
- El panel de emojis entra por abajo y sale por el mismo camino. Las tildes salen de la
  tecla que pulsas.
- El scroll de emojis sigue el dedo 1:1 y conserva la inercia con la curva de
  deceleración de iOS (0,998). En los extremos rebota con resistencia progresiva
  (rubber band) y la velocidad pasa al spring de vuelta.
- Al deslizar el dedo por el espacio para mover el cursor hay un tic háptico por cada
  carácter.
- Si desactivas las animaciones en Android, se respeta.
- Los textos usan un tracking distinto según el tamaño, y los ajustes están en grupos al
  estilo iOS.

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
    siguiente letra y lo que aprende de ti (palabras, orden y emojis).
  - `EmojiData.kt` contiene las categorías de emojis y las asociaciones iniciales.
  - `Suggester.kt` se encarga de las sugerencias y la autocorrección.
  - Los tests se ejecutan con `./gradlew :core:test`.
- `app/` contiene el servicio de teclado (`TecladoService`), la vista (`KeyboardView`),
  el panel de emojis (`EmojiPane`), los springs (`Spring`) y los ajustes.

Diccionario: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (Hermit Dave),
basado en OpenSubtitles 2018, CC BY-SA 4.0. Se ha filtrado y se han quitado las variantes
sin tilde que son faltas de ortografía.
