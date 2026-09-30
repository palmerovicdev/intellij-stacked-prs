package com.stacklane.ui

/**
 * Reparte un texto en lineas que quepan en un ancho dado, como hace Tasklane con el titulo
 * de sus tarjetas.
 *
 * La medida entra como funcion: quien pinta es una `JLabel` y su ancho depende de la fuente,
 * que solo ella conoce. De paso, el troceado se prueba con una metrica falsa.
 */
internal object TextWrap {

    /** Lo que se anade al final cuando el texto no cabe en `maxLines`. */
    const val ELLIPSIS = "…"

    /**
     * Las lineas y si hubo que dejarse algo fuera. Lo segundo no se deduce de las lineas (un
     * titulo puede acabar en «…» escrito a mano, o caber justo en el maximo): lo dice quien
     * trocea, y es lo que decide si la fila ensena el boton de desplegar.
     */
    class Fit(val lines: List<String>, val clipped: Boolean)

    /**
     * @param available ancho util en pixeles. Si no es positivo no se envuelve: la lista aun
     *   no tiene tamano, y se vuelve a medir en cuanto lo tenga.
     */
    fun fit(text: String, available: Int, maxLines: Int, width: (String) -> Int): Fit {
        val clean = text.trim()
        if (clean.isEmpty()) return Fit(emptyList(), false)
        if (available <= 0) return Fit(listOf(clean), false)

        val lines = mutableListOf(StringBuilder())
        for (token in tokens(clean, available, width)) {
            val line = lines.last()
            // El espacio que sigue a la palabra no cuenta: si acaba la linea, no se ve.
            if (line.isEmpty() || width(line.toString() + token.trimEnd()) <= available) {
                line.append(token)
                continue
            }
            if (lines.size >= maxLines) {
                val kept = lines.map { it.toString().trimEnd() }
                return Fit(kept.dropLast(1) + ellipsize(kept.last(), available, width), true)
            }
            lines += StringBuilder(token)
        }
        return Fit(lines.map { it.toString().trimEnd() }, false)
    }

    /**
     * Las palabras con el espacio que las sigue. Una palabra mas ancha que [available] (una
     * rama, una URL) no tiene por donde partirse, asi que se trocea por letras: si no, se
     * saldria de la fila.
     */
    private fun tokens(text: String, available: Int, width: (String) -> Int): Sequence<String> =
        WORD.findAll(text).map { it.value }.flatMap { word ->
            if (width(word.trimEnd()) <= available) sequenceOf(word) else split(word, available, width)
        }

    private fun split(word: String, available: Int, width: (String) -> Int): Sequence<String> = sequence {
        val piece = StringBuilder()
        for (char in word) {
            if (piece.isNotEmpty() && !char.isWhitespace() && width(piece.toString() + char) > available) {
                yield(piece.toString())
                piece.setLength(0)
            }
            piece.append(char)
        }
        if (piece.isNotEmpty()) yield(piece.toString())
    }

    /** La linea con los puntos suspensivos al final, quitando letras hasta que quepan. */
    private fun ellipsize(line: String, available: Int, width: (String) -> Int): String {
        var text = line
        while (text.isNotEmpty() && width(text + ELLIPSIS) > available) text = text.dropLast(1)
        return text.trimEnd() + ELLIPSIS
    }

    private val WORD = Regex("\\S+\\s*")
}
