package com.piggie.tv.ui.player

import android.view.View
import android.widget.LinearLayout

internal data class ActionFocusSnapshot(val description: String?, val index: Int)

internal object DetailsActionFocusPolicy {
    fun capture(focused: View?, actions: LinearLayout): ActionFocusSnapshot? {
        if (focused?.parent !== actions) return null
        val index = actions.indexOfChild(focused).takeIf { it >= 0 } ?: return null
        return ActionFocusSnapshot(focused.contentDescription?.toString(), index)
    }

    fun mayRestore(currentFocus: View?, actions: LinearLayout): Boolean =
        currentFocus == null || currentFocus.parent === actions
}
