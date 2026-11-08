package app.atlante.library.crosspoint

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import java.net.DatagramSocket
import java.net.Socket

/**
 * The Wi-Fi the reader is on: its own "CrossPoint-Reader" (open, no internet) or the home Wi-Fi. Android prefers
 * mobile data on a Wi-Fi without internet, so every CrossPoint socket is bound to the Wi-Fi network.
 */
object CrossPointWifi {
    /** The reader's Wi-Fi asked for by the app (Android 10+): kept while the page uses it. */
    @Volatile private var requested: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private fun manager(c: Context) = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The Wi-Fi network to talk to the reader on: the one asked for, else the phone's current Wi-Fi. */
    @JvmStatic fun network(c: Context): Network? = requested ?: manager(c).allNetworks.firstOrNull { n ->
        manager(c).getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    @JvmStatic fun binder(c: Context): CrossPointClient.Binder? = network(c)?.let { n -> object : CrossPointClient.Binder {
        override fun bind(socket: Socket) = n.bindSocket(socket)
        override fun bind(socket: DatagramSocket) = n.bindSocket(socket)
    } }

    /**
     * Joins "CrossPoint-Reader". Android 10+: the system asks you to confirm, then [onResult] says whether it worked.
     * Older Android: opens the Wi-Fi settings, where you choose it yourself ([onResult] false: nothing to wait for).
     */
    @JvmStatic fun join(c: Context, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) { c.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); onResult(false); return }
        leave(c)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(CrossPointClient.READER_WIFI).build()).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { requested = network; onResult(true) }
            override fun onUnavailable() { requested = null; onResult(false) }
            override fun onLost(network: Network) { if (requested == network) requested = null }
        }
        callback = cb
        manager(c).requestNetwork(request, cb, 60_000)
    }

    /** Lets the reader's Wi-Fi go (the phone returns to its usual networks). */
    @JvmStatic fun leave(c: Context) {
        callback?.let { runCatching { manager(c).unregisterNetworkCallback(it) } }
        callback = null; requested = null
    }
}
