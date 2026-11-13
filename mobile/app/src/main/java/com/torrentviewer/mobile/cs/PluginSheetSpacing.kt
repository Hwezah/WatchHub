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
        // Idempotent: a row already spaced has a margin, so a later pass leaves it be.
        v.viewTreeObserver.addOnGlobalLayoutListener { space(v, gap) }
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
     * A row of pill buttons (Select All, Deselect All…): each as wide as the others, sharing the row's width,
     * with a gap between, and its words centred. Only a horizontal row where every child is a button.
     */
    private fun evenPills(v: ViewGroup, gap: Int) {
        if (v !is LinearLayout || v.orientation != LinearLayout.HORIZONTAL || v.childCount < 2) return
        val pills = (0 until v.childCount).map(v::getChildAt).filter { it.visibility != View.GONE }
        if (pills.size < 2 || !pills.all(::isRow)) return
        pills.forEachIndexed { i, c ->
            val lp = c.layoutParams as? LinearLayout.LayoutParams ?: return@forEachIndexed
            // Done already: its width is the row's share.
            if (lp.width == 0 && lp.weight == 1f) return@forEachIndexed
            lp.width = 0
            lp.weight = 1f
            lp.marginStart = if (i == 0) 0 else gap / 2
            lp.marginEnd = if (i == pills.lastIndex) 0 else gap / 2
            c.layoutParams = lp
            when (c) {
                is android.widget.TextView -> c.gravity = android.view.Gravity.CENTER
                is LinearLayout -> c.gravity = android.view.Gravity.CENTER
            }
        }
    }

    private fun space(v: View, gap: Int) {
        if (v !is ViewGroup) return
        evenPills(v, gap)
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
