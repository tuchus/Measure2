package com.tuchus.measure

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.Executors

/** The photos saved from Measure, newest first. */
class HistoryActivity : Activity() {
    private var items: List<Uri> = emptyList()
    private val loader = Executors.newFixedThreadPool(3)
    private val cache = LruCache<Uri, Bitmap>(60)
    private lateinit var grid: GridView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        findViewById<Button>(R.id.historyDone).setOnClickListener { finish() }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, position, _ -> Gallery.open(this, items[position]) }
        grid.setOnItemLongClickListener { _, _, position, _ -> Gallery.share(this, items[position]); true }
    }

    override fun onResume() {
        super.onResume()
        items = try { Gallery.list(this) } catch (e: Exception) { emptyList() }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val size = parent.width / 3
            val image = (convertView as? ImageView) ?: ImageView(this@HistoryActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(getColor(R.color.line))
            }
            image.layoutParams = AbsListView.LayoutParams(size, size)
            val uri = items[position]
            image.tag = uri
            val cached = cache.get(uri)
            image.setImageBitmap(cached)
            if (cached == null) loader.execute {
                val thumb = try { contentResolver.loadThumbnail(uri, Size(360, 360), null) } catch (e: Exception) { null }
                if (thumb != null) {
                    cache.put(uri, thumb)
                    runOnUiThread { if (image.tag == uri) image.setImageBitmap(thumb) }
                }
            }
            return image
        }
    }
}
