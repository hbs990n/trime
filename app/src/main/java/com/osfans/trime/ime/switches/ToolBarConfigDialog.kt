/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.ToolBarConfig
import com.osfans.trime.ime.bar.ToolBarEntry
import splitties.dimensions.dp

/**
 * Dialog that lets the user reorder and remove the buttons of the candidate
 * bar. Rows are listed vertically in the same order the bar is shown
 * horizontally (first row = leftmost button). Drag the handle at the right
 * to reorder; long press a row to mark it as deleted or restore it. Deleted
 * rows stay visible at the bottom so they can always be brought back.
 */
class ToolBarConfigDialog(
    private val context: Context,
    private val theme: Theme,
) {
    private val configurable = theme.toolBar.buttons.drop(1)
    // Left-to-right order of the configurable buttons (same as the bar), so the
    // dialog lists them top-to-bottom with the leftmost button on top.
    private val availableOrder = configurable.map { it.action }
    private val entries: MutableList<ToolBarEntry> =
        ToolBarConfig.resolve(availableOrder).toMutableList()

    private var touchHelper: ItemTouchHelper? = null

    private fun persist() = ToolBarConfig.save(entries.toList())

    private fun toggleDeleted(position: Int) {
        val entry = entries.removeAt(position)
        if (entry.enabled) {
            entries.add(entry.copy(enabled = false))
        } else {
            val activeCount = entries.count { it.enabled }
            entries.add(activeCount, entry.copy(enabled = true))
        }
        adapter.notifyDataSetChanged()
        persist()
    }

    private val touchCallback =
        object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                val activeCount = entries.count { it.enabled }
                if (from >= activeCount || to >= activeCount) return false
                if (from == to) return false
                val entry = entries.removeAt(from)
                entries.add(to, entry)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                persist()
            }

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                val position = viewHolder.bindingAdapterPosition
                return if (position != RecyclerView.NO_POSITION && entries.getOrNull(position)?.enabled == true) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    makeMovementFlags(0, 0)
                }
            }
        }

    private val adapter =
        object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class Holder(
                root: LinearLayout,
                val content: LinearLayout,
                val number: TextView,
                val label: TextView,
                val status: TextView,
                val handle: TextView,
            ) : RecyclerView.ViewHolder(root)

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): RecyclerView.ViewHolder {
                val row =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = context.dp(52)
                        setPadding(context.dp(16), context.dp(8), context.dp(12), context.dp(8))
                    }

                val number =
                    TextView(context).apply {
                        gravity = Gravity.CENTER
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        layoutParams = LinearLayout.LayoutParams(context.dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
                    }

                val label =
                    TextView(context).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }

                val status =
                    TextView(context).apply {
                        setText(R.string.tool_bar_deleted)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        visibility = View.GONE
                        setPadding(context.dp(8), 0, context.dp(8), 0)
                    }

                val content =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(number)
                        addView(label)
                        addView(status)
                    }

                val handle =
                    TextView(context).apply {
                        text = "≡"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                        gravity = Gravity.CENTER
                        setPadding(context.dp(12), 0, context.dp(4), 0)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }

                row.addView(content)
                row.addView(handle)
                return Holder(row, content, number, label, status, handle)
            }

            override fun onBindViewHolder(
                holder: RecyclerView.ViewHolder,
                position: Int,
            ) {
                holder as Holder
                val entry = entries[position]
                val deleted = !entry.enabled
                holder.number.text = if (deleted) "—" else "${position + 1}"
                holder.label.text = ToolBarConfig.labelOf(theme, entry.action)
                holder.status.visibility = if (deleted) View.VISIBLE else View.GONE
                holder.content.alpha = if (deleted) 0.5f else 1f
                holder.handle.alpha = if (deleted) 0.5f else 1f
                holder.handle.visibility = if (deleted) View.INVISIBLE else View.VISIBLE
                holder.content.setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) toggleDeleted(pos)
                    true
                }
                holder.handle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        touchHelper?.startDrag(holder)
                    }
                    false
                }
            }

            override fun getItemCount(): Int = entries.size
        }

    fun create(): AlertDialog {
        touchHelper = ItemTouchHelper(touchCallback)
        val recycler =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                adapter = this@ToolBarConfigDialog.adapter
                overScrollMode = View.OVER_SCROLL_NEVER
            }
        touchHelper?.attachToRecyclerView(recycler)

        val height = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        val hint =
            TextView(context).apply {
                setText(R.string.tool_bar_config_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.7f
                setPadding(context.dp(16), context.dp(4), context.dp(16), context.dp(8))
            }
        val container =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(4), context.dp(8), context.dp(4), 0)
                addView(hint)
                addView(
                    recycler,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height),
                )
            }

        return AlertDialog
            .Builder(context)
            .setTitle(R.string.tool_bar_config)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }
}
