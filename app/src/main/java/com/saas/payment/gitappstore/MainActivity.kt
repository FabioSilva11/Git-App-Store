package com.saas.payment.gitappstore

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.saas.payment.gitappstore.data.CategoryItem
import com.saas.payment.gitappstore.data.FavoritesStore
import com.saas.payment.gitappstore.data.GitHubStoreApi
import com.saas.payment.gitappstore.data.SearchPage
import com.saas.payment.gitappstore.data.StoreApi
import com.saas.payment.gitappstore.data.StoreFeed
import com.saas.payment.gitappstore.data.StoreRepository
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val api: StoreApi = GitHubStoreApi()
    private val dataExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val imageExecutor: ExecutorService = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val feedCache = mutableMapOf<StoreFeed, List<StoreRepository>>()
    private val readmeCache = mutableMapOf<String, String?>()
    private val visibleApps = mutableListOf<StoreRepository>()

    private lateinit var favoritesStore: FavoritesStore
    private lateinit var searchInput: EditText
    private lateinit var homeContent: View
    private lateinit var categoryRecyclerView: RecyclerView
    private lateinit var appsRecyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var feedStatusText: TextView
    private lateinit var searchLoadingView: View
    private lateinit var searchLoadingTitle: TextView
    private lateinit var searchLoadingSubtitle: TextView
    private lateinit var bottomNavigation: LinearLayout
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var appAdapter: AppCardAdapter

    private var selectedFeed = StoreFeed.TRENDING
    private var selectedSection = HomeSection.HOME
    private var currentQuery = ""
    private var currentSource = emptyList<StoreRepository>()
    private var offset = 0
    private var totalHits = 0
    private var isLoading = false
    private var hasMore = true
    private var requestVersion = 0
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        favoritesStore = FavoritesStore(this)
        applySystemBarsPadding()
        bindViews()
        applyResponsiveWidths()
        setupCategories()
        setupApps()
        setupSearch()
        setupBottomNavigation()
        resetAndLoad()
        setupFavoriteUpdateNotifications()
    }

    override fun onDestroy() {
        pendingSearch?.let(mainHandler::removeCallbacks)
        dataExecutor.shutdownNow()
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            FavoriteUpdateChecker.checkNow(this)
        }
    }

    private fun setupFavoriteUpdateNotifications() {
        FavoriteUpdateChecker.createNotificationChannel(this)
        FavoriteUpdateChecker.schedule(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
            return
        }
        FavoriteUpdateChecker.checkNow(this)
    }

    private fun applySystemBarsPadding() {
        val mainView = findViewById<View>(R.id.main)
        val initialLeft = mainView.paddingLeft
        val initialTop = mainView.paddingTop
        val initialRight = mainView.paddingRight
        val initialBottom = mainView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(mainView) { view, insets ->
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
        homeContent = findViewById(R.id.homeContent)
        searchInput = findViewById(R.id.searchInput)
        categoryRecyclerView = findViewById(R.id.categoryRecyclerView)
        appsRecyclerView = findViewById(R.id.appsRecyclerView)
        emptyStateText = findViewById(R.id.emptyStateText)
        feedStatusText = findViewById(R.id.feedStatusText)
        searchLoadingView = findViewById(R.id.searchLoadingView)
        searchLoadingTitle = searchLoadingView.findViewById(R.id.loadingTitle)
        searchLoadingSubtitle = searchLoadingView.findViewById(R.id.loadingSubtitle)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            showSettingsDialog()
        }
        findViewById<View>(R.id.filterButton).setOnClickListener {
            if (searchInput.text?.isNotBlank() == true) {
                searchInput.setText("")
            } else {
                categoryRecyclerView.smoothScrollToPosition(0)
            }
        }
    }

    private fun applyResponsiveWidths() {
        val screenWidth = resources.displayMetrics.widthPixels
        val maxWidth = resources.getDimensionPixelSize(R.dimen.home_max_content_width)
        val targetWidth = min(screenWidth, maxWidth)
        val useCenteredWidth = screenWidth > maxWidth
        listOf(homeContent, bottomNavigation).forEach { view ->
            val params = view.layoutParams as LinearLayout.LayoutParams
            params.width = if (useCenteredWidth) targetWidth else LinearLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.CENTER_HORIZONTAL
            view.layoutParams = params
        }
    }

    private fun setupCategories() {
        categoryAdapter =
            CategoryAdapter { category ->
                selectedSection = HomeSection.HOME
                selectNavItem(R.id.navHome)
                val categoryQuery = category.id.removePrefix(CATEGORY_QUERY_PREFIX).takeIf {
                    category.id.startsWith(CATEGORY_QUERY_PREFIX)
                }
                if (categoryQuery == null) {
                    selectedFeed = category.id.toStoreFeed()
                    currentQuery = ""
                    if (searchInput.text?.isNotBlank() == true) searchInput.setText("")
                } else {
                    currentQuery = categoryQuery
                    if (searchInput.text?.toString() != categoryQuery) searchInput.setText(categoryQuery)
                }
                resetAndLoad()
            }
        categoryRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        categoryRecyclerView.adapter = categoryAdapter
        categoryAdapter.submitList(
            listOf(
                CategoryItem(StoreFeed.TRENDING.name, StoreFeed.TRENDING.title, R.drawable.ic_trending),
                CategoryItem(StoreFeed.NEW_RELEASES.name, getString(R.string.category_launches), R.drawable.ic_rocket),
                CategoryItem(StoreFeed.MOST_POPULAR.name, StoreFeed.MOST_POPULAR.title, R.drawable.ic_flame),
                CategoryItem(StoreFeed.PRIVACY.name, StoreFeed.PRIVACY.title, R.drawable.ic_shield),
                CategoryItem("${CATEGORY_QUERY_PREFIX}kotlin", "Kotlin", R.drawable.ic_code),
                CategoryItem("${CATEGORY_QUERY_PREFIX}java", "Java", R.drawable.ic_code),
                CategoryItem("${CATEGORY_QUERY_PREFIX}lowcode", "Low-code", R.drawable.ic_rocket),
                CategoryItem("${CATEGORY_QUERY_PREFIX}ferramentas", "Ferramentas", R.drawable.ic_settings),
                CategoryItem("${CATEGORY_QUERY_PREFIX}jogos", "Jogos", R.drawable.ic_flame),
            ),
        )
    }

    private fun setupApps() {
        appAdapter =
            AppCardAdapter(
                imageExecutor = imageExecutor,
                isFavorite = favoritesStore::isFavorite,
                onFavoriteClick = ::toggleFavorite,
                onRepositoryClick = ::onMainRepositoryClick,
                onDetailsClick = ::onMainDetailsClick,
                onCardClick = ::openDetails,
            )
        appsRecyclerView.layoutManager = LinearLayoutManager(this)
        appsRecyclerView.adapter = appAdapter
        appsRecyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    if (dy <= 0) return
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (layoutManager.findLastVisibleItemPosition() >= appAdapter.appItemCount - LOAD_MORE_THRESHOLD) {
                        loadNextPage()
                    }
                }
            },
        )
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    scheduleSearch(s?.toString().orEmpty().trim())
                }
            },
        )
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                pendingSearch?.let(mainHandler::removeCallbacks)
                applySearch(searchInput.text?.toString().orEmpty().trim())
                true
            } else {
                false
            }
        }
    }

    private fun setupBottomNavigation() {
        findViewById<View>(R.id.navHome).setOnClickListener {
            selectedSection = HomeSection.HOME
            selectNavItem(R.id.navHome)
            resetAndLoad()
        }
        findViewById<View>(R.id.navExplore).setOnClickListener {
            selectedSection = HomeSection.EXPLORE
            selectedFeed = StoreFeed.MOST_POPULAR
            selectNavItem(R.id.navExplore)
            resetAndLoad()
        }
        findViewById<View>(R.id.navFavorites).setOnClickListener {
            selectedSection = HomeSection.FAVORITES
            selectNavItem(R.id.navFavorites)
            resetAndLoad()
        }
        findViewById<View>(R.id.navSettings).setOnClickListener {
            selectedSection = HomeSection.RECOMMENDED
            selectNavItem(R.id.navSettings)
            clearSearchWithoutExtraLoad()
            resetAndLoad()
        }
        selectNavItem(R.id.navHome)
    }

    private fun selectNavItem(selectedId: Int) {
        val active = ThemeManager.resolveColor(this, R.attr.themePrimary)
        val inactive = ThemeManager.resolveColor(this, R.attr.themeTextSecondary)
        listOf(R.id.navHome, R.id.navExplore, R.id.navFavorites, R.id.navSettings).forEach { itemId ->
            val item = findViewById<View>(itemId)
            val selected = itemId == selectedId
            val color = if (selected) active else inactive
            item.isSelected = selected
            item.findViewById<ImageView>(R.id.navItemIcon).imageTintList = ColorStateList.valueOf(color)
            item.findViewById<TextView>(R.id.navItemLabel).setTextColor(color)
        }
    }

    private fun scheduleSearch(query: String) {
        pendingSearch?.let(mainHandler::removeCallbacks)
        pendingSearch = Runnable { applySearch(query) }
        mainHandler.postDelayed(pendingSearch!!, SEARCH_DEBOUNCE_MS)
    }

    private fun applySearch(query: String) {
        if (query == currentQuery) return
        currentQuery = query
        resetAndLoad()
    }

    private fun resetAndLoad() {
        requestVersion += 1
        offset = 0
        totalHits = 0
        hasMore = true
        isLoading = false
        currentSource = emptyList()
        visibleApps.clear()
        appAdapter.setRemoveFavoriteActionVisible(selectedSection == HomeSection.FAVORITES)
        appAdapter.submitList(emptyList())
        appAdapter.setLoadingFooterVisible(false)
        searchLoadingView.visibility = View.GONE
        emptyStateText.visibility = View.GONE
        loadNextPage()
        appsRecyclerView.scrollToPosition(0)
    }

    private fun loadNextPage() {
        if (isLoading || !hasMore) return

        val version = requestVersion
        val query = currentQuery
        val firstPage = visibleApps.isEmpty()
        isLoading = true
        if (firstPage) {
            searchLoadingTitle.text =
                when {
                    selectedSection == HomeSection.RECOMMENDED -> getString(R.string.recommended_loading)
                    query.isNotBlank() -> getString(R.string.search_loading)
                    else -> getString(R.string.loading_repos)
                }
            searchLoadingSubtitle.text =
                if (selectedSection == HomeSection.RECOMMENDED) {
                    getString(R.string.recommended_subtitle)
                } else {
                    getString(R.string.loading_recent_first)
                }
        }
        searchLoadingView.visibility = if (firstPage) View.VISIBLE else View.GONE
        appAdapter.setLoadingFooterVisible(!firstPage)
        feedStatusText.text =
            if (firstPage && query.isNotBlank()) {
                getString(R.string.search_loading)
            } else {
                getString(R.string.home_loading_more_apps)
            }

        dataExecutor.execute {
            runCatching {
                when {
                    selectedSection == HomeSection.FAVORITES -> {
                        val favorites = favoritesStore.getAll().filter { it.matches(query) }.sortedByRecentUpdate()
                        currentSource = favorites
                        SearchPage(favorites.drop(offset).take(PAGE_SIZE), favorites.size)
                    }
                    selectedSection == HomeSection.RECOMMENDED -> api.getRecommendedRepositories(PAGE_SIZE, offset)
                    query.isNotBlank() -> api.searchAndroidRepositories(query, PAGE_SIZE, offset)
                    else -> {
                        val feed = feedForSection()
                        val allItems = feedCache[feed] ?: api.getFeed(feed).sortedByRecentUpdate().also { feedCache[feed] = it }
                        currentSource = allItems
                        SearchPage(allItems.drop(offset).take(PAGE_SIZE), allItems.size)
                    }
                }
            }.onSuccess { page ->
                mainHandler.post {
                    if (version == requestVersion) appendPage(page, firstPage)
                }
            }.onFailure { error ->
                mainHandler.post {
                    if (version == requestVersion) showError(error)
                }
            }
        }
    }

    private fun appendPage(
        page: SearchPage,
        firstPage: Boolean,
    ) {
        isLoading = false
        searchLoadingView.visibility = View.GONE
        appAdapter.setLoadingFooterVisible(false)
        val items = page.repositories
        totalHits = maxOf(page.totalHits, offset + items.size)
        offset += items.size
        hasMore = items.size >= PAGE_SIZE && (page.totalHits == 0 || offset < page.totalHits || page.totalHits <= visibleApps.size)
        visibleApps.addAll(items)
        appAdapter.submitList(visibleApps.toList())
        updateStatus()
        updateEmptyState()
        if (items.isNotEmpty()) enrichReadmes(items)
        if (firstPage && items.isEmpty()) hasMore = false
    }

    private fun enrichReadmes(repositories: List<StoreRepository>) {
        dataExecutor.execute {
            repositories.forEach { repository ->
                if (!readmeCache.containsKey(repository.fullName)) {
                    val owner = repository.fullName.substringBefore("/")
                    val repo = repository.fullName.substringAfter("/")
                    readmeCache[repository.fullName] =
                        api.getReadmeSummary(owner, repo, repository.defaultBranch)
                }
            }
            mainHandler.post {
                var changed = false
                for (index in visibleApps.indices) {
                    val repository = visibleApps[index]
                    val summary = readmeCache[repository.fullName]
                    if (!summary.isNullOrBlank() && repository.readmeSummary != summary) {
                        visibleApps[index] = repository.copy(readmeSummary = summary)
                        changed = true
                    }
                }
                if (changed) appAdapter.submitList(visibleApps.toList())
            }
        }
    }

    private fun updateStatus() {
        val total = if (selectedSection == HomeSection.FAVORITES) currentSource.size else totalHits
        feedStatusText.text = getString(R.string.home_showing_apps, visibleApps.size, total)
    }

    private fun updateEmptyState() {
        emptyStateText.text =
            if (selectedSection == HomeSection.FAVORITES) {
                getString(R.string.home_favorites_empty)
            } else if (selectedSection == HomeSection.RECOMMENDED) {
                getString(R.string.recommended_empty)
            } else {
                getString(R.string.empty_repos)
            }
        emptyStateText.visibility = if (!isLoading && visibleApps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showError(error: Throwable) {
        isLoading = false
        searchLoadingView.visibility = View.GONE
        appAdapter.setLoadingFooterVisible(false)
        feedStatusText.text = getString(R.string.load_error)
        emptyStateText.text = error.message.orEmpty().ifBlank { getString(R.string.load_error) }
        emptyStateText.visibility = View.VISIBLE
    }

    private fun toggleFavorite(repository: StoreRepository) {
        val favorite = favoritesStore.toggle(repository)
        appAdapter.refreshFavorite(repository.fullName)
        Toast.makeText(
            this,
            if (favorite) "Projeto adicionado aos favoritos" else "Projeto removido dos favoritos",
            Toast.LENGTH_SHORT,
        ).show()
        if (selectedSection == HomeSection.FAVORITES) resetAndLoad()
    }

    private fun onMainRepositoryClick(repository: StoreRepository) {
        AnalyticsManager.logMainButtonClick(this, "repository", repository.fullName)
        openUrl(repository.htmlUrl)
    }

    private fun onMainDetailsClick(repository: StoreRepository) {
        AnalyticsManager.logMainButtonClick(this, "details", repository.fullName)
        openDetails(repository)
    }

    private fun openDetails(repository: StoreRepository) {
        val intent =
            Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_NAME, repository.name)
                putExtra(DetailActivity.EXTRA_ID, repository.id)
                putExtra(DetailActivity.EXTRA_FULL_NAME, repository.fullName)
                putExtra(DetailActivity.EXTRA_DESCRIPTION, repository.readmeSummary ?: repository.description)
                putExtra(DetailActivity.EXTRA_HTML_URL, repository.htmlUrl)
                putExtra(DetailActivity.EXTRA_STARS, repository.stars)
                putExtra(DetailActivity.EXTRA_FORKS, repository.forks)
                putExtra(DetailActivity.EXTRA_LANGUAGE, repository.language)
                putExtra(DetailActivity.EXTRA_RELEASE_DATE, repository.latestReleaseDate)
                putExtra(DetailActivity.EXTRA_UPDATED_AT, repository.updatedAt)
                putExtra(DetailActivity.EXTRA_DOWNLOADS, repository.downloadCount)
                putExtra(DetailActivity.EXTRA_OWNER, repository.owner.login)
                putExtra(DetailActivity.EXTRA_AVATAR_URL, repository.owner.avatarUrl)
                putExtra(DetailActivity.EXTRA_IMAGE_URL, repository.imageUrl)
                putExtra(DetailActivity.EXTRA_DEFAULT_BRANCH, repository.defaultBranch)
                putStringArrayListExtra(DetailActivity.EXTRA_TOPICS, ArrayList(repository.topics))
            }
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showSettingsDialog() {
        val options = ThemeManager.options
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val current = ThemeManager.selectedOption(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setSingleChoiceItems(labels, options.indexOfFirst { it.key == current.key }.coerceAtLeast(0)) { dialog, which ->
                val selected = options[which]
                ThemeManager.saveSelection(this, selected)
                AppCompatDelegate.setDefaultNightMode(selected.nightMode)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearSearchWithoutExtraLoad() {
        pendingSearch?.let(mainHandler::removeCallbacks)
        pendingSearch = null
        currentQuery = ""
        if (searchInput.text?.isNotBlank() == true) {
            searchInput.setText("")
            pendingSearch?.let(mainHandler::removeCallbacks)
            pendingSearch = null
        }
    }

    private fun feedForSection(): StoreFeed =
        if (selectedSection == HomeSection.EXPLORE) StoreFeed.MOST_POPULAR else selectedFeed

    private fun String.toStoreFeed(): StoreFeed =
        runCatching { StoreFeed.valueOf(this) }.getOrDefault(StoreFeed.TRENDING)

    private fun StoreRepository.matches(query: String): Boolean {
        if (query.isBlank()) return true
        val normalized = query.lowercase(Locale.US)
        return name.lowercase(Locale.US).contains(normalized) ||
            fullName.lowercase(Locale.US).contains(normalized) ||
            description.orEmpty().lowercase(Locale.US).contains(normalized) ||
            readmeSummary.orEmpty().lowercase(Locale.US).contains(normalized) ||
            topics.any { it.lowercase(Locale.US).contains(normalized) } ||
            language.orEmpty().lowercase(Locale.US).contains(normalized)
    }

    private fun List<StoreRepository>.sortedByRecentUpdate(): List<StoreRepository> =
        sortedWith(
            compareByDescending<StoreRepository> { it.updatedAt.orEmpty() }
                .thenByDescending { it.latestReleaseDate.orEmpty() }
                .thenByDescending { it.stars },
        )

    private enum class HomeSection {
        HOME,
        EXPLORE,
        FAVORITES,
        RECOMMENDED,
    }

    private companion object {
        const val PAGE_SIZE = 12
        const val LOAD_MORE_THRESHOLD = 4
        const val CATEGORY_QUERY_PREFIX = "query:"
        const val SEARCH_DEBOUNCE_MS = 450L
        const val NOTIFICATION_PERMISSION_REQUEST = 4007
    }
}
