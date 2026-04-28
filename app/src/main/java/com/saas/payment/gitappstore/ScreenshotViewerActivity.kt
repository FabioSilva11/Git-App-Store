package com.saas.payment.gitappstore

import android.os.Bundle
import android.view.ViewGroup
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScreenshotViewerActivity : AppCompatActivity() {
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var viewerRoot: View
    private lateinit var titleText: TextView
    private lateinit var counterText: TextView
    private lateinit var viewerPager: ViewPager2
    private lateinit var previousButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var pagerAdapter: ImagePagerAdapter

    private var imageUrls: List<String> = emptyList()
    private var currentIndex = 0
    private val pageChangeCallback =
        object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentIndex = position
                updateControls()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_screenshot_viewer)
        bindViews()
        applySystemBarsPadding()
        bindIntentData()
        if (imageUrls.isEmpty()) return
        setupPager()
        setupActions()
        updateControls()
    }

    override fun onDestroy() {
        if (::viewerPager.isInitialized) {
            viewerPager.unregisterOnPageChangeCallback(pageChangeCallback)
        }
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        viewerRoot = findViewById(R.id.viewerRoot)
        titleText = findViewById(R.id.viewerTitleText)
        counterText = findViewById(R.id.viewerCounterText)
        viewerPager = findViewById(R.id.viewerPager)
        previousButton = findViewById(R.id.viewerPreviousButton)
        nextButton = findViewById(R.id.viewerNextButton)
    }

    private fun applySystemBarsPadding() {
        val initialLeft = viewerRoot.paddingLeft
        val initialTop = viewerRoot.paddingTop
        val initialRight = viewerRoot.paddingRight
        val initialBottom = viewerRoot.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(viewerRoot) { view, insets ->
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

    private fun bindIntentData() {
        imageUrls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS).orEmpty().filter { it.isNotBlank() }
        currentIndex = intent.getIntExtra(EXTRA_SELECTED_INDEX, 0).coerceIn(0, (imageUrls.size - 1).coerceAtLeast(0))
        titleText.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
            getString(R.string.screenshot_viewer_title)
        }
        if (imageUrls.isEmpty()) finish()
    }

    private fun setupPager() {
        pagerAdapter = ImagePagerAdapter(imageExecutor)
        viewerPager.adapter = pagerAdapter
        viewerPager.offscreenPageLimit = 1
        pagerAdapter.submitList(imageUrls)
        viewerPager.setCurrentItem(currentIndex, false)
        viewerPager.registerOnPageChangeCallback(pageChangeCallback)
    }

    private fun setupActions() {
        findViewById<View>(R.id.viewerBackButton).setOnClickListener { finish() }
        previousButton.setOnClickListener {
            if (viewerPager.currentItem > 0) {
                viewerPager.setCurrentItem(viewerPager.currentItem - 1, true)
            }
        }
        nextButton.setOnClickListener {
            if (viewerPager.currentItem < imageUrls.lastIndex) {
                viewerPager.setCurrentItem(viewerPager.currentItem + 1, true)
            }
        }
    }

    private fun updateControls() {
        counterText.text = getString(R.string.screenshot_counter, currentIndex + 1, imageUrls.size)
        previousButton.isEnabled = currentIndex > 0
        previousButton.alpha = if (previousButton.isEnabled) 1f else 0.4f
        nextButton.isEnabled = currentIndex < imageUrls.lastIndex
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.4f
    }

    private class ImagePagerAdapter(
        private val imageExecutor: ExecutorService,
    ) : RecyclerView.Adapter<ImagePagerAdapter.ImageViewHolder>() {
        private val items = mutableListOf<String>()

        fun submitList(imageUrls: List<String>) {
            items.clear()
            items.addAll(imageUrls)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): ImageViewHolder {
            val imageView =
                ImageView(parent.context).apply {
                    layoutParams =
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    adjustViewBounds = true
                    contentDescription = parent.context.getString(R.string.screenshot_viewer_title)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            return ImageViewHolder(imageView, imageExecutor)
        }

        override fun onBindViewHolder(
            holder: ImageViewHolder,
            position: Int,
        ) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        private class ImageViewHolder(
            private val imageView: ImageView,
            private val imageExecutor: ExecutorService,
        ) : RecyclerView.ViewHolder(imageView) {
            fun bind(imageUrl: String) {
                ImageLoader.load(
                    imageView,
                    imageUrl,
                    imageExecutor,
                    android.R.color.transparent,
                )
            }
        }
    }

    companion object {
        const val EXTRA_IMAGE_URLS = "extra_image_urls"
        const val EXTRA_SELECTED_INDEX = "extra_selected_index"
        const val EXTRA_TITLE = "extra_title"
    }
}
