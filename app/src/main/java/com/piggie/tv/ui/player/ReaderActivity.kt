package com.piggie.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.piggie.tv.R
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVColors

class ReaderActivity : AppCompatActivity() {
    private val api by lazy { JellyfinNativeApi(this) }
    private val store by lazy { SecureSessionStore(this) }
    private lateinit var session: NativeSession
    private lateinit var bookId: String
    private lateinit var viewPager: ViewPager2
    private lateinit var pageIndicator: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = store.read() ?: run { finish(); return }
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }

        val root = FrameLayout(this).apply {
            setBackgroundColor(PTVColors.background)
        }

        viewPager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
        }
        root.addView(viewPager, FrameLayout.LayoutParams(-1, -1))

        pageIndicator = TextView(this).apply {
            setTextColor(PTVColors.textSecondary)
            textSize = 14f
            setPadding(40, 0, 40, 40)
        }
        root.addView(pageIndicator, FrameLayout.LayoutParams(-2, -2).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        })

        setContentView(root)
        loadBook()
    }

    private fun loadBook() {
        kotlin.concurrent.thread {
            runCatching { api.loadItem(session, bookId) }.onSuccess { book ->
                runOnUiThread {
                    setupReader(book.pageCount)
                }
            }
        }
    }

    private fun setupReader(pages: Int) {
        val count = if (pages > 0) pages else 1
        viewPager.adapter = PageAdapter(bookId, count, session, api)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageIndicator.text = "${position + 1} / $count"
                reportProgress(position)
            }
        })
    }

    private fun reportProgress(page: Int) {
        // Future: api.reportReadingProgress(session, bookId, page)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (viewPager.currentItem < (viewPager.adapter?.itemCount ?: 0) - 1) {
                    viewPager.currentItem = viewPager.currentItem + 1
                    true
                } else false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (viewPager.currentItem > 0) {
                    viewPager.currentItem = viewPager.currentItem - 1
                    true
                } else false
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private class PageAdapter(
        private val bookId: String,
        private val count: Int,
        private val session: NativeSession,
        private val api: JellyfinNativeApi
    ) : RecyclerView.Adapter<PageAdapter.Holder>() {

        class Holder(val view: ImageView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(-1, -1)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return Holder(iv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val url = api.getPageImageUrl(session, bookId, position)
            holder.view.load(url) {
                crossfade(true)
            }
        }

        override fun getItemCount() = count
    }

    companion object {
        private const val EXTRA_BOOK_ID = "extra_book_id"
        fun start(context: Context, bookId: String) {
            context.startActivity(Intent(context, ReaderActivity::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
            })
        }
    }
}
