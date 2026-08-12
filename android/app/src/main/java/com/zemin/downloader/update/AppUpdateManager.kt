package com.zemin.downloader.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.zemin.downloader.R
import com.zemin.downloader.common.util.formatBytes
import com.zemin.downloader.common.util.toast
import com.zemin.downloader.databinding.DialogAppUpdateBinding
import com.zemin.downloader.ui.view.DyActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class AppUpdateManager(
    private val activity: AppCompatActivity,
) : DefaultLifecycleObserver {
    private val checker = AppUpdateChecker(activity.applicationContext)
    private val downloader = ApkDownloader(activity.applicationContext)
    private val verifier = ApkVerifier(activity.applicationContext)
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, 0)
    private val installPermissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        finishInstallPermissionRequest()
    }

    private var checkRequested = false
    private var checkedOnStart = false
    private var updateDialog: AlertDialog? = null
    private var dialogBinding: DialogAppUpdateBinding? = null
    private var downloadJob: Job? = null
    private var pendingInstallFile: File? = null
    private var installPermissionDialog: AlertDialog? = null
    private var awaitingInstallPermission = false

    init {
        activity.lifecycle.addObserver(this)
    }

    fun checkOnStart() {
        checkRequested = true
    }

    override fun onStart(owner: LifecycleOwner) {
        if (!checkRequested || checkedOnStart) return
        checkedOnStart = true
        activity.lifecycleScope.launch {
            runCatching { checker.check() }
                .onFailure { Log.w(TAG, "App update check failed", it) }
                .getOrNull()
                ?.takeUnless(::isIgnored)
                ?.let(::showUpdateDialog)
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (awaitingInstallPermission) {
            finishInstallPermissionRequest()
        }
    }

    private fun isIgnored(info: AppUpdateInfo): Boolean {
        if (info.isRequiredFor(currentVersionCode())) return false
        return preferences.getLong(KEY_IGNORED_VERSION, 0L) == info.versionCode
    }

    private fun showUpdateDialog(info: AppUpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            updateDialog?.isShowing == true
        ) {
            return
        }
        val required = info.isRequiredFor(currentVersionCode())
        val binding = DialogAppUpdateBinding.inflate(activity.layoutInflater).apply {
            tvUpdateVersion.text = activity.getString(
                R.string.update_version_format,
                info.versionName,
                info.versionCode,
            )
            tvUpdateChangelog.text = info.changelog.ifBlank {
                activity.getString(R.string.update_changelog_empty)
            }
            tvUpdateRequired.visibility = if (required) View.VISIBLE else View.GONE
            btnUpdateLater.visibility = if (required) View.GONE else View.VISIBLE
            btnUpdateNow.setStyle(DyActionButton.Style.PRIMARY)
            btnUpdateLater.setStyle(DyActionButton.Style.GHOST)
            btnUpdateLater.setOnClickListener {
                preferences.edit().putLong(KEY_IGNORED_VERSION, info.versionCode).apply()
                updateDialog?.dismiss()
            }
            btnUpdateNow.setOnClickListener { startDownload(info) }
        }
        dialogBinding = binding
        updateDialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .setCancelable(!required)
            .create()
            .also { dialog ->
                dialog.setCanceledOnTouchOutside(!required)
                dialog.setOnDismissListener {
                    downloadJob?.cancel()
                    downloadJob = null
                    dialogBinding = null
                    updateDialog = null
                }
                dialog.show()
                dialog.window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    val width = (activity.resources.displayMetrics.widthPixels - dp(32))
                        .coerceAtMost(dp(360))
                    setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
    }

    private fun startDownload(info: AppUpdateInfo) {
        if (downloadJob?.isActive == true) return
        val binding = dialogBinding ?: return
        binding.progressUpdate.visibility = View.VISIBLE
        binding.tvUpdateStatus.visibility = View.VISIBLE
        binding.tvUpdateStatus.setText(R.string.update_status_downloading)
        binding.btnUpdateNow.isEnabled = false
        binding.btnUpdateLater.isEnabled = false
        downloadJob = activity.lifecycleScope.launch {
            runCatching {
                val apkFile = downloader.download(info) { progress ->
                    activity.runOnUiThread { renderProgress(progress) }
                }
                binding.tvUpdateStatus.setText(R.string.update_status_verifying)
                verifier.verify(apkFile, info)
                apkFile
            }.onSuccess { apkFile ->
                updateDialog?.dismiss()
                requestInstall(apkFile)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "App update download or verification failed", error)
                binding.tvUpdateStatus.setText(R.string.update_status_failed)
                binding.btnUpdateNow.isEnabled = true
                binding.btnUpdateLater.isEnabled = true
                toast(activity.getString(R.string.update_toast_failed))
            }
        }
    }

    private fun renderProgress(progress: ApkDownloader.Progress) {
        val binding = dialogBinding ?: return
        if (progress.totalBytes > 0L) {
            val percent = ((progress.downloadedBytes * 100L) / progress.totalBytes)
                .coerceIn(0L, 100L)
                .toInt()
            binding.progressUpdate.isIndeterminate = false
            binding.progressUpdate.progress = percent
            binding.tvUpdateStatus.text = activity.getString(
                R.string.update_status_progress,
                percent,
                formatBytes(progress.downloadedBytes),
                formatBytes(progress.totalBytes),
            )
        } else {
            binding.progressUpdate.isIndeterminate = true
            binding.tvUpdateStatus.text = activity.getString(
                R.string.update_status_downloaded,
                formatBytes(progress.downloadedBytes),
            )
        }
    }

    private fun requestInstall(apkFile: File) {
        pendingInstallFile = apkFile
        if (continueInstallIfAllowed()) return
        showInstallPermissionDialog(firstRequest = true)
    }

    private fun showInstallPermissionDialog(firstRequest: Boolean) {
        if (pendingInstallFile == null || activity.isFinishing || activity.isDestroyed ||
            installPermissionDialog?.isShowing == true
        ) {
            return
        }
        installPermissionDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_install_ready_title)
            .setMessage(
                if (firstRequest) {
                    R.string.update_install_ready_message
                } else {
                    R.string.update_install_permission_denied
                }
            )
            .setPositiveButton(R.string.update_install_continue) { _, _ ->
                openInstallPermissionSettings()
            }
            .setNegativeButton(R.string.update_install_later) { _, _ ->
                pendingInstallFile = null
            }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { installPermissionDialog = null }
                dialog.show()
            }
    }

    private fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        )
        awaitingInstallPermission = true
        try {
            installPermissionLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            awaitingInstallPermission = false
            toast(activity.getString(R.string.update_install_settings_unavailable))
            activity.window.decorView.post {
                showInstallPermissionDialog(firstRequest = false)
            }
        }
    }

    private fun finishInstallPermissionRequest() {
        if (!awaitingInstallPermission) return
        awaitingInstallPermission = false
        if (!continueInstallIfAllowed() && pendingInstallFile != null) {
            showInstallPermissionDialog(firstRequest = false)
        }
    }

    private fun continueInstallIfAllowed(): Boolean {
        if (!activity.packageManager.canRequestPackageInstalls()) return false
        val apkFile = pendingInstallFile ?: return false
        pendingInstallFile = null
        openSystemInstaller(apkFile)
        return true
    }

    private fun openSystemInstaller(apkFile: File) {
        if (!apkFile.isFile) {
            toast(activity.getString(R.string.update_toast_failed))
            return
        }
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(activity.getString(R.string.update_installer_unavailable))
        }
    }

    private fun currentVersionCode(): Long = activity.packageManager
        .getPackageInfo(activity.packageName, 0)
        .longVersionCode

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    override fun onDestroy(owner: LifecycleOwner) {
        downloadJob?.cancel()
        downloadJob = null
        updateDialog?.dismiss()
        updateDialog = null
        dialogBinding = null
        installPermissionDialog?.dismiss()
        installPermissionDialog = null
        awaitingInstallPermission = false
        pendingInstallFile = null
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val PREFERENCES_NAME = "app_update"
        private const val KEY_IGNORED_VERSION = "ignored_version"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
