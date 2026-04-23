package com.saas.payment.gitappstore.data

data class StoreOwner(
    val login: String,
    val avatarUrl: String?,
)

data class StoreRepository(
    val id: Long,
    val name: String,
    val fullName: String,
    val owner: StoreOwner,
    val description: String?,
    val htmlUrl: String,
    val stars: Int,
    val forks: Int,
    val language: String?,
    val latestReleaseDate: String?,
    val downloadCount: Long,
    val updatedAt: String? = null,
    val imageUrl: String? = null,
    val readmeSummary: String? = null,
    val topics: List<String> = emptyList(),
    val defaultBranch: String = "main",
)

data class StoreAsset(
    val id: Long,
    val name: String,
    val contentType: String?,
    val size: Long,
    val downloadUrl: String,
    val downloadCount: Long,
)

data class StoreRelease(
    val id: Long,
    val tagName: String,
    val name: String?,
    val publishedAt: String?,
    val body: String?,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val assets: List<StoreAsset>,
)

data class StoreContributor(
    val login: String,
    val avatarUrl: String?,
    val htmlUrl: String,
    val contributions: Int,
)

data class ApkOption(
    val releaseTag: String,
    val isPrerelease: Boolean,
    val asset: StoreAsset,
)

data class SearchPage(
    val repositories: List<StoreRepository>,
    val totalHits: Int,
)

data class ReadmeDetails(
    val summary: String?,
    val expandedText: String?,
    val imageUrls: List<String>,
)

enum class StoreFeed(
    val title: String,
    val endpointPath: String,
) {
    TRENDING("Em alta", "categories/trending/android"),
    NEW_RELEASES("Lancamentos", "categories/new-releases/android"),
    MOST_POPULAR("Populares", "categories/most-popular/android"),
    PRIVACY("Privacidade", "topics/privacy/android"),
}
