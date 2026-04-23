package com.saas.payment.gitappstore

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.saas.payment.gitappstore.data.FavoritesStore
import com.saas.payment.gitappstore.data.GitHubStoreApi
import com.saas.payment.gitappstore.data.StoreApi
import com.saas.payment.gitappstore.data.StoreRepository
import java.util.concurrent.Executors

object FavoriteUpdateChecker {
    private const val CHANNEL_ID = "favorite_repository_updates"
    private const val CHANNEL_NAME = "Atualizacoes dos favoritos"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val FIRST_CHECK_DELAY_MS = 15 * 60 * 1000L
    private const val NOTIFICATION_ID = 3107
    private const val MAX_FAVORITES_PER_CHECK = 25

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Avisa quando um repositorio favorito recebe atualizacao."
            }
        manager.createNotificationChannel(channel)
    }

    fun schedule(context: Context) {
        val appContext = context.applicationContext
        createNotificationChannel(appContext)
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + FIRST_CHECK_DELAY_MS
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            CHECK_INTERVAL_MS,
            pendingIntent(appContext),
        )
    }

    fun checkNow(context: Context) {
        val appContext = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            checkNowBlocking(appContext)
        }
    }

    fun checkNowBlocking(context: Context) {
        val appContext = context.applicationContext
        createNotificationChannel(appContext)

        val favoritesStore = FavoritesStore(appContext)
        val api: StoreApi = GitHubStoreApi()
        val updatedFavorites = mutableListOf<StoreRepository>()

        favoritesStore.getAll()
            .take(MAX_FAVORITES_PER_CHECK)
            .forEach { favorite ->
                val owner = favorite.fullName.substringBefore("/")
                val repo = favorite.fullName.substringAfter("/")
                if (owner.isBlank() || repo.isBlank() || !favorite.fullName.contains("/")) return@forEach

                val latestRepository = api.getRepository(owner, repo) ?: return@forEach
                val latestReleaseDate =
                    runCatching { api.getReleases(owner, repo).firstOrNull()?.publishedAt }
                        .getOrNull()
                        ?: favorite.latestReleaseDate
                val latestSnapshot =
                    latestRepository.copy(
                        latestReleaseDate = latestReleaseDate,
                        downloadCount = maxOf(latestRepository.downloadCount, favorite.downloadCount),
                        imageUrl = latestRepository.imageUrl ?: favorite.imageUrl,
                        readmeSummary = favorite.readmeSummary,
                        topics = latestRepository.topics.ifEmpty { favorite.topics },
                    )

                val previousMarker = favorite.updateMarker()
                val latestMarker = latestSnapshot.updateMarker()
                if (latestMarker.isBlank()) return@forEach

                favoritesStore.updateSnapshot(latestSnapshot)
                if (previousMarker.isNotBlank() && latestMarker > previousMarker) {
                    updatedFavorites.add(latestSnapshot)
                }
            }

        if (updatedFavorites.isNotEmpty() && canPostNotifications(appContext)) {
            notifyUpdates(appContext, updatedFavorites)
        }
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun notifyUpdates(
        context: Context,
        repositories: List<StoreRepository>,
    ) {
        val title =
            if (repositories.size == 1) {
                "Favorito atualizado"
            } else {
                "${repositories.size} favoritos atualizados"
            }
        val text =
            if (repositories.size == 1) {
                "${repositories.first().fullName} recebeu atualizacao."
            } else {
                repositories.joinToString(", ") { it.fullName }
            }

        val openIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val contentIntent =
            PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_git_app_store)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setColor(ThemeManager.resolveColor(context, R.attr.themePrimary))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent =
            Intent(context, FavoriteUpdateReceiver::class.java).apply {
                action = FavoriteUpdateReceiver.ACTION_CHECK_FAVORITES
            }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun StoreRepository.updateMarker(): String =
        listOfNotNull(updatedAt, latestReleaseDate)
            .filter { it.isNotBlank() }
            .maxOrNull()
            .orEmpty()
}
