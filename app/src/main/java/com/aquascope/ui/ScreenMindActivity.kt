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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aquascope.R
import com.aquascope.databinding.ActivityScreenmindBinding
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.brain.CaptureProjectionService
import com.aquascope.smriti.screenmind.ScreenMindBridge
import com.aquascope.smriti.screenmind.ScreenMindLocalEngine
import kotlinx.coroutines.launch

class ScreenMindActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityScreenmindBinding
    private lateinit var localEngine: ScreenMindLocalEngine
    private lateinit var bridge: ScreenMindBridge
    private lateinit var haloBind: HaloBinding
    private val allItemsList = mutableListOf<DisplayItem>()
    private val displayedList = mutableListOf<DisplayItem>()
    private lateinit var adapter: ScreenMindAdapter
    private var activeFilter: String = "ALL"

    data class DisplayItem(
        val id: String,
        val app: String,
        val category: String,
        val time: String,
        val summary: String,
        val details: String = "",
        val isPhysicalScan: Boolean = false,
        var isExpanded: Boolean = false
    )

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
            Toast.makeText(this, "Live screen capture armed in background!", Toast.LENGTH_SHORT).show()
            captureCurrentViewSnapshot("Live MediaProjection Armed")
        } else {
            captureCurrentViewSnapshot("AquaScope Active View")
        }
    }

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

        // Search & On-Device Q&A
        binding.btnSearch.setOnClickListener {
            val q = binding.inputQuery.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) {
                performSearch(q)
            } else {
                loadTimeline()
            }
        }

        binding.btnAskDesktop.setOnClickListener {
            val q = binding.inputQuery.text?.toString()?.trim().orEmpty()
            if (q.isNotEmpty()) {
                performChat(q)
            } else {
                Toast.makeText(this, "Enter a question for on-device AI memory", Toast.LENGTH_SHORT).show()
            }
        }

        // On-Device Screen Capture
        binding.btnCaptureScreen.setOnClickListener {
            triggerScreenCapture()
        }

        // Cross-Sync Physical Sensor Scans
        binding.btnSyncScans.setOnClickListener {
            syncPhysicalScans()
        }

        // Seed Realistic Demo Memories
        binding.btnSeedDemo.setOnClickListener {
            seedDemoData()
        }

        // Filter chips
        binding.chipFilterAll.setOnClickListener { applyFilter("ALL") }
        binding.chipFilterDesktop.setOnClickListener { applyFilter("SCREEN") }
        binding.chipFilterAquaScope.setOnClickListener { applyFilter("ACOUSTIC") }

        // Developer PC Sync (Optional)
        binding.btnToggleDevSync.setOnClickListener {
            val isVis = binding.layoutDevSync.visibility == View.VISIBLE
            binding.layoutDevSync.visibility = if (isVis) View.GONE else View.VISIBLE
            binding.btnToggleDevSync.text = if (isVis) {
                "▶ Developer Network Sync (Optional PC Link)"
            } else {
                "▼ Developer Network Sync (Optional PC Link)"
            }
        }

        binding.btnConnect.setOnClickListener {
            val url = binding.inputServerUrl.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                bridge.setServerUrl(url)
                checkRemoteConnection()
            }
        }

        // Load timeline on start (100% on-device!)
        loadTimeline()
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
    }

    override fun onPause() {
        haloBind.stop()
        super.onPause()
    }

    private fun loadTimeline() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)

        lifecycleScope.launch {
            val items = localEngine.getTimeline(50)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.clear()
            items.forEach {
                allItemsList.add(
                    DisplayItem(
                        id = it.id,
                        app = it.appName,
                        category = it.category,
                        time = it.timestamp,
                        summary = it.summary,
                        details = it.details,
                        isPhysicalScan = it.isPhysicalScan
                    )
                )
            }
            applyFilter(activeFilter)
        }
    }

    private fun performSearch(query: String) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)

        lifecycleScope.launch {
            val items = localEngine.search(query)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.clear()
            items.forEach {
                allItemsList.add(
                    DisplayItem(
                        id = it.id,
                        app = it.appName,
                        category = it.category,
                        time = it.timestamp,
                        summary = it.summary,
                        details = if (it.details.isNotBlank()) it.details else "Match relevance: ${(it.score * 100).toInt()}%",
                        isPhysicalScan = it.isPhysicalScan
                    )
                )
            }
            applyFilter(activeFilter)
        }
    }

    private fun performChat(query: String) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.PROCESSING)

        lifecycleScope.launch {
            val answer = localEngine.ask(query)
            binding.progressLoading.visibility = View.GONE
            haloBind.controller().setState(SmritiLightState.NORMAL)

            allItemsList.add(
                0,
                DisplayItem(
                    id = "chat-answer-${System.currentTimeMillis()}",
                    app = "ScreenMind AI (Snapdragon 8 Elite)",
                    category = "ON_DEVICE_SYNTHESIS",
                    time = "Just now",
                    summary = answer,
                    details = "Synthesized 100% on-device using local episodic screen memory and acoustic pipeline scans without internet.",
                    isExpanded = true
                )
            )
            applyFilter("ALL")
        }
    }

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
                val canvas = Canvas(bitmap)
                root.draw(canvas)

                val episode = localEngine.processBitmapAndIngest(bitmap, appHint)
                binding.progressLoading.visibility = View.GONE
                haloBind.controller().setState(SmritiLightState.NORMAL)

                Toast.makeText(this@ScreenMindActivity, "📸 Captured screen & extracted OCR!", Toast.LENGTH_SHORT).show()
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

            Toast.makeText(this@ScreenMindActivity, "Merged $count physical acoustic scans!", Toast.LENGTH_SHORT).show()
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

            Toast.makeText(this@ScreenMindActivity, "✨ Seeded $count realistic demo episodes!", Toast.LENGTH_SHORT).show()
            loadTimeline()
        }
    }

    private fun checkRemoteConnection() {
        binding.textStatusIndicator.text = "PROBING..."
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
                Toast.makeText(this@ScreenMindActivity, "Remote desktop not reachable (local engine active)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyFilter(filter: String) {
        activeFilter = filter
        binding.chipFilterAll.setTextColor(getColor(if (filter == "ALL") R.color.cyan else R.color.warm_faint))
        binding.chipFilterDesktop.setTextColor(getColor(if (filter == "SCREEN") R.color.cyan else R.color.warm_faint))
        binding.chipFilterAquaScope.setTextColor(getColor(if (filter == "ACOUSTIC") R.color.cyan else R.color.warm_faint))

        displayedList.clear()
        when (filter) {
            "SCREEN" -> displayedList.addAll(allItemsList.filter { !it.isPhysicalScan && !it.app.contains("Sensor", true) && !it.app.contains("Acoustic", true) })
            "ACOUSTIC" -> displayedList.addAll(allItemsList.filter { it.isPhysicalScan || it.app.contains("Sensor", true) || it.app.contains("Acoustic", true) })
            else -> displayedList.addAll(allItemsList)
        }
        adapter.notifyDataSetChanged()

        val empty = displayedList.isEmpty()
        binding.textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            binding.textEmpty.text = when (filter) {
                "ACOUSTIC" -> "No acoustic leak scans recorded yet. Tap '🔄 Sync Sensors'."
                "SCREEN" -> "No screen captures recorded yet. Tap '📸 Capture Screen'."
                else -> "No screen memory found.\nTap '📸 Capture Screen' or '✨ Demo' to generate episodes."
            }
        }
        binding.recyclerResults.visibility = if (empty) View.GONE else View.VISIBLE
    }

    inner class ScreenMindAdapter(
        private val list: List<DisplayItem>
    ) : RecyclerView.Adapter<ScreenMindAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textApp: TextView = view.findViewById(R.id.textItemApp)
            val textCategory: TextView = view.findViewById(R.id.textItemCategory)
            val textTime: TextView = view.findViewById(R.id.textItemTime)
            val textSummary: TextView = view.findViewById(R.id.textItemSummary)
            val textDetails: TextView = view.findViewById(R.id.textItemDetails)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_screenmind_result, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.textApp.text = item.app
            holder.textCategory.text = item.category.uppercase()
            holder.textTime.text = item.time
            holder.textSummary.text = item.summary

            if (item.details.isNotBlank()) {
                holder.textDetails.text = item.details
                holder.textDetails.visibility = if (item.isExpanded) View.VISIBLE else View.GONE
            } else {
                holder.textDetails.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                if (item.details.isNotBlank()) {
                    item.isExpanded = !item.isExpanded
                    notifyItemChanged(position)
                }
            }
        }

        override fun getItemCount(): Int = list.size
    }
}
