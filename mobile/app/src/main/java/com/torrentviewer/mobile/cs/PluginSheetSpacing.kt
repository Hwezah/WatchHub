package com.torrentviewer.mobile.cs

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

/**
 * Breathing room in plugins' own settings sheets (StreamPlay's and others'): their option rows come stacked
 * edge to edge. Every fragment a plugin shows gets, in each vertical list, a small gap under each row — a
 * child with a background that is a card or can be tapped — that has no margin of its own. Rows a list adds
 * later (a RecyclerView scrolling) are spaced as they appear. A row of pill buttons is evened out ([evenPills]).
 * The plugin's own layout is otherwise untouched.
 */
object PluginSheetSpacing : FragmentManager.FragmentLifecycleCallbacks() {
    private const val GAP_DP = 8

    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
        // Only a plugin's: its classes come from its own class loader, not the app's.
        if (f.javaClass.classLoader === PluginSheetSpacing::class.java.classLoader) return
        val gap = (GAP_DP * v.resources.displayMetrics.density).toInt()
        space(v, gap)
        (v as? ViewGroup)?.let { evenPills(it, gap) }
        // Idempotent: a row already spaced has a margin, so a later pass leaves it be.
        v.viewTreeObserver.addOnGlobalLayoutListener {
            space(v, gap)
            (v as? ViewGroup)?.let { evenPills(it, gap) }
        }
    }

    private fun named(v: View, part: String): Boolean {
        var c: Class<*>? = v.javaClass
        while (c != null) {
            if (c.name.contains(part)) return true
            c = c.superclass
        }
        return false
    }

    private fun isRow(c: View) = c.visibility == View.VISIBLE && c.background != null && (c.isClickable || named(c, "CardView"))

    /**
     * Rows of pill buttons (Select All, Deselect All…), as the app's own: every pill in the sheet as wide as
     * the widest, words centred; three or more in a row share its width, a pair sits from the start. Only a
     * horizontal row where every child is a button. Widths are known once laid out, so this runs on each layout
     * pass and changes nothing once they match.
     */
    private fun evenPills(root: ViewGroup, gap: Int) {
        val rows = mutableListOf<List<View>>()
        fun find(v: View) {
            if (v !is ViewGroup) return
            if (v is LinearLayout && v.orientation == LinearLayout.HORIZONTAL) {
                val pills = (0 until v.childCount).map(v::getChildAt).filter { it.visibility != View.GONE }
                if (pills.size >= 2 && pills.all(::isRow)) rows += pills
            }
            for (i in 0 until v.childCount) find(v.getChildAt(i))
        }
        find(root)
        val widest = rows.flatten().maxOfOrNull { it.width } ?: return
        if (widest <= 0) return
        rows.forEach { pills ->
            pills.forEachIndexed { i, c ->
                val lp = c.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
                val spread = pills.size >= 3
                val start = if (i == 0) 0 else if (spread) gap / 2 else gap
                val end = if (spread && i != pills.lastIndex) gap / 2 else 0
                val changed = (spread && (lp.width != 0 || lp.weight != 1f)) || lp.marginStart != start || lp.marginEnd != end
                if (changed) {
                    if (spread) { lp.width = 0; lp.weight = 1f }
                    lp.marginStart = start
                    lp.marginEnd = end
                    c.layoutParams = lp
                }
                if (!spread && c.minimumWidth < widest) c.minimumWidth = widest
                when (c) {
                    is android.widget.TextView -> if (c.gravity != android.view.Gravity.CENTER) c.gravity = android.view.Gravity.CENTER
                    is LinearLayout -> if (c.gravity != android.view.Gravity.CENTER) c.gravity = android.view.Gravity.CENTER
                }
            }
        }
    }

    private fun space(v: View, gap: Int) {
        if (v !is ViewGroup) return
        val list = (v is LinearLayout && v.orientation == LinearLayout.VERTICAL) || named(v, "RecyclerView")
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (list && isRow(c)) {
                val lp = c.layoutParams as? ViewGroup.MarginLayoutParams
                if (lp != null && lp.topMargin == 0 && lp.bottomMargin == 0) {
                    lp.bottomMargin = gap
                    c.layoutParams = lp
                }
            }
            space(c, gap)
        }
    }
}
