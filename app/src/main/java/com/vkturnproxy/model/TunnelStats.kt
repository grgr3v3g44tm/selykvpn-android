package com.vkturnproxy.model

import com.google.gson.annotations.SerializedName

data class TunnelStats(
    @SerializedName("rx_bytes") val bytesRx: Long = 0,
    @SerializedName("tx_bytes") val bytesTx: Long = 0,
    val speedRxBps: Long = 0,
    val speedTxBps: Long = 0,
    @SerializedName("active_conns") val activeConns: Int = 0,
    @SerializedName("total_conns") val allocatedConns: Int = 0,
    @SerializedName("turn_rtt_ms") val turnRttMs: Double = 0.0,
    val turnServer: String = "",
    val status: String = ""
)
