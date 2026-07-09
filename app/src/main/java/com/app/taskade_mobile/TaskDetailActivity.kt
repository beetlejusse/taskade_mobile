package com.app.taskade_mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import com.app.taskade_mobile.core.ServiceLocator
import com.app.taskade_mobile.task.Priority
import com.app.taskade_mobile.task.Task
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Read-only detail view for a single task, opened by tapping a task card. Shows
 * every field carried from the list (status, priority, due, created, type, parent,
 * description) and offers a permanent delete that returns to the list on success.
 *
 * The task is passed via [intent] extras (no extra network round-trip needed —
 * the list already has the full record).
 */
class TaskDetailActivity : AppCompatActivity() {

    private lateinit var taskId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_detail)

        taskId = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (taskId.isBlank()) {
            finish()
            return
        }

        bind()
        findViewById<ImageButton>(R.id.detailBack).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.detailDeleteButton).setOnClickListener { confirmDelete() }
        playEntrance()
    }

    /**
     * Entrance choreography matching the Settings screen: the title and cards
     * lift/fade in with a stagger, then the delete action fades up last. Set up
     * pre-layout ([doOnPreDraw]) so the first frame is already in the start state.
     */
    private fun playEntrance() {
        val content = findViewById<ViewGroup>(R.id.detailContent)
        val deleteButton = findViewById<View>(R.id.detailDeleteButton)
        val lift = 24f * resources.displayMetrics.density

        val rows = content.children.toList()
        rows.forEach { it.alpha = 0f; it.translationY = lift }
        deleteButton.alpha = 0f

        content.doOnPreDraw {
            rows.forEachIndexed { index, row ->
                row.animate().alpha(1f).translationY(0f)
                    .setStartDelay(60L + index * 70L).setDuration(320)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
            deleteButton.animate().alpha(1f)
                .setStartDelay(60L + rows.size * 70L).setDuration(260).start()
        }
    }

    override fun finish() {
        super.finish()
        // Mirror the fade-through used to open this screen.
        overridePendingTransition(R.anim.fade_through_in, R.anim.fade_through_out)
    }

    private fun bind() {
        findViewById<TextView>(R.id.detailTaskTitle).text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty()
        setValue(R.id.detailStatus, intent.getStringExtra(EXTRA_STATUS))
        setValue(R.id.detailDue, intent.getStringExtra(EXTRA_DUE))
        setValue(R.id.detailCreated, intent.getStringExtra(EXTRA_CREATED))
        setValue(R.id.detailType, intent.getStringExtra(EXTRA_TYPE))
        setValue(R.id.detailDescription, intent.getStringExtra(EXTRA_DESCRIPTION))
        bindPriority()
        bindParent()
    }

    private fun bindPriority() {
        val priority = runCatching {
            Priority.valueOf(intent.getStringExtra(EXTRA_PRIORITY) ?: Priority.MEDIUM.name)
        }.getOrDefault(Priority.MEDIUM)
        val label = when (priority) {
            Priority.HIGH -> R.string.priority_high
            Priority.MEDIUM -> R.string.priority_medium
            Priority.LOW -> R.string.priority_low
        }
        findViewById<TextView>(R.id.detailPriority).apply {
            text = getString(label)
            setTextColor(ContextCompat.getColor(this@TaskDetailActivity, priority.colorRes))
        }
    }

    private fun bindParent() {
        val parent = intent.getStringExtra(EXTRA_PARENT)
        val label = findViewById<TextView>(R.id.detailParentLabel)
        val value = findViewById<TextView>(R.id.detailParent)
        if (parent.isNullOrBlank()) {
            label.visibility = View.GONE
            value.visibility = View.GONE
        } else {
            value.text = parent
        }
    }

    private fun setValue(id: Int, value: String?) {
        findViewById<TextView>(id).text =
            if (value.isNullOrBlank()) getString(R.string.task_detail_none) else value
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.task_delete_confirm_title)
            .setMessage(R.string.task_delete_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteTask() }
            .show()
    }

    private fun deleteTask() {
        val button = findViewById<MaterialButton>(R.id.detailDeleteButton)
        button.isEnabled = false
        lifecycleScope.launch {
            val result = ServiceLocator.taskRepository.deleteTask(taskId)
            if (result.isSuccess) {
                Toast.makeText(this@TaskDetailActivity, R.string.task_deleted, Toast.LENGTH_SHORT).show()
                finish() // TasksActivity reloads its list in onStart
            } else {
                button.isEnabled = true
                Toast.makeText(this@TaskDetailActivity, R.string.task_delete_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "extra_id"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_DESCRIPTION = "extra_description"
        private const val EXTRA_STATUS = "extra_status"
        private const val EXTRA_PRIORITY = "extra_priority"
        private const val EXTRA_DUE = "extra_due"
        private const val EXTRA_CREATED = "extra_created"
        private const val EXTRA_TYPE = "extra_type"
        private const val EXTRA_PARENT = "extra_parent"

        fun intent(context: Context, task: Task): Intent =
            Intent(context, TaskDetailActivity::class.java).apply {
                putExtra(EXTRA_ID, task.id)
                putExtra(EXTRA_TITLE, task.title)
                putExtra(EXTRA_DESCRIPTION, task.description)
                putExtra(EXTRA_STATUS, task.status)
                putExtra(EXTRA_PRIORITY, task.priority.name)
                putExtra(EXTRA_DUE, task.due)
                putExtra(EXTRA_CREATED, task.created)
                putExtra(EXTRA_TYPE, task.taskType)
                putExtra(EXTRA_PARENT, task.parentTitle)
            }
    }
}
