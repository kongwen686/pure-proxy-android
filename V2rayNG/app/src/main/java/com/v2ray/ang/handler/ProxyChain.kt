package com.v2ray.ang.handler

import com.v2ray.ang.R
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.dto.V2rayConfig

/** Pure chain resolution and wiring, independent of MMKV, UI, and native-core lifecycle. */
object ProxyChain {
    class Invalid(val resource: Int) : IllegalArgumentException("Invalid proxy chain ($resource)")
    data class Plan(val previous: Pair<String, ProfileItem>?, val exit: Pair<String, ProfileItem>?)

    fun enabled(group: SubscriptionItem?): Boolean = group != null && when (group.chainEnabled) {
        false -> false
        true -> true
        null -> !group.prevProfile.isNullOrBlank() || !group.nextProfile.isNullOrBlank() ||
            !group.prevProfileId.isNullOrBlank() || !group.nextProfileId.isNullOrBlank()
    }

    fun supported(profile: ProfileItem): Boolean = profile.configType in setOf(
        EConfigType.VLESS, EConfigType.VMESS, EConfigType.SHADOWSOCKS,
        EConfigType.SOCKS, EConfigType.HTTP, EConfigType.TROJAN
    )

    fun resolve(
        entryId: String,
        entry: ProfileItem,
        group: SubscriptionItem?,
        profiles: List<Pair<String, ProfileItem>>,
        fragment: Boolean = false
    ): Plan {
        if (!enabled(group)) return Plan(null, null)
        group ?: throw Invalid(R.string.chain_error_missing)
        if (fragment) throw Invalid(R.string.chain_error_fragment)
        if (!supported(entry)) throw Invalid(R.string.chain_error_unsupported)
        fun validateEndpoint(profile: ProfileItem) {
            val port = profile.serverPort?.toIntOrNull()
            if (profile.server.isNullOrBlank() || port == null || port !in 1..65535)
                throw Invalid(R.string.chain_error_endpoint)
        }
        validateEndpoint(entry)
        fun find(id: String?, alias: String?): Pair<String, ProfileItem>? {
            if (id.isNullOrBlank() && alias.isNullOrBlank()) return null
            val matches = if (!id.isNullOrBlank()) profiles.filter { it.first == id }
                else profiles.filter { it.second.remarks == alias }
            if (matches.isEmpty()) throw Invalid(R.string.chain_error_missing)
            if (matches.size != 1) throw Invalid(R.string.chain_error_ambiguous)
            return matches.single().also {
                if (!supported(it.second)) throw Invalid(R.string.chain_error_unsupported)
                validateEndpoint(it.second)
            }
        }
        val previous = find(group.prevProfileId, group.prevProfile)
        val exit = find(group.nextProfileId, group.nextProfile)
        if (previous == null && exit == null) throw Invalid(R.string.chain_error_exit)
        val ids = listOfNotNull(entryId, previous?.first, exit?.first)
        if (ids.distinct().size != ids.size) throw Invalid(R.string.chain_error_loop)
        return Plan(previous, exit)
    }

    /** Dial the final outbound through the preceding hop; keep routing's proxy tag on the exit. */
    fun wire(config: V2rayConfig, previous: V2rayConfig.OutboundBean?, exit: V2rayConfig.OutboundBean?) {
        val entry = config.outbounds.first()
        entry.mux = null
        if (previous != null) {
            previous.tag = "proxy-chain-front"
            previous.mux = null
            config.outbounds.add(previous)
            entry.ensureSockopt().dialerProxy = previous.tag
        }
        if (exit != null) {
            entry.tag = "proxy-chain-entry"
            exit.tag = "proxy"
            exit.mux = null
            exit.ensureSockopt().dialerProxy = entry.tag
            config.outbounds.add(0, exit)
        }
    }
}
