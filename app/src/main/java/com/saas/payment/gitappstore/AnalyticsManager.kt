package com.saas.payment.gitappstore

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

object AnalyticsManager {
    private fun analytics(context: Context): FirebaseAnalytics =
        FirebaseAnalytics.getInstance(context.applicationContext)

    fun logMainButtonClick(
        context: Context,
        action: String,
        repositoryFullName: String,
    ) {
        analytics(context).logEvent("main_button_click", Bundle().apply {
            putString("action_type", action)
            putString("repository_name", repositoryFullName.take(100))
        })
    }

    fun logApkDownload(
        context: Context,
        repositoryFullName: String,
        assetName: String,
    ) {
        analytics(context).logEvent("apk_download_requested", Bundle().apply {
            putString("repository_name", repositoryFullName.take(100))
            putString("asset_name", assetName.take(100))
        })
    }
}
