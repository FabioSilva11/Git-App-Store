package com.saas.payment.gitappstore

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScreenshotViewerActivity : AppCompatActivity() {
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private lateinit var viewerRoot: View
    private lateinit var titleText: TextView
    private lateinit var counterText: TextView
    private lateinit var screenshotImage: ImageView
    private lateinit var previousButton: TextView
    private lateinit var nextButton: TextView

    private var imageUrls: List<String> = emptyList()
    private var currentIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_screenshot_viewer)
        bindViews()
        applySystemBarsPadding()
        bindIntentData()
        if (imageUrls.isEmpty()) return
        setupActions()
        renderImage()
    }

    override fun onDestroy() {
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        viewerRoot = findViewById(R.id.viewerRoot)
        titleText = findViewById(R.id.viewerTitleText)
        counterText = findViewById(R.id.viewerCounterText)
        screenshotImage = findViewById(R.id.viewerImage)
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

    private fun setupActions() {
        findViewById<View>(R.id.viewerBackButton).setOnClickListener { finish() }
        previousButton.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex -= 1
                renderImage()
            }
        }
        nextButton.setOnClickListener {
            if (currentIndex < imageUrls.lastIndex) {
                currentIndex += 1
                renderImage()
            }
        }
    }

    private fun renderImage() {
        val imageUrl = imageUrls.getOrNull(currentIndex) ?: return
        counterText.text = getString(R.string.screenshot_counter, currentIndex + 1, imageUrls.size)
        previousButton.isEnabled = currentIndex > 0
        previousButton.alpha = if (previousButton.isEnabled) 1f else 0.4f
        nextButton.isEnabled = currentIndex < imageUrls.lastIndex
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.4f
        ImageLoader.load(
            screenshotImage,
            imageUrl,
            imageExecutor,
            android.R.color.transparent,
        )
    }

    companion object {
        const val EXTRA_IMAGE_URLS = "extra_image_urls"
        const val EXTRA_SELECTED_INDEX = "extra_selected_index"
        const val EXTRA_TITLE = "extra_title"
    }
}
