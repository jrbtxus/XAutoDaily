package me.teble.xposed.autodaily.utils

import me.teble.xposed.autodaily.hook.config.Config.xaConfig
import me.teble.xposed.autodaily.task.util.millisecond
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

object TimeUtil {

    private var timeDiff: Long = 0

    private val diff: Long
        get() = timeDiff

    private val cnZoneId by lazy { ZoneId.of("+8") }

    private val defaultZoneOffset by lazy { OffsetDateTime.now().offset }

    val offsetTime by lazy {
        (ZonedDateTime.now(cnZoneId).offset.totalSeconds - ZonedDateTime.now(defaultZoneOffset).offset.totalSeconds) * 1000
    }

    fun init() {
        val networkTime = getNetworkTime()
        LogUtil.d("networkTime: $networkTime")
        networkTime?.let {
            timeDiff = it - getCNTime()
            xaConfig.putLong("cnTimeDiff", timeDiff)
        } ?: run {
            timeDiff = xaConfig.getLong("cnTimeDiff", 0)
        }
    }

    /** NTP 服务器：优先腾讯，其次阿里云/公共池 */
    private val NTP_SERVERS = arrayOf(
        "time1.cloud.tencent.com",
        "time2.cloud.tencent.com",
        "time3.cloud.tencent.com",
        "time4.cloud.tencent.com",
        "time5.cloud.tencent.com",
        "ntp.tencent.com",
        "ntp1.tencent.com",
        "ntp2.tencent.com",
        "ntp3.tencent.com",
        "ntp4.tencent.com",
        "ntp5.tencent.com",
        "ntp.aliyun.com",
        "ntp1.aliyun.com",
        "cn.pool.ntp.org",
        "pool.ntp.org",
    )

    /** NTP 纪元(1900-01-01)与 Unix 纪元(1970-01-01)的秒数差 */
    private const val NTP_UNIX_OFFSET = 2208988800L

    /** 依次尝试 NTP 服务器（腾讯优先），返回校正后的当前时间（epoch millis），全部失败返回 null */
    private fun getNetworkTime(): Long? {
        for (host in NTP_SERVERS) {
            val time = getNtpTime(host)
            if (time != null) {
                LogUtil.d("NTP 校时成功 -> $host")
                return time
            }
        }
        return null
    }

    /** 通过 SNTP 从指定服务器获取校正后的当前时间（epoch millis），失败返回 null */
    private fun getNtpTime(host: String): Long? {
        return try {
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                socket.soTimeout = 3000
                // 48 字节 NTP 请求：LI=0, VN=3(版本3), Mode=3(客户端)
                val request = ByteArray(48)
                request[0] = 0x1B

                val t0 = System.currentTimeMillis()
                socket.send(DatagramPacket(request, request.size, address, 123))

                val response = ByteArray(48)
                socket.receive(DatagramPacket(response, response.size))
                val t3 = System.currentTimeMillis()

                // t1 = 服务器接收时间（字节 32），t2 = 服务器发送时间（字节 40）
                val t1 = readNtpTimestamp(response, 32)
                val t2 = readNtpTimestamp(response, 40)

                // 时钟偏移 = ((t1 - t0) + (t2 - t3)) / 2
                val offset = ((t1 - t0) + (t2 - t3)) / 2
                // 校正后的当前时间
                t3 + offset
            }
        } catch (e: Exception) {
            LogUtil.d("NTP 校时失败 -> $host: ${e.message}")
            null
        }
    }

    /** 读取 NTP 包中 8 字节时间戳（秒.分数），返回 epoch millis */
    private fun readNtpTimestamp(buffer: ByteArray, offset: Int): Long {
        val seconds = ((buffer[offset].toLong() and 0xFF) shl 24) or
                ((buffer[offset + 1].toLong() and 0xFF) shl 16) or
                ((buffer[offset + 2].toLong() and 0xFF) shl 8) or
                (buffer[offset + 3].toLong() and 0xFF)
        val fraction = ((buffer[offset + 4].toLong() and 0xFF) shl 24) or
                ((buffer[offset + 5].toLong() and 0xFF) shl 16) or
                ((buffer[offset + 6].toLong() and 0xFF) shl 8) or
                (buffer[offset + 7].toLong() and 0xFF)
        return (seconds - NTP_UNIX_OFFSET) * 1000 + fraction * 1000 / 0x100000000L
    }

    private fun getCNTime(): Long {
        val time: LocalDateTime = LocalDateTime.now(cnZoneId)
        return time.millisecond
    }

    private fun getLocalTime(): Long {
        val time: LocalDateTime = LocalDateTime.now()
        return time.toInstant(defaultZoneOffset).toEpochMilli()
    }

    fun cnTimeMillis(): Long {
        return getCNTime() + diff
    }

    fun localTimeMillis(): Long {
        return getLocalTime() + diff
    }

    fun getCNDate(): Date {
        return Date(localTimeMillis() + offsetTime)
    }
}