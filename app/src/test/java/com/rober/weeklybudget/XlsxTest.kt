package com.rober.weeklybudget

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

class XlsxTest {

    private fun weekStart(d: LocalDate): LocalDate =
        d.minusDays((d.dayOfWeek.value % 7).toLong())

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                entries[e.name] = z.readBytes().toString(Charsets.UTF_8)
                e = z.nextEntry
            }
        }
        return entries
    }

    @Test
    fun buildsWorkbookWithBothSheets() {
        val txs = listOf(
            Tx(1, LocalDate.of(2026, 8, 18).toEpochDay(), 4000, "Safeway", "Groceries", false),
            Tx(2, LocalDate.of(2026, 8, 19).toEpochDay(), 50000, "Work", "Income", true)
        )
        val entries = unzip(Xlsx.build(txs) { weekStart(it) })
        assertTrue("[Content_Types].xml" in entries)
        assertTrue("_rels/.rels" in entries)
        assertTrue("xl/workbook.xml" in entries)
        assertTrue("xl/_rels/workbook.xml.rels" in entries)
        assertTrue("xl/styles.xml" in entries)
        val summary = entries.getValue("xl/worksheets/sheet1.xml")
        val sheet = entries.getValue("xl/worksheets/sheet2.xml")
        assertTrue(sheet.contains("Safeway"))
        assertTrue(summary.contains("By Week"))
        assertTrue(summary.contains("By Category"))
        assertTrue(summary.contains("Groceries"))
        // real Excel date serial for 2026-08-18
        val serial = LocalDate.of(2026, 8, 18).toEpochDay() + 25569
        assertTrue(sheet.contains("<v>$serial</v>"))
        // amounts written as plain decimals
        assertTrue(sheet.contains("<v>40.00</v>"))
        assertTrue(sheet.contains("<v>500.00</v>"))
    }

    @Test
    fun everyPartIsWellFormedXml() {
        val txs = listOf(
            Tx(1, LocalDate.of(2026, 8, 18).toEpochDay(), 4000, "Safeway & Sons <QFC>", "Groceries", false),
            Tx(2, LocalDate.of(2026, 8, 23).toEpochDay(), 50000, "Work", "Income", true)
        )
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        for ((name, content) in unzip(Xlsx.build(txs) { weekStart(it) })) {
            try {
                factory.newDocumentBuilder()
                    .parse(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) {
                throw AssertionError("Malformed XML in $name: ${e.message}")
            }
        }
    }

    @Test
    fun escapesXmlInMerchantNames() {
        val txs = listOf(
            Tx(1, LocalDate.of(2026, 8, 20).toEpochDay(), 500, "A&B <Store>", "Other", false)
        )
        val entries = unzip(Xlsx.build(txs) { weekStart(it) })
        val sheet = entries.getValue("xl/worksheets/sheet2.xml")
        assertTrue(sheet.contains("A&amp;B &lt;Store&gt;"))
    }
}
