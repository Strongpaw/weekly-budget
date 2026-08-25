package com.rober.weeklybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CsvImportTest {

    @Test
    fun signedAmountFormat() {
        val csv = """
            Date,Description,Amount
            08/20/2026,SAFEWAY #123 SEATTLE,-42.50
            08/21/2026,PAYROLL DEPOSIT,1500.00
        """.trimIndent()
        val r = CsvImport.parse(csv)
        assertNotNull(r)
        r!!
        assertEquals(2, r.rows.size)
        assertFalse(r.ourFormat)
        val safeway = r.rows[0]
        assertEquals(4250L, safeway.amountCents)
        assertFalse(safeway.incomeDefault)
        assertEquals(LocalDate.of(2026, 8, 20).toEpochDay(), safeway.epochDay)
        assertEquals("Safeway Seattle", safeway.merchant)
        val pay = r.rows[1]
        assertEquals(150000L, pay.amountCents)
        assertTrue(pay.incomeDefault)
        assertEquals(1, r.positiveCount)
        assertEquals(1, r.negativeCount)
    }

    @Test
    fun debitCreditFormat() {
        val csv = """
            Posting Date,Description,Debit,Credit
            2026-08-19,COSTCO WHOLESALE,120.00,
            2026-08-20,REFUND,,15.25
        """.trimIndent()
        val r = CsvImport.parse(csv)
        assertNotNull(r)
        r!!
        assertEquals(2, r.rows.size)
        assertEquals(12000L, r.rows[0].amountCents)
        assertFalse(r.rows[0].incomeDefault)
        assertEquals(1525L, r.rows[1].amountCents)
        assertTrue(r.rows[1].incomeDefault)
    }

    @Test
    fun quotedFieldsAndParens() {
        val csv = """
            Date,Description,Amount
            08/22/2026,"AMAZON, INC",(9.99)
        """.trimIndent()
        val r = CsvImport.parse(csv)
        assertNotNull(r)
        r!!
        assertEquals(1, r.rows.size)
        assertEquals(999L, r.rows[0].amountCents)
        assertFalse(r.rows[0].incomeDefault)
        assertEquals("AMAZON, INC", r.rows[0].rawDesc)
        assertEquals("Amazon, Inc", r.rows[0].merchant)
    }

    @Test
    fun ownExportFormat() {
        val csv = """
            Date,Week Start,Type,Amount,Merchant,Category
            2026-08-18,2026-08-16,Expense,40.00,Safeway,Groceries
            2026-08-19,2026-08-16,Income,500.00,Work,Income
        """.trimIndent()
        val r = CsvImport.parse(csv)
        assertNotNull(r)
        r!!
        assertTrue(r.ourFormat)
        assertEquals(2, r.rows.size)
        assertFalse(r.rows[0].incomeDefault)
        assertEquals("Groceries", r.rows[0].fileCategory)
        assertEquals("Safeway", r.rows[0].merchant)
        assertTrue(r.rows[1].incomeDefault)
    }

    @Test
    fun noHeaderReturnsNull() {
        assertNull(CsvImport.parse("hello\nworld\nfoo"))
    }

    @Test
    fun unparsableRowsSkipped() {
        val csv = """
            Date,Description,Amount
            08/20/2026,GOOD ROW,-10.00
            not-a-date,BAD ROW,-5.00
            08/21/2026,ZERO ROW,0.00
        """.trimIndent()
        val r = CsvImport.parse(csv)
        assertNotNull(r)
        r!!
        assertEquals(1, r.rows.size)
        assertEquals(2, r.skipped)
    }

    @Test
    fun hashStableAcrossParses() {
        val csv = "Date,Description,Amount\n08/20/2026,SAFEWAY,-42.50"
        val a = CsvImport.parse(csv)!!.rows[0].hash
        val b = CsvImport.parse(csv)!!.rows[0].hash
        assertEquals(a, b)
    }

    @Test
    fun csvLineParser() {
        assertEquals(
            listOf("a", "b,c", "d\"e", ""),
            CsvImport.parseLine("""a,"b,c","d""e",""")
        )
    }
}
