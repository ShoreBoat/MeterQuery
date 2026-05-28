package com.meter.ble

/**
 * DL/T 645 协议处理：命令构造 + 响应解析。
 * 逐行对照已验证的 Python 版本翻译，逻辑保持一致。
 */
object MeterProtocol {

    fun normalizeAddress(address: String): String =
        address.replace(":", "").replace("-", "").uppercase()

    /** 地址按字节倒序，例如 A2:50:72:78:25:51 -> 51257872 50A2 */
    fun reverseAddressHex(address: String): String {
        val normalized = normalizeAddress(address)
        require(normalized.length == 12) { "invalid BLE address: $address" }
        val parts = (0 until 12 step 2).map { normalized.substring(it, it + 2) }
        return parts.reversed().joinToString("")
    }

    /** 构造读命令：68 [地址倒序] 68 1104 [dataId] 55 16 */
    fun buildCommand(address: String, dataId: String = "33333333"): String =
        "68${reverseAddressHex(address)}681104${dataId}5516".uppercase()

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
        val control: String,
        val dataLen: Int,
        val dataId: String,
        val decodedHex: String,
        val payloadHex: String,
        val usedMoney: Double?,
        val leftMoney: Double?,
        val buyMoney: Double?,
        val currentA: Double?,
        val rawChunks: List<Long>,
        // 剩余电量：等确认偏移/格式后填这个字段。先留多种候选给你在界面上比对。
        val leftKwhCandidates: List<Double>
    )

    /**
     * 解析响应帧。等价于 Python 的 parse_meter_response。
     * @throws IllegalArgumentException 帧非法或校验失败
     */
    fun parseResponse(rawHex: String): ParseResult {
        val frame = hexToBytes(rawHex)
        require(frame.size >= 12) { "frame too short" }

        fun u(i: Int) = frame[i].toInt() and 0xFF

        require(u(0) == 0x68 && u(7) == 0x68 && u(frame.size - 1) == 0x16) {
            "invalid frame markers"
        }

        var sum = 0
        for (i in 0 until frame.size - 2) sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        val frameChecksum = u(frame.size - 2)
        require(sum == frameChecksum) {
            "checksum mismatch: frame=%02X calc=%02X".format(frameChecksum, sum)
        }

        val dataLen = u(9)
        val dataEnd = minOf(10 + dataLen, frame.size)
        val data = frame.copyOfRange(10, dataEnd)
        // 解扰：每字节 -0x33
        val decoded = ByteArray(data.size) { ((data[it].toInt() and 0xFF) - 0x33 and 0xFF).toByte() }

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

        val usedCents = leU32(0)
        val leftCents = leU32(4)
        val currentMa = leU32(44)

        val chunks = mutableListOf<Long>()
        var i = 0
        while (i + 4 <= payload.size) {
            leU32(i)?.let { chunks.add(it) }
            i += 4
        }

        // 剩余电量候选：不同电表格式不同，先给几种常见解读，界面上你对照真值选对的那个
        val leftKwhCandidates = mutableListOf<Double>()
        leftCents?.let {
            leftKwhCandidates.add(it / 100.0)   // ×100 定点
            leftKwhCandidates.add(it / 1000.0)  // ×1000
        }

        val addr = frame.copyOfRange(1, 7).reversedArray()
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        return ParseResult(
            address = addr,
            control = "%02X".format(u(8)),
            dataLen = dataLen,
            dataId = dataId,
            decodedHex = bytesToHex(decoded),
            payloadHex = bytesToHex(payload),
            usedMoney = usedCents?.let { Math.round(it / 100.0 * 100) / 100.0 },
            leftMoney = leftCents?.let { Math.round(it / 100.0 * 100) / 100.0 },
            buyMoney = if (usedCents != null && leftCents != null)
                Math.round((usedCents + leftCents) / 100.0 * 100) / 100.0 else null,
            currentA = currentMa?.let { Math.round(it / 1000.0 * 1000) / 1000.0 },
            rawChunks = chunks,
            leftKwhCandidates = leftKwhCandidates
        )
    }
}
