/*
 * Copyright 2017 fenbi.com. All rights reserved.
 * FENBI.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.fenbi.android.ytkresourcecache.downloader

import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.fenbi.android.ytkresourcecache.FileCacheStorage
import com.fenbi.android.ytkresourcecache.asResourceOutputStream
import okhttp3.*
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CancellationException


open class ResourceDownloader(private val cacheStorage: FileCacheStorage) {

    protected var url: String? = null
    private var initialSize: Long = 0
    private var interrupted: Boolean = false
    private var totalFileSize = INVALID_FILE_SIZE
    private var outputStream: OutputStream? = null

    var onDownloadedBytes: ((Long) -> Unit)? = null
    var onProgress: ((loaded: Long, total: Long) -> Unit)? = null
    var onSuccess: ((Long) -> Unit)? = null
    var onFailed: ((url: String?, errorType: ErrorType) -> Unit)? = null
    var okHttpClient = defaultOkHttpClient

    fun getFileSize(url: String, callback: FileSizeCallback) {
        if (totalFileSize != INVALID_FILE_SIZE) {
            callback.onFileSizeGot(totalFileSize)
        }
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-5")
            .build()
        val call = okHttpClient.newCall(request)
        call.enqueue(object : Callback {

            override fun onFailure(call: Call?, e: IOException?) {
                callback.onFileSizeGot(INVALID_FILE_SIZE)
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call?, response: Response?) {
                totalFileSize = parseInstanceLength(response)
                callback.onFileSizeGot(totalFileSize)
            }
        })
    }

    fun download(url: String) {
        val call = buildDownloadCall(url) ?: return
        try {
            val response = call.execute()
            if (!processResponse(response)) {
                if (interrupted) {
                    throw CancellationException()
                } else {
                    throw Exception()
                }
            }
        } catch (e: Throwable) {
            throw e
        }
    }

    private fun buildDownloadCall(url: String): Call? {
        this.url = url
        interrupted = false
        outputStream = cacheStorage.cacheWriter.getStream(url)
        initialSize = outputStream?.asResourceOutputStream()?.length() ?: 0L
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$initialSize-")
            .build()
        return okHttpClient.newCall(request)
    }

    private fun processResponse(response: Response): Boolean {
        response.use {
            if (interrupted) {
                onFailed?.invoke(url, ErrorType.TaskCancelled)
                return false
            }
            if (outputStream == null) {
                onFailed?.invoke(url, ErrorType.FileVerifyError)
                return false
            }
            if (!response.isSuccessful) {
                // delete temp file to restart from beginning at the next time.
                outputStream?.asResourceOutputStream()?.onCacheFailed()
                onFailed?.invoke(url, ErrorType.NetworkError)
                return false
            }
            if (!checkSpace()) {
                onFailed?.invoke(url, ErrorType.FullDiskError)
                return false
            }
            val totalLength = parseInstanceLength(response)
            val body = response.body() ?: return false
            val inputStream = body.byteStream()
            try {
                var timestamp = System.currentTimeMillis()
                var savedSize = initialSize
                val buffer = ByteArray(4096)
                var len: Int
                while (true) {
                    len = inputStream.read(buffer)
                    if (len == -1) {
                        break
                    }
                    onDownloadedBytes?.invoke(len.toLong())
                    outputStream?.write(buffer, 0, len)
                    savedSize += len.toLong()
                    if (System.currentTimeMillis() - timestamp > 300L) {
                        timestamp = System.currentTimeMillis()
                        onProgress?.invoke(savedSize, totalLength)
                    }
                    if (interrupted) {
                        onFailed?.invoke(url, ErrorType.TaskCancelled)
                        return false
                    }
                }
                if (savedSize != totalLength) {
                    Log.e(TAG, "file size not match, header $totalLength, download $savedSize")
                    onFailed?.invoke(url, ErrorType.FileVerifyError)
                    return false
                }
                outputStream?.flush()
                outputStream?.asResourceOutputStream()?.onCacheSuccess()
                onProgress?.invoke(savedSize, totalLength)
                onSuccess?.invoke(totalLength)
                return true
            } catch (e: IOException) {
                outputStream?.asResourceOutputStream()?.onCacheFailed()
                onFailed?.invoke(url, ErrorType.NetworkError)
                throw e
            } finally {
                inputStream.close()
                outputStream?.close()
            }
        }
    }

    fun pause() {
        interrupted = true
    }

    interface FileSizeCallback {

        fun onFileSizeGot(fileSize: Long)
    }

    enum class ErrorType {
        NetworkError,
        TaskCancelled,
        FullDiskError,
        FileVerifyError
    }

    companion object {

        private const val INVALID_FILE_SIZE = -1L

        const val TAG = "ResourceDownloader"

        private val defaultOkHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .build()
        }

        private fun parseInstanceLength(response: Response?): Long {
            var length = INVALID_FILE_SIZE
            if (response != null) {
                val range = response.header("Content-Range") ?: return -1L
                try {
                    val section = range.split("/").dropLastWhile { it.isEmpty() }.toTypedArray()
                    length = section[1].toLong()
                } catch (e: NumberFormatException) {
                    e.printStackTrace()
                }

            }
            return length
        }

        fun checkSpace(): Boolean {
            val statFs = StatFs(Environment.getRootDirectory().absolutePath)
            return statFs.availableBlocks * statFs.blockSize >= 20 * 1024 * 1024
        }

    }
}
