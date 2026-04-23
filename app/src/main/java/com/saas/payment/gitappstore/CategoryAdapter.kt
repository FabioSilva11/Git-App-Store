package com.saas.payment.gitappstore

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.saas.payment.gitappstore.data.CategoryItem

class CategoryAdapter(
    private val onCategorySelected: (CategoryItem) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {
    private val categories = mutableListOf<CategoryItem>()
    private var selectedIndex = 0

    fun submitList(items: List<CategoryItem>) {
        categories.clear()
        categories.addAll(items)
        selectedIndex = 0
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): CategoryViewHolder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_category_chip, parent, false) as ViewGroup
        return CategoryViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: CategoryViewHolder,
        position: Int,
    ) {
        holder.bind(categories[position], position == selectedIndex)
    }

    override fun getItemCount(): Int = categories.size

    inner class CategoryViewHolder(
        private val root: ViewGroup,
    ) : RecyclerView.ViewHolder(root) {
        private val icon: ImageView = root.findViewById(R.id.categoryIcon)
        private val title: TextView = root.findViewById(R.id.categoryTitle)

        fun bind(
            item: CategoryItem,
            selected: Boolean,
        ) {
            val context = root.context
            val textColor =
                if (selected) {
                    ThemeManager.resolveColor(context, R.attr.themeOnPrimary)
                } else {
                    ThemeManager.resolveColor(context, R.attr.themeTextPrimary)
                }
            val iconTint =
                if (selected) {
                    ThemeManager.resolveColor(context, R.attr.themeOnPrimary)
                } else {
                    ThemeManager.resolveColor(context, R.attr.themeIcon)
                }

            root.background =
                androidx.core.content.ContextCompat.getDrawable(
                    context,
                    if (selected) R.drawable.bg_category_chip_active else R.drawable.bg_category_chip_inactive,
                )
            title.text = item.title
            title.setTextColor(textColor)
            icon.setImageResource(item.iconRes)
            icon.imageTintList = ColorStateList.valueOf(iconTint)
            root.setOnClickListener {
                val oldIndex = selectedIndex
                selectedIndex = bindingAdapterPosition
                notifyItemChanged(oldIndex)
                notifyItemChanged(selectedIndex)
                onCategorySelected(item)
            }
        }
    }
}
