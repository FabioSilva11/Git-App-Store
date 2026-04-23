package com.saas.payment.gitappstore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class FavoritesStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE favorites (
                full_name TEXT PRIMARY KEY,
                id INTEGER,
                name TEXT NOT NULL,
                owner_login TEXT NOT NULL,
                avatar_url TEXT,
                description TEXT,
                html_url TEXT NOT NULL,
                stars INTEGER,
                forks INTEGER,
                language TEXT,
                latest_release_date TEXT,
                updated_at TEXT,
                download_count INTEGER,
                image_url TEXT,
                readme_summary TEXT,
                topics TEXT,
                default_branch TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < 3) {
            runCatching {
                db.execSQL("ALTER TABLE favorites ADD COLUMN updated_at TEXT")
            }
        } else {
            db.execSQL("DROP TABLE IF EXISTS favorites")
            onCreate(db)
        }
    }

    fun isFavorite(fullName: String): Boolean =
        readableDatabase.query(
            TABLE,
            arrayOf("full_name"),
            "full_name = ?",
            arrayOf(fullName),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            cursor.moveToFirst()
        }

    fun toggle(repository: StoreRepository): Boolean {
        return if (isFavorite(repository.fullName)) {
            remove(repository.fullName)
            false
        } else {
            add(repository)
            true
        }
    }

    fun add(repository: StoreRepository) {
        writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            repository.toValues(includeCreatedAt = true),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun updateSnapshot(repository: StoreRepository) {
        val updatedRows =
            writableDatabase.update(
                TABLE,
                repository.toValues(includeCreatedAt = false),
                "full_name = ?",
                arrayOf(repository.fullName),
            )
        if (updatedRows == 0) add(repository)
    }

    fun remove(fullName: String) {
        writableDatabase.delete(TABLE, "full_name = ?", arrayOf(fullName))
    }

    fun getAll(): List<StoreRepository> =
        readableDatabase.query(
            TABLE,
            null,
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        StoreRepository(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                            fullName = cursor.getString(cursor.getColumnIndexOrThrow("full_name")),
                            owner =
                                StoreOwner(
                                    login = cursor.getString(cursor.getColumnIndexOrThrow("owner_login")),
                                    avatarUrl = cursor.getNullableString("avatar_url"),
                                ),
                            description = cursor.getNullableString("description"),
                            htmlUrl = cursor.getString(cursor.getColumnIndexOrThrow("html_url")),
                            stars = cursor.getInt(cursor.getColumnIndexOrThrow("stars")),
                            forks = cursor.getInt(cursor.getColumnIndexOrThrow("forks")),
                            language = cursor.getNullableString("language"),
                            latestReleaseDate = cursor.getNullableString("latest_release_date"),
                            downloadCount = cursor.getLong(cursor.getColumnIndexOrThrow("download_count")),
                            updatedAt = cursor.getNullableString("updated_at"),
                            imageUrl = cursor.getNullableString("image_url"),
                            readmeSummary = cursor.getNullableString("readme_summary"),
                            topics = cursor.getString(cursor.getColumnIndexOrThrow("topics")).toTopics(),
                            defaultBranch = cursor.getString(cursor.getColumnIndexOrThrow("default_branch")),
                        ),
                    )
                }
            }
        }

    private fun StoreRepository.toValues(includeCreatedAt: Boolean): ContentValues =
        ContentValues().apply {
            put("full_name", fullName)
            put("id", id)
            put("name", name)
            put("owner_login", owner.login.ifBlank { fullName.substringBefore("/") })
            put("avatar_url", owner.avatarUrl)
            put("description", description)
            put("html_url", htmlUrl)
            put("stars", stars)
            put("forks", forks)
            put("language", language)
            put("latest_release_date", latestReleaseDate)
            put("updated_at", updatedAt)
            put("download_count", downloadCount)
            put("image_url", imageUrl)
            put("readme_summary", readmeSummary)
            put("topics", JSONArray(topics).toString())
            put("default_branch", defaultBranch)
            if (includeCreatedAt) put("created_at", System.currentTimeMillis())
        }

    private fun android.database.Cursor.getNullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun String.toTopics(): List<String> {
        val array = JSONArray(this)
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private companion object {
        const val DB_NAME = "git_app_store.db"
        const val DB_VERSION = 3
        const val TABLE = "favorites"
    }
}
