package com.rober.weeklybudget

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(context: Context) : SQLiteOpenHelper(context, "budget.db", null, 2) {

    data class CatRow(val name: String, val builtin: Boolean, val hidden: Boolean, val isIncome: Boolean)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tx(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "epoch_day INTEGER NOT NULL," +
                "amount_cents INTEGER NOT NULL," +
                "merchant TEXT NOT NULL," +
                "category TEXT NOT NULL," +
                "is_income INTEGER NOT NULL DEFAULT 0," +
                "import_hash TEXT)"
        )
        db.execSQL("CREATE INDEX idx_tx_day ON tx(epoch_day)")
        db.execSQL("CREATE INDEX idx_tx_hash ON tx(import_hash)")
        createAuxTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tx ADD COLUMN import_hash TEXT")
            db.execSQL("CREATE INDEX idx_tx_hash ON tx(import_hash)")
            createAuxTables(db)
        }
    }

    private fun createAuxTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS merchant_cat(" +
                "merchant_key TEXT PRIMARY KEY," +
                "category TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS categories(" +
                "name TEXT PRIMARY KEY," +
                "builtin INTEGER NOT NULL DEFAULT 0," +
                "hidden INTEGER NOT NULL DEFAULT 0," +
                "is_income INTEGER NOT NULL DEFAULT 0," +
                "sort INTEGER NOT NULL DEFAULT 50)"
        )
        Categories.BUILTIN_EXPENSE.forEachIndexed { i, name ->
            val sort = if (name == "Other") 90 else i
            db.execSQL(
                "INSERT OR IGNORE INTO categories(name, builtin, hidden, is_income, sort) VALUES(?,1,0,0,?)",
                arrayOf<Any>(name, sort)
            )
        }
        db.execSQL(
            "INSERT OR IGNORE INTO categories(name, builtin, hidden, is_income, sort) VALUES(?,1,0,1,100)",
            arrayOf<Any>(Categories.INCOME)
        )
    }

    // ---- transactions ----

    fun insert(tx: Tx): Long = writableDatabase.insert("tx", null, values(tx))

    fun update(tx: Tx) {
        writableDatabase.update("tx", values(tx), "id=?", arrayOf(tx.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("tx", "id=?", arrayOf(id.toString()))
    }

    fun insertAll(txs: List<Tx>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in txs) db.insert("tx", null, values(t))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listBetween(startDay: Long, endDay: Long): List<Tx> {
        val out = mutableListOf<Tx>()
        readableDatabase.query(
            "tx", null, "epoch_day BETWEEN ? AND ?",
            arrayOf(startDay.toString(), endDay.toString()),
            null, null, "epoch_day DESC, id DESC"
        ).use { c ->
            while (c.moveToNext()) out.add(fromCursor(c))
        }
        return out
    }

    fun listAll(): List<Tx> {
        val out = mutableListOf<Tx>()
        readableDatabase.query(
            "tx", null, null, null, null, null, "epoch_day ASC, id ASC"
        ).use { c ->
            while (c.moveToNext()) out.add(fromCursor(c))
        }
        return out
    }

    fun search(q: String, limit: Int = 200): List<Tx> {
        val out = mutableListOf<Tx>()
        val like = "%$q%"
        readableDatabase.query(
            "tx", null, "merchant LIKE ? OR category LIKE ?", arrayOf(like, like),
            null, null, "epoch_day DESC, id DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) out.add(fromCursor(c))
        }
        return out
    }

    fun existingHashes(): HashSet<String> {
        val out = HashSet<String>()
        readableDatabase.query(
            "tx", arrayOf("import_hash"), "import_hash IS NOT NULL", null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(0)) out.add(c.getString(0))
            }
        }
        return out
    }

    // ---- learned merchant -> category ----

    fun learnCategory(merchantKey: String, category: String) {
        if (merchantKey.isBlank()) return
        val cv = ContentValues().apply {
            put("merchant_key", merchantKey)
            put("category", category)
        }
        writableDatabase.insertWithOnConflict("merchant_cat", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun learnedCategory(merchantKey: String): String? {
        if (merchantKey.isBlank()) return null
        readableDatabase.query(
            "merchant_cat", arrayOf("category"), "merchant_key=?", arrayOf(merchantKey),
            null, null, null
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    // ---- categories ----

    fun categoryRows(): List<CatRow> {
        val out = mutableListOf<CatRow>()
        readableDatabase.query(
            "categories", null, null, null, null, null, "sort ASC, name ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CatRow(
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        builtin = c.getInt(c.getColumnIndexOrThrow("builtin")) == 1,
                        hidden = c.getInt(c.getColumnIndexOrThrow("hidden")) == 1,
                        isIncome = c.getInt(c.getColumnIndexOrThrow("is_income")) == 1
                    )
                )
            }
        }
        return out
    }

    fun categoryList(includeHidden: Boolean): List<String> =
        categoryRows().filter { includeHidden || !it.hidden }.map { it.name }

    fun addCategory(name: String): Boolean {
        val cv = ContentValues().apply {
            put("name", name)
            put("builtin", 0)
            put("hidden", 0)
            put("is_income", 0)
            put("sort", 50)
        }
        return writableDatabase.insertWithOnConflict(
            "categories", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun ensureCategory(name: String) {
        if (name.isNotBlank()) addCategory(name)
    }

    fun renameCategory(old: String, new: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.execSQL("UPDATE categories SET name=? WHERE name=?", arrayOf(new, old))
            db.execSQL("UPDATE tx SET category=? WHERE category=?", arrayOf(new, old))
            db.execSQL("UPDATE merchant_cat SET category=? WHERE category=?", arrayOf(new, old))
            db.setTransactionSuccessful()
            true
        } catch (e: Exception) {
            false
        } finally {
            db.endTransaction()
        }
    }

    fun deleteCategory(name: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE tx SET category='Other' WHERE category=?", arrayOf(name))
            db.execSQL("DELETE FROM merchant_cat WHERE category=?", arrayOf(name))
            db.execSQL("DELETE FROM categories WHERE name=? AND builtin=0", arrayOf(name))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setCategoryHidden(name: String, hidden: Boolean) {
        writableDatabase.execSQL(
            "UPDATE categories SET hidden=? WHERE name=?",
            arrayOf<Any>(if (hidden) 1 else 0, name)
        )
    }

    // ---- helpers ----

    private fun fromCursor(c: Cursor): Tx {
        val hashIdx = c.getColumnIndexOrThrow("import_hash")
        return Tx(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            epochDay = c.getLong(c.getColumnIndexOrThrow("epoch_day")),
            amountCents = c.getLong(c.getColumnIndexOrThrow("amount_cents")),
            merchant = c.getString(c.getColumnIndexOrThrow("merchant")),
            category = c.getString(c.getColumnIndexOrThrow("category")),
            isIncome = c.getInt(c.getColumnIndexOrThrow("is_income")) == 1,
            importHash = if (c.isNull(hashIdx)) null else c.getString(hashIdx)
        )
    }

    private fun values(tx: Tx) = ContentValues().apply {
        put("epoch_day", tx.epochDay)
        put("amount_cents", tx.amountCents)
        put("merchant", tx.merchant)
        put("category", tx.category)
        put("is_income", if (tx.isIncome) 1 else 0)
        if (tx.importHash != null) put("import_hash", tx.importHash) else putNull("import_hash")
    }
}
