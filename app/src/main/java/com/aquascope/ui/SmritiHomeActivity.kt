package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.aquascope.R
import com.aquascope.databinding.ActivitySmritiHomeBinding
import com.aquascope.halo.SmritiLightMapper
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.model.MemoryNodeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmritiHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmritiHomeBinding
    private lateinit var smriti: SmritiCore
    private lateinit var haloBind: HaloBinding
    private var selectedEventId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmritiHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = getColor(R.color.paper_sky)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        smriti = SmritiCore.get(this)
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_HOME)
        haloBind = HaloBinding(this, binding.haloIndicator)

        binding.memoryField.onNodeTap = { node ->
            val snap = smriti.homeMemorySnapshot()
            val state = snap.nodes.find { it.locationId == node.id }
            showNode(state)
        }
        binding.btnExploreMemory.setOnClickListener {
            val id = selectedEventId
            if (id != null) {
                startActivity(Intent(this, MemoryDetailActivity::class.java).putExtra("event_id", id))
            } else {
                startActivity(Intent(this, MemoryTimelineActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
        refresh()
    }

    override fun onPause() {
        haloBind.stop()
        super.onPause()
    }

    private fun refresh() {
        val snap = smriti.homeMemorySnapshot()
        val dateFmt = SimpleDateFormat("d MMMM", Locale.getDefault())
        binding.textHomeDate.text = dateFmt.format(Date()).uppercase(Locale.getDefault())
        binding.textHomeStatus.text = snap.statusLine
        binding.textHomeStatus.setTextColor(
            getColor(
                if (snap.statusLine.contains("NORMAL") || snap.statusLine.contains("AWAITING"))
                    R.color.cyan
                else R.color.amber
            )
        )
        binding.textHomeSupporting.text = buildString {
            append("${snap.memoriesToday} ${if (snap.memoriesToday == 1) "memory" else "memories"} today")
            append("\n")
            append(
                if (snap.anomaliesObserved == 0) "No anomalies observed"
                else "${snap.anomaliesObserved} ${if (snap.anomaliesObserved == 1) "anomaly" else "anomalies"} observed"
            )
        }
        val fieldNodes = snap.nodes.map {
            MemoryFieldView.FieldNode(
                id = it.locationId,
                label = it.label,
                state = it.pulse,
                xFrac = it.xFrac,
                yFrac = it.yFrac
            )
        }
        binding.memoryField.setField(fieldNodes, snap.disturbance, snap.density)

        val light = SmritiLightMapper.fromHomeStatus(snap.statusLine, snap.anomaliesObserved)
        haloBind.controller().setState(light)
        binding.memoryField.syncExpression(light)

        if (snap.nodes.isEmpty()) {
            binding.nodePanel.visibility = View.GONE
        }
    }

    private fun showNode(state: MemoryNodeState?) {
        if (state == null) {
            binding.nodePanel.visibility = View.GONE
            return
        }
        binding.nodePanel.visibility = View.VISIBLE
        binding.memoryField.setSelected(state.locationId)
        binding.textNodeTitle.text = state.label.uppercase(Locale.getDefault())
        binding.textNodeObject.text = state.objectLabel
        val latest = state.latest
        selectedEventId = latest?.id
        val acoustic = when (state.pulse) {
            MemoryNodeState.PULSE_ANOMALY -> "ACOUSTIC STATE    ANOMALY"
            MemoryNodeState.PULSE_ACTIVE -> "ACOUSTIC STATE    ACTIVE"
            else -> "ACOUSTIC STATE    NORMAL"
        }
        binding.textNodeState.text = acoustic
        binding.textNodeState.setTextColor(
            getColor(if (state.pulse == MemoryNodeState.PULSE_ANOMALY) R.color.amber else R.color.cyan)
        )
        binding.textNodeDeviation.text = when {
            latest == null -> "No scan remembered yet"
            latest.anomalyScore > 0 -> "Deviation    %+d%%".format(latest.anomalyScore.toInt())
            else -> "Within baseline"
        }
        binding.btnExploreMemory.visibility =
            if (latest != null) View.VISIBLE else View.GONE

        val nodeLight = when (state.pulse) {
            MemoryNodeState.PULSE_ANOMALY -> SmritiLightState.ANOMALY
            MemoryNodeState.PULSE_ACTIVE -> SmritiLightState.MEMORY_RECALL
            else -> SmritiLightState.NORMAL
        }
        haloBind.controller().setState(nodeLight)
        binding.memoryField.syncExpression(nodeLight)
    }
}
