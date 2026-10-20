package com.airkeyboard.ui.remotes

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.airkeyboard.R
import com.airkeyboard.databinding.ItemRemoteBinding

/**
 * The one-line list row shared by the quick commands, bookmarks and the app switcher.
 * [subtitle] shows a second line when not null; [onLongClick] and [onClose] (a close icon) are optional.
 * [look] only changes how the rows look. Items should be data classes: rows are diffed by equality.
 */
class RemoteAdapter<T : Any>(
    private val title: (T) -> String,
    private val subtitle: (T) -> String? = { null },
    private val onLongClick: ((T) -> Unit)? = null,
    private val onClose: ((T) -> Unit)? = null,
    private val look: Look = Look.COMMAND,
    private val onExecute: (T) -> Unit
) : ListAdapter<T, RemoteAdapter.RemoteViewHolder>(RemoteDiffCallback<T>()) {

    /** COMMAND: separate glass rows (a grid). WINDOW / BOOKMARK: rows in one glass card, with dividers. */
    enum class Look { COMMAND, WINDOW, BOOKMARK }

    companion object {
        /** Bookmark letter tiles; a name always gets the same one */
        private val TILE_COLORS = intArrayOf(
            R.color.tile_bookmarks, R.color.tile_power, R.color.tile_airmouse, R.color.tile_apps, R.color.tile_presentation
        )
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        if (look != Look.COMMAND) recyclerView.addItemDecoration(RowDividers(recyclerView.context))
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteViewHolder {
        val binding = ItemRemoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        if (look != Look.COMMAND) binding.apply {
            val dp = parent.resources.displayMetrics.density
            (root.layoutParams as ViewGroup.MarginLayoutParams).setMargins(0, 0, 0, 0)
            root.setBackgroundResource(R.drawable.bg_row_press)
            if (look == Look.WINDOW) {
                root.minimumHeight = (56 * dp).toInt()
                root.setPaddingRelative((18 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                ivChevron.visibility = View.GONE
            } else {
                root.minimumHeight = (64 * dp).toInt()
                root.setPaddingRelative((16 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt(), (4 * dp).toInt())
                tvLetter.visibility = View.VISIBLE
                tvName.textSize = 17f
                tvName.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        }
        return RemoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RemoteViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.apply {
            val name = title(item)
            tvName.text = name
            val sub = subtitle(item)
            tvSubtitle.text = sub
            tvSubtitle.visibility = if (sub == null) View.GONE else View.VISIBLE
            if (look == Look.BOOKMARK) {
                tvLetter.text = name.trim().take(1).uppercase()
                val color = TILE_COLORS[name.hashCode().mod(TILE_COLORS.size)]
                tvLetter.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(root.context, color))
            }
            root.setOnClickListener { onExecute(item) }
            if (onLongClick != null) root.setOnLongClickListener { onLongClick.invoke(item); true }
            btnClose.visibility = if (onClose == null) View.GONE else View.VISIBLE
            btnClose.setOnClickListener { onClose?.invoke(item) }
        }
    }

    class RemoteViewHolder(val binding: ItemRemoteBinding) : RecyclerView.ViewHolder(binding.root)

    class RemoteDiffCallback<T : Any> : DiffUtil.ItemCallback<T>() {
        override fun areItemsTheSame(oldItem: T, newItem: T) = oldItem == newItem

        @SuppressLint("DiffUtilEquals")
        override fun areContentsTheSame(oldItem: T, newItem: T) = oldItem == newItem
    }

    /** A white 10% line along the top of every row but the first */
    private class RowDividers(context: Context) : RecyclerView.ItemDecoration() {
        private val paint = Paint().apply { color = ContextCompat.getColor(context, R.color.divider) }
        private val height = context.resources.displayMetrics.density

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (parent.getChildAdapterPosition(child) <= 0) continue
                val top = child.top + child.translationY
                c.drawRect(child.left.toFloat(), top, child.right.toFloat(), top + height, paint)
            }
        }
    }
}
