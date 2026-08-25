package com.rober.weeklybudget

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Parses bank statement CSV exports (and this app's own export format).
 * Pure logic — categorization and dedupe happen at commit time in MainActivity.
 */
object CsvImport {

    data class Row(
        val epochDay: Long,
        val amountCents: Long,
        val merchant: String,
        val rawDesc: String,
        val incomeDefault: Boolean,
        val hash: String,
        val fileCategory: String?
    )

    data class Result(
        val rows: List<Row>,
        val ourFormat: Boolean,
        val skipped: Int,
        val positiveCount: Int,
        val negativeCount: Int
    )

    private val dateFormats = listOf(
        "yyyy-MM-dd", "M/d/yyyy", "M/d/yy", "M-d-yyyy", "yyyy/M/d",
        "MMM d, yyyy", "MMM d yyyy", "d MMM yyyy",
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "M/d/yyyy H:mm"
    ).map { DateTimeFormatter.ofPattern(it, Locale.US) }

    fun parse(text: String): Result? {
        val lines = text.split("\r\n", "\n", "\r").filter { it.isNotBlank() }
        if (lines.size < 2) return null

        var headerIdx = -1
        var headers: List<String> = emptyList()
        for (i in 0 until minOf(5, lines.size)) {
            val cells = parseLine(lines[i]).map { it.trim().lowercase(Locale.US) }
            val hasDate = cells.any { it.contains("date") }
            val hasAmount = cells.any { it.contains("amount") }
            val hasDebitCredit =
                cells.any { it.contains("debit") || it.contains("withdrawal") } &&
                    cells.any { it.contains("credit") || it.contains("deposit") }
            if (hasDate && (hasAmount || hasDebitCredit)) {
                headerIdx = i
                headers = cells
                break
            }
        }
        if (headerIdx < 0) return null

        val ourFormat = headers.contains("week start") && headers.contains("type")

        val dateIdx = headers.indexOfFirst { it.contains("date") && !it.contains("update") }
        val descIdx = headers.indexOfFirst { h ->
            listOf("description", "payee", "merchant", "name", "memo", "details", "narrative")
                .any { h.contains(it) }
        }
        val amountIdx = headers.indexOfFirst { it.contains("amount") }
        val debitIdx = headers.indexOfFirst { it.contains("debit") || it.contains("withdrawal") }
        val creditIdx = headers.indexOfFirst { it.contains("credit") || it.contains("deposit") }
        val typeIdx = if (ourFormat) headers.indexOf("type") else -1
        val catIdx = if (ourFormat) headers.indexOfFirst { it.contains("category") } else -1
        if (dateIdx < 0 || (amountIdx < 0 && (debitIdx < 0 || creditIdx < 0))) return null

        val rows = mutableListOf<Row>()
        var skipped = 0
        var pos = 0
        var neg = 0
        for (i in headerIdx + 1 until lines.size) {
            val cells = parseLine(lines[i])
            val day = parseDate(cells.getOrNull(dateIdx)?.trim().orEmpty())
            if (day == null) {
                skipped++
                continue
            }
            var signed: Double? = null
            if (amountIdx >= 0) {
                signed = parseAmount(cells.getOrNull(amountIdx).orEmpty())
            }
            if (signed == null && debitIdx >= 0 && creditIdx >= 0) {
                val d = parseAmount(cells.getOrNull(debitIdx).orEmpty())
                val cr = parseAmount(cells.getOrNull(creditIdx).orEmpty())
                signed = when {
                    d != null && d != 0.0 -> -abs(d)
                    cr != null && cr != 0.0 -> abs(cr)
                    else -> null
                }
            }
            if (signed == null || signed == 0.0) {
                skipped++
                continue
            }
            val cents = (abs(signed) * 100).roundToLong()
            if (cents == 0L) {
                skipped++
                continue
            }
            val rawDesc = (if (descIdx >= 0) cells.getOrNull(descIdx) else null)?.trim().orEmpty()
            var incomeDefault = signed > 0
            if (ourFormat && typeIdx >= 0) {
                incomeDefault = cells.getOrNull(typeIdx)?.trim().equals("income", ignoreCase = true)
            }
            if (signed > 0) pos++ else neg++
            val merchant = if (ourFormat) rawDesc else cleanMerchant(rawDesc)
            val fileCategory = if (ourFormat && catIdx >= 0) {
                cells.getOrNull(catIdx)?.trim()?.takeIf { it.isNotEmpty() }
            } else null
            val hash = "${day.toEpochDay()}|$cents|${Categories.key(rawDesc.ifBlank { merchant })}"
            rows.add(Row(day.toEpochDay(), cents, merchant, rawDesc, incomeDefault, hash, fileCategory))
        }
        if (rows.isEmpty()) return null
        return Result(rows, ourFormat, skipped, pos, neg)
    }

    /** Splits one CSV line, honoring quoted fields and "" escapes. */
    fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parseDate(s: String): LocalDate? {
        if (s.isEmpty()) return null
        val cleaned = s.replace("\"", "").trim()
        for (f in dateFormats) {
            try {
                return LocalDate.parse(cleaned, f)
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun parseAmount(s: String): Double? {
        var t = s.trim().replace("\"", "").replace("$", "").replace(",", "").replace(" ", "")
        if (t.isEmpty()) return null
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true
            t = t.substring(1, t.length - 1)
        }
        if (t.startsWith("-")) {
            negative = true
            t = t.substring(1)
        }
        if (t.startsWith("+")) t = t.substring(1)
        val v = t.toDoubleOrNull() ?: return null
        return if (negative) -v else v
    }

    /** "SAFEWAY #123 SEATTLE WA" -> "Safeway Seattle Wa" */
    private fun cleanMerchant(raw: String): String {
        if (raw.isBlank()) return ""
        val stripped = raw
            .replace(Regex("\\d{3,}"), " ")
            .replace(Regex("[*#]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.US)
        return stripped.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase(Locale.US) } }
    }
}
