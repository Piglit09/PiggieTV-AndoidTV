package com.piggie.tv.ui.discovery

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
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
        val previous = shelves[shelf.definition.id]
        shelves[shelf.definition.id] = shelf
        if (!DiscoveryShelfUiContentPolicy.matches(previous, shelf)) {
            notifyItemChanged(index + headerOffset)
        }
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
            DiscoveryShelfSlotView(parent.context, definitions[viewType]).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = parent.context.resources.getDimensionPixelSize(R.dimen.tv_shelf_margin_vertical)
                    bottomMargin = parent.context.resources.getDimensionPixelSize(R.dimen.tv_shelf_margin_vertical)
                }
            }
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
                        )
                    DiscoveryShelfSlotState.ERROR -> {
                        val failed = requireNotNull(shelf)
                        val retry = {
                            holder.slot.showLoading(failed.definition.title, preserveFocus = true)
                            onRetry(failed.definition.id)
                        }
                        if (failed.items.isNotEmpty()) {
                            holder.slot.showStaleFailure(
                                createShelfContent(failed),
                                failed.message,
                                retry
                            )
                        } else {
                            holder.slot.showFailure(
                                failed.definition.title,
                                failed.status,
                                failed.message,
                                retry
                            )
                        }
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

/** Excludes generation/timing diagnostics from RecyclerView's visual diff decision. */
object DiscoveryShelfUiContentPolicy {
    fun matches(previous: DiscoveryShelf?, current: DiscoveryShelf): Boolean =
        previous != null &&
            previous.definition == current.definition &&
            previous.items == current.items &&
            previous.status == current.status &&
            previous.viewMoreItem == current.viewMoreItem &&
            previous.browseRequest == current.browseRequest &&
            previous.message == current.message
}

object DiscoveryShelfActionPolicy {
    fun canRetry(status: com.piggie.tv.data.discovery.ShelfStatus): Boolean = status in setOf(
        com.piggie.tv.data.discovery.ShelfStatus.TIMEOUT,
        com.piggie.tv.data.discovery.ShelfStatus.HTTP_ERROR,
        com.piggie.tv.data.discovery.ShelfStatus.INVALID_QUERY,
        com.piggie.tv.data.discovery.ShelfStatus.MISSING_LIBRARY,
        com.piggie.tv.data.discovery.ShelfStatus.FAILED_RENDER,
        com.piggie.tv.data.discovery.ShelfStatus.RENDER_ERROR
    )
}

internal enum class DiscoveryMediaAction {
    VIEW_MORE,
    RESUME_PLAYBACK,
    DETAILS
}

/** Continue Watching is the one discovery shelf whose card press is a playback action. */
internal object DiscoveryMediaActionPolicy {
    fun resolve(
        itemType: String,
        shelfType: com.piggie.tv.data.discovery.DiscoveryShelfType
    ): DiscoveryMediaAction = when {
        itemType.equals("ViewMore", ignoreCase = true) -> DiscoveryMediaAction.VIEW_MORE
        shelfType == com.piggie.tv.data.discovery.DiscoveryShelfType.CONTINUE_WATCHING &&
            (itemType.equals("Movie", ignoreCase = true) || itemType.equals("Episode", ignoreCase = true)) ->
            DiscoveryMediaAction.RESUME_PLAYBACK
        else -> DiscoveryMediaAction.DETAILS
    }
}
