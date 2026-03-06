package com.electro.dassify_application.helpers;  // ← Corregido (antes: package com.electro.dassify_application;)

import android.util.Log;
import androidx.annotation.NonNull;
import com.electro.dassify_application.services.AccelerometerManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * AccelPacket - Binary packet parser for ESP32 accelerometer data.
 *
 * <p><b>Packet Structure (54 bytes):</b></p>
 * <pre>
 * Field                Type        Bytes   Offset
 * ──────────────────────────────────────────────────
 * timestamp_start      uint64_t    8       0
 * timestamp_end        uint64_t    8       8
 * rms_x                float       4       16
 * rms_y                float       4       20
 * rms_z                float       4       24
 * rms_mag              float       4       28
 * max_x                float       4       32
 * max_y                float       4       36
 * max_z                float       4       40
 * max_mag              float       4       44
 * peak_count           uint16_t    2       48
 * sample_count         uint16_t    2       50
 * flags                uint8_t     1       52
 * checksum             uint8_t     1       53
 * ──────────────────────────────────────────────────
 * TOTAL                            54 bytes
 * </pre>
 *
 * <p><b>Endianness:</b> Little-endian (ESP32 default)</p>
 */
public class AccelPacket {

    private static final String TAG = "AccelPacket";
    private static final int PACKET_SIZE = 54;

    // ═══════════════════════════════════════════════════════
    // Packet Fields
    // ═══════════════════════════════════════════════════════

    private long timestampStart;
    private long timestampEnd;

    private float rmsX, rmsY, rmsZ, rmsMagnitude;
    private float maxX, maxY, maxZ, maxMagnitude;

    private int peakCount;
    private int sampleCount;

    private int flags;
    private int checksum;

    private boolean valid = false;

    // ═══════════════════════════════════════════════════════
    // Parsing
    // ═══════════════════════════════════════════════════════

    /**
     * Parse binary packet from ESP32.
     *
     * @param data Raw bytes (must be exactly 54 bytes)
     * @return Parsed AccelPacket, or null if invalid
     */
    public static AccelPacket fromBytes(@NonNull byte[] data) {
        if (data.length != PACKET_SIZE) {
            Log.e(TAG, String.format("Invalid packet size: %d (expected %d)",
                    data.length, PACKET_SIZE));
            return null;
        }

        try {
            AccelPacket packet = new AccelPacket();
            ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

            // Parse fields
            packet.timestampStart = buffer.getLong();
            packet.timestampEnd = buffer.getLong();

            packet.rmsX = buffer.getFloat();
            packet.rmsY = buffer.getFloat();
            packet.rmsZ = buffer.getFloat();
            packet.rmsMagnitude = buffer.getFloat();

            packet.maxX = buffer.getFloat();
            packet.maxY = buffer.getFloat();
            packet.maxZ = buffer.getFloat();
            packet.maxMagnitude = buffer.getFloat();

            packet.peakCount = buffer.getShort() & 0xFFFF;  // unsigned
            packet.sampleCount = buffer.getShort() & 0xFFFF;  // unsigned

            packet.flags = buffer.get() & 0xFF;  // unsigned
            packet.checksum = buffer.get() & 0xFF;  // unsigned

            // Validate checksum
            packet.valid = packet.validateChecksum(data);

            if (!packet.valid) {
                Log.w(TAG, "Checksum validation failed");
            }

            return packet;

        } catch (Exception e) {
            Log.e(TAG, "Error parsing packet", e);
            return null;
        }
    }

    /**
     * Validate XOR checksum.
     *
     * @param data Raw packet bytes
     * @return true if checksum is valid
     */
    private boolean validateChecksum(byte[] data) {
        int computed = 0;
        for (int i = 0; i < PACKET_SIZE - 1; i++) {
            computed ^= (data[i] & 0xFF);
        }
        return computed == checksum;
    }

    // ═══════════════════════════════════════════════════════
    // Conversion to AccelerometerManager.AccelStatistics
    // ═══════════════════════════════════════════════════════

    /**
     * Convert AccelPacket to AccelerometerManager.AccelStatistics.
     * This allows ESP32 data to be used seamlessly with existing code.
     *
     * @return AccelStatistics object compatible with AccelerometerManager
     */
    public AccelerometerManager.AccelStatistics toAccelStatistics() {
        // AccelStatistics uses a constructor with all fields (they are final)
        return new AccelerometerManager.AccelStatistics(
                timestampStart,
                timestampEnd,
                rmsX,
                rmsY,
                rmsZ,
                rmsMagnitude,
                maxX,
                maxY,
                maxZ,
                maxMagnitude,
                peakCount,
                sampleCount
        );
    }
    // ═══════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════

    public boolean isValid() { return valid; }
    public long getTimestampStart() { return timestampStart; }
    public long getTimestampEnd() { return timestampEnd; }
    public float getRmsX() { return rmsX; }
    public float getRmsY() { return rmsY; }
    public float getRmsZ() { return rmsZ; }
    public float getRmsMagnitude() { return rmsMagnitude; }
    public float getMaxX() { return maxX; }
    public float getMaxY() { return maxY; }
    public float getMaxZ() { return maxZ; }
    public float getMaxMagnitude() { return maxMagnitude; }
    public int getPeakCount() { return peakCount; }
    public int getSampleCount() { return sampleCount; }
    public int getFlags() { return flags; }

    /**
     * Get duration of measurement window in milliseconds.
     */
    public long getDurationMs() {
        return timestampEnd - timestampStart;
    }

    /**
     * Check if window has timing error (expected ~5000ms).
     */
    public boolean hasTimingError() {
        long duration = getDurationMs();
        return Math.abs(duration - 5000) > 500;  // ±500ms tolerance
    }

    @NonNull
    @Override
    public String toString() {
        return String.format(java.util.Locale.US,
                "AccelPacket{valid=%b, duration=%dms, rms_mag=%.4f, max_mag=%.4f, peaks=%d, samples=%d, flags=0x%02X}",
                valid, getDurationMs(), rmsMagnitude, maxMagnitude, peakCount, sampleCount, flags
        );
    }
}