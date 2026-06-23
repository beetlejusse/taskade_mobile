package com.app.taskade_mobile.task

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.app.taskade_mobile.R

/** Grid (2-column) vs list (full-width rows) presentation of the same tasks. */
enum class TaskViewMode { GRID, LIST }

/**
 * Renders [tasks] either as grid cards or list rows. Both layouts share the same
 * view ids, so a single [TaskViewHolder] binds both — only the inflated layout
 * differs by [viewMode].
 */
class TaskAdapter(
    private val tasks: List<Task>,
    var viewMode: TaskViewMode = TaskViewMode.GRID
) : RecyclerView.Adapter<TaskAdapter.TaskViewHolder>() {

    override fun getItemViewType(position: Int): Int =
        if (viewMode == TaskViewMode.GRID) TYPE_GRID else TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val layout = if (viewType == TYPE_GRID) R.layout.item_task_grid else R.layout.item_task_list
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        holder.bind(tasks[position])
    }

    override fun getItemCount(): Int = tasks.size

    class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.taskTitle)
        private val desc: TextView = itemView.findViewById(R.id.taskDesc)
        private val status: TextView = itemView.findViewById(R.id.taskStatusChip)
        private val due: TextView = itemView.findViewById(R.id.taskDueText)
        private val priority: View = itemView.findViewById(R.id.taskPriority)

        fun bind(task: Task) {
            title.text = task.title
            desc.text = task.description
            status.text = task.status
            due.text = task.due
            val color = ContextCompat.getColor(itemView.context, task.priority.colorRes)
            priority.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private companion object {
        const val TYPE_GRID = 0
        const val TYPE_LIST = 1
    }
}
