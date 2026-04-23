package com.saas.payment.gitappstore.data

import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

interface StoreApi {
    fun getFeed(feed: StoreFeed): List<StoreRepository>

    fun searchAndroidRepositories(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchPage

    fun getRecommendedRepositories(
        limit: Int,
        offset: Int,
    ): SearchPage

    fun getRepository(
        owner: String,
        repo: String,
    ): StoreRepository?

    fun getReleases(
        owner: String,
        repo: String,
    ): List<StoreRelease>

    fun getContributors(
        owner: String,
        repo: String,
    ): List<StoreContributor>

    fun getReadmeSummary(
        owner: String,
        repo: String,
        defaultBranch: String,
    ): String?

    fun getReadmeDetails(
        owner: String,
        repo: String,
        defaultBranch: String,
    ): ReadmeDetails?
}

class GitHubStoreApi : StoreApi {
    override fun getFeed(feed: StoreFeed): List<StoreRepository> {
        val backendRepositories =
            runCatching {
                val body = get("$BACKEND_BASE_URL/${feed.endpointPath}")
                parseRepositories(JSONArray(body))
            }.getOrDefault(emptyList())

        val fallbackRepositories =
            runCatching {
                getFallbackRepositories(feed)
            }.getOrDefault(emptyList())

        return (backendRepositories + fallbackRepositories)
            .distinctBy { it.fullName.lowercase() }
            .sortedByRecentUpdate()
    }

    override fun searchAndroidRepositories(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchPage {
        val trimmedQuery = query.trim()
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        if (trimmedQuery.isBlank()) return SearchPage(emptyList(), 0)

        extractGitHubRepository(trimmedQuery)?.let { (owner, repo) ->
            getRepository(owner, repo)
                ?.takeIf { repository -> hasApkRelease(repository) }
                ?.let { repository -> return SearchPage(listOf(repository), 1) }
        }

        for (candidateQuery in backendSearchQueries(trimmedQuery)) {
            val page = searchBackendRepositories(candidateQuery, safeLimit, safeOffset)
            if (page != null && page.repositories.isNotEmpty()) return page
        }

        return searchGitHubRepositories(trimmedQuery, safeLimit, safeOffset)
    }

    override fun getRecommendedRepositories(
        limit: Int,
        offset: Int,
    ): SearchPage {
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        val curatedRepositories =
            RECOMMENDED_REPOSITORIES.mapNotNull { fullName ->
                val owner = fullName.substringBefore("/")
                val repo = fullName.substringAfter("/")
                getRepository(owner, repo)?.asCuratedRecommendation()
            }

        val supplementalRepositories =
            searchLowStarAndroidRepositories()
                .filterNot { repository ->
                    curatedRepositories.any { it.fullName.equals(repository.fullName, ignoreCase = true) }
                }

        val repositories =
            (curatedRepositories + supplementalRepositories)
                .distinctBy { it.fullName.lowercase() }

        return SearchPage(
            repositories = repositories.drop(safeOffset).take(safeLimit),
            totalHits = repositories.size,
        )
    }

    override fun getReleases(
        owner: String,
        repo: String,
    ): List<StoreRelease> {
        val encodedOwner = owner.encodePath()
        val encodedRepo = repo.encodePath()
        val releases =
            runCatching {
                parseReleases(get("$BACKEND_BASE_URL/releases/$encodedOwner/$encodedRepo?per_page=30").toArrayPayload())
            }.getOrElse {
                parseReleases(JSONArray(get("$GITHUB_API_BASE/repos/$encodedOwner/$encodedRepo/releases?per_page=30")))
            }

        return releases
            .filter { !it.isDraft }
            .sortedByDescending { it.publishedAt.orEmpty() }
    }

    override fun getContributors(
        owner: String,
        repo: String,
    ): List<StoreContributor> {
        val encodedOwner = owner.encodePath()
        val encodedRepo = repo.encodePath()
        val url = "$GITHUB_API_BASE/repos/$encodedOwner/$encodedRepo/contributors?per_page=12"
        return runCatching {
            parseContributors(JSONArray(get(url)))
        }.getOrDefault(emptyList())
            .ifEmpty { getCommitContributors(owner, repo) }
    }

    override fun getReadmeSummary(
        owner: String,
        repo: String,
        defaultBranch: String,
    ): String? = getReadmeDetails(owner, repo, defaultBranch)?.summary

    override fun getReadmeDetails(
        owner: String,
        repo: String,
        defaultBranch: String,
    ): ReadmeDetails? {
        val source =
            runCatching { fetchReadmeSource(owner, repo) }
                .getOrElse { readRawReadme(owner, repo, defaultBranch) }
                ?: return null

        return source.content.toReadmeDetails(
            owner = owner,
            repo = repo,
            defaultBranch = defaultBranch,
            readmePath = source.path,
        )
    }

    private fun getFallbackRepositories(feed: StoreFeed): List<StoreRepository> {
        val path =
            when (feed) {
                StoreFeed.TRENDING -> "cached-data/trending/android.json"
                StoreFeed.NEW_RELEASES -> "cached-data/new-releases/android.json"
                StoreFeed.MOST_POPULAR -> "cached-data/most-popular/android.json"
                StoreFeed.PRIVACY -> "cached-data/topics/privacy/android.json"
            }
        val body = get("$FALLBACK_BASE_URL/$path")
        val root = JSONObject(body)
        return parseRepositories(root.optJSONArray("repositories") ?: JSONArray())
    }

    private fun searchBackendRepositories(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchPage? =
        runCatching {
            val url =
                "$BACKEND_BASE_URL/search?q=${query.encodeQuery()}&platform=android" +
                    "&sort=relevance&limit=$limit&offset=$offset"
            val root = JSONObject(get(url))
            SearchPage(
                repositories = parseRepositories(root.optJSONArray("items") ?: JSONArray()).sortedByRecentUpdate(),
                totalHits = root.optInt("totalHits"),
            )
        }.getOrNull()

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "GitAppStore-Android")
        }

        return try {
            val code = connection.responseCode
            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: error("HTTP $code from $url")
                }

            val response = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use {
                it.readText()
            }
            if (code !in 200..299) {
                error("HTTP $code from $url: ${response.take(200)}")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRepositories(array: JSONArray): List<StoreRepository> =
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseRepository(item)?.let(::add)
            }
        }

    private fun parseRepository(item: JSONObject): StoreRepository? {
        if (!item.optBoolean("hasInstallersAndroid", true)) return null

        val owner = item.optJSONObject("owner") ?: JSONObject()
        val fullName = item.optString("fullName").ifBlank {
            item.optString("full_name").ifBlank {
                "${owner.optString("login")}/${item.optString("name")}"
            }
        }.trim()
        if (fullName.isBlank() || !fullName.contains("/")) return null

        val repositoryName = item.optString("name").ifBlank {
            fullName.substringAfter("/")
        }
        val ownerLogin = owner.optString("login").ifBlank {
            fullName.substringBefore("/")
        }
        val topics = item.optJSONArray("topics")?.toStringList().orEmpty()

        return StoreRepository(
            id = item.optLong("id"),
            name = repositoryName,
            fullName = fullName,
            owner = StoreOwner(
                login = ownerLogin,
                avatarUrl = owner.optNullableString("avatarUrl")
                    ?: owner.optNullableString("avatar_url"),
            ),
            description = item.optNullableString("description"),
            htmlUrl = item.optNullableString("htmlUrl")
                ?: item.optNullableString("html_url")
                ?: "https://github.com/$fullName",
            stars = item.optInt("stargazersCount", item.optInt("stargazers_count")),
            forks = item.optInt("forksCount", item.optInt("forks_count")),
            language = item.optNullableString("language"),
            latestReleaseDate = item.optNullableString("latestReleaseDate"),
            downloadCount = item.optLong("downloadCount", item.optLong("download_count")),
            updatedAt = item.optNullableString("updatedAt")
                ?: item.optNullableString("pushedAt")
                ?: item.optNullableString("pushed_at")
                ?: item.optNullableString("updated_at"),
            imageUrl = item.optNullableString("imageUrl")
                ?: item.optNullableString("image_url")
                ?: item.optNullableString("iconUrl")
                ?: item.optNullableString("icon_url")
                ?: item.optNullableString("openGraphImageUrl")
                ?: item.optNullableString("open_graph_image_url"),
            topics = topics,
            defaultBranch = item.optNullableString("defaultBranch")
                ?: item.optNullableString("default_branch")
                ?: "main",
        )
    }

    private fun searchGitHubRepositories(
        query: String,
        limit: Int,
        offset: Int,
    ): SearchPage {
        val perPage = ((limit + offset) * 2).coerceIn(10, 30)
        val candidates = linkedMapOf<String, StoreRepository>()

        extractGitHubRepository(query)?.let { (owner, repo) ->
            getRepository(owner, repo)?.let { repository ->
                candidates[repository.fullName.lowercase()] = repository
            }
        }

        for (githubQuery in githubSearchQueries(query)) {
            val url =
                "$GITHUB_API_BASE/search/repositories?q=${githubQuery.encodeQuery()}" +
                    "&sort=updated&order=desc&per_page=$perPage&page=1"
            val root = runCatching { JSONObject(get(url)) }.getOrNull() ?: continue
            parseRepositories(root.optJSONArray("items") ?: JSONArray()).forEach { repository ->
                candidates.putIfAbsent(repository.fullName.lowercase(), repository)
            }
        }

        val validatedRepositories =
            candidates.values
                .filter(::hasApkRelease)
                .sortedByRecentUpdate()

        val pageRepositories =
            validatedRepositories
                .drop(offset)
                .take(limit)

        return SearchPage(
            repositories = pageRepositories,
            totalHits = validatedRepositories.size,
        )
    }

    private fun searchLowStarAndroidRepositories(): List<StoreRepository> {
        val candidates = linkedMapOf<String, StoreRepository>()
        for (githubQuery in LOW_STAR_RECOMMENDATION_QUERIES) {
            val url =
                "$GITHUB_API_BASE/search/repositories?q=${githubQuery.encodeQuery()}" +
                    "&sort=updated&order=desc&per_page=30&page=1"
            val root = runCatching { JSONObject(get(url)) }.getOrNull() ?: continue
            parseRepositories(root.optJSONArray("items") ?: JSONArray())
                .filter { repository -> repository.stars in 1..LOW_STAR_LIMIT }
                .forEach { repository ->
                    candidates.putIfAbsent(repository.fullName.lowercase(), repository.asApiRecommendation())
                }
        }

        return candidates.values
            .toList()
            .sortedByRecentUpdate()
            .take(36)
    }

    override fun getRepository(
        owner: String,
        repo: String,
    ): StoreRepository? =
        runCatching {
            val url = "$GITHUB_API_BASE/repos/${owner.encodePath()}/${repo.encodePath()}"
            parseRepository(JSONObject(get(url)))
        }.getOrNull()

    private fun hasApkRelease(repository: StoreRepository): Boolean =
        runCatching {
            getReleases(repository.owner.login, repository.name)
                .any { release -> release.assets.any { asset -> asset.isApk() } }
        }.getOrDefault(false)

    private fun getCommitContributors(
        owner: String,
        repo: String,
    ): List<StoreContributor> =
        runCatching {
            val encodedOwner = owner.encodePath()
            val encodedRepo = repo.encodePath()
            val url = "$GITHUB_API_BASE/repos/$encodedOwner/$encodedRepo/commits?per_page=30"
            parseCommitContributors(JSONArray(get(url)))
        }.getOrDefault(emptyList())

    private fun List<StoreRepository>.sortedByRecentUpdate(): List<StoreRepository> =
        sortedWith(
            compareByDescending<StoreRepository> { it.updatedAt.orEmpty() }
                .thenByDescending { it.latestReleaseDate.orEmpty() }
                .thenByDescending { it.stars },
        )

    private fun StoreRepository.asCuratedRecommendation(): StoreRepository {
        val recommendationText = "Recomendado: projeto procurando pessoas para atualizar e manter."
        val mergedDescription =
            description
                ?.takeIf { it.isNotBlank() }
                ?.let { "$recommendationText $it" }
                ?: recommendationText
        return copy(
            description = mergedDescription,
            topics = (listOf("recomendado", "precisa-de-ajuda") + topics).distinct(),
        )
    }

    private fun StoreRepository.asApiRecommendation(): StoreRepository =
        copy(
            topics = (listOf("api", "poucas-estrelas") + topics).distinct(),
        )

    private fun StoreAsset.isApk(): Boolean =
        name.endsWith(".apk", ignoreCase = true) ||
            contentType.equals(APK_CONTENT_TYPE, ignoreCase = true)

    private fun backendSearchQueries(query: String): List<String> =
        linkedSetOf<String>().apply {
            add(query)
            add(query.normalizedSearchText())
            searchTerms(query).firstOrNull()?.let(::add)
        }.filter { it.isNotBlank() }

    private fun githubSearchQueries(query: String): List<String> =
        linkedSetOf<String>().apply {
            add("$query android apk")
            add("$query android")
            for (term in searchTerms(query)) {
                add("$term android apk")
                add("$term android")
                add("topic:$term android")
            }
        }.filter { it.isNotBlank() }

    private fun searchTerms(query: String): List<String> {
        val values = linkedSetOf(query)
        extractGitHubRepository(query)?.let { (owner, repo) ->
            values.add(owner)
            values.add(repo)
        }

        val terms = linkedSetOf<String>()
        for (value in values) {
            val cleaned =
                value
                    .lowercase()
                    .replace(Regex("^https?://"), "")
                    .replace(Regex("^www\\.github\\.com/"), "")
                    .replace(Regex("^github\\.com/"), "")
                    .substringBefore("?")
                    .substringBefore("#")
                    .removeSuffix(".git")

            cleaned
                .split("/", " ", "_")
                .map { it.trim('-') }
                .filter { it.length >= 3 }
                .forEach { part ->
                    terms.add(part)
                    part.split("-")
                        .filter { it.length >= 3 }
                        .forEach(terms::add)
                }
        }

        return terms.take(6)
    }

    private fun String.normalizedSearchText(): String =
        lowercase()
            .replace(Regex("^https?://"), "")
            .replace(Regex("^www\\.github\\.com/"), "")
            .replace(Regex("^github\\.com/"), "")
            .substringBefore("?")
            .substringBefore("#")
            .removeSuffix(".git")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun extractGitHubRepository(query: String): Pair<String, String>? {
        val normalized =
            query
                .trim()
                .replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^www\\.github\\.com/", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^github\\.com/", RegexOption.IGNORE_CASE), "")
                .substringBefore("?")
                .substringBefore("#")
                .removeSuffix(".git")

        val parts = normalized.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val owner = parts[0].trim()
        val repo = parts[1].trim().removeSuffix(".git")
        if (owner.isBlank() || repo.isBlank()) return null
        return owner to repo
    }

    private fun fetchReadmeSource(
        owner: String,
        repo: String,
    ): ReadmeSource {
        val encodedOwner = owner.encodePath()
        val encodedRepo = repo.encodePath()
        val root = JSONObject(get("$GITHUB_API_BASE/repos/$encodedOwner/$encodedRepo/readme"))
        val encodedContent = root.optString("content").replace("\n", "").trim()
        val decoded =
            String(
                Base64.decode(encodedContent, Base64.DEFAULT),
                StandardCharsets.UTF_8,
            ).takeIf { it.isNotBlank() }
                ?: error("README vazio para $owner/$repo")

        return ReadmeSource(
            content = decoded,
            path = root.optString("path").ifBlank { "README.md" },
        )
    }

    private fun readRawReadme(
        owner: String,
        repo: String,
        defaultBranch: String,
    ): ReadmeSource? {
        val candidates = listOf("README.md", "README", "readme.md")
        return candidates.firstNotNullOfOrNull { file ->
            runCatching {
                ReadmeSource(
                    content =
                        get(
                            "https://raw.githubusercontent.com/" +
                                "${owner.encodePath()}/${repo.encodePath()}/${defaultBranch.encodePath()}/$file",
                        ),
                    path = file,
                )
            }.getOrNull()
        }
    }

    private fun parseReleases(array: JSONArray): List<StoreRelease> =
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    StoreRelease(
                        id = item.optLong("id"),
                        tagName = item.optString("tag_name", item.optString("tagName")),
                        name = item.optNullableString("name"),
                        publishedAt = item.optNullableString("published_at")
                            ?: item.optNullableString("publishedAt")
                            ?: item.optNullableString("created_at"),
                        body = item.optNullableString("body")
                            ?: item.optNullableString("description"),
                        isDraft = item.optBoolean("draft", false),
                        isPrerelease = item.optBoolean("prerelease", false),
                        assets = parseAssets(item.optJSONArray("assets") ?: JSONArray()),
                    ),
                )
            }
        }

    private fun parseAssets(array: JSONArray): List<StoreAsset> =
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val downloadUrl = item.optNullableString("browser_download_url")
                    ?: item.optNullableString("downloadUrl")
                    ?: continue
                add(
                    StoreAsset(
                        id = item.optLong("id", index.toLong()),
                        name = item.optString("name"),
                        contentType = item.optNullableString("content_type")
                            ?: item.optNullableString("contentType"),
                        size = item.optLong("size"),
                        downloadUrl = downloadUrl,
                        downloadCount = item.optLong("download_count", item.optLong("downloadCount")),
                    ),
                )
            }
        }

    private fun parseContributors(array: JSONArray): List<StoreContributor> =
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val login = item.optString("login").takeIf { it.isNotBlank() } ?: continue
                add(
                    StoreContributor(
                        login = login,
                        avatarUrl = item.optNullableString("avatar_url"),
                        htmlUrl = item.optNullableString("html_url") ?: "https://github.com/$login",
                        contributions = item.optInt("contributions"),
                    ),
                )
            }
        }

    private fun parseCommitContributors(array: JSONArray): List<StoreContributor> {
        val contributors = linkedMapOf<String, StoreContributor>()
        val counts = linkedMapOf<String, Int>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val author = item.optJSONObject("author")
            val commitAuthor = item.optJSONObject("commit")?.optJSONObject("author")
            val login = author?.optNullableString("login")
                ?: commitAuthor?.optNullableString("name")
                ?: continue
            val key = login.lowercase()
            counts[key] = (counts[key] ?: 0) + 1
            contributors.putIfAbsent(
                key,
                StoreContributor(
                    login = login,
                    avatarUrl = author?.optNullableString("avatar_url"),
                    htmlUrl = author?.optNullableString("html_url") ?: "https://github.com/$login",
                    contributions = 0,
                ),
            )
        }

        return contributors.map { (key, contributor) ->
            contributor.copy(contributions = counts[key] ?: 0)
        }.sortedByDescending { it.contributions }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (has(name) && !isNull(name)) {
            optString(name).takeIf { it.isNotBlank() }
        } else {
            null
        }

    private fun String.encodePath(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun String.encodeRawPath(): String =
        split("/")
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }

    private fun String.encodeQuery(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun JSONArray.toStringList(): List<String> =
        buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

    private fun String.toArrayPayload(): JSONArray {
        val trimmed = trim()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val root = JSONObject(trimmed)
        return root.optJSONArray("releases")
            ?: root.optJSONArray("items")
            ?: root.optJSONArray("data")
            ?: JSONArray()
    }

    private fun String.toReadmeDetails(
        owner: String,
        repo: String,
        defaultBranch: String,
        readmePath: String,
    ): ReadmeDetails {
        val paragraphs = readmeParagraphs()
        val summary = paragraphs.firstOrNull()?.take(320)
        val expandedText =
            paragraphs
                .take(6)
                .joinToString("\n\n")
                .take(2600)
                .trim()
                .takeIf { it.isNotBlank() }

        return ReadmeDetails(
            summary = summary,
            expandedText = expandedText,
            imageUrls = extractReadmeImageUrls(owner, repo, defaultBranch, readmePath),
        )
    }

    private fun String.toReadmeSummary(): String? = readmeParagraphs().firstOrNull()?.take(320)

    private fun String.readmeParagraphs(): List<String> =
        replace(Regex("(?s)```.*?```"), "\n")
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "\n")
            .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "\n")
            .replace(Regex("<img[^>]+>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "$1")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .split(Regex("\\n\\s*\\n"))
            .mapNotNull { block ->
                block.lines()
                    .map { line ->
                        line.trim()
                            .replace(Regex("^#{1,6}\\s*"), "")
                            .replace(Regex("^[-*+]\\s+"), "")
                            .replace(Regex("^>\\s*"), "")
                            .replace(Regex("[*_`~]"), "")
                    }
                    .filter { line ->
                        line.isNotBlank() &&
                            line.length >= 24 &&
                            !line.startsWith("[!") &&
                            !line.contains("shields.io") &&
                            !line.contains("badge", ignoreCase = true)
                    }
                    .joinToString(" ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeIf { it.length >= 24 }
            }
            .distinct()

    private fun String.extractReadmeImageUrls(
        owner: String,
        repo: String,
        defaultBranch: String,
        readmePath: String,
    ): List<String> {
        val markdownImages =
            Regex("!\\[[^]]*]\\(([^)]+)\\)")
                .findAll(this)
                .map { match ->
                    match.groupValues[1]
                        .substringBefore(" \"")
                        .removePrefix("<")
                        .removeSuffix(">")
                        .trim()
                }

        val htmlImages =
            Regex("<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
                .findAll(this)
                .map { it.groupValues[1].trim() }

        return (markdownImages + htmlImages)
            .mapNotNull { rawUrl ->
                rawUrl
                    .takeIf { it.isNotBlank() }
                    ?.resolveReadmeImageUrl(owner, repo, defaultBranch, readmePath)
            }
            .filterNot { it.isBadgeImageUrl() }
            .distinct()
            .take(8)
            .toList()
    }

    private fun String.resolveReadmeImageUrl(
        owner: String,
        repo: String,
        defaultBranch: String,
        readmePath: String,
    ): String {
        val normalized = trim().removePrefix("./")
        if (normalized.startsWith("//")) return "https:$normalized"
        if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            val githubBlobPrefix = "https://github.com/$owner/$repo/blob/"
            return if (normalized.startsWith(githubBlobPrefix)) {
                normalized.replace(
                    githubBlobPrefix,
                    "https://raw.githubusercontent.com/${owner.encodePath()}/${repo.encodePath()}/",
                )
            } else {
                normalized
            }
        }

        val readmeDirectory = readmePath.substringBeforeLast("/", "")
        val relativePath =
            if (normalized.startsWith("/")) {
                normalized.removePrefix("/")
            } else {
                listOf(readmeDirectory, normalized)
                    .filter { it.isNotBlank() }
                    .joinToString("/")
            }

        return "https://raw.githubusercontent.com/" +
            "${owner.encodePath()}/${repo.encodePath()}/${defaultBranch.encodePath()}/" +
            relativePath.encodeRawPath()
    }

    private fun String.isBadgeImageUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("shields.io") ||
            lower.contains("badge") ||
            lower.contains("workflow") ||
            lower.contains("github.com/apps/dependabot") ||
            lower.endsWith(".svg")
    }

    private companion object {
        const val BACKEND_BASE_URL = "https://api.github-store.org/v1"
        const val GITHUB_API_BASE = "https://api.github.com"
        const val FALLBACK_BASE_URL = "https://raw.githubusercontent.com/OpenHub-Store/api/main"
        const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"
        const val LOW_STAR_LIMIT = 50
        val RECOMMENDED_REPOSITORIES =
            listOf(
                "FabioSilva11/Sketchware-IA",
                "git-jr/Projeto-Amadeus-Assistente-Android",
                "FabioSilva11/Apk-Editor-PLus",
            )
        val LOW_STAR_RECOMMENDATION_QUERIES =
            listOf(
                "android apk stars:1..50 archived:false fork:false",
                "topic:android stars:1..50 archived:false fork:false",
                "android app stars:1..50 archived:false fork:false",
            )
    }

    private data class ReadmeSource(
        val content: String,
        val path: String,
    )
}
