package com.codex.tvphone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** Finds the TV on the local network (the TV app announces itself as _tvvoice._tcp). */
object TvDiscovery {
    /** Calls [onResult] on the main thread with the TV's IP address, or null if not found within 8 seconds. */
    fun find(context: Context, onResult: (String?) -> Unit) {
        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val main = Handler(Looper.getMainLooper())
        var finished = false
        lateinit var discovery: NsdManager.DiscoveryListener

        fun finish(ip: String?) {
            if (finished) return
            finished = true
            try { nsd.stopServiceDiscovery(discovery) } catch (_: Exception) { }
            main.post { onResult(ip) }
        }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) { }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { finish(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { }
            override fun onDiscoveryStopped(serviceType: String) { }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) { }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("tvvoice")) return
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) { finish(serviceInfo.host?.hostAddress) }
                })
            }
        }
        try {
            nsd.discoverServices("_tvvoice._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (_: Exception) {
            finish(null)
            return
        }
        main.postDelayed({ finish(null) }, 8000)
    }
}
