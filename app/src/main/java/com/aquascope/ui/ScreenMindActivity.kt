package com.aquascope.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aquascope.R
import com.aquascope.databinding.ActivityScreenmindBinding
import com.aquascope.smriti.screenmind.ScreenMindBridge
import com.aquascope.smriti.screenmind.ScreenMindSearchResult
import com.aquascope.smriti.screenmind.ScreenMindTimelineItem
import kotlinx.coroutines.launch

class ScreenMindActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityScreenmindBinding
    private lateinit var bridge: ScreenMindBridge
    private val resultsList = mutableListOf<DisplayItem>()
    private lateinit var adapter: ScreenMindAdapter

    data class DisplayItem(
        val app: String,
        val category: String,
        val time: String,
        val summary: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScreenmindBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bridge = ScreenMindBridge.get(this)
        binding.inputServerUrl.setText(bridge.getServerUrl())

        adapter = ScreenMindAdapter(resultsList)
        binding.recyclerResults.layoutManager = LinearLayoutManager(this)
        binding.recyclerResults.adapter = adapter

        binding.btnConnect.setOnClickListener {
            val url = binding.inputServerUrl.text?.toString()?.trim().orEmpty()
            if (url.isNotEmpty()) {
                bridge.setServerUrl(url)
                checkConnection()
            }
        }

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
                Toast.makeText(this, "Enter a question for desktop memory", Toast.LENGTH_SHORT).show()
            }
        }

        checkConnection()
    }

    private fun checkConnection() {
        binding.textStatusIndicator.text = getString(R.string.screenmind_status_checking)
        binding.textStatusIndicator.setTextColor(getColor(R.color.amber))
        lifecycleScope.launch {
            val res = bridge.client.checkHealth()
            if (res.isSuccess) {
                binding.textStatusIndicator.text = "ONLINE"
                binding.textStatusIndicator.setTextColor(getColor(R.color.cyan))
                loadTimeline()
            } else {
                binding.textStatusIndicator.text = "OFFLINE"
                binding.textStatusIndicator.setTextColor(getColor(R.color.status_alert))
                binding.textEmpty.text = "ScreenMind server not reachable at ${bridge.getServerUrl()}.\nMake sure 'python -m screenmind' is running."
                binding.textEmpty.visibility = View.VISIBLE
                binding.recyclerResults.visibility = View.GONE
            }
        }
    }

    private fun loadTimeline() {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val res = bridge.client.getTimeline(25)
            binding.progressLoading.visibility = View.GONE
            if (res.isSuccess) {
                val items = res.getOrNull().orEmpty()
                resultsList.clear()
                items.forEach {
                    resultsList.add(
                        DisplayItem(
                            app = it.appName.ifEmpty { "Desktop Screen" },
                            category = it.category.ifEmpty { "Activity" },
                            time = it.timestamp,
                            summary = it.summary
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                val empty = resultsList.isEmpty()
                binding.textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                if (empty) binding.textEmpty.text = "Connected to ScreenMind, but no screen captures recorded yet."
                binding.recyclerResults.visibility = if (empty) View.GONE else View.VISIBLE
            } else {
                Toast.makeText(this@ScreenMindActivity, "Failed to load timeline", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performSearch(query: String) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val res = bridge.client.search(query)
            binding.progressLoading.visibility = View.GONE
            if (res.isSuccess) {
                val items = res.getOrNull().orEmpty()
                resultsList.clear()
                items.forEach {
                    resultsList.add(
                        DisplayItem(
                            app = it.appName.ifEmpty { "Desktop" },
                            category = it.category.ifEmpty { "Match" },
                            time = it.timestamp,
                            summary = it.summary
                        )
                    )
                }
                adapter.notifyDataSetChanged()
                val empty = resultsList.isEmpty()
                binding.textEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                if (empty) binding.textEmpty.text = "No desktop screen memory found matching '$query'."
                binding.recyclerResults.visibility = if (empty) View.GONE else View.VISIBLE
            } else {
                Toast.makeText(this@ScreenMindActivity, "Search request failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performChat(query: String) {
        binding.progressLoading.visibility = View.VISIBLE
        binding.textEmpty.visibility = View.GONE
        lifecycleScope.launch {
            val res = bridge.client.chat(query)
            binding.progressLoading.visibility = View.GONE
            if (res.isSuccess) {
                val answer = res.getOrNull().orEmpty()
                resultsList.clear()
                resultsList.add(
                    DisplayItem(
                        app = "ScreenMind AI (Gemma 4)",
                        category = "Q&A",
                        time = "Now",
                        summary = answer
                    )
                )
                adapter.notifyDataSetChanged()
                binding.textEmpty.visibility = View.GONE
                binding.recyclerResults.visibility = View.VISIBLE
            } else {
                Toast.makeText(this@ScreenMindActivity, "Chat request failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class ScreenMindAdapter(
        private val list: List<DisplayItem>
    ) : RecyclerView.Adapter<ScreenMindAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textApp: TextView = view.findViewById(R.id.textItemApp)
            val textCategory: TextView = view.findViewById(R.id.textItemCategory)
            val textTime: TextView = view.findViewById(R.id.textItemTime)
            val textSummary: TextView = view.findViewById(R.id.textItemSummary)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_screenmind_result, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.textApp.text = item.app
            holder.textCategory.text = item.category
            holder.textTime.text = item.time
            holder.textSummary.text = item.summary
        }

        override fun getItemCount(): Int = list.size
    }
}
