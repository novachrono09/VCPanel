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
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var inputCallsign: EditText
    private lateinit var inputChannelCode: EditText
    private lateinit var btnRandomizeCode: TextView
    private lateinit var btnDeploy: View
    private var vibrator: Vibrator? = null

    private val recentChips = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

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

        // Setup 1-tap channel chips with spring feedback
        val defaultChannels = listOf("SQ-884", "SCRIM-A", "APEX-01", "DUO-99")
        for (i in recentChips.indices) {
            val code = prefs.getString("chip_$i", defaultChannels[i]) ?: defaultChannels[i]
            val chip = recentChips[i]
            chip.text = "#$code"
            attachTactileSpring(chip) {
                inputChannelCode.setText(code)
                updateChipSelection(chip)
                vibrateClick()
            }
            if (code.equals(savedRoom, ignoreCase = true)) {
                chip.isSelected = true
                chip.setTextColor(Color.parseColor("#FF5500"))
            }
        }

        // Randomize Tactical Channel Code button with rotational spring animation
        attachTactileSpring(btnRandomizeCode) {
            vibrateClick()
            btnRandomizeCode.animate()
                .rotationBy(360f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()

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

        // Deploy button with tactile bounce
        attachTactileSpring(btnDeploy) {
            vibrateClick()
            checkPermissionsAndDeploy()
        }

        // Lightweight entrance cascade (0 object allocations)
        runStaggeredEntranceAnimation()
    }

    private fun runStaggeredEntranceAnimation() {
        val root = findViewById<View>(android.R.id.content)
        root.alpha = 0f
        root.translationY = 18f
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun attachTactileSpring(view: View, onClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    val targetScale = if (isInside) 0.93f else 1.0f
                    v.animate().scaleX(targetScale).scaleY(targetScale).setDuration(60).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f)
                        .setDuration(140)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (isInside) {
                        onClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
            }
            true
        }
    }

    private fun updateChipSelection(selectedChip: TextView) {
        for (chip in recentChips) {
            val isCurrent = (chip == selectedChip)
            chip.isSelected = isCurrent
            chip.setTextColor(Color.parseColor(if (isCurrent) "#FF5500" else "#F0F4F8"))
            if (isCurrent) {
                chip.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).withEndAction {
                    chip.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }.start()
            }
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

    private fun vibrateClick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15)
            }
        } catch (_: Exception) {}
    }

    private fun checkPermissionsAndDeploy() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable 'Display over other apps' to float HUD", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

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
            .putString("chip_0", roomCode)
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

        finish()
    }
}
