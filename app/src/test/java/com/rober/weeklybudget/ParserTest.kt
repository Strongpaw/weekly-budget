package com.rober.weeklybudget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ParserTest {

    @Test
    fun basicExpense() {
        val p = Parser.parse("40 dollars at Safeway")
        assertEquals(4000L, p.amountCents)
        assertEquals("Safeway", p.merchant)
        assertEquals("Groceries", p.category)
        assertFalse(p.isIncome)
    }

    @Test
    fun dollarSignWithCents() {
        val p = Parser.parse("$25.50 at Chevron")
        assertEquals(2550L, p.amountCents)
        assertEquals("Chevron", p.merchant)
        assertEquals("Gas & Transport", p.category)
    }

    @Test
    fun income() {
        val p = Parser.parse("got paid 500 from work")
        assertEquals(50000L, p.amountCents)
        assertTrue(p.isIncome)
        assertEquals("Income", p.category)
        assertEquals("Work", p.merchant)
    }

    @Test
    fun wordNumbers() {
        val p = Parser.parse("forty five dollars at Trader Joe's")
        assertEquals(4500L, p.amountCents)
        assertEquals("Trader Joe's", p.merchant)
        assertEquals("Groceries", p.category)
    }

    @Test
    fun hundredWords() {
        val p = Parser.parse("one hundred twenty dollars for rent")
        assertEquals(12000L, p.amountCents)
        assertEquals("Bills & Utilities", p.category)
    }

    @Test
    fun dollarsAndCents() {
        val p = Parser.parse("4 dollars and 50 cents at Starbucks")
        assertEquals(450L, p.amountCents)
        assertEquals("Starbucks", p.merchant)
        assertEquals("Dining", p.category)
    }

    @Test
    fun centsOnly() {
        val p = Parser.parse("75 cents for parking")
        assertEquals(75L, p.amountCents)
        assertEquals("Gas & Transport", p.category)
    }

    @Test
    fun spentOnGas() {
        val p = Parser.parse("spent 20 on gas")
        assertEquals(2000L, p.amountCents)
        assertEquals("Gas", p.merchant)
        assertEquals("Gas & Transport", p.category)
    }

    @Test
    fun yesterday() {
        val p = Parser.parse("12 dollars at McDonald's yesterday")
        assertEquals(1200L, p.amountCents)
        assertEquals(LocalDate.now().minusDays(1).toEpochDay(), p.epochDay)
        assertEquals("Dining", p.category)
        assertEquals("Mcdonald's", p.merchant)
    }

    @Test
    fun commaThousands() {
        val p = Parser.parse("1,200 dollars rent")
        assertEquals(120000L, p.amountCents)
    }

    @Test
    fun noAmount() {
        val p = Parser.parse("some stuff at the store")
        assertNull(p.amountCents)
    }

    @Test
    fun bareNumber() {
        val p = Parser.parse("15 pizza")
        assertEquals(1500L, p.amountCents)
        assertEquals("Dining", p.category)
    }
}
