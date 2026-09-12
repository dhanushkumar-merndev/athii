package com.oki.feature.assistant

internal sealed interface AssistantBlock {
    data class Prose(val text: String) : AssistantBlock

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val alignments: List<CellAlignment>,
    ) : AssistantBlock
}

internal enum class CellAlignment {
    LEFT,
    CENTER,
    RIGHT,
}

/** Split only unescaped pipes outside inline code, preserving empty cells. */
internal fun tableCells(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    val value = line.trim()
    var codeTicks = 0
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '\\' && index + 1 < value.length && value[index + 1] == '|' -> {
                cell.append('|')
                index += 2
            }
            char == '`' -> {
                var end = index
                while (end < value.length && value[end] == '`') end++
                val count = end - index
                if (codeTicks == 0) codeTicks = count else if (count == codeTicks) codeTicks = 0
                cell.append(value.substring(index, end))
                index = end
            }
            char == '|' && codeTicks == 0 -> {
                cells += cell.toString().trim()
                cell.clear()
                index++
            }
            else -> {
                cell.append(char)
                index++
            }
        }
    }
    cells += cell.toString().trim()
    if (value.startsWith('|') && cells.firstOrNull() == "") cells.removeAt(0)
    if (value.endsWith('|') && cells.lastOrNull() == "" && cells.isNotEmpty())
        cells.removeAt(cells.lastIndex)
    return cells
}

internal fun assistantBlocks(text: String): List<AssistantBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<AssistantBlock>()
    val prose = mutableListOf<String>()
    val separator = Regex(":?-{3,}:?")
    var fence: Char? = null
    var fenceLength = 0
    var index = 0
    fun flush() {
        if (prose.isNotEmpty()) {
            val content = prose.joinToString("\n").trimEnd()
            if (content.isNotBlank()) blocks += AssistantBlock.Prose(content)
            prose.clear()
        }
    }
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()
        val ticks = trimmed.takeWhile { it == marker }.length
        if ((marker == '`' || marker == '~') && ticks >= 3) {
            if (fence == null) {
                fence = marker
                fenceLength = ticks
            } else if (fence == marker && ticks >= fenceLength && trimmed.drop(ticks).isBlank())
                fence = null
            prose += line
            index++
            continue
        }
        val header = tableCells(line)
        val divider = lines.getOrNull(index + 1)?.let(::tableCells).orEmpty()
        if (
            fence == null &&
                '|' in line &&
                header.isNotEmpty() &&
                divider.size == header.size &&
                divider.all(separator::matches)
        ) {
            flush()
            val rows = mutableListOf<List<String>>()
            index += 2
            while (
                index < lines.size &&
                    lines[index].isNotBlank() &&
                    '|' in lines[index] &&
                    !lines[index].trimStart().startsWith("```") &&
                    !lines[index].trimStart().startsWith("~~~")
            ) {
                val row = tableCells(lines[index])
                if (row.size > header.size) break
                rows += row + List(header.size - row.size) { "" }
                index++
            }
            blocks +=
                AssistantBlock.Table(
                    header,
                    rows,
                    divider.map {
                        when {
                            it.startsWith(':') && it.endsWith(':') -> CellAlignment.CENTER
                            it.endsWith(':') -> CellAlignment.RIGHT
                            else -> CellAlignment.LEFT
                        }
                    },
                )
        } else {
            prose += line
            index++
        }
    }
    flush()
    return blocks
}
