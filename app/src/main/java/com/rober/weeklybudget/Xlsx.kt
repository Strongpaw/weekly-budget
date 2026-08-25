package com.rober.weeklybudget

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Hand-rolled .xlsx writer (no libraries). Produces a two-sheet workbook —
 * a Summary sheet (weekly totals + category breakdown) and a Transactions
 * sheet — with styled headers, real date/currency cells, and color-coded
 * amounts. Opens cleanly in Excel and Google Sheets.
 */
object Xlsx {

    // cellXfs style indices — must match STYLES below
    private const val S_HEADER = 1
    private const val S_DATE = 2
    private const val S_CUR = 3
    private const val S_CUR_RED = 4
    private const val S_CUR_GREEN = 5
    private const val S_BOLD = 6
    private const val S_TITLE = 7
    private const val S_TOTAL_CUR_RED = 9
    private const val S_TOTAL_CUR_GREEN = 10
    private const val S_TOTAL_TEXT = 11

    fun build(all: List<Tx>, weekStartOf: (LocalDate) -> LocalDate): ByteArray {
        val txs = all.sortedWith(compareBy({ it.epochDay }, { it.id }))

        val income = txs.filter { it.isIncome }.sumOf { it.amountCents }
        val spent = txs.filter { !it.isIncome }.sumOf { it.amountCents }
        val net = income - spent
        val byWeek = txs.groupBy { weekStartOf(LocalDate.ofEpochDay(it.epochDay)).toEpochDay() }
            .toSortedMap()
        val byCat = txs.filter { !it.isIncome }
            .groupBy { it.category }
            .mapValues { e -> e.value.sumOf { it.amountCents } }
            .entries.sortedByDescending { it.value }

        val s1 = Sheet()
        s1.row(text("Weekly Budget", S_TITLE))
        s1.row(
            text(
                "Exported " + LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
            )
        )
        s1.blank()
        s1.row(text("By Week", S_BOLD))
        s1.row(
            text("Week Start", S_HEADER), text("Income", S_HEADER),
            text("Spent", S_HEADER), text("Net", S_HEADER)
        )
        for ((ws, list) in byWeek) {
            val wi = list.filter { it.isIncome }.sumOf { it.amountCents }
            val wsp = list.filter { !it.isIncome }.sumOf { it.amountCents }
            val wnet = wi - wsp
            s1.row(
                date(ws),
                cur(wi, S_CUR_GREEN),
                cur(wsp, S_CUR_RED),
                cur(wnet, if (wnet < 0) S_CUR_RED else S_CUR_GREEN)
            )
        }
        s1.row(
            text("Total", S_TOTAL_TEXT),
            cur(income, S_TOTAL_CUR_GREEN),
            cur(spent, S_TOTAL_CUR_RED),
            cur(net, if (net < 0) S_TOTAL_CUR_RED else S_TOTAL_CUR_GREEN)
        )
        s1.blank()
        s1.row(text("By Category", S_BOLD))
        s1.row(text("Category", S_HEADER), text("Spent", S_HEADER))
        for ((cat, total) in byCat) {
            s1.row(text(cat), cur(total, S_CUR))
        }

        val s2 = Sheet()
        s2.row(
            text("Date", S_HEADER), text("Week Start", S_HEADER), text("Type", S_HEADER),
            text("Amount", S_HEADER), text("Merchant", S_HEADER), text("Category", S_HEADER)
        )
        for (t in txs) {
            val d = LocalDate.ofEpochDay(t.epochDay)
            s2.row(
                date(t.epochDay),
                date(weekStartOf(d).toEpochDay()),
                text(if (t.isIncome) "Income" else "Expense"),
                cur(t.amountCents, if (t.isIncome) S_CUR_GREEN else S_CUR_RED),
                text(t.merchant),
                text(t.category)
            )
        }

        val parts = linkedMapOf(
            "[Content_Types].xml" to CONTENT_TYPES,
            "_rels/.rels" to ROOT_RELS,
            "xl/workbook.xml" to WORKBOOK,
            "xl/_rels/workbook.xml.rels" to WORKBOOK_RELS,
            "xl/styles.xml" to STYLES,
            "xl/worksheets/sheet1.xml" to sheetXml(SUMMARY_COLS, s1.xml(), freezeTopRow = false),
            "xl/worksheets/sheet2.xml" to sheetXml(TX_COLS, s2.xml(), freezeTopRow = true)
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((path, content) in parts) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ---- cell/row builders ----

    private class Sheet {
        private val sb = StringBuilder()
        private var r = 0

        fun row(vararg cells: String) {
            r++
            sb.append("<row r=\"").append(r).append("\">")
            cells.forEachIndexed { i, c ->
                sb.append(c.replace("{R}", colLetter(i) + r))
            }
            sb.append("</row>")
        }

        fun blank() {
            r++
            sb.append("<row r=\"").append(r).append("\"/>")
        }

        fun xml(): String = sb.toString()
    }

    private fun colLetter(i: Int) = ('A' + i).toString()

    private fun text(v: String, s: Int = 0) =
        "<c r=\"{R}\" s=\"$s\" t=\"inlineStr\"><is><t>${esc(v)}</t></is></c>"

    private fun num(v: String, s: Int) = "<c r=\"{R}\" s=\"$s\"><v>$v</v></c>"

    /** Excel 1900-system serial: days since 1899-12-30; unix epoch = 25569. */
    private fun date(epochDay: Long, s: Int = S_DATE) = num((epochDay + 25569).toString(), s)

    private fun cur(cents: Long, s: Int) =
        num(String.format(Locale.US, "%.2f", cents / 100.0), s)

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun sheetXml(cols: String, rows: String, freezeTopRow: Boolean): String {
        val views = if (freezeTopRow) {
            "<sheetViews><sheetView workbookViewId=\"0\">" +
                "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>" +
                "</sheetView></sheetViews>"
        } else {
            "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>"
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            views + cols + "<sheetData>" + rows + "</sheetData></worksheet>"
    }

    // ---- static workbook parts ----

    private const val SUMMARY_COLS =
        "<cols><col min=\"1\" max=\"1\" width=\"14\" customWidth=\"1\"/>" +
            "<col min=\"2\" max=\"4\" width=\"12\" customWidth=\"1\"/></cols>"

    private const val TX_COLS =
        "<cols><col min=\"1\" max=\"2\" width=\"12\" customWidth=\"1\"/>" +
            "<col min=\"3\" max=\"3\" width=\"9\" customWidth=\"1\"/>" +
            "<col min=\"4\" max=\"4\" width=\"11\" customWidth=\"1\"/>" +
            "<col min=\"5\" max=\"5\" width=\"30\" customWidth=\"1\"/>" +
            "<col min=\"6\" max=\"6\" width=\"16\" customWidth=\"1\"/></cols>"

    private val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Summary" sheetId="1" r:id="rId1"/><sheet name="Transactions" sheetId="2" r:id="rId2"/></sheets></workbook>"""

    private val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;${'$'}&quot;#,##0.00"/></numFmts><fonts count="8"><font><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFFFFFFF"/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><color rgb="FF2E7D32"/><name val="Calibri"/></font><font><color rgb="FFC62828"/><sz val="11"/><name val="Calibri"/></font><font><color rgb="FF2E7D32"/><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FFC62828"/><sz val="11"/><name val="Calibri"/></font><font><b/><color rgb="FF2E7D32"/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF2E7D32"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFF1F8F1"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="12"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" applyFont="1" applyFill="1"/><xf numFmtId="14" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/><xf numFmtId="164" fontId="4" fillId="0" borderId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="164" fontId="5" fillId="0" borderId="0" applyNumberFormat="1" applyFont="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" applyFont="1"/><xf numFmtId="0" fontId="3" fillId="0" borderId="0" applyFont="1"/><xf numFmtId="164" fontId="2" fillId="3" borderId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/><xf numFmtId="164" fontId="6" fillId="3" borderId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/><xf numFmtId="164" fontId="7" fillId="3" borderId="0" applyNumberFormat="1" applyFont="1" applyFill="1"/><xf numFmtId="0" fontId="2" fillId="3" borderId="0" applyFont="1" applyFill="1"/></cellXfs></styleSheet>"""
}
