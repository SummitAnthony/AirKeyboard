package com.airkeyboard.network.discovery

import android.util.Log
import com.airkeyboard.network.ChunkBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds AirKeyboard PCs on the LAN: broadcasts "Hello" to UDP 2008 every 300ms
 * and collects "BC" + 3-digit length + name replies.
 */
object ServerDiscovery {
    private const val TAG = "ServerDiscovery"
    private const val DISCOVERY_PORT = 2008
    private const val BROADCAST_INTERVAL_MS = 300L
    private val HELLO = "Hello".toByteArray(Charsets.US_ASCII)

    suspend fun discover(timeoutMs: Long = 3000): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, DiscoveredServer>()
        val targets = broadcastAddresses()
        Log.d(TAG, "Broadcasting to $targets")

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = BROADCAST_INTERVAL_MS.toInt()
            val buffer = ByteArray(1024)
            val end = System.currentTimeMillis() + timeoutMs

            while (System.currentTimeMillis() < end) {
                for (target in targets) {
                    try {
                        socket.send(DatagramPacket(HELLO, HELLO.size, target, DISCOVERY_PORT))
                    } catch (e: Exception) {
                        Log.w(TAG, "Broadcast to $target failed: ${e.message}")
                    }
                }

                // Drain replies until the next broadcast is due
                val nextBroadcast = System.currentTimeMillis() + BROADCAST_INTERVAL_MS
                while (System.currentTimeMillis() < nextBroadcast) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val host = packet.address.hostAddress ?: continue
                        val parsed = ChunkBuilder.parseDiscoveryResponse(packet.data.copyOf(packet.length))
                        if (parsed != null && host !in found) {
                            found[host] = DiscoveredServer(parsed, host)
                            Log.d(TAG, "Found $parsed at $host")
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
            }
        }
        found.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                nif.interfaceAddresses.mapNotNullTo(addresses) { it.broadcast }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate interfaces: ${e.message}")
        }
        addresses.add(InetAddress.getByName("255.255.255.255"))
        return addresses.distinct()
    }
}

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int = 1978
)
