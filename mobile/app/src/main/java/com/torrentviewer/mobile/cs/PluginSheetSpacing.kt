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
 * later (a RecyclerView scrolling) are spaced as they appear. The plugin's own layout is otherwise untouched.
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
