package com.app.taskade_mobile.task

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.app.taskade_mobile.R
import com.app.taskade_mobile.ui.Motion

/** Grid (2-column) vs list (full-width rows) presentation of the same tasks. */
enum class TaskViewMode { GRID, LIST }

/**
 * Renders [tasks] either as grid cards or list rows. Both layouts share the same
 * view ids, so a single [TaskViewHolder] binds both — only the inflated layout
 * differs by [viewMode].
 *
 * Performance: stable ids + [DiffUtil] (via [submit]) so updates animate the
 * minimal change instead of rebinding everything; a tap opens the detail screen
 * ([onTaskClick]) and the trash button deletes ([onDeleteClick]).
 */
class TaskAdapter(
    initial: List<Task>,
    var viewMode: TaskViewMode = TaskViewMode.GRID,
    private val onTaskClick: (Task) -> Unit = {},
    private val onDeleteClick: (Task) -> Unit = {}
) : RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

    private val tasks: MutableList<Task> = initial.toMutableList()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = tasks[position].id.hashCode().toLong()

    /** Replaces the backing data with a minimal diff (after a `GET /tasks` refetch). */
    fun submit(items: List<Task>) {
        val diff = DiffUtil.calculateDiff(TaskDiff(tasks.toList(), items))
        tasks.clear()
        tasks.addAll(items)
        diff.dispatchUpdatesTo(this)
    }

    /** Removes a task locally (optimistic delete); returns its old index or -1. */
    fun removeTask(id: String): Int {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx >= 0) {
            tasks.removeAt(idx)
            notifyItemRemoved(idx)
        }
        return idx
    }

    override fun getItemViewType(position: Int): Int =
        if (viewMode == TaskViewMode.GRID) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val layout = if (viewType == TYPE_GRID) R.layout.item_task_grid else R.layout.item_task_list
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return TaskViewHolder(view, onTaskClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        // A view recycled mid-press can carry a partial squish — always bind clean.
        Motion.resetTransforms(holder.itemView)
        holder.bind(tasks[position])
    }

    override fun getItemCount(): Int = tasks.size

    class TaskViewHolder(
        itemView: View,
        private val onTaskClick: (Task) -> Unit,
        private val onDeleteClick: (Task) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.taskTitle)
        private val desc: TextView = itemView.findViewById(R.id.taskDesc)
        private val status: TextView = itemView.findViewById(R.id.taskStatusChip)
        private val due: TextView = itemView.findViewById(R.id.taskDueText)
        private val priority: View = itemView.findViewById(R.id.taskPriority)
        private val delete: ImageButton = itemView.findViewById(R.id.taskDelete)
        private var current: Task? = null

        init {
            itemView.setOnClickListener { current?.let(onTaskClick) }
            delete.setOnClickListener { current?.let(onDeleteClick) }
            // Cards squish slightly under the finger (Things/Todoist-style feedback).
            Motion.pressScale(itemView, depth = 0.97f)
        }

        fun bind(task: Task) {
            current = task
            title.text = task.title
            desc.text = task.description
            desc.visibility = if (task.description.isBlank()) View.GONE else View.VISIBLE
            status.text = task.status
            due.text = task.due
            val color = ContextCompat.getColor(itemView.context, task.priority.colorRes)
            priority.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private class TaskDiff(
        private val old: List<Task>,
        private val new: List<Task>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].id == new[n].id
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }

    private companion object {
        const val TYPE_GRID = 0
        const val TYPE_LIST = 1
    }
}
