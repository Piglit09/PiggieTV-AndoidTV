package com.piggie.tv.ui.reader

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.io.InputStream

class ReaderPageAdapter(
    private val pageCount: Int,
    private val getPageStream: (Int, (InputStream?) -> Unit) -> Unit
) : RecyclerView.Adapter<ReaderPageAdapter.Holder>() {

    class Holder(val view: com.piggie.tv.ui.widgets.PTVZoomImageView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val iv = com.piggie.tv.ui.widgets.PTVZoomImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }
        return Holder(iv)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        getPageStream(position) { stream ->
            if (stream != null) {
                holder.view.load(stream.readBytes()) {
                    crossfade(false)
                }
            } else {
                holder.view.setImageResource(android.R.drawable.ic_dialog_alert)
            }
        }
    }

    override fun getItemCount() = pageCount
}
