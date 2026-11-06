package dev.droplet.app

import dev.droplet.app.iphonelink.Callbacks
import dev.droplet.app.iphonelink.Conn
import dev.droplet.app.iphonelink.Iphonelink
import dev.droplet.app.iphonelink.Receiver
import dev.droplet.app.iphonelink.Signer
import dev.droplet.app.mesh.Iphone
import dev.droplet.app.mesh.IphoneBridge
import dev.droplet.app.mesh.IphoneChannel
import dev.droplet.app.mesh.MeshNode
import java.security.Signature

/**
 * The iPhone link's UDP port (docs/iphone.md §1): the Go library in
 * android/iphonelink (Pion), with the mesh certificate. Its key stays in the
 * Android Keystore: each DTLS handshake's digest is signed here.
 */
object IphoneTransport {
    /** Starts listening for iPhones. Returns what stops it; throws (with why) when it can't start. */
    fun start(node: MeshNode, bridge: IphoneBridge, log: (String) -> Unit): () -> Unit {
        val key = node.identity.key
        val signer = object : Signer {
            override fun signDigest(digest: ByteArray): ByteArray = Signature.getInstance("NONEwithECDSA").run {
                initSign(key)
                update(digest)
                sign()
            }
        }
        val callbacks = object : Callbacks {
            override fun log(msg: String) = log(msg)
            override fun opened(c: Conn): Receiver {
                val conn = bridge.opened(GoChannel(c))
                return object : Receiver {
                    override fun onText(text: String) = conn.text(text)
                    override fun onBinary(data: ByteArray) = conn.binary(data)
                    override fun onClose() = conn.close()
                }
            }
        }
        val server = Iphonelink.listen(Iphone.DEFAULT_PORT.toLong(), 11, node.identity.der, signer, callbacks)
        bridge.port = server.port().toInt()
        log("iphone: listening for iPhones on UDP ${bridge.port}")
        return { server.close() }
    }

    private class GoChannel(private val c: Conn) : IphoneChannel {
        override val address: String = c.address()
        override val dtlsFp: String = c.remoteFingerprint()
        override fun sendText(text: String) = runCatching { c.sendText(text) }.isSuccess
        override fun sendBinary(data: ByteArray) = runCatching { c.sendBinary(data) }.isSuccess
        override fun buffered(): Long = c.buffered()
        override fun close() = c.close()
    }
}
