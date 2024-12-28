package com.example.networkdiscoverytest

import java.util.UUID

data class SyncItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)