package com.rober.weeklybudget

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private const val XLSX_MIME =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

class MainActivity : AppCompatActivity() {

    private lateinit var db: Db
    private lateinit var adapter: TxAdapter
    private var weekStart: LocalDate = LocalDate.now()
    private var monthStart: LocalDate = LocalDate.now()
    private var monthMode = false
    private var txs: List<Tx> = emptyList()
    private var capCents: Long = 0

    // when set, the next speech result is appended to this text ("Add more")
    private var appendBase: String? = null

    private lateinit var tvWeekTitle: TextView
    private lateinit var tvWeekRange: TextView
    private lateinit var tvIncome: TextView
    private lateinit var tvSpent: TextView
    private lateinit var tvNet: TextView
    private lateinit var tvBreakdown: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var chart: WeekBarChart
    private lateinit var capRow: View
    private lateinit var capBar: LinearProgressIndicator
    private lateinit var tvCap: TextView

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val base = appendBase
            appendBase = null
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    onSpeech(if (base != null) "$base $spoken" else spoken)
                }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handleImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = Db(this)
        weekStart = startOfWeek(LocalDate.now())
        monthStart = LocalDate.now().withDayOfMonth(1)

        tvWeekTitle = findViewById(R.id.tvWeekTitle)
        tvWeekRange = findViewById(R.id.tvWeekRange)
        tvIncome = findViewById(R.id.tvIncome)
        tvSpent = findViewById(R.id.tvSpent)
        tvNet = findViewById(R.id.tvNet)
        tvBreakdown = findViewById(R.id.tvBreakdown)
        tvEmpty = findViewById(R.id.tvEmpty)
        chart = findViewById(R.id.chart)
        capRow = findViewById(R.id.capRow)
        capBar = findViewById(R.id.capBar)
        tvCap = findViewById(R.id.tvCap)
        capCents = getSharedPreferences("settings", MODE_PRIVATE).getLong("weekly_cap_cents", 0)
        capRow.setOnClickListener { showCapDialog() }

        val list = findViewById<RecyclerView>(R.id.list)
        adapter = TxAdapter()
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButtonToggleGroup>(R.id.toggleView)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    monthMode = checkedId == R.id.btnMonthView
                    reload()
                }
            }

        findViewById<ImageButton>(R.id.btnPrevWeek).setOnClickListener {
            if (monthMode) monthStart = monthStart.minusMonths(1)
            else weekStart = weekStart.minusWeeks(1)
            reload()
        }
        findViewById<ImageButton>(R.id.btnNextWeek).setOnClickListener {
            if (monthMode) monthStart = monthStart.plusMonths(1)
            else weekStart = weekStart.plusWeeks(1)
            reload()
        }
        findViewById<ImageButton>(R.id.btnExport).setOnClickListener { exportBudget() }
        findViewById<ImageButton>(R.id.btnSearch).setOnClickListener { showSearchDialog() }
        findViewById<ImageButton>(R.id.btnCategories).setOnClickListener { showCategoriesDialog() }
        findViewById<ExtendedFloatingActionButton>(R.id.fabSpeak).setOnClickListener { startListening() }
        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener { showEditDialog(null) }

        reload()

        if (savedInstanceState == null &&
            intent?.getBooleanExtra(BudgetWidget.EXTRA_START_LISTENING, false) == true
        ) {
            intent.removeExtra(BudgetWidget.EXTRA_START_LISTENING)
            startListening()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(BudgetWidget.EXTRA_START_LISTENING, false)) {
            intent.removeExtra(BudgetWidget.EXTRA_START_LISTENING)
            startListening()
        }
    }

    // ---- speech ----

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
            // keep listening ~2s after you pause so the tail doesn't get cut off
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            appendBase = null
            Toast.makeText(this, R.string.no_speech, Toast.LENGTH_LONG).show()
            showEditDialog(null)
        }
    }

    private fun onSpeech(spoken: String) {
        val p = Parser.parse(spoken)
        if (p.amountCents == null) {
            Toast.makeText(this, R.string.no_amount_heard, Toast.LENGTH_SHORT).show()
        }
        // your past corrections win over the built-in keyword guess
        var category = p.category
        var isIncome = p.isIncome
        if (p.merchant.isNotBlank()) {
            db.learnedCategory(Categories.key(p.merchant))?.let {
                category = it
                if (it == Categories.INCOME) isIncome = true
            }
        }
        val draft = Tx(
            id = 0,
            epochDay = p.epochDay,
            amountCents = p.amountCents ?: 0,
            merchant = p.merchant,
            category = category,
            isIncome = isIncome
        )
        showEditDialog(draft, isNew = true, heard = spoken)
    }

    // ---- add/edit dialog ----

    private fun showEditDialog(existing: Tx?, isNew: Boolean = existing == null, heard: String? = null) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_edit, null)
        val etAmount = v.findViewById<TextInputEditText>(R.id.etAmount)
        val etMerchant = v.findViewById<TextInputEditText>(R.id.etMerchant)
        val spCategory = v.findViewById<Spinner>(R.id.spCategory)
        val rbExpense = v.findViewById<RadioButton>(R.id.rbExpense)
        val rbIncome = v.findViewById<RadioButton>(R.id.rbIncome)
        val btnDate = v.findViewById<Button>(R.id.btnDate)

        val cats = db.categoryList(false).toMutableList()
        existing?.category?.let { if (it !in cats) cats.add(it) }
        spCategory.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, cats
        )

        var day: LocalDate = existing?.let { LocalDate.ofEpochDay(it.epochDay) } ?: LocalDate.now()

        fun renderDate() {
            btnDate.text = day.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
        }
        renderDate()
        btnDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, mo, d ->
                    day = LocalDate.of(y, mo + 1, d)
                    renderDate()
                },
                day.year, day.monthValue - 1, day.dayOfMonth
            ).show()
        }

        if (existing != null) {
            if (existing.amountCents > 0) {
                etAmount.setText(String.format(Locale.US, "%.2f", existing.amountCents / 100.0))
            }
            etMerchant.setText(existing.merchant)
            val idx = cats.indexOf(existing.category)
            if (idx >= 0) spCategory.setSelection(idx)
            if (existing.isIncome) rbIncome.isChecked = true else rbExpense.isChecked = true
        } else {
            rbExpense.isChecked = true
        }

        rbIncome.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val i = cats.indexOf(Categories.INCOME)
                if (i >= 0) spCategory.setSelection(i)
            }
        }
        rbExpense.setOnCheckedChangeListener { _, checked ->
            if (checked && spCategory.selectedItem == Categories.INCOME) {
                val i = cats.indexOf("Other")
                if (i >= 0) spCategory.setSelection(i)
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(
                when {
                    heard != null -> getString(R.string.heard_title, heard)
                    isNew -> getString(R.string.add_title)
                    else -> getString(R.string.edit_title)
                }
            )
            .setView(v)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
        if (!isNew && existing != null) {
            builder.setNeutralButton(R.string.delete) { _, _ ->
                db.delete(existing.id)
                reload()
            }
        } else if (heard != null) {
            // voice draft: speak again and append, in case the recognizer cut you off
            builder.setNeutralButton(R.string.add_more) { _, _ ->
                appendBase = heard
                startListening()
            }
        }
        val dlg = builder.create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val amount = etAmount.text?.toString()?.trim()?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                etAmount.error = getString(R.string.amount_required)
                return@setOnClickListener
            }
            val tx = Tx(
                id = existing?.id ?: 0,
                epochDay = day.toEpochDay(),
                amountCents = (amount * 100).roundToLong(),
                merchant = etMerchant.text?.toString()?.trim().orEmpty(),
                category = spCategory.selectedItem as String,
                isIncome = rbIncome.isChecked
            )
            if (tx.id == 0L) db.insert(tx) else db.update(tx)
            if (tx.merchant.isNotBlank()) {
                db.learnCategory(Categories.key(tx.merchant), tx.category)
            }
            val d = LocalDate.ofEpochDay(tx.epochDay)
            weekStart = startOfWeek(d)
            monthStart = d.withDayOfMonth(1)
            dlg.dismiss()
            reload()
        }
    }

    // ---- export ----

    private fun exportBudget() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (Build.VERSION.SDK_INT >= 29) {
            items.add(getString(R.string.export_xlsx_download) to { exportXlsx(toDownloads = true) })
            items.add(getString(R.string.export_download) to { exportCsvFile(toDownloads = true) })
            items.add(getString(R.string.export_xlsx_share) to { exportXlsx(toDownloads = false) })
        } else {
            items.add(getString(R.string.export_xlsx_share) to { exportXlsx(toDownloads = false) })
            items.add(getString(R.string.export_share) to { exportCsvFile(toDownloads = false) })
        }
        items.add(getString(R.string.import_bank) to { importLauncher.launch("*/*") })
        AlertDialog.Builder(this)
            .setTitle(R.string.export_import_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun exportXlsx(toDownloads: Boolean) {
        val all = db.listAll()
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = Xlsx.build(all) { d -> startOfWeek(d) }
        val name = "WeeklyBudget-${LocalDate.now()}.xlsx"
        if (toDownloads) saveToDownloads(bytes, name, XLSX_MIME) else shareFile(bytes, name, XLSX_MIME)
    }

    private fun exportCsvFile(toDownloads: Boolean) {
        val all = db.listAll()
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("Date,Week Start,Type,Amount,Merchant,Category\n")
        for (t in all) {
            val d = LocalDate.ofEpochDay(t.epochDay)
            sb.append(d).append(',')
                .append(startOfWeek(d)).append(',')
                .append(if (t.isIncome) "Income" else "Expense").append(',')
                .append(String.format(Locale.US, "%.2f", t.amountCents / 100.0)).append(',')
                .append(csvField(t.merchant)).append(',')
                .append(csvField(t.category)).append('\n')
        }
        val bytes = sb.toString().toByteArray()
        val name = "WeeklyBudget-${LocalDate.now()}.csv"
        if (toDownloads) saveToDownloads(bytes, name, "text/csv") else shareFile(bytes, name, "text/csv")
    }

    private fun csvField(s: String): String =
        if (s.contains(',') || s.contains('"') || s.contains('\n')) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s

    private fun saveToDownloads(bytes: ByteArray, name: String, mime: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream failed")
            Toast.makeText(this, getString(R.string.export_saved, name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(bytes: ByteArray, name: String, mime: String) {
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val f = File(dir, name)
            f.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(this, "com.rober.weeklybudget.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.export)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- bank CSV import ----

    private fun handleImport(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        val res = text?.let { CsvImport.parse(it) }
        if (res == null) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_LONG).show()
            return
        }
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_import, null)
        val tvSummary = v.findViewById<TextView>(R.id.tvImportSummary)
        val cbFlip = v.findViewById<CheckBox>(R.id.cbFlip)
        val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
        val from = LocalDate.ofEpochDay(res.rows.minOf { it.epochDay }).format(fmt)
        val to = LocalDate.ofEpochDay(res.rows.maxOf { it.epochDay }).format(fmt)

        fun render(flip: Boolean) {
            val exp: Int
            if (res.ourFormat) {
                exp = res.rows.count { !it.incomeDefault }
            } else {
                exp = res.rows.count { it.incomeDefault == flip }
            }
            var msg = getString(R.string.import_preview, res.rows.size, from, to, exp, res.rows.size - exp)
            if (res.skipped > 0) msg += getString(R.string.import_skipped, res.skipped)
            tvSummary.text = msg
        }

        if (res.ourFormat) {
            cbFlip.visibility = View.GONE
        } else {
            cbFlip.isChecked = res.positiveCount > res.negativeCount
        }
        render(cbFlip.isChecked && !res.ourFormat)
        cbFlip.setOnCheckedChangeListener { _, checked -> render(checked) }

        AlertDialog.Builder(this)
            .setTitle(R.string.import_preview_title)
            .setView(v)
            .setPositiveButton(R.string.import_button) { _, _ ->
                commitImport(res, cbFlip.isChecked && !res.ourFormat)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun commitImport(res: CsvImport.Result, flip: Boolean) {
        val existing = db.existingHashes()
        var dup = 0
        val toInsert = mutableListOf<Tx>()
        for (r in res.rows) {
            if (r.hash in existing) {
                dup++
                continue
            }
            existing.add(r.hash)
            val isIncome = if (res.ourFormat) r.incomeDefault else (r.incomeDefault != flip)
            val category = r.fileCategory
                ?: if (isIncome) {
                    Categories.INCOME
                } else {
                    db.learnedCategory(Categories.key(r.merchant))
                        ?: Categories.categorize(r.merchant, r.rawDesc, false)
                }
            toInsert.add(
                Tx(
                    id = 0,
                    epochDay = r.epochDay,
                    amountCents = r.amountCents,
                    merchant = r.merchant,
                    category = category,
                    isIncome = isIncome,
                    importHash = r.hash
                )
            )
        }
        toInsert.map { it.category }.distinct().forEach { db.ensureCategory(it) }
        db.insertAll(toInsert)
        Toast.makeText(
            this, getString(R.string.import_done, toInsert.size, dup), Toast.LENGTH_LONG
        ).show()
        if (toInsert.isNotEmpty()) {
            val newest = LocalDate.ofEpochDay(toInsert.maxOf { it.epochDay })
            weekStart = startOfWeek(newest)
            monthStart = newest.withDayOfMonth(1)
        }
        reload()
    }

    // ---- search ----

    private fun showSearchDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_search, null)
        val et = v.findViewById<TextInputEditText>(R.id.etSearch)
        val rv = v.findViewById<RecyclerView>(R.id.searchList)
        val tvNone = v.findViewById<TextView>(R.id.tvNoResults)
        val results = mutableListOf<Tx>()
        var dlg: AlertDialog? = null
        val ad = SearchAdapter(results) { tx ->
            dlg?.dismiss()
            showEditDialog(tx)
        }
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = ad
        et.doAfterTextChanged { text ->
            results.clear()
            val q = text?.toString()?.trim().orEmpty()
            if (q.length >= 2) results.addAll(db.search(q))
            ad.notifyDataSetChanged()
            tvNone.visibility = if (q.length >= 2 && results.isEmpty()) View.VISIBLE else View.GONE
        }
        dlg = AlertDialog.Builder(this)
            .setTitle(R.string.search)
            .setView(v)
            .setNegativeButton(R.string.close, null)
            .create()
        dlg.show()
    }

    // ---- manage categories ----

    private fun showCategoriesDialog() {
        val rows = db.categoryRows()
        val labels = rows.map { r ->
            r.name + if (r.hidden) "  " + getString(R.string.hidden_suffix) else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.categories_title)
            .setItems(labels) { _, i -> showCategoryOptions(rows[i]) }
            .setPositiveButton(R.string.add_category) { _, _ -> promptCategoryName(null) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showCategoryOptions(row: Db.CatRow) {
        val locked = row.name == "Other" || row.name == Categories.INCOME
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (!row.builtin) {
            labels.add(getString(R.string.rename))
            actions.add { promptCategoryName(row.name) }
        }
        if (!locked) {
            labels.add(getString(if (row.hidden) R.string.unhide else R.string.hide))
            actions.add {
                db.setCategoryHidden(row.name, !row.hidden)
                reload()
                showCategoriesDialog()
            }
        }
        if (!row.builtin) {
            labels.add(getString(R.string.delete))
            actions.add { confirmDeleteCategory(row.name) }
        }
        if (labels.isEmpty()) {
            Toast.makeText(this, R.string.builtin_locked, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(row.name)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptCategoryName(renameFrom: String?) {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_name, null)
        val et = v.findViewById<TextInputEditText>(R.id.etName)
        if (renameFrom != null) et.setText(renameFrom)
        AlertDialog.Builder(this)
            .setTitle(if (renameFrom == null) R.string.add_category else R.string.rename)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = et.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name == renameFrom) return@setPositiveButton
                val ok = if (renameFrom == null) db.addCategory(name)
                else db.renameCategory(renameFrom, name)
                if (!ok) Toast.makeText(this, R.string.category_exists, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteCategory(name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete) + " " + name)
            .setMessage(R.string.delete_category_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                db.deleteCategory(name)
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- data / rendering ----

    private fun reload() {
        val today = LocalDate.now()
        val rangeStart: LocalDate
        val rangeEnd: LocalDate
        if (monthMode) {
            rangeStart = monthStart
            rangeEnd = monthStart.plusMonths(1).minusDays(1)
        } else {
            rangeStart = weekStart
            rangeEnd = weekStart.plusDays(6)
        }
        txs = db.listBetween(rangeStart.toEpochDay(), rangeEnd.toEpochDay())
        adapter.notifyDataSetChanged()

        val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val yearSuffix = if (rangeStart.year != today.year) ", ${rangeStart.year}" else ""
        if (monthMode) {
            tvWeekTitle.text = if (monthStart == today.withDayOfMonth(1)) {
                getString(R.string.this_month)
            } else {
                monthStart.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
            }
        } else {
            val thisWeek = startOfWeek(today)
            tvWeekTitle.text = when (weekStart) {
                thisWeek -> getString(R.string.this_week)
                thisWeek.minusWeeks(1) -> getString(R.string.last_week)
                else -> getString(R.string.week_of, weekStart.format(fmt))
            }
        }
        tvWeekRange.text = "${rangeStart.format(fmt)} – ${rangeEnd.format(fmt)}$yearSuffix"

        val income = txs.filter { it.isIncome }.sumOf { it.amountCents }
        val spent = txs.filter { !it.isIncome }.sumOf { it.amountCents }
        val net = income - spent
        tvIncome.text = money(income)
        tvSpent.text = money(spent)
        tvNet.text = (if (net < 0) "-" else "") + money(abs(net))
        tvNet.setTextColor(getColor(if (net < 0) R.color.expense_red else R.color.income_green))

        renderCap(spent)

        val byCat = txs.filter { !it.isIncome }
            .groupBy { it.category }
            .mapValues { e -> e.value.sumOf { it.amountCents } }
            .entries.sortedByDescending { it.value }
        tvBreakdown.visibility = if (byCat.isEmpty()) View.GONE else View.VISIBLE
        tvBreakdown.text = byCat.take(4).joinToString("  ·  ") { "${it.key} ${money(it.value)}" }

        if (monthMode && txs.isNotEmpty()) {
            val bars = mutableListOf<WeekBarChart.Bar>()
            var ws = startOfWeek(rangeStart)
            while (ws <= rangeEnd) {
                val we = ws.plusDays(6)
                val weekTxs = txs.filter { it.epochDay in ws.toEpochDay()..we.toEpochDay() }
                bars.add(
                    WeekBarChart.Bar(
                        label = "${ws.monthValue}/${ws.dayOfMonth}",
                        income = weekTxs.filter { it.isIncome }.sumOf { it.amountCents },
                        expense = weekTxs.filter { !it.isIncome }.sumOf { it.amountCents }
                    )
                )
                ws = ws.plusWeeks(1)
            }
            chart.setCap(capCents)
            chart.setData(bars)
            chart.visibility = View.VISIBLE
        } else {
            chart.visibility = View.GONE
        }

        tvEmpty.setText(if (monthMode) R.string.empty_month else R.string.empty)
        tvEmpty.visibility = if (txs.isEmpty()) View.VISIBLE else View.GONE

        BudgetWidget.refresh(this)
    }

    // ---- weekly spending cap ----

    private fun renderCap(spent: Long) {
        capRow.visibility = if (monthMode) View.GONE else View.VISIBLE
        if (monthMode) return
        if (capCents <= 0) {
            capBar.visibility = View.GONE
            tvCap.text = getString(R.string.set_cap)
            tvCap.setTextColor(getColor(R.color.income_green))
            return
        }
        capBar.visibility = View.VISIBLE
        val pct = (spent * 100 / capCents).toInt()
        capBar.progress = pct.coerceAtMost(100)
        val color = when {
            spent >= capCents -> getColor(R.color.expense_red)
            pct >= 80 -> getColor(R.color.cap_warn)
            else -> getColor(R.color.income_green)
        }
        capBar.setIndicatorColor(color)
        tvCap.setTextColor(color)
        tvCap.text = if (spent > capCents) {
            getString(R.string.cap_over, money(spent - capCents), money(capCents))
        } else {
            getString(R.string.cap_of, money(spent), money(capCents))
        }
    }

    private fun showCapDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_cap, null)
        val etCap = v.findViewById<TextInputEditText>(R.id.etCap)
        if (capCents > 0) {
            etCap.setText(String.format(Locale.US, "%.2f", capCents / 100.0))
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.cap_title)
            .setView(v)
            .setPositiveButton(R.string.save) { _, _ ->
                val amount = etCap.text?.toString()?.trim()?.toDoubleOrNull()
                storeCap(if (amount != null && amount > 0) (amount * 100).roundToLong() else 0)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (capCents > 0) {
            builder.setNeutralButton(R.string.remove) { _, _ -> storeCap(0) }
        }
        builder.show()
    }

    private fun storeCap(cents: Long) {
        capCents = cents
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putLong("weekly_cap_cents", cents).apply()
        reload()
    }

    private fun money(cents: Long) = String.format(Locale.US, "$%,.2f", cents / 100.0)

    private fun startOfWeek(d: LocalDate): LocalDate =
        d.minusDays((d.dayOfWeek.value % 7).toLong())

    // ---- list ----

    private inner class SearchAdapter(
        private val results: List<Tx>,
        private val onClick: (Tx) -> Unit
    ) : RecyclerView.Adapter<SearchAdapter.VH>() {

        inner class VH(item: View) : RecyclerView.ViewHolder(item) {
            val tvDow: TextView = item.findViewById(R.id.tvDow)
            val tvDate: TextView = item.findViewById(R.id.tvDate)
            val tvMerchant: TextView = item.findViewById(R.id.tvMerchant)
            val tvCategory: TextView = item.findViewById(R.id.tvCategory)
            val tvAmount: TextView = item.findViewById(R.id.tvAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tx, parent, false))

        override fun getItemCount() = results.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tx = results[position]
            val d = LocalDate.ofEpochDay(tx.epochDay)
            holder.tvDow.text = d.format(DateTimeFormatter.ofPattern("EEE", Locale.US))
            holder.tvDate.text = d.format(DateTimeFormatter.ofPattern("M/d/yy", Locale.US))
            holder.tvMerchant.text = tx.merchant.ifBlank { tx.category }
            holder.tvCategory.text = tx.category
            holder.tvCategory.setTextColor(Categories.color(tx.category))
            holder.tvAmount.text = (if (tx.isIncome) "+" else "-") + money(tx.amountCents)
            holder.tvAmount.setTextColor(
                holder.itemView.context.getColor(
                    if (tx.isIncome) R.color.income_green else R.color.expense_red
                )
            )
            holder.itemView.setOnClickListener { onClick(tx) }
        }
    }

    private inner class TxAdapter : RecyclerView.Adapter<TxAdapter.VH>() {

        inner class VH(item: View) : RecyclerView.ViewHolder(item) {
            val tvDow: TextView = item.findViewById(R.id.tvDow)
            val tvDate: TextView = item.findViewById(R.id.tvDate)
            val tvMerchant: TextView = item.findViewById(R.id.tvMerchant)
            val tvCategory: TextView = item.findViewById(R.id.tvCategory)
            val tvAmount: TextView = item.findViewById(R.id.tvAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tx, parent, false))

        override fun getItemCount() = txs.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tx = txs[position]
            val d = LocalDate.ofEpochDay(tx.epochDay)
            holder.tvDow.text = d.format(DateTimeFormatter.ofPattern("EEE", Locale.US))
            holder.tvDate.text = d.format(DateTimeFormatter.ofPattern("M/d", Locale.US))
            holder.tvMerchant.text = tx.merchant.ifBlank { tx.category }
            holder.tvCategory.text = tx.category
            holder.tvCategory.setTextColor(Categories.color(tx.category))
            holder.tvAmount.text = (if (tx.isIncome) "+" else "-") + money(tx.amountCents)
            holder.tvAmount.setTextColor(
                holder.itemView.context.getColor(
                    if (tx.isIncome) R.color.income_green else R.color.expense_red
                )
            )
            holder.itemView.setOnClickListener { showEditDialog(tx) }
        }
    }
}
