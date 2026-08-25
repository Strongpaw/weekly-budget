package com.rober.weeklybudget

import java.time.LocalDate
import java.util.Locale

data class ParsedTx(
    val amountCents: Long?,
    val merchant: String,
    val category: String,
    val isIncome: Boolean,
    val epochDay: Long
)

/**
 * Turns free-form speech like "40 dollars at Safeway" or
 * "forty five bucks for gas yesterday" into a draft transaction.
 */
object Parser {

    private val digitMoney = Regex("""\$?\s*(\d[\d,]*)(?:\.(\d{1,2}))?""")
    private val unitAfter = Regex("""^\s*(dollars?|bucks?|usd|cents?)\b""", RegexOption.IGNORE_CASE)
    private val andCents = Regex("""^\s*and\s+(\d{1,2})\s*cents?\b""", RegexOption.IGNORE_CASE)

    private val units = mapOf(
        "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
        "six" to 6L, "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L,
        "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L, "fourteen" to 14L,
        "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L, "eighteen" to 18L,
        "nineteen" to 19L
    )
    private val tens = mapOf(
        "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
        "sixty" to 60L, "seventy" to 70L, "eighty" to 80L, "ninety" to 90L
    )
    private const val NUM_WORD =
        "(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|" +
            "fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|" +
            "sixty|seventy|eighty|ninety|hundred|thousand|and)"
    private val wordMoney =
        Regex("""\b($NUM_WORD(?:[\s-]+$NUM_WORD)*)[\s-]+(dollars?|bucks?)\b""")

    fun parse(raw: String): ParsedTx {
        val lower = raw.trim().lowercase(Locale.US)

        var amountCents: Long? = null
        var start = -1
        var endExclusive = -1

        val m = digitMoney.find(lower)
        if (m != null) {
            val whole = m.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
            val frac = m.groupValues[2]
            val fracCents = when (frac.length) {
                1 -> frac.toLong() * 10
                2 -> frac.toLong()
                else -> 0L
            }
            start = m.range.first
            endExclusive = m.range.last + 1

            var isCentsOnly = false
            val afterUnit = unitAfter.find(lower.substring(endExclusive))
            if (afterUnit != null) {
                if (afterUnit.groupValues[1].lowercase(Locale.US).startsWith("cent")) isCentsOnly = true
                endExclusive += afterUnit.range.last + 1
            }
            var total = if (isCentsOnly && frac.isEmpty()) whole else whole * 100 + fracCents
            if (!isCentsOnly) {
                val ac = andCents.find(lower.substring(endExclusive))
                if (ac != null) {
                    total += ac.groupValues[1].toLong()
                    endExclusive += ac.range.last + 1
                }
            }
            amountCents = total
        } else {
            val w = wordMoney.find(lower)
            if (w != null) {
                var total = 0L
                var current = 0L
                for (t in w.groupValues[1].split(Regex("[\\s-]+"))) {
                    when {
                        t == "and" -> {}
                        t == "hundred" -> current = (if (current == 0L) 1L else current) * 100
                        t == "thousand" -> {
                            total += (if (current == 0L) 1L else current) * 1000
                            current = 0
                        }
                        units.containsKey(t) -> current += units.getValue(t)
                        tens.containsKey(t) -> current += tens.getValue(t)
                    }
                }
                val value = total + current
                if (value > 0) {
                    amountCents = value * 100
                    start = w.range.first
                    endExclusive = w.range.last + 1
                }
            }
        }

        // blank out the money text so what's left is the description
        var rem = if (start >= 0) {
            lower.substring(0, start) +
                " ".repeat(endExclusive - start) +
                lower.substring(endExclusive)
        } else lower

        var day = LocalDate.now()
        if (Regex("\\byesterday\\b").containsMatchIn(rem)) day = day.minusDays(1)
        rem = rem.replace(
            Regex("\\b(yesterday|today|tonight|last night|this morning|this afternoon|this evening)\\b"),
            " "
        )

        val isIncome = Categories.looksLikeIncome(lower)

        var merchant: String
        val prep = Regex("\\b(?:at|from|to|for|on|in)\\s+(.+)$").find(rem)
        if (prep != null) {
            merchant = prep.groupValues[1]
                .replace(Regex("^(?:(?:at|from|to|for|on|in)\\s+)+"), "")
        } else {
            merchant = rem.replace(
                Regex(
                    "\\b(i|we|just|spent|paid|pay|bought|buy|got|gotten|purchased?|purchase|add|" +
                        "an?|the|some|my|it|was|charged?|cost|expense|income|received|earned|sold|" +
                        "deposit|refund|dollars?|bucks?|cents?|and)\\b"
                ),
                " "
            )
        }
        merchant = merchant
            .replace(Regex("[^a-z0-9'&\\- ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase(Locale.US) } }

        val category = Categories.categorize(merchant, lower, isIncome)
        return ParsedTx(amountCents, merchant, category, isIncome, day.toEpochDay())
    }
}
