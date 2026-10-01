package com.shilapi.xcertplay.transport
import java.io.IOException
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
}
