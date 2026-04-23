package com.saas.payment.gitappstore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

class FavoriteUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            FavoriteUpdateChecker.schedule(context)
        }

        val pendingResult = goAsync()
        Executors.newSingleThreadExecutor().execute {
            try {
                FavoriteUpdateChecker.checkNowBlocking(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_CHECK_FAVORITES = "com.saas.payment.gitappstore.CHECK_FAVORITES"
    }
}
