package com.aquascope.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.aquascope.data.ScanLocation
import com.aquascope.data.ScanRepository
import com.aquascope.databinding.ActivityMainBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ScanRepository
    private lateinit var adapter: LocationAdapter

    companion object {
        private const val RC_AUDIO_PERMISSION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ScanRepository(this)
        adapter = LocationAdapter(
            onScan = { location -> launchScan(location) },
            onHistory = { location ->
                startActivity(Intent(this, HistoryActivity::class.java).apply {
                    putExtra("location_id", location.id)
                })
            },
            onDelete = { location -> confirmDelete(location) }
        )

        binding.recyclerLocations.layoutManager = LinearLayoutManager(this)
        binding.recyclerLocations.adapter = adapter
        binding.fabNewScan.setOnClickListener { showNewLocationDialog() }

        ensureAudioPermission()
    }

    override fun onResume() {
        super.onResume()
        val locations = repo.loadLocations()
        adapter.submitList(locations)
        val empty = locations.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerLocations.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showNewLocationDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val inputLayout = TextInputLayout(this).apply {
            hint = "Location name"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = TextInputEditText(inputLayout.context).apply {
            setHint("Kitchen wall — left of sink")
        }
        inputLayout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("New scan location")
            .setView(inputLayout)
            .setPositiveButton("Create & scan") { _, _ ->
                val label = input.text?.toString()?.trim().orEmpty()
                if (label.isEmpty()) {
                    Toast.makeText(this, "Enter a location name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val location = ScanLocation(label = label)
                repo.addLocation(location)
                launchScan(location)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(location: ScanLocation) {
        AlertDialog.Builder(this)
            .setTitle("Delete location?")
            .setMessage("Remove \"${location.label}\" and all its baselines and scan history?")
            .setPositiveButton("Delete") { _, _ ->
                repo.deleteLocation(location.id)
                onResume()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchScan(location: ScanLocation) {
        if (!hasAudioPermission()) {
            ensureAudioPermission()
            return
        }
        startActivity(Intent(this, ScanActivity::class.java).apply {
            putExtra("location_id", location.id)
        })
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureAudioPermission() {
        if (!hasAudioPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RC_AUDIO_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_AUDIO_PERMISSION &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Microphone permission is required for scanning",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
