package com.aquascope.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aquascope.data.ScanLocation
import com.aquascope.databinding.ItemLocationBinding

class LocationAdapter(
    private val onScan: (ScanLocation) -> Unit,
    private val onHistory: (ScanLocation) -> Unit,
    private val onDelete: (ScanLocation) -> Unit
) : ListAdapter<ScanLocation, LocationAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ScanLocation>() {
            override fun areItemsTheSame(a: ScanLocation, b: ScanLocation) = a.id == b.id
            override fun areContentsTheSame(a: ScanLocation, b: ScanLocation) = a == b
        }
    }

    inner class VH(private val b: ItemLocationBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(loc: ScanLocation) {
            b.textLabel.text = loc.label
            val scans = loc.scanHistory.size
            val baseline = loc.baselineFeatures.size
            b.textInfo.text = when {
                baseline == 0 -> "Baseline not set — scan once to calibrate"
                scans == 0 -> "$baseline baseline sample${if (baseline == 1) "" else "s"} · ready to compare"
                else -> "$baseline baseline · $scans scan${if (scans == 1) "" else "s"}"
            }
            b.btnScan.setOnClickListener { onScan(loc) }
            b.btnHistory.setOnClickListener { onHistory(loc) }
            b.btnDelete.setOnClickListener { onDelete(loc) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))
}
