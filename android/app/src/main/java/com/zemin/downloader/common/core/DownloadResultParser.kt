package com.zemin.downloader.common.core

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.zemin.downloader.R
import com.zemin.downloader.appContext
import com.zemin.downloader.common.PyDownloadResult
import com.zemin.downloader.common.bean.PyDownloadResponse

object DownloadResultParser {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(PyDownloadResponse::class.java)

    fun parse(raw: String): PyDownloadResult {
        val response = adapter.fromJson(raw) ?: PyDownloadResponse()
        val error = response.error?.takeIf { it.isNotBlank() }

        return PyDownloadResult(
            ok = response.ok,
            message = response.message?.takeIf { it.isNotBlank() } ?: error ?: appContext.getString(
                R.string.main_task_complete
            ),
            error = error,
            outputDir = response.outputDir,
            files = response.files.filter { it.isNotBlank() },
            success = response.success,
            failed = response.failed,
            skipped = response.skipped,
            timings = response.timings,
            downloadMetrics = response.downloadMetrics,
            apiMetrics = response.apiMetrics,
        )
    }
}
