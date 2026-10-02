package com.piggie.tv.ui.search

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.api.JellyfinNativeApi
import com.piggie.tv.data.api.NativeRequestScope
import com.piggie.tv.data.models.MediaCardPresentation
import com.piggie.tv.data.models.MediaItem
import com.piggie.tv.data.models.MediaShelf
import com.piggie.tv.data.models.NativeSession
import com.piggie.tv.data.session.SecureSessionStore
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.layout.TvHorizontalRecyclerView
import com.piggie.tv.ui.layout.TvLinearLayoutManager
import com.piggie.tv.ui.library.LibraryBrowserActivity
import com.piggie.tv.ui.player.MediaDetailsActivity
import com.piggie.tv.ui.rendering.applyRenderingTuning
import com.piggie.tv.ui.widgets.MediaCardFactory
import com.piggie.tv.ui.widgets.MediaCardHolder
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes
import kotlin.concurrent.thread

private sealed class SearchPageRow {
    data object Header : SearchPageRow()
    data class Message(val text: String) : SearchPageRow()
    data class Shelf(val value: MediaShelf) : SearchPageRow()
}

class SearchFragment : Fragment() {
    private val apiDelegate = lazy { JellyfinNativeApi(requireContext().applicationContext) }
    private val api by apiDelegate
    private val store by lazy { SecureSessionStore(requireContext()) }

    private lateinit var session: NativeSession
    private var page: RecyclerView? = null
    private var pageAdapter: SearchPageAdapter? = null
    private val cardPool = RecyclerView.RecycledViewPool()

    private var draftQuery = ""
    private var submittedQuery = ""
    private var focusedItemId: String? = null
    private var focusedControl = CONTROL_INPUT
    private var selectedScope = SearchResultPolicy.Scope.ALL
    private var requestGeneration = 0
    private var activeRequestScope: NativeRequestScope? = null
    private val filterButtonIds = SearchResultPolicy.Scope.entries.associateWith {
        View.generateViewId()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draftQuery = savedInstanceState?.getString(STATE_DRAFT_QUERY).orEmpty()
        submittedQuery = savedInstanceState?.getString(STATE_SUBMITTED_QUERY).orEmpty()
        focusedItemId = savedInstanceState?.getString(STATE_FOCUSED_ITEM)
        focusedControl = savedInstanceState?.getString(STATE_FOCUSED_CONTROL) ?: CONTROL_INPUT
        selectedScope = SearchResultPolicy.Scope.fromStored(
            savedInstanceState?.getString(STATE_SELECTED_SCOPE)
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        session = (activity as? PtvHostActivity)?.session
            ?: requireNotNull(store.read()) { "A signed-in session is required for Search" }

        val context = requireContext()
        val manager = TvLinearLayoutManager(context, RecyclerView.VERTICAL, false)
        val adapter = SearchPageAdapter()
        val safeMargin = SearchResultPolicy.compactSafeMarginPx(
            context.resources.displayMetrics.density
        )

        return RecyclerView(context).apply {
            id = View.generateViewId()
            layoutManager = manager
            this.adapter = adapter
            applyRenderingTuning(manager, visibleItems = 4)
            setPadding(
                safeMargin,
                0,
                safeMargin,
                context.dim(R.dimen.tv_spacing_large)
            )
            clipToPadding = false
            clipChildren = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            page = this
            pageAdapter = adapter
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pageAdapter?.submitRows(listOf(SearchPageRow.Header))
        if (submittedQuery.isBlank()) {
            page?.post(::restoreFocus)
        } else {
            performSearch(submittedQuery, retainFocusedItem = true)
        }
    }

    override fun onResume() {
        super.onResume()
        page?.post {
            if (page?.findFocus() == null) restoreFocus()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_DRAFT_QUERY, draftQuery)
        outState.putString(STATE_SUBMITTED_QUERY, submittedQuery)
        outState.putString(STATE_FOCUSED_ITEM, focusedItemId)
        outState.putString(STATE_FOCUSED_CONTROL, focusedControl)
        outState.putString(STATE_SELECTED_SCOPE, selectedScope.name)
    }

    override fun onDestroyView() {
        requestGeneration += 1
        activeRequestScope?.cancel()
        activeRequestScope = null
        page?.adapter = null
        pageAdapter = null
        page = null
        super.onDestroyView()
    }

    private fun performSearch(rawQuery: String, retainFocusedItem: Boolean = false) {
        val query = SearchResultPolicy.normalizeQuery(rawQuery)
        if (query.isBlank()) return

        submittedQuery = query
        if (!retainFocusedItem) focusedItemId = null
        val scope = selectedScope
        activeRequestScope?.cancel()
        val requestScope = NativeRequestScope().also { activeRequestScope = it }
        val generation = ++requestGeneration
        pageAdapter?.submitRows(
            listOf(
                SearchPageRow.Header,
                SearchPageRow.Message("Searching…")
            )
        )
        page?.post(::restoreFocus)

        thread(name = "ptv-search-$generation", start = true) {
            runCatching {
                api.withRequestScope(requestScope) {
                    api.searchIncrementally(
                        session = session,
                        query = query,
                        itemTypes = scope.itemTypes,
                        includeGenres = scope.includeGenres,
                        includeStudios = scope.includeStudios,
                        onPartialResults = { partialItems ->
                            activity?.runOnUiThread {
                                if (!canApply(generation)) return@runOnUiThread
                                val shelves = SearchResultPolicy.shelves(partialItems, scope)
                                if (shelves.isNotEmpty()) {
                                    pageAdapter?.submitRows(
                                        buildList {
                                            add(SearchPageRow.Header)
                                            shelves.forEach { add(SearchPageRow.Shelf(it)) }
                                        }
                                    )
                                    page?.post(::restoreFocus)
                                }
                            }
                        }
                    )
                }
            }
                .onSuccess { items ->
                    activity?.runOnUiThread {
                        if (!canApply(generation)) return@runOnUiThread
                        if (activeRequestScope === requestScope) activeRequestScope = null
                        val shelves = SearchResultPolicy.shelves(items, scope)
                        val rows = if (shelves.isEmpty()) {
                            listOf(
                                SearchPageRow.Header,
                                SearchPageRow.Message("No results found for “$query”")
                            )
                        } else {
                            buildList {
                                add(SearchPageRow.Header)
                                shelves.forEach { add(SearchPageRow.Shelf(it)) }
                            }
                        }
                        pageAdapter?.submitRows(rows)
                        page?.post(::restoreFocus)
                    }
                }
                .onFailure { error ->
                    activity?.runOnUiThread {
                        if (!canApply(generation)) return@runOnUiThread
                        if (activeRequestScope === requestScope) activeRequestScope = null
                        val detail = error.message?.takeIf { it.isNotBlank() } ?: "Unknown error"
                        pageAdapter?.submitRows(
                            listOf(
                                SearchPageRow.Header,
                                SearchPageRow.Message("Search error: $detail")
                            )
                        )
                        page?.post(::restoreFocus)
                    }
                }
        }
    }

    private fun selectScope(scope: SearchResultPolicy.Scope) {
        if (selectedScope == scope) return
        selectedScope = scope
        focusedControl = scope.controlKey
        focusedItemId = null
        pageAdapter?.refreshHeader()

        val query = SearchResultPolicy.normalizeQuery(draftQuery)
        if (query.isBlank()) {
            submittedQuery = ""
            requestGeneration += 1
            activeRequestScope?.cancel()
            activeRequestScope = null
            pageAdapter?.submitRows(listOf(SearchPageRow.Header))
            page?.post(::restoreFocus)
        } else {
            performSearch(query)
        }
    }

    private fun canApply(generation: Int): Boolean =
        generation == requestGeneration && isAdded && pageAdapter != null

    private fun restoreFocus() {
        val adapter = pageAdapter ?: return
        val resultId = focusedItemId
        if (resultId != null && adapter.restoreResultFocus(resultId)) return
        adapter.restoreHeaderFocus(focusedControl)
    }

    private fun createLabel(
        value: String,
        sizeResource: Int,
        colorResource: Int,
        bold: Boolean = false
    ): TextView = TextView(requireContext()).apply {
        text = value
        setTextSizeRes(sizeResource)
        setTextColor(context.getColor(colorResource))
        typeface = Typeface.create(
            if (bold) "sans-serif-medium" else "sans-serif",
            Typeface.NORMAL
        )
    }

    private inner class SearchPageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<SearchPageRow> = emptyList()

        init {
            setHasStableIds(true)
        }

        fun submitRows(updated: List<SearchPageRow>) {
            rows = updated
            notifyDataSetChanged()
        }

        fun refreshHeader() {
            if (rows.firstOrNull() == SearchPageRow.Header) notifyItemChanged(0)
        }

        override fun getItemId(position: Int): Long = when (val row = rows[position]) {
            SearchPageRow.Header -> HEADER_ROW_ID
            is SearchPageRow.Message -> MESSAGE_ROW_ID
            is SearchPageRow.Shelf -> stableId("shelf:${row.value.title}")
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            SearchPageRow.Header -> VIEW_TYPE_HEADER
            is SearchPageRow.Message -> VIEW_TYPE_MESSAGE
            is SearchPageRow.Shelf -> VIEW_TYPE_SHELF
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                VIEW_TYPE_HEADER -> createHeaderHolder(parent)
                VIEW_TYPE_MESSAGE -> MessageHolder(
                    createLabel(
                        value = "",
                        sizeResource = R.dimen.tv_text_size_body,
                        colorResource = R.color.tv_text_secondary
                    ).apply {
                        setPadding(
                            0,
                            context.dim(R.dimen.tv_spacing_large),
                            0,
                            context.dim(R.dimen.tv_spacing_medium)
                        )
                    }
                )
                else -> createShelfHolder(parent)
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                SearchPageRow.Header -> (holder as HeaderHolder).bind()
                is SearchPageRow.Message -> (holder as MessageHolder).label.text = row.text
                is SearchPageRow.Shelf -> (holder as ShelfHolder).bind(row.value)
            }
        }

        override fun getItemCount(): Int = rows.size

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is ShelfHolder) holder.recycle()
            super.onViewRecycled(holder)
        }

        fun restoreHeaderFocus(control: String) {
            val recycler = page ?: return
            recycler.scrollToPosition(0)
            recycler.post {
                val holder = recycler.findViewHolderForAdapterPosition(0) as? HeaderHolder
                holder?.requestFocus(control)
            }
        }

        fun restoreResultFocus(itemId: String): Boolean {
            val rowPosition = rows.indexOfFirst { row ->
                row is SearchPageRow.Shelf && row.value.items.any { it.id == itemId }
            }
            if (rowPosition < 0) return false

            val recycler = page ?: return false
            recycler.scrollToPosition(rowPosition)
            requestResultFocus(recycler, rowPosition, itemId, attemptsRemaining = 3)
            return true
        }

        private fun requestResultFocus(
            recycler: RecyclerView,
            rowPosition: Int,
            itemId: String,
            attemptsRemaining: Int
        ) {
            recycler.post {
                val holder = recycler.findViewHolderForAdapterPosition(rowPosition) as? ShelfHolder
                if (holder?.requestItemFocus(itemId) != true && attemptsRemaining > 0) {
                    requestResultFocus(recycler, rowPosition, itemId, attemptsRemaining - 1)
                }
            }
        }

        private fun createHeaderHolder(parent: ViewGroup): HeaderHolder {
            val context = parent.context
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, context.dim(R.dimen.tv_spacing_medium))
            }
            content.addView(
                createLabel(
                    value = "Search",
                    sizeResource = R.dimen.tv_text_size_page_title,
                    colorResource = R.color.tv_text_primary,
                    bold = true
                )
            )

            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, context.dim(R.dimen.tv_spacing_medium), 0, 0)
            }
            val input = EditText(context).apply {
                id = View.generateViewId()
                hint = "Movies, series, actors…"
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(context.getColor(R.color.tv_text_primary))
                setHintTextColor(context.getColor(R.color.tv_text_secondary))
                setBackgroundResource(R.drawable.tv_field_bg)
                setPadding(
                    context.dim(R.dimen.tv_spacing_medium),
                    context.dim(R.dimen.tv_spacing_small),
                    context.dim(R.dimen.tv_spacing_medium),
                    context.dim(R.dimen.tv_spacing_small)
                )
            }
            val searchButton = Button(context).apply {
                id = View.generateViewId()
                text = "Search"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                isAllCaps = false
                setTextColor(context.getColor(R.color.tv_text_primary))
                setBackgroundResource(R.drawable.tv_button_primary)
            }
            input.nextFocusRightId = searchButton.id
            searchButton.nextFocusLeftId = input.id
            row.addView(
                input,
                LinearLayout.LayoutParams(
                    0,
                    context.dim(R.dimen.tv_nav_button_height) +
                        context.dim(R.dimen.tv_spacing_small),
                    1f
                )
            )
            row.addView(
                searchButton,
                LinearLayout.LayoutParams(
                    context.dim(R.dimen.tv_hero_button_width),
                    context.dim(R.dimen.tv_nav_button_height) +
                        context.dim(R.dimen.tv_spacing_small)
                ).apply {
                    marginStart = context.dim(R.dimen.tv_spacing_medium)
                }
            )
            content.addView(row)

            val scopeAdapter = SearchScopeAdapter(input.id, searchButton.id)
            val scopeManager = TvLinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            val scopeRecycler = TvHorizontalRecyclerView(context).apply {
                id = View.generateViewId()
                layoutManager = scopeManager
                adapter = scopeAdapter
                applyRenderingTuning(scopeManager, visibleItems = SearchResultPolicy.Scope.entries.size)
                isFocusable = false
                isNestedScrollingEnabled = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                clipChildren = false
                clipToPadding = false
                setPadding(0, context.dim(R.dimen.tv_spacing_small), 0, 0)
            }
            content.addView(
                scopeRecycler,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    context.dim(R.dimen.tv_nav_button_height) +
                        context.dim(R.dimen.tv_spacing_medium)
                )
            )
            return HeaderHolder(content, input, searchButton, scopeRecycler, scopeAdapter)
        }

        private fun createShelfHolder(parent: ViewGroup): ShelfHolder {
            val context = parent.context
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, context.dim(R.dimen.tv_spacing_large), 0, 0)
            }
            val title = createLabel(
                value = "",
                sizeResource = R.dimen.tv_text_size_section_title,
                colorResource = R.color.tv_text_primary,
                bold = true
            )
            val manager = TvLinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            val recycler = RecyclerView(context).apply {
                layoutManager = manager
                applyRenderingTuning(manager, visibleItems = 7)
                setRecycledViewPool(cardPool)
                clipChildren = false
                clipToPadding = false
                isNestedScrollingEnabled = false
                setPadding(
                    0,
                    context.dim(R.dimen.tv_spacing_small),
                    0,
                    context.dim(R.dimen.tv_spacing_small)
                )
            }
            container.addView(title)
            container.addView(recycler, LinearLayout.LayoutParams(-1, 0))
            return ShelfHolder(container, title, recycler)
        }
    }

    private inner class SearchScopeAdapter(
        private val inputId: Int,
        private val searchButtonId: Int
    ) : RecyclerView.Adapter<SearchScopeAdapter.ScopeHolder>() {
        private val scopes = SearchResultPolicy.Scope.entries

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = scopes[position].ordinal.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScopeHolder {
            val context = parent.context
            val button = Button(context).apply {
                isAllCaps = false
                isFocusable = true
                setTextSizeRes(R.dimen.tv_nav_text_size)
                setTextColor(context.getColor(R.color.tv_text_primary))
                minimumWidth = 0
                minWidth = 0
                minimumHeight = 0
                minHeight = 0
                setPadding(
                    context.dim(R.dimen.tv_spacing_large),
                    0,
                    context.dim(R.dimen.tv_spacing_large),
                    0
                )
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    context.dim(R.dimen.tv_nav_button_height)
                ).apply {
                    marginEnd = context.dim(R.dimen.tv_spacing_small)
                }
            }
            return ScopeHolder(button)
        }

        override fun onBindViewHolder(holder: ScopeHolder, position: Int) {
            holder.bind(scopes[position], position)
        }

        override fun onViewRecycled(holder: ScopeHolder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = scopes.size

        inner class ScopeHolder(private val button: Button) : RecyclerView.ViewHolder(button) {
            fun bind(scope: SearchResultPolicy.Scope, position: Int) {
                button.id = requireNotNull(filterButtonIds[scope])
                button.text = scope.label
                button.contentDescription = if (scope == selectedScope) {
                    "${scope.label}, selected search category"
                } else {
                    "${scope.label}, search category"
                }
                button.setBackgroundResource(
                    if (scope == selectedScope) {
                        R.drawable.tv_button_primary
                    } else {
                        R.drawable.tv_button_secondary
                    }
                )
                button.nextFocusUpId = if (position >= scopes.lastIndex - 1) {
                    searchButtonId
                } else {
                    inputId
                }
                button.setOnClickListener { selectScope(scope) }
                button.setOnFocusChangeListener { view, focused ->
                    PTVShapes.applyFocusEffect(view, focused)
                    if (focused) {
                        focusedControl = scope.controlKey
                        focusedItemId = null
                    }
                }
            }

            fun recycle() {
                button.setOnClickListener(null)
                button.setOnFocusChangeListener(null)
            }
        }
    }

    private inner class HeaderHolder(
        view: View,
        private val input: EditText,
        private val searchButton: Button,
        private val scopeRecycler: RecyclerView,
        private val scopeAdapter: SearchScopeAdapter
    ) : RecyclerView.ViewHolder(view) {
        private var binding = false

        init {
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) = Unit

                override fun afterTextChanged(text: Editable?) {
                    if (!binding) draftQuery = text?.toString().orEmpty()
                }
            })
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submit()
                    true
                } else {
                    false
                }
            }
            input.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    focusedControl = CONTROL_INPUT
                    focusedItemId = null
                }
            }
            searchButton.setOnClickListener { submit() }
            searchButton.setOnFocusChangeListener { _, focused ->
                if (focused) {
                    focusedControl = CONTROL_BUTTON
                    focusedItemId = null
                }
            }
        }

        fun bind() {
            val selection = input.selectionStart.coerceAtLeast(0)
            if (input.text.toString() != draftQuery) {
                binding = true
                input.setText(draftQuery)
                input.setSelection(selection.coerceAtMost(draftQuery.length))
                binding = false
            }
            scopeAdapter.notifyDataSetChanged()
            val selectedButtonId = requireNotNull(filterButtonIds[selectedScope])
            input.nextFocusDownId = selectedButtonId
            searchButton.nextFocusDownId = selectedButtonId
        }

        fun requestFocus(control: String) {
            val scope = SearchResultPolicy.Scope.fromControlKey(control)
            when {
                scope != null -> {
                    val position = scope.ordinal
                    scopeRecycler.scrollToPosition(position)
                    scopeRecycler.post {
                        scopeRecycler.findViewHolderForAdapterPosition(position)
                            ?.itemView
                            ?.requestFocus()
                    }
                }
                control == CONTROL_BUTTON -> searchButton.requestFocus()
                else -> input.requestFocus()
            }
        }

        private fun submit() {
            performSearch(input.text.toString())
        }
    }

    private class MessageHolder(val label: TextView) : RecyclerView.ViewHolder(label)

    private inner class ShelfHolder(
        view: View,
        private val title: TextView,
        private val recycler: RecyclerView
    ) : RecyclerView.ViewHolder(view) {
        private var resultAdapter: SearchResultAdapter? = null

        fun bind(shelf: MediaShelf) {
            title.text = shelf.title
            val adapter = SearchResultAdapter(shelf.items, shelf.presentation)
            resultAdapter = adapter
            recycler.adapter = adapter

            val artworkHeight = when (shelf.presentation) {
                MediaCardPresentation.POSTER -> R.dimen.tv_poster_height
                MediaCardPresentation.LANDSCAPE -> R.dimen.tv_landscape_height
                MediaCardPresentation.SQUARE -> R.dimen.tv_square_height
            }
            recycler.layoutParams = (recycler.layoutParams as LinearLayout.LayoutParams).apply {
                height = recycler.context.dim(artworkHeight) +
                    recycler.context.dim(R.dimen.tv_shelf_extra_height)
            }
        }

        fun requestItemFocus(itemId: String): Boolean {
            val position = resultAdapter?.positionOf(itemId) ?: return false
            if (position < 0) return false
            recycler.scrollToPosition(position)
            recycler.post {
                recycler.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
            return true
        }

        fun recycle() {
            recycler.adapter = null
            resultAdapter = null
        }
    }

    private inner class SearchResultAdapter(
        private val items: List<MediaItem>,
        private val presentation: MediaCardPresentation
    ) : RecyclerView.Adapter<MediaCardHolder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = stableId(items[position].id)

        override fun getItemViewType(position: Int): Int = presentation.ordinal

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaCardHolder =
            MediaCardHolder(MediaCardFactory.createView(parent, presentation))

        override fun onBindViewHolder(holder: MediaCardHolder, position: Int) {
            val item = items[position]
            MediaCardFactory.bindView(holder, item, presentation, session, api)
            holder.itemView.setOnClickListener { view ->
                val browseRequest = SearchResultPolicy.categoryBrowseRequest(item)
                if (browseRequest != null) {
                    LibraryBrowserActivity.start(view.context, browseRequest)
                } else {
                    MediaDetailsActivity.start(view.context, item)
                }
            }
            holder.itemView.setOnFocusChangeListener { view, focused ->
                PTVShapes.applyFocusEffect(view, focused)
                if (focused) {
                    focusedItemId = item.id
                    focusedControl = CONTROL_RESULT
                }
            }
        }

        override fun getItemCount(): Int = items.size

        override fun onViewRecycled(holder: MediaCardHolder) {
            MediaCardFactory.recycleView(holder)
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnFocusChangeListener(null)
            super.onViewRecycled(holder)
        }

        fun positionOf(itemId: String): Int = items.indexOfFirst { it.id == itemId }
    }

    private fun stableId(value: String): Long =
        value.fold(1_125_899_906_842_597L) { hash, character ->
            31L * hash + character.code
        }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_MESSAGE = 1
        const val VIEW_TYPE_SHELF = 2

        const val HEADER_ROW_ID = Long.MIN_VALUE + 1
        const val MESSAGE_ROW_ID = Long.MIN_VALUE + 2

        const val CONTROL_INPUT = "input"
        const val CONTROL_BUTTON = "button"
        const val CONTROL_RESULT = "result"

        const val STATE_DRAFT_QUERY = "search.draft_query"
        const val STATE_SUBMITTED_QUERY = "search.submitted_query"
        const val STATE_FOCUSED_ITEM = "search.focused_item"
        const val STATE_FOCUSED_CONTROL = "search.focused_control"
        const val STATE_SELECTED_SCOPE = "search.selected_scope"
    }
}
