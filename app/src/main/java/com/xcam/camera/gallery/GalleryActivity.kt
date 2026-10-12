package com.xcam.camera.gallery

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xcam.camera.databinding.ActivityGalleryBinding
import com.xcam.camera.databinding.ItemDateHeaderBinding
import com.xcam.camera.databinding.ItemMediaBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal sealed class Row {
    class Header(val title: String) : Row()
    class Cell(val item: MediaStoreHelper.MediaItem) : Row()
}

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private lateinit var adapter: GalleryAdapter
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { finish() }

        val dm = resources.displayMetrics
        val span = (dm.widthPixels / dm.density / 110f).toInt().coerceIn(3, 8)
        adapter = GalleryAdapter { item ->
            startActivity(
                Intent(this, MediaViewerActivity::class.java)
                    .putExtra(MediaViewerActivity.EXTRA_URI, item.uri.toString())
            )
        }
        val layoutManager = GridLayoutManager(this, span)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (adapter.isHeader(position)) span else 1
        }
        binding.list.layoutManager = layoutManager
        binding.list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun reload() {
        try {
            io.execute {
                val items = MediaStoreHelper.queryAll(this)
                val rows = buildRows(items)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    adapter.submit(rows)
                    binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun buildRows(items: List<MediaStoreHelper.MediaItem>): List<Row> {
        val locale = Locale.forLanguageTag("id-ID")
        val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dayTitle = SimpleDateFormat("d MMMM yyyy", locale)
        val rows = mutableListOf<Row>()
        var lastKey = ""
        for (item in items) {
            val key = dayKey.format(item.dateMs)
            if (key != lastKey) {
                lastKey = key
                rows.add(Row.Header(dayTitle.format(item.dateMs)))
            }
            rows.add(Row.Cell(item))
        }
        return rows
    }

    private class GalleryAdapter(
        private val onClick: (MediaStoreHelper.MediaItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        fun isHeader(position: Int) = rows.getOrNull(position) is Row.Header

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (isHeader(position)) TYPE_HEADER else TYPE_CELL

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(ItemDateHeaderBinding.inflate(inflater, parent, false))
            } else {
                CellHolder(ItemMediaBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).binding.tvDate.text = row.title
                is Row.Cell -> {
                    val b = (holder as CellHolder).binding
                    val item = row.item
                    ThumbLoader.load(b.root.context, item, THUMB_SIZE, b.thumb)
                    if (item.isLive) {
                        b.tvDuration.visibility = View.VISIBLE
                        b.tvDuration.text = b.root.context.getString(com.xcam.camera.R.string.live_badge)
                    } else if (item.isVideo) {
                        b.tvDuration.visibility = View.VISIBLE
                        b.tvDuration.text = "\u25B6 " + formatDuration(item.durationMs)
                    } else {
                        b.tvDuration.visibility = View.GONE
                    }
                    b.root.setOnClickListener { onClick(item) }
                }
            }
        }

        private fun formatDuration(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) {
                String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.US, "%d:%02d", m, s)
            }
        }

        class HeaderHolder(val binding: ItemDateHeaderBinding) : RecyclerView.ViewHolder(binding.root)
        class CellHolder(val binding: ItemMediaBinding) : RecyclerView.ViewHolder(binding.root)

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_CELL = 1
            private const val THUMB_SIZE = 320
        }
    }
}
