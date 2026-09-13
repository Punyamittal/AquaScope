package com.aquascope.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aquascope.R
import com.aquascope.databinding.ActivityScreenmindBinding
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.brain.CaptureProjectionService
import com.aquascope.smriti.screenmind.ScreenMindBridge
import com.aquascope.smriti.screenmind.ScreenMindCategory
import com.aquascope.smriti.screenmind.ScreenMindLocalEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ScreenMindActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityScreenmindBinding
    private lateinit var localEngine: ScreenMindLocalEngine
    private lateinit var bridge: ScreenMindBridge
    private lateinit var haloBind: HaloBinding
    private val allItemsList = mutableListOf<ListRow>()
    private val displayedList = mutableListOf<ListRow>()
    private lateinit var adapter: ScreenMindAdapter
    private var activeFilter: String = "ALL"

    // ── Data models ──────────────────────────────────────────────────────────

    sealed class ListRow {
        data class DateHeader(val label: String) : ListRow()
        data class EpisodeRow(
            val id: String,
            val app: String,
            val category: String,
            val time: String,
            val timestampMs: Long,
            val summary: String,
            val details: String = "",
            val isPhysicalScan: Boolean = false,
            var isExpanded: Boolean = false
        ) : ListRow()
    }

    // ── Launcher ─────────────────────────────────────────────────────────────

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val metrics = resources.displayMetrics
            CaptureProjectionService.start(
                this,
                result.resultCode,
                result.data!!,
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi
            )
            Toast.makeText(this, "Live screen capture armed!", Toast.LENGTH_SHORT).show()
            captureCurrentViewSnapshot("Live MediaProjection Armed")
        } else {
            captureCurrentViewSnapshot("AquaScope Active View")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenmindBinding.inflate(layoutInflater)
        setContentView(binding.root)

        localEngine = ScreenMindLocalEngine.get(this)
        bridge = ScreenMindBridge.get(this)
        haloBind = HaloBinding(this, binding.haloIndicator)

        binding.inputServerUrl.setText(bridge.getServerUrl())

        adapter = ScreenMindAdapter(displayedList)
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.btnSearch.setOnClickListener {
            val q = binding.inputQuery.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) performSearch(q) else loadTimeline()
        }

        binding.btnAskDesktop.setOnClickListener {
            val q = binding.inputQuery.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) performChat(q)
            else Toast.makeText(this, "Enter a question for on-device AI memory", Toast.LENGTH_SHORT).show()
        }

        binding.btnCaptureScreen.setOnClickListener { triggerScreenCapture() }
        binding.btnSyncScans.setOnClickListener { syncPhysicalScans() }
        binding.btnSeedDemo.setOnClickListener { seedDemoData() }

        binding.chipFilterAll.setOnClickListener { applyFilter("ALL") }
        binding.chipFilterDesktop.setOnClickListener { applyFilter("SCREEN") }
        binding.chipFilterAquaScope.setOnClickListener { applyFilter("ACOUSTIC") }

        binding.btnToggleDevSync.setOnClickListener {
            val isVis = binding.layoutDevSync.visibility == View.VISIBLE
            binding.layoutDevSync.visibility = if (isVis) View.GONE else View.VISIBLE
            binding.btnToggleDevSync.text =
                if (isVis) "▶ Developer PC Link (optional)" else "▼ Developer PC Link (optional)"
        }

        binding.btnConnect.setOnClickListener {
            val url = binding.inputServerUrl.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                bridge.setServerUrl(url)
                checkRemoteConnection()
            }
        }

        loadTimeline()
    }

    override fun onResume() { super.onResume(); haloBind.start() }
    override fun onPause() { haloBind.stop(); super.onPause() }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadTimeline() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.layoutEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)

        lifecycleScope.launch {
            val items = localEngine.getTimeline(50)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.clear()
            allItemsList.addAll(buildRowsWithDateHeaders(items.map { ep ->
                ListRow.EpisodeRow(
                    id = ep.id,
                    app = ep.appName,
                    category = ep.category,
                    time = ep.timestamp,
                    timestampMs = ep.timestampMs,
                    summary = ep.summary,
                    details = ep.details,
                    isPhysicalScan = ep.isPhysicalScan
                )
            }))
            applyFilter(activeFilter)
            updateCategoryStrip()
        }
    }

    private fun performSearch(query: String) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.layoutEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)

        lifecycleScope.launch {
            val items = localEngine.search(query)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.clear()
            // Search results: no date headers, flat ranked list
            allItemsList.addAll(items.map { ep ->
                ListRow.EpisodeRow(
                    id = ep.id,
                    app = ep.appName,
                    category = ep.category,
                    time = ep.timestamp,
                    timestampMs = ep.timestampMs,
                    summary = ep.summary,
                    details = if (ep.details.isNotBlank()) ep.details
                    else "Match relevance: ${(ep.score * 100).toInt()}%",
                    isPhysicalScan = ep.isPhysicalScan
                )
            })
            applyFilter(activeFilter)
        }
    }

    private fun performChat(query: String) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.layoutEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.PROCESSING)

        lifecycleScope.launch {
            val answer = localEngine.ask(query)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.add(
                0,
                ListRow.EpisodeRow(
                    id = "chat-${System.currentTimeMillis()}",
                    app = "ScreenMind AI",
                    category = "ON_DEVICE_SYNTHESIS",
                    time = "Just now",
                    timestampMs = System.currentTimeMillis(),
                    summary = answer,
                    details = "Synthesized 100% on-device using local episodic screen memory and acoustic pipeline scans. No internet required.",
                    isExpanded = true
                )
            )
            applyFilter("ALL")
        }
    }

    // ── Screen capture ────────────────────────────────────────────────────────

    private fun triggerScreenCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mgr != null && !CaptureProjectionService.isRunning()) {
            try {
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            } catch (_: Throwable) {
                captureCurrentViewSnapshot("AquaScope Screen")
            }
        } else {
            captureCurrentViewSnapshot("AquaScope Screen")
        }
    }

    private fun captureCurrentViewSnapshot(appHint: String) {
        binding.progressLoading.visibility = View.VISIBLE
        haloBind.controller().setState(SmritiLightState.EXTRACTION)

        lifecycleScope.launch {
            try {
                val root = window.decorView.rootView
                val bitmap = Bitmap.createBitmap(
                    root.width.coerceAtLeast(720),
                    root.height.coerceAtLeast(1280),
                    Bitmap.Config.ARGB_8888
                )
                Canvas(bitmap).also { root.draw(it) }

                localEngine.processBitmapAndIngest(bitmap, appHint)
                binding.progressLoading.visibility = View.GONE
                haloBind.controller().setState(SmritiLightState.NORMAL)

                Toast.makeText(this@ScreenMindActivity, "📸 Screen captured & OCR extracted!", Toast.LENGTH_SHORT).show()
                loadTimeline()
            } catch (t: Throwable) {
                binding.progressLoading.visibility = View.GONE
                haloBind.controller().setState(SmritiLightState.NORMAL)
                Toast.makeText(this@ScreenMindActivity, "Capture note: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncPhysicalScans() {
        binding.progressLoading.visibility = View.VISIBLE
        haloBind.controller().setState(SmritiLightState.PROCESSING)
        lifecycleScope.launch {
            val count = localEngine.syncPhysicalAcousticScans()
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)
            Toast.makeText(this@ScreenMindActivity, "Merged $count acoustic scans!", Toast.LENGTH_SHORT).show()
            loadTimeline()
        }
    }

    private fun seedDemoData() {
        binding.progressLoading.visibility = View.VISIBLE
        haloBind.controller().setState(SmritiLightState.PROCESSING)
        lifecycleScope.launch {
            val count = localEngine.seedDemoData()
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)
            Toast.makeText(this@ScreenMindActivity, "✨ Seeded $count demo episodes!", Toast.LENGTH_SHORT).show()
            loadTimeline()
        }
    }

    private fun checkRemoteConnection() {
        binding.textStatusIndicator.text = "PROBING…"
        binding.textStatusIndicator.setTextColor(getColor(R.color.amber))
        lifecycleScope.launch {
            val status = bridge.probeConnection()
            if (status.connected) {
                binding.textStatusIndicator.text = "ONLINE"
                binding.textStatusIndicator.setTextColor(getColor(R.color.cyan))
                Toast.makeText(this@ScreenMindActivity, "Connected to desktop endpoint", Toast.LENGTH_SHORT).show()
            } else {
                binding.textStatusIndicator.text = "OFFLINE"
                binding.textStatusIndicator.setTextColor(getColor(R.color.status_alert))
                Toast.makeText(this@ScreenMindActivity, "Remote not reachable (local engine active)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Filtering & UI ────────────────────────────────────────────────────────

    private fun applyFilter(filter: String) {
        activeFilter = filter

        // Chip visual state — active = glass elevated fill, idle = pill outline
        fun chipActive(chip: TextView, isActive: Boolean) {
            chip.setBackgroundResource(
                if (isActive) R.drawable.bg_glass_card_elevated else R.drawable.bg_glass_pill
            )
            chip.setTextColor(getColor(if (isActive) R.color.cyan else R.color.warm_faint))
        }
        chipActive(binding.chipFilterAll, filter == "ALL")
        chipActive(binding.chipFilterDesktop, filter == "SCREEN")
        chipActive(binding.chipFilterAquaScope, filter == "ACOUSTIC")

        val episodesOnly = allItemsList.filterIsInstance<ListRow.EpisodeRow>()
        val filtered = when (filter) {
            "SCREEN" -> episodesOnly.filter { !it.isPhysicalScan }
            "ACOUSTIC" -> episodesOnly.filter { it.isPhysicalScan }
            else -> episodesOnly
        }

        val newList: List<ListRow> = if (filter == "ALL") {
            buildRowsWithDateHeaders(filtered)
        } else {
            filtered
        }

        val diff = DiffUtil.calculateDiff(RowDiffCallback(displayedList, newList))
        displayedList.clear()
        displayedList.addAll(newList)
        diff.dispatchUpdatesTo(adapter)

        val empty = filtered.isEmpty()
        binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerResults.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            binding.textEmpty.text = when (filter) {
                "ACOUSTIC" -> "No acoustic scans recorded yet.\nTap 🔄 Sync Sensors."
                "SCREEN" -> "No screen captures recorded yet.\nTap 📸 Capture."
                else -> "No screen memory found.\nTap 📸 Capture or ✨ Demo."
            }
        }
    }

    /** Build a flat list injecting date header rows whenever the day changes. */
    private fun buildRowsWithDateHeaders(episodes: List<ListRow.EpisodeRow>): List<ListRow> {
        if (episodes.isEmpty()) return emptyList()
        val result = mutableListOf<ListRow>()
        val dayFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        val calNow = Calendar.getInstance()
        var lastDay = -1
        for (ep in episodes) {
            val cal = Calendar.getInstance().apply { timeInMillis = ep.timestampMs }
            val dayKey = cal.get(Calendar.DAY_OF_YEAR) * 10000 + cal.get(Calendar.YEAR)
            if (dayKey != lastDay) {
                val label = when {
                    isSameDay(cal, calNow) -> "Today"
                    isYesterday(cal, calNow) -> "Yesterday"
                    else -> dayFmt.format(Date(ep.timestampMs))
                }
                result.add(ListRow.DateHeader(label.uppercase()))
                lastDay = dayKey
            }
            result.add(ep)
        }
        return result
    }

    private fun isSameDay(a: Calendar, b: Calendar) =
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR)

    private fun isYesterday(a: Calendar, b: Calendar): Boolean {
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = b.timeInMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameDay(a, yesterday)
    }

    /** Rebuild the category count strip after data loads. */
    private fun updateCategoryStrip() {
        val episodes = allItemsList.filterIsInstance<ListRow.EpisodeRow>()
        if (episodes.isEmpty()) {
            binding.scrollCategorySummary.visibility = View.GONE
            return
        }
        val counts = episodes.groupingBy { it.category }.eachCount()
            .entries.sortedByDescending { it.value }

        binding.layoutCategorySummary.removeAllViews()
        counts.forEach { (rawCat, count) ->
            val cat = ScreenMindCategory.from(rawCat)
            val chip = TextView(this).apply {
                text = "${cat.emoji} $count"
                textSize = 11f
                setTextColor(getColor(cat.colorRes))
                setBackgroundResource(R.drawable.bg_glass_pill)
                setPadding(28, 12, 28, 12)
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            binding.layoutCategorySummary.addView(chip, params)
        }
        binding.scrollCategorySummary.visibility = View.VISIBLE
        GlassAnimator.fadeInScale(binding.scrollCategorySummary, delay = 120)
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    companion object {
        private const val VT_HEADER = 0
        private const val VT_EPISODE = 1
    }

    inner class ScreenMindAdapter(
        private val list: List<ListRow>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int) =
            if (list[position] is ListRow.DateHeader) VT_HEADER else VT_EPISODE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VT_HEADER) {
                val v = inflater.inflate(R.layout.item_screenmind_date_header, parent, false)
                DateHeaderHolder(v)
            } else {
                val v = inflater.inflate(R.layout.item_screenmind_result, parent, false)
                EpisodeHolder(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = list[position]) {
                is ListRow.DateHeader -> (holder as DateHeaderHolder).bind(row)
                is ListRow.EpisodeRow -> {
                    (holder as EpisodeHolder).bind(row)
                    // Stagger fade-in animation
                    GlassAnimator.fadeInUp(holder.itemView, index = position % 8)
                }
            }
        }

        override fun getItemCount() = list.size

        inner class DateHeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val textDate: TextView = view.findViewById(R.id.textDateHeader)
            fun bind(row: ListRow.DateHeader) {
                textDate.text = row.label
            }
        }

        inner class EpisodeHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val layoutBadge: LinearLayout = view.findViewById(R.id.layoutCategoryBadge)
            private val textEmoji: TextView = view.findViewById(R.id.textCategoryEmoji)
            private val textCatLabel: TextView = view.findViewById(R.id.textCategoryLabel)
            private val textApp: TextView = view.findViewById(R.id.textItemApp)
            private val textTime: TextView = view.findViewById(R.id.textItemTime)
            private val textSummary: TextView = view.findViewById(R.id.textItemSummary)
            private val layoutDetails: LinearLayout = view.findViewById(R.id.layoutDetails)
            private val textDetails: TextView = view.findViewById(R.id.textItemDetails)
            private val divider: View = view.findViewById(R.id.dividerDetails)
            private val layoutExpand: LinearLayout = view.findViewById(R.id.layoutExpandRow)
            private val textExpand: TextView = view.findViewById(R.id.textExpandHint)

            fun bind(row: ListRow.EpisodeRow) {
                val cat = ScreenMindCategory.from(row.category)

                // Category badge
                textEmoji.text = cat.emoji
                textCatLabel.text = cat.label
                textCatLabel.setTextColor(getColor(cat.colorRes))

                textApp.text = row.app
                textTime.text = row.time
                textSummary.text = row.summary

                val hasDetails = row.details.isNotBlank()

                // Details collapsible
                if (hasDetails) {
                    textDetails.text = formatDetails(row.details, cat)
                    layoutDetails.visibility = if (row.isExpanded) View.VISIBLE else View.GONE
                    divider.visibility = if (row.isExpanded) View.VISIBLE else View.GONE
                    layoutExpand.visibility = View.VISIBLE
                    textExpand.text = if (row.isExpanded) "Details  ▲" else "Details  ▼"
                } else {
                    layoutDetails.visibility = View.GONE
                    divider.visibility = View.GONE
                    layoutExpand.visibility = View.GONE
                }

                itemView.setOnClickListener {
                    if (hasDetails) {
                        row.isExpanded = !row.isExpanded
                        layoutDetails.visibility = if (row.isExpanded) View.VISIBLE else View.GONE
                        divider.visibility = if (row.isExpanded) View.VISIBLE else View.GONE
                        textExpand.text = if (row.isExpanded) "Details  ▲" else "Details  ▼"
                        if (row.isExpanded) {
                            GlassAnimator.fadeInScale(layoutDetails, delay = 0)
                        }
                    }
                }
            }

            /**
             * Format the raw details blob into a cleaner, structured view.
             * AI answers pass through verbatim; raw OCR gets trimmed and cleaned.
             */
            private fun formatDetails(raw: String, cat: ScreenMindCategory): String {
                if (cat == ScreenMindCategory.ON_DEVICE_SYNTHESIS) return raw
                // Clean up raw OCR: collapse whitespace, limit to 600 chars
                val cleaned = raw.trim()
                    .replace(Regex("[ \\t]{2,}"), " ")
                    .replace(Regex("\\n{3,}"), "\n\n")
                return if (cleaned.length > 600) cleaned.take(597) + "…" else cleaned
            }
        }
    }

    // ── DiffUtil ──────────────────────────────────────────────────────────────

    private class RowDiffCallback(
        private val old: List<ListRow>,
        private val new: List<ListRow>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(op: Int, np: Int): Boolean {
            val o = old[op]; val n = new[np]
            return when {
                o is ListRow.DateHeader && n is ListRow.DateHeader -> o.label == n.label
                o is ListRow.EpisodeRow && n is ListRow.EpisodeRow -> o.id == n.id
                else -> false
            }
        }
        override fun areContentsTheSame(op: Int, np: Int) = old[op] == new[np]
    }
}
