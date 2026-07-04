package com.hrmonitor.heartmonitor

/**
 * Parses the BLE Heart Rate Measurement characteristic (UUID 0x2A37)
 * per the Bluetooth SIG Heart Rate Profile specification.
 *
 * Byte layout:
 *   [0]     Flags
 *   [1]     Heart Rate Value (UINT8 if flags bit 0 = 0, UINT16 if = 1)
 *   [2]     (only present if UINT16)
 *   ...     optional: Energy Expended (2 bytes), RR-Intervals (2 bytes each)
 */
object HeartRateParser {

    fun parse(data: ByteArray): Int {
        if (data.isEmpty()) return 0

        val flags = data[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0

        return if (is16Bit && data.size >= 3) {
            // UINT16 little-endian
            (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
        } else if (data.size >= 2) {
            // UINT8
            data[1].toInt() and 0xFF
        } else {
            0
        }
    }
}
