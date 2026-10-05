package com.v2ray.ang.service

enum class VpnStopReason { USER_STOP, USER_RESTART, VPN_REVOKED, CORE_STOPPED, START_FAILED, SERVICE_DESTROYED }

/** One service instance owns one session. Late native callbacks cannot stop it twice. */
class VpnLifecycle {
    enum class Phase { NEW, STARTING, CONNECTED, STOPPING, STOPPED }
    @Volatile var phase = Phase.NEW
        private set
    var stopReason: VpnStopReason? = null
        private set

    @Synchronized fun beginStart(): Boolean {
        if (phase != Phase.NEW) return false
        phase = Phase.STARTING
        return true
    }

    @Synchronized fun connected(): Boolean {
        if (phase != Phase.STARTING) return false
        phase = Phase.CONNECTED
        return true
    }

    @Synchronized fun beginStop(reason: VpnStopReason): Boolean {
        if (phase == Phase.STOPPING || phase == Phase.STOPPED) return false
        stopReason = reason
        phase = Phase.STOPPING
        return true
    }

    @Synchronized fun stopped() {
        if (phase == Phase.STOPPING) phase = Phase.STOPPED
    }
}
