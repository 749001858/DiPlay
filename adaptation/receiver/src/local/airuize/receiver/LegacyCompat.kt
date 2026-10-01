package local.airuize.receiver

import java.nio.charset.Charset

object LegacyCharsets {
    val UTF_8: Charset = Charset.forName("UTF-8")
    val US_ASCII: Charset = Charset.forName("US-ASCII")
}
object LegacyBase64 {
    fun getEncoder() = Encoder()
    class Encoder {
        fun encodeToString(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}
object LegacyNumbers {
    fun unsigned(value: Long): String = if (value >= 0) value.toString()
        else java.math.BigInteger.valueOf(value and Long.MAX_VALUE).setBit(63).toString()
    fun unsigned(value: Int): String = (value.toLong() and 0xffffffffL).toString()
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a xor b) < 0 && q * b != a) q - 1 else q
    }
    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}
object LegacyCloseables {
    fun close(value: Any?) {
        when (value) {
            is java.net.Socket -> value.close()
            is java.net.ServerSocket -> value.close()
            is java.net.DatagramSocket -> value.close()
            is java.io.Closeable -> value.close()
        }
    }
}
