package io.ktor.network.tls.cipher

import io.ktor.http.cio.internals.*
import kotlinx.io.core.*
import kotlinx.io.pool.*
import java.nio.*
import javax.crypto.*

internal val CryptoBufferPool: ObjectPool<ByteBuffer> = object : DefaultPool<ByteBuffer>(128) {
    override fun produceInstance(): ByteBuffer = ByteBuffer.allocate(65536)
    override fun clearInstance(instance: ByteBuffer): ByteBuffer = instance.apply { clear() }
}

internal fun ByteReadPacket.encrypted(cipher: Cipher, recordIv: Long?): ByteReadPacket {
    return cipherLoop(cipher, recordIv)
}

internal fun ByteReadPacket.decrypted(cipher: Cipher): ByteReadPacket {
    return cipherLoop(cipher)
}

internal fun ByteReadPacket.cipherLoop(cipher: Cipher, recordIv: Long? = null): ByteReadPacket {
    val srcBuffer = DefaultByteBufferPool.borrow()
    var dstBuffer = CryptoBufferPool.borrow()
    var dstBufferFromPool = true

    try {
        return buildPacket {
            srcBuffer.clear()
            writeFully(cipher.iv)
//            recordIv?.let { writeLong(it) }

            while (true) {
                val rc = if (srcBuffer.hasRemaining()) readAvailable(srcBuffer) else 0
                srcBuffer.flip()

                if (!srcBuffer.hasRemaining() && (rc == -1 || this@cipherLoop.isEmpty)) break

                dstBuffer.clear()

                if (cipher.getOutputSize(srcBuffer.remaining()) > dstBuffer.remaining()) {
                    if (dstBufferFromPool) {
                        CryptoBufferPool.recycle(dstBuffer)
                    }
                    dstBuffer = ByteBuffer.allocate(cipher.getOutputSize(srcBuffer.remaining()))
                    dstBufferFromPool = false
                }

                cipher.update(srcBuffer, dstBuffer)
                dstBuffer.flip()
                writeFully(dstBuffer)
                srcBuffer.compact()
            }

            assert(!srcBuffer.hasRemaining()) { "Cipher loop completed too early: there are unprocessed bytes" }
            assert(!dstBuffer.hasRemaining()) { "Not all bytes were appended to the packet" }

            val requiredBufferSize = cipher.getOutputSize(0)
            if (requiredBufferSize == 0) return@buildPacket
            if (requiredBufferSize > dstBuffer.capacity()) {
                writeFully(cipher.doFinal())
                return@buildPacket
            }

            dstBuffer.clear()
            cipher.doFinal(EmptyByteBuffer, dstBuffer)
            dstBuffer.flip()

            if (!dstBuffer.hasRemaining()) { // workaround JDK bug
                writeFully(cipher.doFinal())
                return@buildPacket
            }

            writeFully(dstBuffer)
        }
    } finally {
        DefaultByteBufferPool.recycle(srcBuffer)
        if (dstBufferFromPool) {
            CryptoBufferPool.recycle(dstBuffer)
        }
    }
}
