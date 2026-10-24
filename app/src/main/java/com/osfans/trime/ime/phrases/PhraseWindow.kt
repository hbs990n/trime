/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.phrases

import android.app.AlertDialog
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.util.rippleDrawable
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams

/**
 * A small window opened from the candidate bar's "常用语" button. It lists the
 * user's common phrases top-to-bottom; tapping one commits it to the editor and
 * closes the window, long-pressing opens an edit / delete menu, and the "+"
 * button at the top-right opens a full-size editor to add a new phrase.
 */
class PhraseWindow(di: DI) : BoardWindow.BarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val windowManager: BoardWindowManager by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val theme: Theme get() = scope.theme

    override val showTitle: Boolean = true
    override val title: String get() = context.getString(R.string.phrase)

    private val previewLength = 10

    private var items: MutableList<String> = mutableListOf()
    private var popupMenu: PopupMenu? = null
    private var emptyView: TextView? = null

    private val addButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_add_24, scope).apply {
            setOnClickListener { showEditDialog(null, -1) }
        }
    }

    private val adapter =
        object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class Holder(val row: LinearLayout, val text: TextView) :
                RecyclerView.ViewHolder(row)

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): RecyclerView.ViewHolder {
                val text =
                    TextView(context).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setTextColor(scope.colors.candidateTextColor)
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams =
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                val row =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = context.dp(46)
                        setPadding(context.dp(16), context.dp(4), context.dp(12), context.dp(4))
                        background = rippleDrawable(scope.colors.hilitedCandidateBackColor)
                        addView(text)
                    }
                return Holder(row, text)
            }

            override fun onBindViewHolder(
                holder: RecyclerView.ViewHolder,
                position: Int,
            ) {
                holder as Holder
                val phrase = items[position]
                holder.text.text = previewOf(phrase)
                holder.text.setTextColor(scope.colors.candidateTextColor)
                holder.row.background = rippleDrawable(scope.colors.hilitedCandidateBackColor)
                holder.row.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) commit(items[pos])
                }
                holder.row.setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) showItemMenu(holder.row, pos)
                    true
                }
            }

            override fun getItemCount(): Int = items.size
        }

    private val recycler =
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@PhraseWindow.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
        }

    override fun onCreateBarView(): View {
        val size = context.dp(theme.style.run { candidateViewHeight + commentHeight })
        return constraintLayout {
            add(addButton, lParams(size, size) { endOfParent() })
        }
    }

    override fun onCreateView(): View {
        val hint =
            TextView(context).apply {
                setText(R.string.phrase_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.7f
                setPadding(context.dp(16), context.dp(6), context.dp(16), context.dp(6))
                setTextColor(scope.colors.candidateTextColor)
            }
        val empty =
            TextView(context).apply {
                setText(R.string.phrase_empty)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(context.dp(16), context.dp(24), context.dp(16), context.dp(24))
                setTextColor(scope.colors.candidateTextColor)
                visibility = View.GONE
            }
        emptyView = empty
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint)
            addView(empty)
            addView(
                recycler,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    override fun onAttached() {
        items = PhraseManager.load(context)
        refresh()
    }

    override fun onDetached() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    override fun refreshColors() {
        // Rebind the list so colors follow a scheme switch.
        adapter.notifyDataSetChanged()
    }

    private fun refresh() {
        adapter.notifyDataSetChanged()
        emptyView?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun previewOf(phrase: String): String {
        val flat = phrase.replace('\n', ' ')
        return if (flat.length <= previewLength) flat else flat.take(previewLength) + "…"
    }

    private fun commit(phrase: String) {
        service.commitText(phrase)
        windowManager.attachWindow(keyboardWindow)
    }

    private fun showItemMenu(anchor: View, position: Int) {
        popupMenu?.dismiss()
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, 0, 0, R.string.edit).setOnMenuItemClickListener {
            showEditDialog(items[position], position)
            true
        }
        popup.menu.add(0, 1, 0, R.string.delete).setOnMenuItemClickListener {
            items.removeAt(position)
            PhraseManager.save(context, items)
            refresh()
            true
        }
        popupMenu = popup
        popup.show()
    }

    /**
     * Opens a centered, full-size editor. [index] < 0 means "add a new phrase";
     * otherwise it edits the phrase at [index]. Saving writes through to
     * internal storage immediately.
     */
    private fun showEditDialog(existing: String?, index: Int) {
        val editText =
            EditText(context).apply {
                setText(existing ?: "")
                setSingleLine(false)
                gravity = Gravity.TOP or Gravity.START
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            }
        val scroll =
            ScrollView(context).apply {
                addView(
                    editText,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        val height = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
                addView(
                    scroll,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height),
                )
            }
        AlertDialog.Builder(context)
            .setTitle(if (existing == null) R.string.phrase_add else R.string.edit)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (index >= 0) items[index] = text else items.add(text)
                    PhraseManager.save(context, items)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
