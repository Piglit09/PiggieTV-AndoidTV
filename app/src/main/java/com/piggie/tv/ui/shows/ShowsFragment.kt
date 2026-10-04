package com.piggie.tv.ui.shows

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.piggie.tv.R
import com.piggie.tv.core.PtvHostActivity
import com.piggie.tv.data.discovery.DiscoveryPage
import com.piggie.tv.theme.PTVColors
import com.piggie.tv.theme.PTVGlassTier
import com.piggie.tv.theme.PTVMaterials
import com.piggie.tv.theme.PTVShapes
import com.piggie.tv.ui.discovery.BaseDiscoveryFragment
import com.piggie.tv.ui.hero.HeroRoute
import com.piggie.tv.util.dim
import com.piggie.tv.util.setTextSizeRes

/** One Shows-family shell, with a distinct scoped discovery page for every actual library. */
class ShowsFragment : BaseDiscoveryFragment() {
    private lateinit var selection: ShowsFamilySelectionState
    private lateinit var selectionStore: ShowsFamilySelectionStore
    private val tabs = linkedMapOf<ShowsFamilyTab, Button>()
    private val underlines = linkedMapOf<ShowsFamilyTab, View>()

    override val discoveryPage: DiscoveryPage
        get() = if (::selection.isInitialized) selection.selected.page else DiscoveryPage.SHOWS
    override val heroRoute = HeroRoute.SHOWS

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        selectionStore = ShowsFamilySelectionStore(requireContext())
        if (!::selection.isInitialized) {
            val stored = (activity as? PtvHostActivity)?.session?.let(selectionStore::read)
                ?: ShowsFamilyTab.SHOWS
            selection = ShowsFamilySelectionState(
                savedInstanceState?.getString(STATE_SELECTED_TAB)?.let(ShowsFamilyTab::fromStored)
                    ?: stored
            )
        }
        val discoveryContent = super.onCreateView(inflater, container, savedInstanceState)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.ptv_library_page_background)
            addView(View(context), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dim(R.dimen.tv_floating_nav_clearance)
            ))
            addView(createTabStrip(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(discoveryContent, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateTabSelection()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::selection.isInitialized) outState.putString(STATE_SELECTED_TAB, selection.selected.name)
    }

    override fun onMediaItemFocused(itemId: String) {
        selection.rememberFocus(itemId)
    }

    private fun createTabStrip(): View {
        val context = requireContext()
        tabs.clear()
        underlines.clear()
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = false
        }
        val segment = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = PTVMaterials.drawable(context, PTVGlassTier.LIGHT,
                context.dim(R.dimen.tv_spacing_medium).toFloat())
            val inset = context.dim(R.dimen.tv_spacing_small)
            setPadding(inset, inset / 2, inset, inset / 2)
        }
        ShowsFamilyTab.entries.forEach { tab ->
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val button = Button(context).apply {
                id = when (tab) {
                    ShowsFamilyTab.SHOWS -> R.id.ptv_shows_tab_shows
                    ShowsFamilyTab.ANIME -> R.id.ptv_shows_tab_anime
                    ShowsFamilyTab.CARTOONS -> R.id.ptv_shows_tab_cartoons
                }
                text = tab.label
                contentDescription = tab.label
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setTextSizeRes(R.dimen.tv_text_size_section_title)
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { select(tab) }
                onFocusChangeListener = View.OnFocusChangeListener { view, focused ->
                    PTVShapes.applyFocusEffect(view, focused)
                }
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                        event.action == KeyEvent.ACTION_DOWN
                    ) {
                        focusVisibleMediaItem(selection.currentViewport().focusedItemId)
                    } else false
                }
            }
            val underline = View(context)
            column.addView(button, LinearLayout.LayoutParams(
                context.dim(R.dimen.tv_nav_button_width),
                context.dim(R.dimen.tv_nav_button_height)
            ))
            column.addView(underline, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dim(R.dimen.ptv_material_border_width)
            ))
            segment.addView(column)
            tabs[tab] = button
            underlines[tab] = underline
        }
        strip.addView(segment)
        updateTabSelection()
        return strip
    }

    private fun select(tab: ShowsFamilyTab) {
        if (tab == selection.selected) return
        val (firstVisible, topOffset) = currentShelfViewport()
        selection.rememberViewport(firstVisible, topOffset)
        selection.switchTo(tab)
        selectionStore.save(session, tab)
        replaceDiscoveryPage()
        selection.currentViewport().let {
            restoreShelfViewport(it.firstVisiblePosition, it.topOffset)
        }
        updateTabSelection()
        tabs[tab]?.requestFocus()
    }

    private fun updateTabSelection() {
        if (!::selection.isInitialized) return
        tabs.forEach { (tab, button) ->
            val selected = tab == selection.selected
            button.isSelected = selected
            button.setTextColor(if (selected) PTVColors.accent else PTVColors.textSecondary)
            underlines[tab]?.apply {
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
                setBackgroundColor(PTVColors.accent)
            }
        }
        (activity as? PtvHostActivity)?.updateShowsSecondaryControls(
            tabs.values.toList(), tabs[selection.selected]
        )
    }

    private companion object {
        const val STATE_SELECTED_TAB = "shows_family_selected_tab"
    }
}
