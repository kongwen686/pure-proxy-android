package com.v2ray.ang.handler

import com.tencent.mmkv.MMKV
import com.v2ray.ang.service.VpnStopReason

/** Only event codes and timestamps. Never persist node names, configs, or exception messages. */
object VpnSessionDiagnostics {
    private val storage by lazy { MMKV.mmkvWithID("VPN_DIAGNOSTICS", MMKV.MULTI_PROCESS_MODE) }

    fun record(event: String) {
        val allowed = setOf("STARTING", "CONNECTED") + VpnStopReason.entries.map { it.name }
        require(event in allowed)
        // A single value keeps the event and its timestamp consistent across processes.
        // Diagnostics must never prevent startup or resource cleanup if storage is unavailable.
        runCatching {
            storage.encode("last_event", "${System.currentTimeMillis()}:$event")
            storage.sync()
        }
    }

    fun lastEvent(): Pair<Long, String>? {
        val parts = runCatching { storage.decodeString("last_event") }.getOrNull()
            ?.split(':', limit = 2) ?: return null
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val event = parts.getOrNull(1) ?: return null
        return time to event
    }
}
