package com.juanma.teclado.core

class EmojiCategory(val name: String, val icon: String, val emojis: List<String>)

object EmojiData {

    private fun split(s: String): List<String> = s.trim().split(Regex("\\s+"))

    val CATEGORIES = listOf(
        EmojiCategory("Caras", "😀", split("""
            😀 😃 😄 😁 😆 😅 😂 🤣 🥲 ☺️ 😊 😇 🙂 🙃 😉 😌 😍 🥰 😘 😗 😙 😚 😋 😛 😝 😜 🤪 🤨
            🧐 🤓 😎 🥸 🤩 🥳 😏 😒 😞 😔 😟 😕 🙁 ☹️ 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵
            🥶 😱 😨 😰 😥 😓 🫣 🤗 🫡 🤔 🫢 🤭 🤫 🤥 😶 😐 😑 😬 🫠 🙄 😯 😦 😧 😮 😲 🥱 😴 🤤
            😪 😵 🤐 🥴 🤢 🤮 🤧 😷 🤒 🤕 🤑 🤠 😈 👿 🤡 💩 👻 💀 👽 🤖 🎃 😺 😸 😹 😻 😼 😽 🙀
        """)),
        EmojiCategory("Gestos", "👍", split("""
            👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🫰 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 🫶
            👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 🧠 👀 👁️ 👅 👄 💋 🫦 👶 🧒 👦 👧 🧑 👱 👨 👩 🧓 👴 👵 🙋
            🙇 🤦 🤷 💁 🙆 🙅 🧏 💃 🕺 👯 🧘 🏃 🚶 👫 👬 👭 💏 💑 👪
        """)),
        EmojiCategory("Corazones", "❤️", split("""
            ❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 🩷 🩵 🩶 💔 ❤️‍🔥 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 💌 💯 💢 💥 💫
            💦 💨 💬 💭 💤 ✨ ⭐ 🌟 🔥 ⚡ 🎉 🎊 🎈 🎁 🏆 🥇 ✅ ❌ ⚠️ ❗ ❓ ‼️ 🆗 🆒 🆕 🔝 🎶 🎵
        """)),
        EmojiCategory("Animales", "🐶", split("""
            🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐻‍❄️ 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐗
            🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🦎 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐊 🐅 🐆 🦓 🐘 🦒 🐪 🐄 🐖
            🐑 🐐 🦌 🐕 🐈 🐓 🦃 🕊️ 🐇 🐁 🌵 🎄 🌲 🌳 🌴 🌱 🌿 🍀 🍁 🍂 🍃 🌷 🌹 🥀 🌺 🌸 🌼 🌻
            🌞 🌝 🌚 🌙 🌎 🪐 ☀️ 🌤️ ⛅ 🌧️ ⛈️ ❄️ ☃️ 🌈 ☔ 🌊
        """)),
        EmojiCategory("Comida", "🍕", split("""
            🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍈 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🍆 🥑 🥦 🥬 🥒 🌶️ 🌽 🥕 🧄 🧅
            🥔 🍠 🥐 🥖 🍞 🥨 🧀 🥚 🍳 🥞 🧇 🥓 🥩 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🥘 🍝 🍜 🍲 🍛
            🍣 🍱 🥟 🍤 🍙 🍚 🍘 🍦 🍰 🎂 🧁 🍮 🍭 🍬 🍫 🍿 🍩 🍪 🥛 ☕ 🍵 🧃 🥤 🧋 🍺 🍻 🥂 🍷
            🥃 🍸 🍹 🧉 🍾 🧊
        """)),
        EmojiCategory("Actividad", "⚽", split("""
            ⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 ⛳ 🏹 🎣 🤿 🛹 ⛸️ 🎿 🏂 🏋️ 🤸 ⛹️ 🤺 🏊 🚴 🧗 🎮 🎲
            🧩 🎯 🎳 🎭 🎨 🎬 🎤 🎧 🎼 🎹 🥁 🎷 🎺 🎸 🎻 🚗 🚕 🚌 🏎️ 🚓 🚑 🚒 🚲 🛵 🏍️ ✈️ 🚀 🚢
            🚆 🚇 ⛵ 🗺️ 🏖️ 🏝️ 🏔️ ⛺ 🏠 🏡 🏢 🏥 🏦 🏫 ⛪ 🕌 🗽 🗼 🏰 🎡 🎢
        """)),
        EmojiCategory("Objetos", "💡", split("""
            ⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 📷 📸 🎥 📺 📻 ⏰ ⏳ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💶 💰 💳 💎 🔧 🔨 🛠️ 🔑
            🗝️ 🚪 🛏️ 🛋️ 🚿 🛁 🧸 🎀 📦 📫 ✉️ 📩 📝 ✏️ 🖊️ 📌 📎 📚 📖 🔖 🗓️ 📅 📈 📉 🔒 🔓 💊 💉
            🩺 🧪 🔬 🔭 🛒 👕 👖 👗 👙 👠 👟 🧢 👑 💍 👓 🕶️ 🎒 👜 ☂️
        """)),
        EmojiCategory("Banderas", "🏳️", split("""
            🏳️ 🏴 🏁 🚩 🏳️‍🌈 🇪🇸 🇲🇽 🇦🇷 🇨🇴 🇨🇱 🇵🇪 🇻🇪 🇺🇾 🇵🇾 🇧🇴 🇪🇨 🇨🇺 🇩🇴 🇵🇷 🇨🇷 🇵🇦 🇬🇹 🇭🇳 🇸🇻 🇳🇮
            🇺🇸 🇬🇧 🇫🇷 🇩🇪 🇮🇹 🇵🇹 🇧🇷 🇯🇵 🇨🇳 🇰🇷 🇪🇺
        """)),
    )

    /**
     * Built-in starting points (folded word → emoji). What the user actually does
     * always wins over this list.
     */
    val KEYWORDS: Map<String, String> = buildMap {
        fun k(emoji: String, words: String) = words.split(' ').forEach { put(TextUtil.fold(it), emoji) }
        k("😂", "jaja jajaja jajajaja jeje jejeje jiji xd lol")
        k("❤️", "amor corazon cariño quiero")
        k("😍", "precioso preciosa guapo guapa hermoso hermosa")
        k("😘", "besos beso besito besitos mua")
        k("🙏", "gracias porfa porfavor ojala")
        k("👍", "vale ok okey perfecto genial bien dale")
        k("👏", "bravo enhorabuena")
        k("🎉", "fiesta celebrar cumple")
        k("🎂", "cumpleaños tarta pastel")
        k("😢", "triste pena llorar")
        k("😡", "enfadado enfadada rabia")
        k("😴", "sueño dormir cansado cansada")
        k("🤔", "pensando hmm mmm quizas")
        k("😎", "guay")
        k("🔥", "fuego increible brutal")
        k("💪", "fuerza animo vamos gym gimnasio")
        k("☕", "cafe")
        k("🍺", "cerveza birra cañas")
        k("🍷", "vino")
        k("🍕", "pizza")
        k("🍔", "hamburguesa")
        k("🌮", "tacos taco")
        k("🍣", "sushi")
        k("🍝", "pasta")
        k("🍦", "helado")
        k("🍫", "chocolate")
        k("⚽", "futbol partido gol")
        k("🏀", "baloncesto basket")
        k("🎮", "jugar juego play")
        k("🎵", "musica cancion")
        k("🎬", "pelicula peli cine")
        k("📚", "estudiar libro libros examen")
        k("💻", "trabajo trabajar ordenador")
        k("📱", "movil telefono")
        k("✈️", "viaje avion vuelo")
        k("🏖️", "playa vacaciones")
        k("🚗", "coche")
        k("🏠", "casa")
        k("🐶", "perro perrito")
        k("🐱", "gato gatito")
        k("☀️", "sol calor")
        k("🌧️", "lluvia llueve")
        k("❄️", "frio nieve")
        k("🌙", "noches")
        k("☀️", "dias")
        k("💸", "dinero")
        k("⏰", "tarde hora")
        k("✅", "hecho listo")
        k("👋", "hola adios chao chau")
        k("🤗", "abrazo abrazos")
        k("😅", "uff ups")
        k("🥳", "felicidades")
    }
}
