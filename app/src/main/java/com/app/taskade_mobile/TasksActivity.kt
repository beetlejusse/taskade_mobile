package com.app.taskade_mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.task.Task
import com.app.taskade_mobile.task.TaskAdapter
import com.app.taskade_mobile.task.TaskViewMode
import com.app.taskade_mobile.ui.DockView
import eightbitlab.com.blurview.BlurTarget

/**
 * Task screen — opened from the dock's first (Tasks) button. Shows mock tasks in
 * a 2-column grid by default, toggleable to a full-width list. The persistent
 * [DockView] floats above the scrolling content with live blur-through, and its
 * Tasks button is shown as the active item.
 */
class TasksActivity : AppCompatActivity() {

    private val authManager by lazy { AuthManager.getInstance(this) }

    private lateinit var taskList: RecyclerView
    private lateinit var toggleButton: ImageButton
    private lateinit var dock: DockView

    private val adapter = TaskAdapter(Task.mockTasks(), TaskViewMode.GRID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tasks)

        if (!authManager.isAuthenticated) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        taskList = findViewById(R.id.taskList)
        toggleButton = findViewById(R.id.viewToggleButton)
        dock = findViewById(R.id.dock)

        // Placeholder name — injected dynamically once user fetching is wired up.
        findViewById<TextView>(R.id.tasksUserName).text = getString(R.string.tasks_user_placeholder)

        taskList.adapter = adapter
        applyViewMode(TaskViewMode.GRID)
        toggleButton.setOnClickListener { toggleViewMode() }

        // Persistent dock: blur-through, active Tasks state, Chat button returns to chat.
        dock.setupBlur(findViewById<BlurTarget>(R.id.blurTarget))
        dock.setActiveItem(DockView.Item.TASKS)
        dock.setOnNavClickListener { item ->
            when (item) {
                DockView.Item.CHAT -> startActivity(
                    Intent(this, ChatActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
                DockView.Item.PROFILE -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(R.anim.slide_up, R.anim.stay)
                }
                else -> Unit // Tasks is current; Calendar is a placeholder.
            }
        }

        applyWindowInsets()
    }

    private fun toggleViewMode() {
        val next = if (adapter.viewMode == TaskViewMode.GRID) TaskViewMode.LIST else TaskViewMode.GRID
        applyViewMode(next)
    }

    private fun applyViewMode(mode: TaskViewMode) {
        adapter.viewMode = mode
        taskList.layoutManager = when (mode) {
            TaskViewMode.GRID -> GridLayoutManager(this, GRID_COLUMNS)
            TaskViewMode.LIST -> LinearLayoutManager(this)
        }
        adapter.notifyDataSetChanged()
        // Icon reflects the currently active mode.
        toggleButton.setImageResource(
            if (mode == TaskViewMode.GRID) R.drawable.ic_grid_view else R.drawable.ic_list_view
        )
    }

    private fun applyWindowInsets() {
        val header = findViewById<View>(R.id.tasksHeader)
        val headerBaseTop = header.paddingTop
        val dockBaseMargin = dp(18)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.tasksRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = headerBaseTop + bars.top)
            dock.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = dockBaseMargin + bars.bottom
            }
            // Reserve room so the last card never hides under the floating dock (incl. chip).
            taskList.updatePadding(bottom = dp(152) + bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val GRID_COLUMNS = 2
    }
}
