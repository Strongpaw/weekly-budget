package com.rober.weeklybudget

import java.util.Locale
import kotlin.math.abs

object Categories {
    const val INCOME = "Income"

    val BUILTIN_EXPENSE = listOf(
        "Groceries", "Dining", "Gas & Transport", "Shopping",
        "Entertainment", "Bills & Utilities", "Health", "Other"
    )

    /** Normalized key for learned merchant-to-category lookups. */
    fun key(s: String): String = s.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    private val keywords: Map<String, List<String>> = mapOf(
        "Groceries" to listOf(
            "safeway", "kroger", "albertsons", "walmart", "costco", "trader joe", "aldi",
            "whole foods", "winco", "fred meyer", "grocery", "groceries", "supermarket",
            "target", "sam's club", "sams club", "food lion", "publix", "heb", "meijer",
            "sprouts", "grocery outlet"
        ),
        "Dining" to listOf(
            "mcdonald", "starbucks", "restaurant", "pizza", "taco", "burger", "chipotle",
            "subway", "doordash", "door dash", "grubhub", "uber eats", "ubereats", "wendy",
            "chick fil a", "chick-fil-a", "kfc", "panda express", "panera", "dunkin",
            "coffee", "cafe", "diner", "sushi", "in n out", "in-n-out", "five guys",
            "sonic", "dairy queen", "jack in the box", "arby", "domino", "papa john",
            "little caesars", "lunch", "dinner", "breakfast", "brewery", "pub", "takeout",
            "fast food", "food truck"
        ),
        "Gas & Transport" to listOf(
            "chevron", "shell", "exxon", "mobil", "arco", "texaco", "gas", "fuel",
            "uber", "lyft", "parking", "toll", "bus fare", "train", "transit",
            "car wash", "oil change", "jiffy lube", "les schwab", "autozone", "o'reilly",
            "oreilly", "mechanic", "tires"
        ),
        "Shopping" to listOf(
            "amazon", "ebay", "etsy", "best buy", "home depot", "lowes", "lowe's", "ikea",
            "ross", "tj maxx", "tjmaxx", "marshalls", "old navy", "nike", "clothes",
            "clothing", "shoes", "mall", "dollar tree", "dollar general", "five below",
            "hobby lobby", "michaels", "staples", "office depot", "harbor freight"
        ),
        "Entertainment" to listOf(
            "netflix", "hulu", "spotify", "disney", "hbo", "paramount", "peacock",
            "youtube", "steam", "playstation", "xbox", "nintendo", "movie", "cinema",
            "theater", "theatre", "concert", "golf", "bowling", "arcade", "game", "games"
        ),
        "Bills & Utilities" to listOf(
            "rent", "mortgage", "electric", "electricity", "water bill", "gas bill",
            "internet", "wifi", "phone bill", "verizon", "at&t", "t-mobile", "tmobile",
            "comcast", "xfinity", "insurance", "utility", "utilities", "hoa", "trash",
            "sewer", "subscription"
        ),
        "Health" to listOf(
            "pharmacy", "cvs", "walgreens", "rite aid", "doctor", "dentist", "dental",
            "hospital", "clinic", "urgent care", "gym", "fitness", "planet fitness",
            "copay", "prescription", "vet", "veterinar"
        )
    )

    private val incomeWords = listOf(
        "income", "paycheck", "pay check", "payday", "salary", "got paid", "paid me",
        "deposit", "refund", "reimburs", "cashback", "cash back", "sold", "earned",
        "bonus", "allowance"
    )

    fun looksLikeIncome(text: String): Boolean {
        val t = text.lowercase(Locale.US)
        return incomeWords.any { t.contains(it) }
    }

    fun categorize(merchant: String, fullText: String, isIncome: Boolean): String {
        if (isIncome) return INCOME
        val hay = (merchant + " " + fullText).lowercase(Locale.US)
        for ((cat, words) in keywords) {
            if (words.any { hay.contains(it) }) return cat
        }
        return "Other"
    }

    private val customPalette = intArrayOf(
        0xFF5C6BC0.toInt(), 0xFF8D6E63.toInt(), 0xFF00897B.toInt(), 0xFF7CB342.toInt(),
        0xFFF06292.toInt(), 0xFF29B6F6.toInt(), 0xFFFFB300.toInt(), 0xFF9575CD.toInt()
    )

    fun color(category: String): Int = when (category) {
        "Groceries" -> 0xFF66BB6A.toInt()
        "Dining" -> 0xFFFF7043.toInt()
        "Gas & Transport" -> 0xFF42A5F5.toInt()
        "Shopping" -> 0xFFAB47BC.toInt()
        "Entertainment" -> 0xFFEC407A.toInt()
        "Bills & Utilities" -> 0xFFFFA726.toInt()
        "Health" -> 0xFF26A69A.toInt()
        "Other" -> 0xFF9E9E9E.toInt()
        INCOME -> 0xFF43A047.toInt()
        else -> customPalette[abs(category.hashCode()) % customPalette.size]
    }
}
