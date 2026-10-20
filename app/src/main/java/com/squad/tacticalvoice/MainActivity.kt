package com.squad.tacticalvoice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var inputCallsign: EditText
    private lateinit var inputRoomCode: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        val savedName = prefs.getString("callsign", "Nova") ?: "Nova"
        val savedRoom = prefs.getString("room", "squad-1") ?: "squad-1"

        // Programmatic layout = zero XML inflation overhead for the launcher
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B0D11"))
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "SQUAD // TACTICAL COMMS"
            setTextColor(Color.parseColor("#00F59B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 0, 0, 48)
        }

        inputCallsign = EditText(this).apply {
            hint = "CALLSIGN (YOUR NAME)"
            setText(savedName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setBackgroundResource(R.drawable.bg_tactical_pill)
            setPadding(36, 28, 36, 28)
            inputType = InputType.TYPE_CLASS_TEXT
            typeface = android.graphics.Typeface.MONOSPACE
        }

        val spacer = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 24 }

        inputRoomCode = EditText(this).apply {
            hint = "CHANNEL CODE"
            setText(savedRoom)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setBackgroundResource(R.drawable.bg_tactical_pill)
            setPadding(36, 28, 36, 28)
            inputType = InputType.TYPE_CLASS_TEXT
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = spacer
        }

        val btnLaunch = Button(this).apply {
            text = "DEPLOY FLOATING HUD"
            setTextColor(Color.parseColor("#0B0D11"))
            setBackgroundColor(Color.parseColor("#00F59B"))
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
            setOnClickListener { checkPermissionsAndDeploy() }
        }

        root.addView(title)
        root.addView(inputCallsign)
        root.addView(inputRoomCode)
        root.addView(btnLaunch)
        setContentView(root)
    }

    private fun checkPermissionsAndDeploy() {
        // 1. Check Display Over Other Apps permission
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable 'Display over other apps' first", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 2. Check Microphone & Notification permissions
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
        val callsign = inputCallsign.text.toString().trim().ifEmpty { "Operator" }
        val roomCode = inputRoomCode.text.toString().trim().ifEmpty { "squad-1" }

        getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE).edit()
            .putString("callsign", callsign)
            .putString("room", roomCode)
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

        // Destroy the Activity immediately so only the ultra-light overlay stays in RAM
        finish()
    }
}
