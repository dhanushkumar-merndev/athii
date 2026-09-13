package com.oki.core.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

enum class CellStyle {
    NORMAL,
    HEADER,
    BOLD,
    PERCENT,
    TITLE,
}

sealed interface Cell {
    val style: CellStyle
}

data class TextCell(val text: String, override val style: CellStyle = CellStyle.NORMAL) : Cell

data class NumberCell(val value: Double, override val style: CellStyle = CellStyle.NORMAL) : Cell

fun text(value: String, style: CellStyle = CellStyle.NORMAL) = TextCell(value, style)

fun number(value: Number, style: CellStyle = CellStyle.NORMAL) = NumberCell(value.toDouble(), style)

enum class ChartType {
    COLUMN_STACKED,
    COLUMN_CLUSTERED,
    PIE,
}

/** A chart series reads one column of numbers; colours are RRGGBB. */
data class ChartSeries(val column: Int, val color: String)

/**
 * A native Excel chart over cells of the sheet it is placed on. Rows and columns are 0-based;
 * [headerRow] holds series names and [firstRow]..[lastRow] hold the plotted values.
 */
data class SheetChart(
    val title: String,
    val type: ChartType,
    val headerRow: Int,
    val firstRow: Int,
    val lastRow: Int,
    val categoryColumn: Int,
    val series: List<ChartSeries>,
    val anchorColumn: Int,
    val anchorRow: Int,
    val widthColumns: Int = 8,
    val heightRows: Int = 16,
    val sliceColors: List<String> = emptyList(),
)

data class SheetSpec(
    val name: String,
    val rows: List<List<Cell?>>,
    val columnWidths: List<Double> = emptyList(),
    val frozenRows: Int = 0,
    val charts: List<SheetChart> = emptyList(),
)

/**
 * Writes a minimal Office Open XML workbook with native charts. Charts carry cached values so
 * viewers that do not recalculate (mobile previewers) still draw them.
 */
object XlsxWriter {
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart"
    private const val DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val SHEET_DRAWING_NS =
        "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
    private const val HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
    private const val MAX_CELL_TEXT = 32_767

    fun write(sheets: List<SheetSpec>, out: OutputStream) {
        require(sheets.isNotEmpty()) { "A workbook needs at least one sheet." }
        val names = sheets.map { safeSheetName(it.name) }
        require(names.distinct().size == names.size) { "Sheet names must be unique." }
        sheets.forEach { sheet ->
            sheet.charts.forEach {
                require(it.firstRow <= it.lastRow && it.series.isNotEmpty()) {
                    "Chart ${it.title} has no data."
                }
            }
        }
        val strings = LinkedHashMap<String, Int>()
        val zip = ZipOutputStream(out)
        fun entry(name: String, body: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        var chartNumber = 0
        val chartParts = mutableListOf<String>()
        val drawingParts = mutableListOf<String>()
        sheets.forEachIndexed { index, sheet ->
            val sheetNumber = index + 1
            val hasCharts = sheet.charts.isNotEmpty()
            entry("xl/worksheets/sheet$sheetNumber.xml", sheetXml(sheet, strings, hasCharts))
            if (hasCharts) {
                entry(
                    "xl/worksheets/_rels/sheet$sheetNumber.xml.rels",
                    relationships(
                        listOf(
                            Triple("rId1", "$REL/drawing", "../drawings/drawing$sheetNumber.xml")
                        )
                    ),
                )
                val firstChart = chartNumber + 1
                entry("xl/drawings/drawing$sheetNumber.xml", drawingXml(sheet.charts))
                entry(
                    "xl/drawings/_rels/drawing$sheetNumber.xml.rels",
                    relationships(
                        sheet.charts.indices.map {
                            Triple(
                                "rId${it + 1}",
                                "$REL/chart",
                                "../charts/chart${firstChart + it}.xml",
                            )
                        }
                    ),
                )
                drawingParts += "/xl/drawings/drawing$sheetNumber.xml"
                sheet.charts.forEach { chart ->
                    chartNumber++
                    entry("xl/charts/chart$chartNumber.xml", chartXml(chart, sheet, names[index]))
                    chartParts += "/xl/charts/chart$chartNumber.xml"
                }
            }
        }
        entry("xl/sharedStrings.xml", sharedStringsXml(strings))
        entry("xl/styles.xml", STYLES)
        entry(
            "xl/workbook.xml",
            buildString {
                append(HEADER)
                append("""<workbook xmlns="$MAIN" xmlns:r="$REL"><bookViews>""")
                append("""<workbookView activeTab="0"/></bookViews><sheets>""")
                names.forEachIndexed { i, name ->
                    append("""<sheet name="${xml(name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                }
                append("</sheets></workbook>")
            },
        )
        entry(
            "xl/_rels/workbook.xml.rels",
            relationships(
                names.indices.map {
                    Triple("rId${it + 1}", "$REL/worksheet", "worksheets/sheet${it + 1}.xml")
                } +
                    listOf(
                        Triple("rId${names.size + 1}", "$REL/styles", "styles.xml"),
                        Triple("rId${names.size + 2}", "$REL/sharedStrings", "sharedStrings.xml"),
                    )
            ),
        )
        entry(
            "_rels/.rels",
            relationships(listOf(Triple("rId1", "$REL/officeDocument", "xl/workbook.xml"))),
        )
        entry(
            "[Content_Types].xml",
            buildString {
                append(HEADER)
                append(
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">"""
                )
                append(
                    """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>"""
                )
                append("""<Default Extension="xml" ContentType="application/xml"/>""")
                append(
                    """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>"""
                )
                names.indices.forEach {
                    append(
                        """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
                    )
                }
                append(
                    """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>"""
                )
                append(
                    """<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>"""
                )
                drawingParts.forEach {
                    append(
                        """<Override PartName="$it" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>"""
                    )
                }
                chartParts.forEach {
                    append(
                        """<Override PartName="$it" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>"""
                    )
                }
                append("</Types>")
            },
        )
        zip.finish()
    }

    fun columnName(index: Int): String {
        require(index >= 0)
        var n = index + 1
        val name = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            name.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return name.toString()
    }

    /** Excel rejects names over 31 characters or containing []:*?/\ . */
    fun safeSheetName(name: String): String =
        name.replace(Regex("""[\[\]:*?/\\]"""), " ").trim().take(31).ifBlank { "Sheet" }

    private fun sheetXml(sheet: SheetSpec, strings: MutableMap<String, Int>, drawing: Boolean) =
        buildString {
            append(HEADER)
            append("""<worksheet xmlns="$MAIN" xmlns:r="$REL"><sheetViews>""")
            if (sheet.frozenRows > 0) {
                val top = "A${sheet.frozenRows + 1}"
                append("""<sheetView workbookViewId="0">""")
                append(
                    """<pane ySplit="${sheet.frozenRows}" topLeftCell="$top" activePane="bottomLeft" state="frozen"/>"""
                )
                append("</sheetView>")
            } else append("""<sheetView workbookViewId="0"/>""")
            append("""</sheetViews><sheetFormatPr defaultRowHeight="15"/>""")
            if (sheet.columnWidths.isNotEmpty()) {
                append("<cols>")
                sheet.columnWidths.forEachIndexed { i, width ->
                    append(
                        """<col min="${i + 1}" max="${i + 1}" width="$width" customWidth="1"/>"""
                    )
                }
                append("</cols>")
            }
            append("<sheetData>")
            sheet.rows.forEachIndexed { r, row ->
                if (row.all { it == null }) return@forEachIndexed
                append("""<row r="${r + 1}">""")
                row.forEachIndexed { c, cell ->
                    if (cell == null) return@forEachIndexed
                    val ref = "${columnName(c)}${r + 1}"
                    val style = styleIndex(cell.style)
                    when (cell) {
                        is TextCell -> {
                            val value = cell.text.take(MAX_CELL_TEXT)
                            val id = strings.getOrPut(value) { strings.size }
                            append("""<c r="$ref" t="s" s="$style"><v>$id</v></c>""")
                        }
                        is NumberCell ->
                            append(
                                """<c r="$ref" s="$style"><v>${numberText(cell.value)}</v></c>"""
                            )
                    }
                }
                append("</row>")
            }
            append("</sheetData>")
            if (drawing) append("""<drawing r:id="rId1"/>""")
            append("</worksheet>")
        }

    private fun sharedStringsXml(strings: Map<String, Int>) = buildString {
        append(HEADER)
        append("""<sst xmlns="$MAIN" count="${strings.size}" uniqueCount="${strings.size}">""")
        strings.keys.forEach { append("""<si><t xml:space="preserve">${xml(it)}</t></si>""") }
        append("</sst>")
    }

    private fun drawingXml(charts: List<SheetChart>) = buildString {
        append(HEADER)
        append(
            """<xdr:wsDr xmlns:xdr="$SHEET_DRAWING_NS" xmlns:a="$DRAWING_NS" xmlns:r="$REL" xmlns:c="$CHART_NS">"""
        )
        charts.forEachIndexed { i, chart ->
            append("""<xdr:twoCellAnchor editAs="oneCell">""")
            append(
                "<xdr:from><xdr:col>${chart.anchorColumn}</xdr:col><xdr:colOff>0</xdr:colOff>" +
                    "<xdr:row>${chart.anchorRow}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>"
            )
            append(
                "<xdr:to><xdr:col>${chart.anchorColumn + chart.widthColumns}</xdr:col><xdr:colOff>0</xdr:colOff>" +
                    "<xdr:row>${chart.anchorRow + chart.heightRows}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>"
            )
            append("""<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr>""")
            append(
                """<xdr:cNvPr id="${i + 2}" name="${xml(chart.title)}"/><xdr:cNvGraphicFramePr/>"""
            )
            append(
                """</xdr:nvGraphicFramePr><xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/>"""
            )
            append("""</xdr:xfrm><a:graphic><a:graphicData uri="$CHART_NS">""")
            append(
                """<c:chart r:id="rId${i + 1}"/></a:graphicData></a:graphic></xdr:graphicFrame>"""
            )
            append("<xdr:clientData/></xdr:twoCellAnchor>")
        }
        append("</xdr:wsDr>")
    }

    private fun chartXml(chart: SheetChart, sheet: SheetSpec, sheetName: String) = buildString {
        val quoted = "'" + sheetName.replace("'", "''") + "'"
        val rows = chart.firstRow..chart.lastRow
        fun cellAt(r: Int, c: Int) = sheet.rows.getOrNull(r)?.getOrNull(c)
        fun label(r: Int, c: Int) =
            when (val cell = cellAt(r, c)) {
                is TextCell -> cell.text
                is NumberCell -> numberText(cell.value)
                null -> ""
            }
        fun range(c: Int) =
            "$quoted!\$${columnName(c)}\$${chart.firstRow + 1}:\$${columnName(c)}\$${chart.lastRow + 1}"
        fun categories() = buildString {
            append("<c:cat><c:strRef><c:f>${xml(range(chart.categoryColumn))}</c:f><c:strCache>")
            append("""<c:ptCount val="${rows.count()}"/>""")
            rows.forEachIndexed { i, r ->
                append(
                    """<c:pt idx="$i"><c:v>${xml(label(r, chart.categoryColumn))}</c:v></c:pt>"""
                )
            }
            append("</c:strCache></c:strRef></c:cat>")
        }
        fun values(c: Int) = buildString {
            append("<c:val><c:numRef><c:f>${xml(range(c))}</c:f><c:numCache>")
            append("""<c:formatCode>General</c:formatCode><c:ptCount val="${rows.count()}"/>""")
            rows.forEachIndexed { i, r ->
                val v = (cellAt(r, c) as? NumberCell)?.value ?: 0.0
                append("""<c:pt idx="$i"><c:v>${numberText(v)}</c:v></c:pt>""")
            }
            append("</c:numCache></c:numRef></c:val>")
        }
        fun seriesName(c: Int) =
            "<c:tx><c:strRef><c:f>${xml("$quoted!\$${columnName(c)}\$${chart.headerRow + 1}")}</c:f>" +
                """<c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>${xml(label(chart.headerRow, c))}</c:v></c:pt></c:strCache></c:strRef></c:tx>"""
        fun fill(color: String) =
            """<c:spPr><a:solidFill><a:srgbClr val="$color"/></a:solidFill></c:spPr>"""

        append(HEADER)
        append("""<c:chartSpace xmlns:c="$CHART_NS" xmlns:a="$DRAWING_NS" xmlns:r="$REL">""")
        append("""<c:roundedCorners val="0"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p>""")
        append("""<a:r><a:t>${xml(chart.title)}</a:t></a:r></a:p></c:rich></c:tx>""")
        append(
            """<c:overlay val="0"/></c:title><c:autoTitleDeleted val="0"/><c:plotArea><c:layout/>"""
        )
        if (chart.type == ChartType.PIE) {
            val s = chart.series.first()
            append(
                """<c:pieChart><c:varyColors val="1"/><c:ser><c:idx val="0"/><c:order val="0"/>"""
            )
            append(seriesName(s.column))
            rows.forEachIndexed { i, _ ->
                val color = chart.sliceColors.getOrNull(i) ?: s.color
                append("""<c:dPt><c:idx val="$i"/><c:bubble3D val="0"/>${fill(color)}</c:dPt>""")
            }
            append(
                """<c:dLbls><c:showLegendKey val="0"/><c:showVal val="1"/><c:showCatName val="0"/>""" +
                    """<c:showSerName val="0"/><c:showPercent val="0"/><c:showBubbleSize val="0"/>""" +
                    """<c:showLeaderLines val="1"/></c:dLbls>"""
            )
            append(categories())
            append(values(s.column))
            append("""</c:ser><c:firstSliceAng val="0"/></c:pieChart>""")
        } else {
            val stacked = chart.type == ChartType.COLUMN_STACKED
            append("""<c:barChart><c:barDir val="col"/>""")
            append("""<c:grouping val="${if (stacked) "stacked" else "clustered"}"/>""")
            append("""<c:varyColors val="0"/>""")
            chart.series.forEachIndexed { i, s ->
                append("""<c:ser><c:idx val="$i"/><c:order val="$i"/>""")
                append(seriesName(s.column))
                append(fill(s.color))
                append("""<c:invertIfNegative val="0"/>""")
                append(categories())
                append(values(s.column))
                append("</c:ser>")
            }
            append("""<c:gapWidth val="60"/>""")
            if (stacked) append("""<c:overlap val="100"/>""")
            append("""<c:axId val="500000001"/><c:axId val="500000002"/></c:barChart>""")
            append(
                """<c:catAx><c:axId val="500000001"/><c:scaling><c:orientation val="minMax"/></c:scaling>""" +
                    """<c:delete val="0"/><c:axPos val="b"/><c:numFmt formatCode="General" sourceLinked="0"/>""" +
                    """<c:majorTickMark val="none"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/>""" +
                    """<c:crossAx val="500000002"/><c:crosses val="autoZero"/><c:auto val="1"/>""" +
                    """<c:lblAlgn val="ctr"/><c:lblOffset val="100"/><c:noMultiLvlLbl val="0"/></c:catAx>"""
            )
            append(
                """<c:valAx><c:axId val="500000002"/><c:scaling><c:orientation val="minMax"/></c:scaling>""" +
                    """<c:delete val="0"/><c:axPos val="l"/><c:majorGridlines/>""" +
                    """<c:numFmt formatCode="0" sourceLinked="0"/><c:majorTickMark val="none"/>""" +
                    """<c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="500000001"/>""" +
                    """<c:crosses val="autoZero"/><c:crossBetween val="between"/></c:valAx>"""
            )
        }
        append("""</c:plotArea><c:legend><c:legendPos val="b"/><c:overlay val="0"/></c:legend>""")
        append("""<c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart></c:chartSpace>""")
    }

    private fun relationships(items: List<Triple<String, String, String>>) = buildString {
        append(HEADER)
        append("""<Relationships xmlns="$PKG_REL">""")
        items.forEach { (id, type, target) ->
            append("""<Relationship Id="$id" Type="$type" Target="$target"/>""")
        }
        append("</Relationships>")
    }

    private fun styleIndex(style: CellStyle) =
        when (style) {
            CellStyle.NORMAL -> 0
            CellStyle.HEADER -> 1
            CellStyle.PERCENT -> 2
            CellStyle.BOLD -> 3
            CellStyle.TITLE -> 4
        }

    private fun numberText(value: Double): String =
        if (value.isNaN() || value.isInfinite()) "0"
        else if (value % 1.0 == 0.0 && abs(value) < 1e15) value.toLong().toString()
        else value.toString()

    /** Escapes markup and drops characters XML 1.0 cannot carry. */
    internal fun xml(value: String): String = buildString {
        value.forEach { ch ->
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> append(ch)
                ch < ' ' || ch == '￾' || ch == '￿' -> Unit
                else -> append(ch)
            }
        }
    }

    private const val STYLES =
        HEADER +
            """<styleSheet xmlns="$MAIN">""" +
            """<fonts count="3"><font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="15"/><name val="Calibri"/></font></fonts>""" +
            """<fills count="3"><fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FFDCEBE3"/><bgColor indexed="64"/></patternFill></fill></fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="5">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>""" +
            """<xf numFmtId="9" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """</cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""
}
