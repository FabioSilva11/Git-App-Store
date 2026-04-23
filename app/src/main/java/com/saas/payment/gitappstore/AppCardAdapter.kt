package com.saas.payment.gitappstore

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.saas.payment.gitappstore.data.StoreRepository
import java.util.Locale
import java.util.concurrent.ExecutorService

class AppCardAdapter(
    private val imageExecutor: ExecutorService,
    private val isFavorite: (String) -> Boolean,
    private val onFavoriteClick: (StoreRepository) -> Unit,
    private val onRepositoryClick: (StoreRepository) -> Unit,
    private val onDetailsClick: (StoreRepository) -> Unit,
    private val onCardClick: (StoreRepository) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val apps = mutableListOf<StoreRepository>()
    private var showLoadingFooter = false
    private var showRemoveFavoriteAction = false

    fun submitList(items: List<StoreRepository>) {
        apps.clear()
        apps.addAll(items)
        notifyDataSetChanged()
    }

    fun setRemoveFavoriteActionVisible(visible: Boolean) {
        if (showRemoveFavoriteAction == visible) return
        showRemoveFavoriteAction = visible
        notifyItemRangeChanged(0, apps.size)
    }

    fun setLoadingFooterVisible(visible: Boolean) {
        if (showLoadingFooter == visible) return
        showLoadingFooter = visible
        if (visible) {
            notifyItemInserted(apps.size)
        } else {
            notifyItemRemoved(apps.size)
        }
    }

    fun refreshFavorite(fullName: String) {
        val index = apps.indexOfFirst { it.fullName == fullName }
        if (index >= 0) notifyItemChanged(index)
    }

    val appItemCount: Int
        get() = apps.size

    override fun getItemViewType(position: Int): Int =
        if (position < apps.size) VIEW_TYPE_APP else VIEW_TYPE_LOADING

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val layout =
            if (viewType == VIEW_TYPE_LOADING) {
                R.layout.item_loading_footer
            } else {
                R.layout.item_app_card
            }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return if (viewType == VIEW_TYPE_LOADING) LoadingViewHolder(view) else AppViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        if (holder is AppViewHolder) {
            holder.bind(apps[position])
        }
    }

    override fun getItemCount(): Int = apps.size + if (showLoadingFooter) 1 else 0

    class LoadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.appIcon)
        private val favoriteButton: ImageView = itemView.findViewById(R.id.favoriteCardButton)
        private val appName: TextView = itemView.findViewById(R.id.appName)
        private val appRepository: TextView = itemView.findViewById(R.id.appRepository)
        private val appStars: TextView = itemView.findViewById(R.id.appStars)
        private val appDescription: TextView = itemView.findViewById(R.id.appDescription)
        private val appForks: TextView = itemView.findViewById(R.id.appForks)
        private val appDownloads: TextView = itemView.findViewById(R.id.appDownloads)
        private val appLanguage: TextView = itemView.findViewById(R.id.appLanguage)
        private val tagsContainer: ViewGroup = itemView.findViewById(R.id.tagsContainer)
        private val repositoryButton: TextView = itemView.findViewById(R.id.repositoryButton)
        private val detailsButton: TextView = itemView.findViewById(R.id.detailsButton)

        fun bind(item: StoreRepository) {
            ImageLoader.load(
                appIcon,
                item.imageUrl ?: item.owner.avatarUrl,
                imageExecutor,
                android.R.color.transparent,
            )
            appName.text = item.name.ifBlank { item.fullName.substringAfter("/") }
            appRepository.text = item.fullName
            appDescription.text =
                item.readmeSummary ?: item.description ?: "README ainda nao carregado para este repositorio."
            appStars.text = "Stars ${item.stars.toLong().formatCount()}"
            appForks.text = "Forks ${item.forks.toLong().formatCount()}"
            appDownloads.text = "Downloads ${item.downloadCount.formatCount()}"
            appLanguage.text = item.language.orEmpty().ifBlank { "N/D" }
            bindTags(item.topics.take(4))
            bindFavoriteAction(item.fullName)

            repositoryButton.setOnClickListener { onRepositoryClick(item) }
            detailsButton.setOnClickListener { onDetailsClick(item) }
            favoriteButton.setOnClickListener {
                onFavoriteClick(item)
                bindFavoriteAction(item.fullName)
            }
            itemView.setOnClickListener { onCardClick(item) }
        }

        private fun bindTags(tags: List<String>) {
            for (index in 0 until tagsContainer.childCount) {
                val tagView = tagsContainer.getChildAt(index) as TextView
                val tag = tags.getOrNull(index)
                tagView.visibility = if (tag == null) View.GONE else View.VISIBLE
                tagView.text = tag?.let { "#$it" }.orEmpty()
            }
        }

        private fun bindFavoriteAction(fullName: String) {
            if (showRemoveFavoriteAction) {
                favoriteButton.setImageResource(R.drawable.ic_trash)
                favoriteButton.imageTintList =
                    ColorStateList.valueOf(ThemeManager.resolveColor(itemView.context, R.attr.themeDanger))
                favoriteButton.contentDescription = "Remover dos favoritos"
                return
            }

            favoriteButton.setImageResource(R.drawable.ic_bookmark)
            val color =
                if (isFavorite(fullName)) {
                    ThemeManager.resolveColor(itemView.context, R.attr.themePrimary)
                } else {
                    ThemeManager.resolveColor(itemView.context, R.attr.themeTextSecondary)
                }
            favoriteButton.imageTintList = ColorStateList.valueOf(color)
            favoriteButton.contentDescription = "Adicionar aos favoritos"
        }
    }

    private companion object {
        const val VIEW_TYPE_APP = 0
        const val VIEW_TYPE_LOADING = 1
    }
}

private fun Long.formatCount(): String =
    when {
        this >= 1_000_000 -> "%.1fM".format(Locale.US, this / 1_000_000.0)
        this >= 1_000 -> "%,d".format(Locale.US, this).replace(",", ".")
        else -> toString()
    }
