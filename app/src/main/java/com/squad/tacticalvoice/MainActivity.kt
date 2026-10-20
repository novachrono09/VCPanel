package com.squad.tacticalvoice

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var inputCallsign: EditText
    private lateinit var inputChannelCode: EditText
    private lateinit var btnRandomizeCode: TextView
    private lateinit var btnDeploy: View

    private val recentChips = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputCallsign = findViewById(R.id.input_callsign)
        inputChannelCode = findViewById(R.id.input_channel_code)
        btnRandomizeCode = findViewById(R.id.btn_randomize_code)
        btnDeploy = findViewById(R.id.btn_deploy_hud)

        recentChips.add(findViewById(R.id.chip_channel_1))
        recentChips.add(findViewById(R.id.chip_channel_2))
        recentChips.add(findViewById(R.id.chip_channel_3))
        recentChips.add(findViewById(R.id.chip_channel_4))

        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("callsign", "NOVA") ?: "NOVA"
        val savedRoom = prefs.getString("room", "SQ-884") ?: "SQ-884"

        inputCallsign.setText(savedName)
        inputChannelCode.setText(savedRoom)

        // Setup 1-tap channel chips
        val defaultChannels = listOf("SQ-884", "SCRIM-A", "APEX-01", "DUO-99")
        for (i in recentChips.indices) {
            val code = prefs.getString("chip_$i", defaultChannels[i]) ?: defaultChannels[i]
            val chip = recentChips[i]
            chip.text = "#$code"
            chip.setOnClickListener {
                inputChannelCode.setText(code)
                updateChipSelection(chip)
            }
            if (code.equals(savedRoom, ignoreCase = true)) {
                chip.isSelected = true
                chip.setTextColor(Color.parseColor("#FF5500"))
            }
        }

        // Randomize Tactical Channel Code button
        btnRandomizeCode.setOnClickListener {
            val prefixes = listOf("SQ", "VEX", "APEX", "GHOST", "VIPER", "TITAN")
            val randCode = "${prefixes.random()}-${(100..999).random()}"
            inputChannelCode.setText(randCode)
        }

        // Check for deep link (vcpanel://join?room=XXXX)
        intent?.data?.let { uri ->
            uri.getQueryParameter("room")?.let { deepRoom ->
                inputChannelCode.setText(deepRoom.uppercase())
                Toast.makeText(this, "Joined frequency: $deepRoom", Toast.LENGTH_SHORT).show()
            }
        }

        // Smart Clipboard Detection
        checkClipboardForRoomCode()

        btnDeploy.setOnClickListener { checkPermissionsAndDeploy() }
    }

    private fun updateChipSelection(selectedChip: TextView) {
        for (chip in recentChips) {
            val isCurrent = (chip == selectedChip)
            chip.isSelected = isCurrent
            chip.setTextColor(Color.parseColor(if (isCurrent) "#FF5500" else "#F0F4F8"))
        }
    }

    private fun checkClipboardForRoomCode() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim() ?: ""
                if (text.startsWith("vcpanel://join?room=")) {
                    val code = Uri.parse(text).getQueryParameter("room")
                    if (!code.isNullOrEmpty()) {
                        inputChannelCode.setText(code.uppercase())
                        Toast.makeText(this, "Detected room $code from clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun checkPermissionsAndDeploy() {
        // 1. Overlay permission check
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable 'Display over other apps' to float HUD", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 2. Microphone & Notifications permission check
        val neededPerms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = neededPerms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 200)
            return
        }

        deployHudAndSelfDestruct()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            deployHudAndSelfDestruct()
        }
    }

    private fun deployHudAndSelfDestruct() {
        val callsign = inputCallsign.text.toString().trim().ifEmpty { "OPERATOR" }.uppercase()
        val roomCode = inputChannelCode.text.toString().trim().ifEmpty { "SQ-884" }.uppercase()

        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("callsign", callsign)
            .putString("room", roomCode)
            .putString("chip_0", roomCode) // Save as first recent chip
            .apply()

        val serviceIntent = Intent(this, VoiceOverlayService::class.java).apply {
            putExtra("CALLSIGN", callsign)
            putExtra("ROOM_CODE", roomCode)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Destroy launcher activity immediately to save memory
        finish()
    }
}
