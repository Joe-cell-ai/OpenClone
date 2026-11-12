package org.openclone.app.clone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

object CloneInstaller {
    const val ACTION_INSTALL_RESULT = "org.openclone.app.CLONE_INSTALL_RESULT"
    const val EXTRA_PACKAGE_NAME = "org.openclone.app.EXTRA_PACKAGE_NAME"
    const val EXTRA_STATUS = "org.openclone.app.EXTRA_STATUS"
    const val EXTRA_STATUS_MESSAGE = "org.openclone.app.EXTRA_STATUS_MESSAGE"
    const val EXTRA_OPERATION = "org.openclone.app.EXTRA_OPERATION"

    fun install(context: Context, result: CloneBuildResult) {
        require(result.apks.isNotEmpty()) { "The clone contains no APK files." }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            result.apks.forEachIndexed { index, file ->
                require(file.isFile) { "Clone APK is missing: ${file.name}" }
                val entryName = if (index == 0) "base.apk" else "split_$index.apk"
                session.openWrite(entryName, 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
            }
            val callback = Intent(context, CloneInstallReceiver::class.java)
                .setAction(ACTION_INSTALL_RESULT)
                .putExtra(EXTRA_PACKAGE_NAME, result.clonePackage)
                .putExtra(EXTRA_OPERATION, "install")
            val mutableFlag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
            )
            session.commit(pending.intentSender)
        } catch (error: Exception) {
            runCatching { session.abandon() }
            throw error
        } finally {
            session.close()
        }
    }

    fun uninstall(context: Context, packageName: String) {
        val callback = Intent(context, CloneInstallReceiver::class.java)
            .setAction(ACTION_INSTALL_RESULT)
            .putExtra(EXTRA_PACKAGE_NAME, packageName)
            .putExtra(EXTRA_OPERATION, "uninstall")
        val mutableFlag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pending = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            callback,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
        )
        context.packageManager.packageInstaller.uninstall(packageName, pending.intentSender)
    }
}

class CloneInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val packageName = intent.getStringExtra(CloneInstaller.EXTRA_PACKAGE_NAME).orEmpty()
        val operation = intent.getStringExtra(CloneInstaller.EXTRA_OPERATION).orEmpty()

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }
            confirmation?.let { pending ->
                pending.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(pending)
            }
            return
        }

        val result = Intent(CloneInstaller.ACTION_INSTALL_RESULT)
            .setPackage(context.packageName)
            .putExtra(CloneInstaller.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(CloneInstaller.EXTRA_OPERATION, operation)
            .putExtra(CloneInstaller.EXTRA_STATUS, status)
            .putExtra(
                CloneInstaller.EXTRA_STATUS_MESSAGE,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            )
        context.sendBroadcast(result)
    }
}
