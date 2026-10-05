package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.util.Log
import androidx.annotation.RequiresApi
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.handler.VpnSessionDiagnostics
import com.v2ray.ang.util.MyContextWrapper
import com.v2ray.ang.util.Utils
import java.lang.ref.SoftReference

class V2RayVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    @Volatile private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val lifecycle = VpnLifecycle()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        // Android may revoke VPN authorization from a Binder thread.
        stopService(VpnStopReason.VPN_REVOKED)
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        stopV2Ray(VpnStopReason.SERVICE_DESTROYED, stopServiceInstance = false)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!lifecycle.beginStart()) {
            return if (lifecycle.phase == VpnLifecycle.Phase.CONNECTED) START_STICKY else START_NOT_STICKY
        }
        try {
            // Promote before config generation and native startup, including sticky restarts.
            NotificationManager.showNotification(null)
            VpnSessionDiagnostics.record("STARTING")
            if (V2RayServiceManager.startCoreLoop()) {
                startService()
                if (isRunning && lifecycle.connected()) {
                    VpnSessionDiagnostics.record("CONNECTED")
                    return START_STICKY
                }
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to start VPN session", e)
        }
        stopV2Ray(VpnStopReason.START_FAILED)
        return START_NOT_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        setupService()
    }

    override fun stopService(reason: VpnStopReason) {
        // Native shutdown callbacks may arrive on a worker thread.
        if (Looper.myLooper() == Looper.getMainLooper()) stopV2Ray(reason)
        else mainHandler.post { stopV2Ray(reason) }
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupService() {
        val prepare = prepare(this)
        if (prepare != null) {
            stopV2Ray(VpnStopReason.VPN_REVOKED)
            return
        }

        if (configureVpnService() != true) {
            return
        }

        runTun2socks()
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed
        try {
            mInterface.close()
        } catch (ignored: Exception) {
            // ignored
        }

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            isRunning = true
            return true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopV2Ray(VpnStopReason.START_FAILED)
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android P (API 28) and above: Configure network callbacks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to request default network", e)
            }
        }

        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(AppConfig.TAG, "Failed to configure app in VPN: ${e.localizedMessage}", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (!isRunning) return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, true) == true) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { restartTunnelIfRunning() }
            )
        } else {
            tun2SocksService = Tun2SocksService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { restartTunnelIfRunning() }
            )
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun restartTunnelIfRunning() {
        mainHandler.post {
            if (isRunning && lifecycle.phase == VpnLifecycle.Phase.CONNECTED) runTun2socks()
        }
    }

    /**
     * Stops the V2Ray service.
     * @param stopServiceInstance False only when Android is already destroying this instance.
     */
    private fun stopV2Ray(reason: VpnStopReason, stopServiceInstance: Boolean = true) {
        if (!lifecycle.beginStop(reason)) return
        isRunning = false
        VpnSessionDiagnostics.record(reason.name)
        Log.i(AppConfig.TAG, "VPN session stopped: ${reason.name}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (ignored: Exception) {
                // ignored
            }
        }

        try {
            tun2SocksService?.stopTun2Socks()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop tunnel", e)
        }
        tun2SocksService = null

        V2RayServiceManager.stopCoreLoop()

        if (stopServiceInstance) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()
        }
        try {
            if (::mInterface.isInitialized) mInterface.close()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to close VPN interface", e)
        }
        lifecycle.stopped()
    }
}
