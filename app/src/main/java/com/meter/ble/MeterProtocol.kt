package com.meter.ble

/**
 * DL/T 645 协议处理：命令构造 + 响应解析。
 * 字段偏移已用真实数据+小程序显示值标定：
 *   剩余电量(度) = payload offset 4, u32小端 / 100
 *   总用量(度)   = payload offset 0, u32小端 / 100
 */
object MeterProtocol {

    fun normalizeAddress(address: String): String =
        address.replace(":", "").replace("-", "").uppercase()

    fun reverseAddressHex(address: String): String {
        val normalized = normalizeAddress(address)
        require(normalized.length == 12) { "invalid BLE address: $address" }
        val parts = (0 until 12 step 2).map { normalized.substring(it, it + 2) }
        return parts.reversed().joinToString("")
    }

    /** 构造读命令(固定查询电量指令)：68 [地址倒序] 68 1104 [dataId] 校验 16 */
    fun buildCommand(address: String, dataId: String = "32373322"): String {
        // 真实查询指令的数据标识(加扰)是 32373322；先用地址拼帧再算校验
        val body = "68${reverseAddressHex(address)}681104$dataId"
        val bytes = hexToBytes(body)
        var cs = 0
        for (b in bytes) cs = (cs + (b.toInt() and 0xFF)) and 0xFF
        return (body + "%02X".format(cs) + "16").uppercase()
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(data: ByteArray): String =
        data.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    data class ParseResult(
        val address: String,
        val leftKwh: Double?,    // 剩余电量(度)
        val totalKwh: Double?,   // 总用量(度)
        val dataId: String,
        val payloadHex: String
    )

    fun parseResponse(rawHex: String): ParseResult {
        val frame = hexToBytes(rawHex)
        require(frame.size >= 12) { "frame too short" }
        fun u(i: Int) = frame[i].toInt() and 0xFF
        require(u(0) == 0x68 && u(7) == 0x68 && u(frame.size - 1) == 0x16) {
            "invalid frame markers"
        }
        var sum = 0
        for (i in 0 until frame.size - 2) sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        require(sum == u(frame.size - 2)) {
            "checksum mismatch: frame=%02X calc=%02X".format(u(frame.size - 2), sum)
        }

        val dataLen = u(9)
        val dataEnd = minOf(10 + dataLen, frame.size)
        val decoded = ByteArray(dataEnd - 10) {
            ((frame[10 + it].toInt() and 0xFF) - 0x33 and 0xFF).toByte()
        }
        val dataId = if (decoded.size >= 4)
            decoded.copyOfRange(0, 4).reversedArray()
                .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        else ""
        val payload = if (decoded.size > 4) decoded.copyOfRange(4, decoded.size) else ByteArray(0)

        fun leU32(offset: Int): Long? {
            if (offset + 4 > payload.size) return null
            var v = 0L
            for (k in 0 until 4) v = v or ((payload[offset + k].toLong() and 0xFF) shl (8 * k))
            return v
        }

        val total = leU32(0)?.let { it / 100.0 }
        val left = leU32(4)?.let { it / 100.0 }
        val addr = frame.copyOfRange(1, 7).reversedArray()
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        return ParseResult(
            address = addr,
            leftKwh = left,
            totalKwh = total,
            dataId = dataId,
            payloadHex = bytesToHex(payload)
        )
    }
}
