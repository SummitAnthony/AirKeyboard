package com.airkeyboard.network.encryption

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Encryption/Decryption utilities for the chunk frames
 * Implements XOR cipher with 8-byte cyclic key
 */
object EncryptionUtil {
    private const val PROTOCOL_VERSION = 3.0f
    private const val HEADER_BYTE = 0x23.toByte()
    private const val XOR_KEY_MASK = 0x35.toByte()
    private const val HEADER_SIZE = 38

    /**
     * Generate random key byte for encryption (1-255)
     */
    fun generateKeyByte(): Byte {
        return (Random.nextInt(1, 256)).toByte()
    }

    /**
     * Generate random frame key (8 bytes, each 1-255)
     */
    fun generateFrameKey(): ByteArray {
        return ByteArray(8) { Random.nextInt(1, 256).toByte() }
    }

    /**
     * Random filler int
     */
    fun randomFiller(): Int {
        return Random.nextInt()
    }

    /**
     * Encrypt payload using the chunk format (UDP and the TCP session)
     * Returns complete frame ready to send
     */
    fun encryptChunk(payload: ByteArray, chunkId: Int, chunkInterval: Float = 0f): ByteArray {
        val keyByte = generateKeyByte()
        val frameKey = generateFrameKey()
        val filler1 = randomFiller()
        val filler2 = randomFiller()
        val currentTime = System.currentTimeMillis() / 1000.0

        // Encrypt timestamp (Unix seconds as double) with key byte
        val timestamp = ByteArray(8)
        ByteBuffer.wrap(timestamp).order(ByteOrder.LITTLE_ENDIAN).putDouble(currentTime)
        for (i in timestamp.indices) {
            timestamp[i] = (timestamp[i].toInt() xor (keyByte.toInt() and 0xFF)).toByte()
        }

        // XOR frame key with key byte
        val encryptedFrameKey = frameKey.map { key ->
            (key.toInt() xor (keyByte.toInt() and 0xFF)).toByte()
        }.toByteArray()

        // XOR key byte with 0x35
        val finalKeyByte = (keyByte.toInt() xor XOR_KEY_MASK.toInt()).toByte()

        // XOR payload cyclically with original frame key
        val encryptedPayload = ByteArray(payload.size)
        for (i in payload.indices) {
            val keyIndex = i % frameKey.size
            encryptedPayload[i] = (payload[i].toInt() xor (frameKey[keyIndex].toInt() and 0xFF)).toByte()
        }

        // Assemble frame
        val frame = ByteBuffer.allocate(HEADER_SIZE + encryptedPayload.size)
            .order(ByteOrder.LITTLE_ENDIAN)

        frame.put(HEADER_BYTE)                           // 1 byte
        frame.putFloat(PROTOCOL_VERSION)                 // 4 bytes
        frame.putInt(chunkId)                            // 4 bytes
        frame.putFloat(chunkInterval)                    // 4 bytes
        frame.put(timestamp)                             // 8 bytes
        frame.putInt(filler1)                            // 4 bytes
        frame.putInt(filler2)                            // 4 bytes
        frame.put(finalKeyByte)                          // 1 byte
        frame.put(encryptedFrameKey)                     // 8 bytes
        frame.put(encryptedPayload)                      // N bytes

        return frame.array()
    }
}
