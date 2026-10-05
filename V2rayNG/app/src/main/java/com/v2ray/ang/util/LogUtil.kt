package com.v2ray.ang.util

import android.util.Log
import com.v2ray.ang.AppConfig

/** Logs only an operation and non-secret identity; caller exception messages may contain URLs. */
object LogUtil {
    fun failure(operation: String, component: String, id: String, exception: Throwable) {
        Log.e(AppConfig.TAG, "operation=$operation component=$component id=$id exception=${exception.javaClass.simpleName}")
    }
}
