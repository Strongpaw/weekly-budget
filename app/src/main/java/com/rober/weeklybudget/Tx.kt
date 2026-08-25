package com.rober.weeklybudget

data class Tx(
    val id: Long,
    val epochDay: Long,
    val amountCents: Long,
    val merchant: String,
    val category: String,
    val isIncome: Boolean,
    val importHash: String? = null
)
