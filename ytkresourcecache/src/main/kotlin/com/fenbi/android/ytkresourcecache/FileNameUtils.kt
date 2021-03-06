package com.fenbi.android.ytkresourcecache

import android.net.Uri

/**
 * @author zheng on 12/24/18
 */

object FileNameUtils {

    fun getFilePath(url: String): String {
        val uri = Uri.parse(url)
        return uri.host.orEmpty() + uri.path
    }

    fun getExtension(url: String): String? {
        val slash = url.lastIndexOf('/')
        if (slash < 0) {
            return null
        }
        val name = url.substring(slash)
        val dot = name.lastIndexOf('.')
        return if (dot < 0) {
            null
        } else name.substring(dot + 1)
    }
}
