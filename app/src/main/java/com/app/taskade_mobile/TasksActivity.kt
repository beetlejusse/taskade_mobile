package com.app.taskade_mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.app.taskade_mobile.core.enableSeamlessEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.app.taskade_mobile.auth.AuthManager
import com.app.taskade_mobile.core.ServiceLocator
import com.app.taskade_mobile.task.Task
import com.app.taskade_mobile.task.TaskAdapter
import com.app.taskade_mobile.task.TaskViewMode
import com.app.taskade_mobile.ui.DockView
import com.app.taskade_mobile.ui.GlassNavDockView
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.launch

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
    private lateinit var dock: GlassNavDockView
    private lateinit var userNameLabel: TextView
    private lateinit var countLabel: TextView
    private lateinit var emptyState: View

    // The empty state / count line only render once the FIRST data answer exists
    // (cache hit or fetch completion) — never during the initial loading gap, so
    // "No tasks yet" can't flash before real tasks arrive.
    private var firstLoadDone = false

    // Re-entry guard for the view-toggle icon spin.
    private var toggleAnimating = false

    private val adapter = TaskAdapter(
        initial = emptyList(),
        viewMode = TaskViewMode.GRID,
        onTaskClick = { openTaskDetail(it) },
        onDeleteClick = { confirmDelete(it) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSeamlessEdgeToEdge()
        setContentView(R.layout.activity_tasks)

        if (!authManager.isAuthenticated) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        ServiceLocator.init(this)

        taskList = findViewById(R.id.taskList)
        toggleButton = findViewById(R.id.viewToggleButton)
        dock = findViewById(R.id.dock)
        userNameLabel = findViewById(R.id.tasksUserName)
        countLabel = findViewById(R.id.tasksCountLabel)
        emptyState = findViewById(R.id.tasksEmptyState)

        // Placeholder until the signed-in user's name resolves from the profile.
        userNameLabel.text = getString(R.string.tasks_user_placeholder)

        taskList.adapter = adapter
        taskList.setHasFixedSize(true)
        taskList.setItemViewCacheSize(12)
        applyViewMode(TaskViewMode.GRID)
        toggleButton.setOnClickListener { toggleViewMode() }

        // Any data mutation (refetch diff, optimistic delete, revert) re-evaluates
        // the count line and empty state from one place.
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = refreshTaskMeta()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refreshTaskMeta()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refreshTaskMeta()
        })

        loadProfileName()

        // Persistent dock: blur-through, active Tasks state, Chat button returns to chat.
        dock.setupBlur(findViewById<BlurTarget>(R.id.blurTarget))
        dock.setActiveItem(DockView.Item.TASKS)
        dock.setOnNavClickListener { item ->
            when (item) {
                DockView.Item.CHAT -> {
                    startActivity(
                        Intent(this, ChatActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                    overridePendingTransition(R.anim.fade_through_in, R.anim.fade_through_out)
                }
                DockView.Item.PROFILE -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    overridePendingTransition(R.anim.slide_up, R.anim.stay)
                }
                else -> Unit // Tasks is current; Calendar is a placeholder.
            }
        }

        applyWindowInsets()
    }

    override fun onStart() {
        super.onStart()
        if (authManager.isAuthenticated) {
            // Paint the last-known list instantly (no empty flash), then refresh so
            // changes made by voice (tool.result) or on web show up.
            ServiceLocator.taskRepository.cachedTasks?.let {
                adapter.submit(it)
                // A cache hit is a real answer — meta can render immediately.
                firstLoadDone = true
                refreshTaskMeta()
            }
            loadTasks()
        }
    }

    /** Loads the persisted task tree (`GET /tasks`) and binds it to the adapter. */
    private fun loadTasks() {
        lifecycleScope.launch {
            ServiceLocator.taskRepository.getTasks().getOrNull()?.let {
                // Cold start without a cache: the first layout already consumed the
                // XML layoutAnimation on an empty list, so replay the cascade for
                // the first real content.
                if (adapter.itemCount == 0 && it.isNotEmpty()) taskList.scheduleLayoutAnimation()
                adapter.submit(it)
            }
            // Resolved (even on failure): an empty list is now an answer, not a
            // loading gap, so the empty state / count line may appear.
            firstLoadDone = true
            refreshTaskMeta()
        }
    }

    /**
     * Renders the header count line and the empty state from the adapter's current
     * contents. No-op until the first load resolves. Show/hide is always animated;
     * a newer call's animate() supersedes an in-flight one, so rapid flips
     * (optimistic delete then revert) can't leave the view half-faded or mis-hidden.
     */
    private fun refreshTaskMeta() {
        if (!firstLoadDone) return

        val count = adapter.itemCount
        countLabel.text = if (count == 0) {
            getString(R.string.tasks_count_empty)
        } else {
            resources.getQuantityString(R.plurals.tasks_count, count, count)
        }
        if (countLabel.alpha < 1f) countLabel.animate().alpha(1f).setDuration(220).start()

        val showEmpty = count == 0
        if (showEmpty && emptyState.visibility != View.VISIBLE) {
            emptyState.visibility = View.VISIBLE
            emptyState.alpha = 0f
            emptyState.translationY = dp(16).toFloat()
            emptyState.animate().alpha(1f).translationY(0f)
                .setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        } else if (!showEmpty && emptyState.visibility == View.VISIBLE) {
            emptyState.animate().alpha(0f).setDuration(150)
                .withEndAction { emptyState.visibility = View.GONE }
                .start()
        }
    }

    /** Resolves the signed-in user's display name (`GET /profile`) for the header. */
    private fun loadProfileName() {
        // Instant paint from the cached profile (avoids the placeholder flash), then refresh.
        ServiceLocator.profileRepository.cached?.displayName?.let {
            if (it.isNotBlank()) userNameLabel.text = it
        }
        lifecycleScope.launch {
            val name = ServiceLocator.profileRepository.getProfile().getOrNull()?.displayName
            if (!name.isNullOrBlank()) userNameLabel.text = name
        }
    }

    private fun openTaskDetail(task: Task) {
        startActivity(TaskDetailActivity.intent(this, task))
        overridePendingTransition(R.anim.fade_through_in, R.anim.fade_through_out)
    }

    /** Confirms before a permanent delete (the action can't be undone). */
    private fun confirmDelete(task: Task) {
        AlertDialog.Builder(this)
            .setTitle(R.string.task_delete_confirm_title)
            .setMessage(R.string.task_delete_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteTask(task) }
            .show()
    }

    /** Optimistically removes the task, then deletes on the backend (reverting on failure). */
    private fun deleteTask(task: Task) {
        adapter.removeTask(task.id)
        lifecycleScope.launch {
            val result = ServiceLocator.taskRepository.deleteTask(task.id)
            if (result.isSuccess) {
                Toast.makeText(this@TasksActivity, R.string.task_deleted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@TasksActivity, R.string.task_delete_failed, Toast.LENGTH_LONG).show()
                loadTasks() // restore the authoritative list
            }
        }
    }

    /**
     * Grid <-> list with choreography: the toggle icon spins out, the mode (and
     * icon) swaps, the icon spins back in, and the cards replay their staggered
     * cascade in the new arrangement. [toggleAnimating] ignores taps mid-swap.
     */
    private fun toggleViewMode() {
        if (toggleAnimating) return
        toggleAnimating = true
        val next = if (adapter.viewMode == TaskViewMode.GRID) TaskViewMode.LIST else TaskViewMode.GRID
        toggleButton.animate().rotation(90f).alpha(0f).setDuration(110)
            .withEndAction {
                applyViewMode(next)
                taskList.scheduleLayoutAnimation()
                toggleButton.rotation = -90f
                toggleButton.animate().rotation(0f).alpha(1f).setDuration(160)
                    .withEndAction { toggleAnimating = false }
                    .start()
            }
            .start()
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
        val dockBaseMargin = dp(28)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.tasksRoot)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            header.updatePadding(top = headerBaseTop + bars.top)
            dock.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = dockBaseMargin + bars.bottom
            }
            // Reserve room so the last card never hides under the floating dock (incl. chip).
            taskList.updatePadding(bottom = dp(162) + bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val GRID_COLUMNS = 2
    }
}
