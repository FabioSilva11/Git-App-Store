package com.saas.payment.gitappstore

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saas.payment.gitappstore.data.ApkOption
import com.saas.payment.gitappstore.data.FavoritesStore
import com.saas.payment.gitappstore.data.GitHubStoreApi
import com.saas.payment.gitappstore.data.ReadmeDetails
import com.saas.payment.gitappstore.data.StoreApi
import com.saas.payment.gitappstore.data.StoreAsset
import com.saas.payment.gitappstore.data.StoreContributor
import com.saas.payment.gitappstore.data.StoreOwner
import com.saas.payment.gitappstore.data.StoreRelease
import com.saas.payment.gitappstore.data.StoreRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class DetailActivity : AppCompatActivity() {
    private val api: StoreApi = GitHubStoreApi()
    private val dataExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var favoritesStore: FavoritesStore
    private lateinit var detailContent: LinearLayout
    private lateinit var favoriteIcon: ImageView
    private lateinit var detailIcon: ImageView
    private lateinit var detailTitle: TextView
    private lateinit var detailRepository: TextView
    private lateinit var detailDescription: TextView
    private lateinit var releaseDateText: TextView
    private lateinit var tagsGroup: ChipGroup
    private lateinit var detailLoadingRow: View
    private lateinit var detailLoadingStatus: TextView
    private lateinit var detailLoadingSubtitle: TextView
    private lateinit var screenshotsSection: View
    private lateinit var screenshotsRecyclerView: RecyclerView
    private lateinit var readmeSection: View
    private lateinit var readmeExpandedText: TextView
    private lateinit var readmeExpandButton: TextView
    private lateinit var contributorsContainer: LinearLayout
    private lateinit var releaseVersionText: TextView
    private lateinit var releaseBulletsText: TextView
    private lateinit var releaseNotesText: TextView
    private lateinit var apksContainer: LinearLayout

    private lateinit var repository: StoreRepository
    private lateinit var screenshotAdapter: ScreenshotGridAdapter
    private var latestRelease: StoreRelease? = null
    private var latestApks: List<ApkOption> = emptyList()
    private var currentScreenshotUrls: List<String> = emptyList()
    private var currentReadmeText: String? = null
    private var isReadmeExpanded = false
    private val observedDownloadIds = mutableMapOf<Long, String>()
    private val downloadCompleteReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context?,
                intent: Intent?,
            ) {
                if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (downloadId == -1L || observedDownloadIds.remove(downloadId) == null) return

                bindApks(latestApks)
                Toast.makeText(
                    this@DetailActivity,
                    getString(R.string.download_ready),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_detail)
        favoritesStore = FavoritesStore(this)
        applySystemBarsPadding()
        bindViews()
        setupScreenshotsGrid()
        applyResponsiveWidth()
        repository = repositoryFromIntent()
        renderInitialDetail()
        setupActions()
        loadLiveDetails()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        if (latestApks.isNotEmpty()) bindApks(latestApks)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(downloadCompleteReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        dataExecutor.shutdownNow()
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun applySystemBarsPadding() {
        val root = findViewById<View>(R.id.detailRoot)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + systemBars.left,
                initialTop + systemBars.top,
                initialRight + systemBars.right,
                initialBottom + systemBars.bottom,
            )
            insets
        }
    }

    private fun bindViews() {
        detailContent = findViewById(R.id.detailContent)
        favoriteIcon = findViewById(R.id.favoriteIcon)
        detailIcon = findViewById(R.id.detailIcon)
        detailTitle = findViewById(R.id.detailTitle)
        detailRepository = findViewById(R.id.detailRepository)
        detailDescription = findViewById(R.id.detailDescription)
        releaseDateText = findViewById(R.id.releaseDateText)
        tagsGroup = findViewById(R.id.tagsGroup)
        detailLoadingRow = findViewById(R.id.detailLoadingRow)
        detailLoadingStatus = detailLoadingRow.findViewById(R.id.loadingTitle)
        detailLoadingSubtitle = detailLoadingRow.findViewById(R.id.loadingSubtitle)
        screenshotsSection = findViewById(R.id.screenshotsSection)
        screenshotsRecyclerView = findViewById(R.id.screenshotsRecyclerView)
        readmeSection = findViewById(R.id.readmeSection)
        readmeExpandedText = findViewById(R.id.readmeExpandedText)
        readmeExpandButton = findViewById(R.id.readmeExpandButton)
        contributorsContainer = findViewById(R.id.contributorsContainer)
        releaseVersionText = findViewById(R.id.releaseVersionText)
        releaseBulletsText = findViewById(R.id.releaseBulletsText)
        releaseNotesText = findViewById(R.id.releaseNotesText)
        apksContainer = findViewById(R.id.apksContainer)
    }

    private fun setupScreenshotsGrid() {
        screenshotAdapter =
            ScreenshotGridAdapter(
                imageExecutor = imageExecutor,
                onScreenshotClick = ::openScreenshotViewer,
            )
        screenshotsRecyclerView.layoutManager = GridLayoutManager(this, SCREENSHOT_GRID_SPAN_COUNT)
        screenshotsRecyclerView.adapter = screenshotAdapter
        screenshotsRecyclerView.itemAnimator = null
    }

    private fun applyResponsiveWidth() {
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = resources.getDimensionPixelSize(R.dimen.home_max_content_width)
        val targetWidth = min(screenWidth, maxWidth)
        if (screenWidth <= maxWidth) return

        val params = detailContent.layoutParams as FrameLayout.LayoutParams
        params.width = targetWidth
        params.gravity = android.view.Gravity.CENTER_HORIZONTAL
        detailContent.layoutParams = params
    }

    private fun repositoryFromIntent(): StoreRepository {
        val fullName = intent.getStringExtra(EXTRA_FULL_NAME).orEmpty()
        val fallbackName = fullName.substringAfter("/", missingDelimiterValue = fullName)
        val ownerLogin = intent.getStringExtra(EXTRA_OWNER).orEmpty().ifBlank {
            fullName.substringBefore("/", missingDelimiterValue = "")
        }
        val description = intent.getStringExtra(EXTRA_DESCRIPTION)
        return StoreRepository(
            id = intent.getLongExtra(EXTRA_ID, 0L),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifBlank { fallbackName },
            fullName = fullName,
            owner = StoreOwner(
                login = ownerLogin,
                avatarUrl = intent.getStringExtra(EXTRA_AVATAR_URL),
            ),
            description = description,
            htmlUrl = intent.getStringExtra(EXTRA_HTML_URL).orEmpty().ifBlank {
                "https://github.com/$fullName"
            },
            stars = intent.getIntExtra(EXTRA_STARS, 0),
            forks = intent.getIntExtra(EXTRA_FORKS, 0),
            language = intent.getStringExtra(EXTRA_LANGUAGE),
            latestReleaseDate = intent.getStringExtra(EXTRA_RELEASE_DATE),
            downloadCount = intent.getLongExtra(EXTRA_DOWNLOADS, 0L),
            updatedAt = intent.getStringExtra(EXTRA_UPDATED_AT),
            imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL),
            readmeSummary = description,
            topics = intent.getStringArrayListExtra(EXTRA_TOPICS).orEmpty(),
            defaultBranch = intent.getStringExtra(EXTRA_DEFAULT_BRANCH).orEmpty().ifBlank { "main" },
        )
    }

    private fun renderInitialDetail() {
        ImageLoader.load(
            detailIcon,
            repository.imageUrl ?: repository.owner.avatarUrl,
            imageExecutor,
            R.drawable.ic_logo_git,
        )
        detailTitle.text = repository.name
        detailRepository.text = repository.fullName
        detailDescription.text =
            repository.readmeSummary ?: repository.description ?: getString(R.string.loading_repos)
        releaseDateText.text = getString(
            R.string.latest_release_date,
            repository.latestReleaseDate.toDisplayDateOrFallback(),
        )
        bindStat(R.id.statStars, R.drawable.ic_star, repository.stars.toLong().formatCount(), "Stars")
        bindStat(R.id.statForks, R.drawable.ic_fork, repository.forks.toLong().formatCount(), "Forks")
        bindStat(R.id.statDownloads, R.drawable.ic_download, repository.downloadCount.formatCount(), "Downloads")
        bindStat(R.id.statLanguage, R.drawable.ic_code, repository.language.orEmpty().ifBlank { "N/D" }, "Language")
        bindTags(repository.topics)
        bindFavorite()
        showDetailLoading(true, getString(R.string.details_loading_data))
        bindScreenshots(emptyList())
        bindReadme(null)
        bindRelease(null, isLoading = true)
        bindContributors(emptyList(), isLoading = true)
        bindApks(emptyList(), isLoading = true)
    }

    private fun loadLiveDetails() {
        val owner = repository.fullName.substringBefore("/")
        val repo = repository.fullName.substringAfter("/")
        if (owner.isBlank() || repo.isBlank() || !repository.fullName.contains("/")) {
            showDetailLoading(false)
            bindScreenshots(emptyList())
            bindReadme(null)
            bindRelease(null)
            bindContributors(emptyList())
            bindApks(emptyList())
            return
        }

        val pendingCoreLoads = AtomicInteger(2)
        fun finishCoreLoad() {
            if (pendingCoreLoads.decrementAndGet() == 0) {
                mainHandler.post { showDetailLoading(false) }
            }
        }

        dataExecutor.execute {
            val releasesResult = runCatching { api.getReleases(owner, repo) }
            val releases = releasesResult.getOrDefault(emptyList())
            val release = releases.firstOrNull()
            val apks =
                releases
                    .flatMap { storeRelease ->
                        storeRelease.assets
                            .filter { asset -> asset.isApk() }
                            .map { asset ->
                                ApkOption(
                                    releaseTag = storeRelease.tagName,
                                    isPrerelease = storeRelease.isPrerelease,
                                    asset = asset,
                                )
                            }
                    }
                    .take(12)

            mainHandler.post {
                if (releasesResult.isFailure) {
                    Toast.makeText(this, R.string.details_load_error, Toast.LENGTH_SHORT).show()
                }
                latestRelease = release
                latestApks = apks
                releaseDateText.text = getString(
                    R.string.latest_release_date,
                    (release?.publishedAt ?: repository.latestReleaseDate).toDisplayDateOrFallback(),
                )
                bindRelease(release)
                bindApks(apks)
                bindFavorite()
            }
            finishCoreLoad()
        }

        dataExecutor.execute {
            val contributorsResult = runCatching { api.getContributors(owner, repo) }
            val contributors = contributorsResult.getOrDefault(emptyList())
            mainHandler.post {
                if (contributorsResult.isFailure) {
                    Toast.makeText(this, R.string.details_load_error, Toast.LENGTH_SHORT).show()
                }
                bindContributors(contributors)
            }
            finishCoreLoad()
        }

        dataExecutor.execute {
            val readmeDetails = runCatching {
                api.getReadmeDetails(owner, repo, repository.defaultBranch)
            }.getOrNull()
            mainHandler.post {
                readmeDetails?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                    repository = repository.copy(readmeSummary = summary)
                    detailDescription.text = summary
                }
                bindReadme(readmeDetails)
                bindScreenshots(readmeDetails?.imageUrls.orEmpty())
            }
        }
    }

    private fun bindStat(
        rootId: Int,
        iconRes: Int,
        value: String,
        label: String,
    ) {
        val root = findViewById<View>(rootId)
        root.findViewById<ImageView>(R.id.statIcon).setImageResource(iconRes)
        root.findViewById<TextView>(R.id.statValue).text = value
        root.findViewById<TextView>(R.id.statLabel).text = label
    }

    private fun bindTags(tags: List<String>) {
        tagsGroup.removeAllViews()
        val inflater = LayoutInflater.from(this)
        tags.take(10).forEach { tag ->
            val view = inflater.inflate(R.layout.item_tag_chip, tagsGroup, false) as TextView
            view.text = if (tag.startsWith("#")) tag else "#$tag"
            tagsGroup.addView(view)
        }
    }

    private fun bindScreenshots(imageUrls: List<String>) {
        currentScreenshotUrls = imageUrls
        screenshotsSection.visibility = if (imageUrls.isEmpty()) View.GONE else View.VISIBLE
        screenshotAdapter.submitList(imageUrls)
    }

    private fun bindReadme(details: ReadmeDetails?) {
        currentReadmeText = details?.expandedText?.takeIf { it.isNotBlank() }
        isReadmeExpanded = false
        readmeSection.visibility = if (currentReadmeText == null) View.GONE else View.VISIBLE
        updateReadmeText()
    }

    private fun openScreenshotViewer(selectedIndex: Int) {
        if (currentScreenshotUrls.isEmpty()) return

        startActivity(
            Intent(this, ScreenshotViewerActivity::class.java).apply {
                putStringArrayListExtra(
                    ScreenshotViewerActivity.EXTRA_IMAGE_URLS,
                    ArrayList(currentScreenshotUrls),
                )
                putExtra(ScreenshotViewerActivity.EXTRA_SELECTED_INDEX, selectedIndex)
                putExtra(ScreenshotViewerActivity.EXTRA_TITLE, repository.name)
            },
        )
    }

    private fun updateReadmeText() {
        val readmeText = currentReadmeText.orEmpty()
        if (readmeText.isBlank()) {
            readmeExpandButton.visibility = View.GONE
            readmeExpandedText.text = ""
            return
        }

        val shouldCollapse = !isReadmeExpanded && readmeText.length > README_PREVIEW_CHAR_LIMIT
        readmeExpandedText.text =
            if (shouldCollapse) {
                readmeText.take(README_PREVIEW_CHAR_LIMIT).trimEnd() + "..."
            } else {
                readmeText
            }
        readmeExpandButton.visibility =
            if (readmeText.length > README_PREVIEW_CHAR_LIMIT) View.VISIBLE else View.GONE
        readmeExpandButton.text =
            getString(if (isReadmeExpanded) R.string.readme_collapse else R.string.readme_expand)
    }

    private fun bindRelease(
        release: StoreRelease?,
        isLoading: Boolean = false,
    ) {
        if (isLoading) {
            releaseVersionText.text = getString(R.string.release_loading)
            releaseBulletsText.text = getString(R.string.details_loading_releases)
            releaseNotesText.text = ""
            releaseNotesText.visibility = View.GONE
            return
        }

        val releaseTag = release?.tagName.orEmpty()
        releaseVersionText.text =
            releaseTag.takeIf { it.isNotBlank() } ?: getString(R.string.release_unavailable)
        val lines = release?.body.toReleaseLines()
        releaseBulletsText.text =
            if (lines.isEmpty()) {
                getString(R.string.release_empty)
            } else {
                lines.take(8).joinToString("\n") { "- $it" }
            }
        releaseNotesText.text =
            release?.name
                ?.takeIf { it.isNotBlank() && it != releaseTag }
                ?.let { getString(R.string.release_name_format, it) }
                ?: ""
        releaseNotesText.visibility = if (releaseNotesText.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun bindContributors(
        contributors: List<StoreContributor>,
        isLoading: Boolean = false,
    ) {
        contributorsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        if (isLoading) {
            contributorsContainer.addView(createInlineLoadingView(getString(R.string.details_loading_contributors)))
            return
        }

        if (contributors.isEmpty()) {
            val status = TextView(this).apply {
                text = getString(R.string.no_contributors)
                setTextColor(ThemeManager.resolveColor(this@DetailActivity, R.attr.themeTextSecondary))
                textSize = 10f
                setPadding(3, 3, 3, 3)
            }
            contributorsContainer.addView(status)
            return
        }

        contributors.forEach { contributor ->
            val item = inflater.inflate(R.layout.item_contributor, contributorsContainer, false)
            item.findViewById<TextView>(R.id.contributorLogin).text = contributor.login
            item.findViewById<TextView>(R.id.contributorMeta).text =
                getString(R.string.contributor_commits, contributor.contributions)
            ImageLoader.load(
                item.findViewById(R.id.contributorAvatar),
                contributor.avatarUrl,
                imageExecutor,
                R.drawable.ic_logo_git,
            )
            item.setOnClickListener { openUrl(contributor.htmlUrl) }
            contributorsContainer.addView(item)
        }
    }

    private fun bindApks(
        apks: List<ApkOption>,
        isLoading: Boolean = false,
    ) {
        apksContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        if (isLoading) {
            apksContainer.addView(createInlineLoadingView(getString(R.string.loading_apks)))
            return
        }

        if (apks.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.no_apks)
                setTextColor(ThemeManager.resolveColor(this@DetailActivity, R.attr.themeTextSecondary))
                textSize = 13f
                setPadding(3, 3, 3, 3)
            }
            apksContainer.addView(empty)
            return
        }

        apks.forEach { option ->
            val apk = option.asset
            val localFile = localApkFile(apk)
            val apkDetails = localFile.takeIf { it.exists() }?.let(::inspectApkFile)
            val item = inflater.inflate(R.layout.item_apk, apksContainer, false)
            item.findViewById<TextView>(R.id.apkName).text = apk.name
            item.findViewById<TextView>(R.id.apkVersion).text =
                option.releaseTag.takeIf { it.isNotBlank() } ?: "APK"
            item.findViewById<TextView>(R.id.apkMeta).text =
                "${apk.size.formatBytes()} - Downloads ${apk.downloadCount.formatCount()}"
            item.findViewById<View>(R.id.apkDownloadButton).setOnClickListener { trackAndDownloadApk(apk) }

            val installButton = item.findViewById<View>(R.id.apkInstallButton)
            val statusText = item.findViewById<TextView>(R.id.apkStatusText)
            val permissionsText = item.findViewById<TextView>(R.id.apkPermissionsText)
            if (apkDetails == null) {
                installButton.visibility = View.GONE
                statusText.visibility = View.GONE
                permissionsText.text = getString(R.string.apk_permissions_hint)
            } else {
                installButton.visibility = View.VISIBLE
                statusText.visibility = View.VISIBLE
                statusText.text = getString(R.string.apk_status_downloaded)
                permissionsText.text =
                    apkDetails.permissions
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString("\n") { "- $it" }
                        ?: getString(R.string.apk_permissions_none)
                installButton.setOnClickListener { installDownloadedApk(apk) }
            }

            item.setOnClickListener {
                if (apkDetails != null) {
                    installDownloadedApk(apk)
                } else {
                    trackAndDownloadApk(apk)
                }
            }
            apksContainer.addView(item)
        }
    }

    private fun setupActions() {
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.favoriteButton).setOnClickListener { toggleFavorite() }
        findViewById<View>(R.id.shareButton).setOnClickListener { shareDetail() }
        findViewById<View>(R.id.moreButton).setOnClickListener { showMoreMenu() }
        findViewById<View>(R.id.githubButton).setOnClickListener { openUrl(repository.htmlUrl) }
        readmeExpandButton.setOnClickListener {
            isReadmeExpanded = !isReadmeExpanded
            updateReadmeText()
        }
    }

    private fun toggleFavorite() {
        val added = favoritesStore.toggle(repository)
        bindFavorite()
        Toast.makeText(
            this,
            if (added) "Projeto adicionado aos favoritos" else "Projeto removido dos favoritos",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun bindFavorite() {
        val color =
            if (favoritesStore.isFavorite(repository.fullName)) {
                ThemeManager.resolveColor(this, R.attr.themePrimary)
            } else {
                ThemeManager.resolveColor(this, R.attr.themeIcon)
            }
        favoriteIcon.imageTintList = ColorStateList.valueOf(color)
    }

    private fun shareDetail() {
        val shareIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, getString(R.string.details_share_text, repository.htmlUrl))
            }
        startActivity(Intent.createChooser(shareIntent, repository.name))
    }

    private fun showMoreMenu() {
        val firstApk = latestApks.firstOrNull()?.asset
        val thirdOption = if (firstApk != null && localApkFile(firstApk).exists()) "Instalar APK" else "Baixar APK"
        MaterialAlertDialogBuilder(this)
            .setTitle(repository.name)
            .setItems(arrayOf("Abrir repositorio", "Compartilhar", thirdOption)) { _, which ->
                when (which) {
                    0 -> openUrl(repository.htmlUrl)
                    1 -> shareDetail()
                    2 -> latestApks.firstOrNull()?.let {
                        if (localApkFile(it.asset).exists()) {
                            installDownloadedApk(it.asset)
                        } else {
                            trackAndDownloadApk(it.asset)
                        }
                    }
                        ?: Toast.makeText(this, R.string.no_apks, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun trackAndDownloadApk(apk: StoreAsset) {
        AnalyticsManager.logApkDownload(this, repository.fullName, apk.name)
        downloadApk(apk)
    }

    private fun downloadApk(apk: StoreAsset) {
        runCatching {
            val request =
                DownloadManager.Request(Uri.parse(apk.downloadUrl))
                    .setTitle(apk.name)
                    .setDescription(repository.fullName)
                    .setMimeType("application/vnd.android.package-archive")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(
                        this,
                        Environment.DIRECTORY_DOWNLOADS,
                        apk.name.toSafeApkFileName(),
                    )
            getSystemService(DownloadManager::class.java).enqueue(request)
        }.onSuccess { downloadId ->
            observedDownloadIds[downloadId] = apk.name
            Toast.makeText(this, getString(R.string.download_started, apk.name), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun installDownloadedApk(apk: StoreAsset) {
        val file = localApkFile(apk)
        if (!file.exists()) {
            Toast.makeText(this, R.string.install_missing, Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
            Toast.makeText(this, R.string.install_permission_required, Toast.LENGTH_SHORT).show()
            return
        }

        runCatching {
            val apkUri =
                FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file,
                )
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, APK_MIME_TYPE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
        }.onFailure {
            Toast.makeText(this, R.string.install_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun localApkFile(apk: StoreAsset): File =
        File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(filesDir, "downloads"),
            apk.name.toSafeApkFileName(),
        )

    private fun inspectApkFile(file: File): ApkLocalDetails? {
        val packageInfo = file.toPackageArchiveInfo() ?: return null
        val permissions =
            packageInfo.requestedPermissions
                .orEmpty()
                .mapNotNull(::resolvePermissionLabel)
                .distinct()
                .take(MAX_PERMISSION_LINES)

        return ApkLocalDetails(
            permissions = permissions,
        )
    }

    private fun File.toPackageArchiveInfo(): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(absolutePath, PackageManager.GET_PERMISSIONS)
        }

    private fun resolvePermissionLabel(permissionName: String): String? {
        val permissionInfo =
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPermissionInfo(permissionName, 0)
            }.getOrNull()

        if (permissionInfo == null) {
            return permissionName.substringAfterLast('.').replace('_', ' ')
        }

        val protectionLevel =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                permissionInfo.protection
            } else {
                @Suppress("DEPRECATION")
                permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
            }
        if (protectionLevel != PermissionInfo.PROTECTION_DANGEROUS &&
            protectionLevel != PermissionInfo.PROTECTION_NORMAL
        ) {
            return null
        }

        return permissionInfo.loadLabel(packageManager)
            .toString()
            .ifBlank { permissionName.substringAfterLast('.').replace('_', ' ') }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun String?.toDisplayDateOrFallback(): String {
        val value = this?.takeIf { it.isNotBlank() } ?: return "sem data"
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm 'UTC'", Locale.forLanguageTag("pt-BR")).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return runCatching { formatter.format(parser.parse(value)!!) }.getOrDefault(value.take(10))
    }

    private fun String?.toReleaseLines(): List<String> =
        orEmpty()
            .lines()
            .map { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^[-*+]\\s+"), "")
                    .replace(Regex("[*_`~]"), "")
            }
            .filter { it.length in 3..140 }

    private fun StoreAsset.isApk(): Boolean =
        name.endsWith(".apk", ignoreCase = true) ||
            contentType.equals("application/vnd.android.package-archive", ignoreCase = true)

    private fun showDetailLoading(
        visible: Boolean,
        message: String = getString(R.string.details_loading_data),
    ) {
        detailLoadingStatus.text = message
        detailLoadingSubtitle.text = getString(R.string.details_loading_releases)
        detailLoadingRow.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun createInlineLoadingView(message: String): View {
        val view = LayoutInflater.from(this).inflate(R.layout.view_inline_loading, null, false)
        view.findViewById<TextView>(R.id.inlineLoadingText).text = message
        return view
    }

    private fun Long.formatCount(): String =
        when {
            this >= 1_000_000 -> "%.1fM".format(Locale.US, this / 1_000_000.0)
            this >= 1_000 -> "%,d".format(Locale.US, this).replace(",", ".")
            else -> toString()
        }

    private fun Long.formatBytes(): String =
        when {
            this >= 1_000_000 -> "%.1f MB".format(Locale.US, this / 1_000_000.0)
            this >= 1_000 -> "%.1f KB".format(Locale.US, this / 1_000.0)
            else -> "$this B"
        }

    private fun String.toSafeApkFileName(): String {
        val safeName = replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "download.apk" }
        return if (safeName.endsWith(".apk", ignoreCase = true)) safeName else "$safeName.apk"
    }

    private data class ApkLocalDetails(
        val permissions: List<String>,
    )

    companion object {
        private const val README_PREVIEW_CHAR_LIMIT = 820
        private const val MAX_PERMISSION_LINES = 6
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val SCREENSHOT_GRID_SPAN_COUNT = 2
        const val EXTRA_ID = "extra_id"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_FULL_NAME = "extra_full_name"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_HTML_URL = "extra_html_url"
        const val EXTRA_STARS = "extra_stars"
        const val EXTRA_FORKS = "extra_forks"
        const val EXTRA_LANGUAGE = "extra_language"
        const val EXTRA_RELEASE_DATE = "extra_release_date"
        const val EXTRA_UPDATED_AT = "extra_updated_at"
        const val EXTRA_DOWNLOADS = "extra_downloads"
        const val EXTRA_OWNER = "extra_owner"
        const val EXTRA_AVATAR_URL = "extra_avatar_url"
        const val EXTRA_IMAGE_URL = "extra_image_url"
        const val EXTRA_DEFAULT_BRANCH = "extra_default_branch"
        const val EXTRA_TOPICS = "extra_topics"
    }
}
