package com.saas.payment.gitappstore

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.ExecutorService

class ScreenshotGridAdapter(
    private val imageExecutor: ExecutorService,
    private val onScreenshotClick: (index: Int) -> Unit,
) : RecyclerView.Adapter<ScreenshotGridAdapter.ScreenshotViewHolder>() {
    private val imageUrls = mutableListOf<String>()

    fun submitList(items: List<String>) {
        imageUrls.clear()
        imageUrls.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ScreenshotViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_screenshot, parent, false)
        return ScreenshotViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: ScreenshotViewHolder,
        position: Int,
    ) {
        holder.bind(imageUrls[position], position)
    }

    override fun getItemCount(): Int = imageUrls.size

    inner class ScreenshotViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val screenshotImage: ImageView = itemView.findViewById(R.id.screenshotImage)

        fun bind(
            imageUrl: String,
            position: Int,
        ) {
            ImageLoader.load(
                screenshotImage,
                imageUrl,
                imageExecutor,
                android.R.color.transparent,
            )
            itemView.setOnClickListener { onScreenshotClick(position) }
        }
    }
}
