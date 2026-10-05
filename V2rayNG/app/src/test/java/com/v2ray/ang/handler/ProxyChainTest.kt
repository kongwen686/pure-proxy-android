package com.v2ray.ang.handler

import com.google.gson.Gson
import com.v2ray.ang.R
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.dto.V2rayConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProxyChainTest {
    private fun profile(type: EConfigType = EConfigType.VLESS, name: String = "relay") =
        ProfileItem(configType=type, remarks = name, server = "server.example", serverPort = "443")
    private val entry = profile()
    private val exit = profile(EConfigType.SOCKS, "exit")
    private val nodes = listOf("relay-id" to entry, "exit-id" to exit)
    private fun checkError(resource: Int, operation: () -> Unit) {
        try { operation(); fail("Expected chain rejection") }
        catch (e: ProxyChain.Invalid) { assertEquals(resource, e.resource) }
    }
    private fun config(): V2rayConfig = Gson().fromJson("""{
        "log":{}, "inbounds":[], "outbounds":[
        {"protocol":"vless","tag":"proxy","mux":{"enabled":true}},
        {"protocol":"freedom","tag":"direct"}, {"protocol":"blackhole","tag":"block"}],
        "routing":{"rules":[{"type":"field","outboundTag":"proxy","port":"443"}]}
    }""", V2rayConfig::class.java)

    @Test fun singleHopAndExplicitlyDisabledLegacyChainRemainSingleHop() {
        assertFalse(ProxyChain.enabled(null))
        assertFalse(ProxyChain.enabled(SubscriptionItem()))
        assertNull(ProxyChain.resolve("relay-id", entry, SubscriptionItem(chainEnabled=false,
            nextProfile="removed"), nodes, fragment=true).exit)
    }

    @Test fun oldReleasedGroupFormatStillResolvesAliases() {
        val old = Gson().fromJson("""{"remarks":"group","nextProfile":"exit","enabled":true}""",
            SubscriptionItem::class.java)
        assertNull(old.chainEnabled)
        assertEquals("exit-id", ProxyChain.resolve("relay-id", entry, old, nodes).exit?.first)
        val roundTrip = Gson().fromJson(Gson().toJson(old.copy(chainEnabled=true, nextProfileId="exit-id")),
            SubscriptionItem::class.java)
        assertEquals("exit-id", roundTrip.nextProfileId)
        assertEquals("exit", roundTrip.nextProfile)
    }

    @Test fun nodeRenameDoesNotBreakGuidSelection() {
        val renamed = exit.copy(remarks="new name")
        val plan = ProxyChain.resolve("relay-id", entry, SubscriptionItem(chainEnabled=true,
            nextProfileId="exit-id", nextProfile="old name"), listOf("relay-id" to entry, "exit-id" to renamed))
        assertEquals("new name", plan.exit?.second?.remarks)
    }

    @Test fun missingIdNeverFallsBackToAliasOrRelay() {
        checkError(R.string.chain_error_missing) {
            ProxyChain.resolve("relay-id", entry, SubscriptionItem(chainEnabled=true,
                nextProfileId="deleted-id", nextProfile="exit"), nodes)
        }
    }

    @Test fun duplicateLegacyAliasesRejectedButGuidStillWorks() {
        val duplicates = nodes + ("other-id" to exit.copy())
        checkError(R.string.chain_error_ambiguous) {
            ProxyChain.resolve("relay-id", entry, SubscriptionItem(nextProfile="exit"), duplicates)
        }
        assertEquals("exit-id", ProxyChain.resolve("relay-id", entry,
            SubscriptionItem(nextProfileId="exit-id"), duplicates).exit?.first)
    }

    @Test fun selfAndRepeatedHopLoopsRejected() {
        checkError(R.string.chain_error_loop) { ProxyChain.resolve("relay-id", entry,
            SubscriptionItem(chainEnabled=true,nextProfileId="relay-id"),nodes) }
        checkError(R.string.chain_error_loop) { ProxyChain.resolve("relay-id", entry,
            SubscriptionItem(chainEnabled=true,prevProfileId="exit-id",nextProfileId="exit-id"),nodes) }
    }

    @Test fun unsupportedEntryOrExitAndFragmentRejected() {
        for (type in listOf(EConfigType.CUSTOM,EConfigType.HYSTERIA2,EConfigType.WIREGUARD)) {
            checkError(R.string.chain_error_unsupported) { ProxyChain.resolve("relay-id", profile(type),
                SubscriptionItem(chainEnabled=true,nextProfileId="exit-id"),nodes) }
            checkError(R.string.chain_error_unsupported) { ProxyChain.resolve("relay-id",entry,
                SubscriptionItem(chainEnabled=true,nextProfileId="bad-id"),nodes+("bad-id" to profile(type))) }
        }
        checkError(R.string.chain_error_fragment) { ProxyChain.resolve("relay-id",entry,
            SubscriptionItem(chainEnabled=true,nextProfileId="exit-id"),nodes,true) }
    }

    @Test fun incompleteEnabledChainAndBadPortsRejected() {
        checkError(R.string.chain_error_exit) { ProxyChain.resolve("relay-id",entry,SubscriptionItem(chainEnabled=true),nodes) }
        for(port in listOf(null,"0","65536","text","")) {
            checkError(R.string.chain_error_endpoint) { ProxyChain.resolve("relay-id",entry,
                SubscriptionItem(chainEnabled=true,nextProfileId="bad-id"),nodes+("bad-id" to exit.copy(serverPort=port))) }
            checkError(R.string.chain_error_endpoint) { ProxyChain.resolve("relay-id",entry.copy(serverPort=port),
                SubscriptionItem(chainEnabled=true,nextProfileId="exit-id"),nodes) }
        }
    }

    @Test fun twoHopRoutingUsesExitAndExitDialsThroughRelay() {
        val config=config()
        val original=config.outbounds.first()
        val landing=V2rayConfig.OutboundBean(protocol="socks",tag="unused")
        ProxyChain.wire(config,null,landing)
        assertSame(landing,config.outbounds.first())
        assertEquals("proxy",landing.tag)
        assertEquals(original.tag,landing.streamSettings?.sockopt?.dialerProxy)
        assertNull(original.streamSettings?.sockopt?.dialerProxy)
        assertNull(original.mux)
        assertEquals("proxy",config.routing.rules.first().outboundTag)
        assertEquals(setOf("proxy","proxy-chain-entry","direct","block"),config.outbounds.map {it.tag}.toSet())
    }

    @Test fun threeHopOrderAndLegacyFrontOnlyArePreserved() {
        val config=config()
        val front=V2rayConfig.OutboundBean(protocol="socks",tag="front")
        val landing=V2rayConfig.OutboundBean(protocol="socks",tag="landing")
        ProxyChain.wire(config,front,landing)
        val byTag=config.outbounds.associateBy {it.tag}
        assertEquals("proxy-chain-entry",byTag["proxy"]?.streamSettings?.sockopt?.dialerProxy)
        assertEquals("proxy-chain-front",byTag["proxy-chain-entry"]?.streamSettings?.sockopt?.dialerProxy)
        assertNull(byTag["proxy-chain-front"]?.streamSettings?.sockopt?.dialerProxy)
        val frontOnly=config()
        ProxyChain.wire(frontOnly,V2rayConfig.OutboundBean(protocol="socks",tag="front"),null)
        assertEquals("proxy",frontOnly.outbounds.first().tag)
        assertEquals("proxy-chain-front",frontOnly.outbounds.first().streamSettings?.sockopt?.dialerProxy)
    }

    @Test fun nativeProbeFixturesUseProductionWiring() {
        fun client(front: Boolean): V2rayConfig {
            val config = Gson().fromJson("""{
                "log":{"loglevel":"warning"},
                "inbounds":[{"tag":"local","listen":"127.0.0.1","port":39300,"protocol":"socks","settings":{"auth":"noauth","udp":false}}],
                "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":39301,
                "users":[{"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}],
                "routing":{"rules":[]}
            }""", V2rayConfig::class.java)
            fun socks(port: Int) = Gson().fromJson("""{"tag":"unused","protocol":"socks","settings":{"servers":[
                {"address":"127.0.0.1","port":$port,"users":[{"user":"demo","pass":"demo"}]}]}}""",V2rayConfig.OutboundBean::class.java)
            ProxyChain.wire(config,if(front) socks(39303) else null,socks(39302))
            return config
        }
        val directory=File("build/chain-fixtures"); directory.mkdirs()
        for (front in listOf(false,true)) {
            val config=client(front)
            assertEquals("proxy-chain-entry",config.outbounds.first().streamSettings?.sockopt?.dialerProxy)
            File(directory,if(front) "client-three-hop.json" else "client-two-hop.json").writeText(Gson().toJson(config))
        }
    }
}
