package com.piggie.tv.ui.discovery

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.data.discovery.DiscoveryShelf
import com.piggie.tv.data.discovery.ShelfDefinition

/**
 * Vertically virtualizes discovery shelves. Only attached and near-visible rows construct their
 * horizontal RecyclerViews; terminal shelf state remains keyed by the stable manifest ID.
 */
class DiscoveryPageAdapter(
    private val definitions: List<ShelfDefinition>,
    private val createShelfContent: (DiscoveryShelf) -> View,
    private val onRetry: (String) -> Unit,
    private val bindHeader: ((FrameLayout) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val shelves = LinkedHashMap<String, DiscoveryShelf>()
    val headerOffset: Int = if (bindHeader == null) 0 else 1

    init {
        setHasStableIds(true)
    }

    fun updateShelf(shelf: DiscoveryShelf) {
        val index = definitions.indexOfFirst { it.id == shelf.definition.id }
        if (index < 0) return
        shelves[shelf.definition.id] = shelf
        notifyItemChanged(index + headerOffset)
    }

    fun refreshHeader() {
        if (bindHeader != null) notifyItemChanged(0)
    }

    fun shelfIdAt(adapterPosition: Int): String? {
        val definitionIndex = adapterPosition - headerOffset
        return definitions.getOrNull(definitionIndex)?.id
    }

    override fun getItemCount(): Int = definitions.size + headerOffset

    override fun getItemId(position: Int): Long {
        if (position < headerOffset) return Long.MIN_VALUE
        val index = position - headerOffset
        return (definitions[index].id.hashCode().toLong() shl 32) xor index.toLong()
    }

    override fun getItemViewType(position: Int): Int =
        if (position < headerOffset) HEADER_VIEW_TYPE else position - headerOffset

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == HEADER_VIEW_TYPE) {
            return HeaderHolder(FrameLayout(parent.context))
        }
        return ShelfHolder(
            DiscoveryShelfSlotView(parent.context, definitions[viewType])
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderHolder -> {
                holder.container.removeAllViews()
                bindHeader?.invoke(holder.container)
            }
            is ShelfHolder -> {
                val definition = definitions[position - headerOffset]
                val shelf = shelves[definition.id]
                when (DiscoveryShelfSlotStatePolicy.resolve(shelf?.status)) {
                    DiscoveryShelfSlotState.LOADING ->
                        holder.slot.showLoading(definition.title)
                    DiscoveryShelfSlotState.CONTENT ->
                        holder.slot.showContent(createShelfContent(requireNotNull(shelf)))
                    DiscoveryShelfSlotState.EMPTY ->
                        holder.slot.showEmpty(
                            requireNotNull(shelf).definition.title,
                            shelf.message
                        ) {
                            holder.slot.showLoading(shelf.definition.title)
                            onRetry(shelf.definition.id)
                        }
                    DiscoveryShelfSlotState.ERROR -> holder.slot.showFailure(
                        requireNotNull(shelf).definition.title,
                        shelf.status,
                        shelf.message
                    ) {
                        holder.slot.showLoading(shelf.definition.title)
                        onRetry(shelf.definition.id)
                    }
                }
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is HeaderHolder -> holder.container.removeAllViews()
            is ShelfHolder -> holder.slot.release()
        }
        super.onViewRecycled(holder)
    }

    private class HeaderHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)
    private class ShelfHolder(val slot: DiscoveryShelfSlotView) : RecyclerView.ViewHolder(slot)

    private companion object {
        const val HEADER_VIEW_TYPE = Int.MIN_VALUE
    }
}
