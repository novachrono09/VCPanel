package com.squad.tacticalvoice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.AudioTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class VoiceOverlayService : Service() {

    companion object {
        // =========================================================================
        // LIVEKIT CLOUD KEYS CONFIGURED
        // =========================================================================
        private const val LIVEKIT_WS_URL = "wss://vcpanel-a7dk28u7.livekit.cloud"
        private const val LIVEKIT_API_KEY = "APImAwZ8BNUqRc2"
        private const val LIVEKIT_API_SECRET = "frhN1e6pWcL15tpQtH3Dx0a5Q6oOgJNg0oW9hyrOAP1"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var overlayView: View? = null
    private var room: Room? = null

    // State flags
    private var isMicEnabled = true
    private var isDeafened = false
    private var isSpeakerphone = true
    private val participantVolumes = mutableMapOf<String, Int>() // Stores 0..200% per user

    // UI References
    private lateinit var layoutCollapsed: View
    private lateinit var layoutExpanded: View
    private lateinit var pillStatusDot: View
    private lateinit var headerStatusDot: View
    private lateinit var pillSquadCount: TextView
    private lateinit var pillQuickMuteBtn: TextView
    private lateinit var txtRoomTitle: TextView
    private lateinit var btnToggleMic: TextView
    private lateinit var btnToggleDeafen: TextView
    private lateinit var btnAudioRoute: TextView
    private lateinit var squadListContainer: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startMinimalForegroundNotification()
        spawnTacticalOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callsign = intent?.getStringExtra("CALLSIGN") ?: "Player_${(10..99).random()}"
        val roomName = intent?.getStringExtra("ROOM_CODE") ?: "squad-alpha"
        connectToVoiceBackend(callsign, roomName)
        return START_STICKY
    }

    private fun connectToVoiceBackend(callsign: String, roomName: String) {
        if (room != null) return

        // Configure Opus at 96kbps studio voice quality + hardware AEC/NS
        val roomOptions = RoomOptions(
            adaptiveStream = false,
            dynacast = false,
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true,
                highPassFilter = true
            ),
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = 96_000, // 96kbps crystal-clear Opus (Discord defaults to 64k)
                dtx = true,            // Discontinuous transmission: 0 bandwidth when silent
                red = true             // Redundant packet encoding against mobile ping spikes
            )
        )

        val currentRoom = LiveKit.create(
            appContext = applicationContext,
            options = roomOptions,
            overrides = LiveKitOverrides(
                audioOptions = AudioOptions(
                    audioProcessorOptions = AudioProcessorOptions()
                )
            )
        )
        room = currentRoom

        // Listen for room & speaking events
        serviceScope.launch {
            currentRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        updateStatusColor("#00F59B")
                        txtRoomTitle.text = "${roomName.uppercase()} // 96K"
                        currentRoom.localParticipant.setMicrophoneEnabled(isMicEnabled)
                        rebuildSquadList()
                    }
                    is RoomEvent.Reconnecting -> {
                        updateStatusColor("#F59E0B")
                        txtRoomTitle.text = "RECONNECTING..."
                    }
                    is RoomEvent.Reconnected -> {
                        updateStatusColor("#00F59B")
                        txtRoomTitle.text = "${roomName.uppercase()} // 96K"
                    }
                    is RoomEvent.ParticipantConnected,
                    is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackSubscribed,
                    is RoomEvent.TrackUnsubscribed -> {
                        rebuildSquadList()
                    }
                    is RoomEvent.ActiveSpeakersChanged -> {
                        highlightActiveSpeakers(event.speakers)
                    }
                    else -> {}
                }
            }
        }

        // Generate JWT locally on-device and connect directly to LiveKit SFU
        serviceScope.launch(Dispatchers.IO) {
            try {
                val token = generateLiveKitJwt(
                    apiKey = LIVEKIT_API_KEY,
                    apiSecret = LIVEKIT_API_SECRET,
                    roomName = roomName,
                    identity = callsign
                )
                currentRoom.connect(LIVEKIT_WS_URL, token)
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    updateStatusColor("#FF3B5C")
                    txtRoomTitle.text = "CONN ERR"
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun spawnTacticalOverlay() {
        val inflater = LayoutInflater.from(this)
        val root = inflater.inflate(R.layout.overlay_voice_panel, null)
        overlayView = root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE // Hardware optimization: tells SurfaceFlinger no alpha blending needed
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 180
            // We switch to TRANSLUCENT only so the 10dp rounded corners clip cleanly
            format = PixelFormat.TRANSLUCENT
        }

        layoutCollapsed = root.findViewById(R.id.layoutCollapsed)
        layoutExpanded = root.findViewById(R.id.layoutExpanded)
        pillStatusDot = root.findViewById(R.id.pillStatusDot)
        headerStatusDot = root.findViewById(R.id.headerStatusDot)
        pillSquadCount = root.findViewById(R.id.pillSquadCount)
        pillQuickMuteBtn = root.findViewById(R.id.pillQuickMuteBtn)
        txtRoomTitle = root.findViewById(R.id.txtRoomTitle)
        btnToggleMic = root.findViewById(R.id.btnToggleMic)
        btnToggleDeafen = root.findViewById(R.id.btnToggleDeafen)
        btnAudioRoute = root.findViewById(R.id.btnAudioRoute)
        squadListContainer = root.findViewById(R.id.squadListContainer)

        val btnCollapse = root.findViewById<View>(R.id.btnCollapse)
        val headerDragArea = root.findViewById<View>(R.id.headerDragArea)
        val btnDisconnect = root.findViewById<View>(R.id.btnDisconnect)

        // Attach drag & tap handler to both the collapsed pill and the expanded header
        val dragTouchListener = createDragTouchListener(params) {
            // On single tap of the collapsed pill -> expand tactical deck
            if (layoutCollapsed.visibility == View.VISIBLE) {
                layoutCollapsed.visibility = View.GONE
                layoutExpanded.visibility = View.VISIBLE
                rebuildSquadList()
                windowManager.updateViewLayout(root, params)
            }
        }

        layoutCollapsed.setOnTouchListener(dragTouchListener)
        headerDragArea.setOnTouchListener(createDragTouchListener(params, onTap = null))

        btnCollapse.setOnClickListener {
            layoutExpanded.visibility = View.GONE
            layoutCollapsed.visibility = View.VISIBLE
            windowManager.updateViewLayout(root, params)
        }

        pillQuickMuteBtn.setOnClickListener { toggleMic() }
        btnToggleMic.setOnClickListener { toggleMic() }
        btnToggleDeafen.setOnClickListener { toggleDeafen() }
        btnAudioRoute.setOnClickListener { toggleAudioRoute() }
        btnDisconnect.setOnClickListener { shutdownService() }

        windowManager.addView(root, params)
    }

    private fun toggleMic() {
        isMicEnabled = !isMicEnabled
        // If user unmutes mic while deafened, automatically undeafen
        if (isMicEnabled && isDeafened) {
            isDeafened = false
            applyRemoteVolumes()
            updateDeafenUi()
        }
        serviceScope.launch {
            room?.localParticipant?.setMicrophoneEnabled(isMicEnabled)
        }
        updateMicUi()
    }

    private fun toggleDeafen() {
        isDeafened = !isDeafened
        if (isDeafened) {
            isMicEnabled = false
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(false) }
        }
        applyRemoteVolumes()
        updateMicUi()
        updateDeafenUi()
    }

    private fun toggleAudioRoute() {
        isSpeakerphone = !isSpeakerphone
        audioManager.isSpeakerphoneOn = isSpeakerphone
        btnAudioRoute.text = if (isSpeakerphone) "OUT: SPKR" else "OUT: EAR"
        btnAudioRoute.setTextColor(Color.parseColor(if (isSpeakerphone) "#38BDF8" else "#A78BFA"))
    }

    private fun updateMicUi() {
        val label = if (isMicEnabled) "MIC: ON" else "MUTED"
        val color = Color.parseColor(if (isMicEnabled) "#00F59B" else "#FF3B5C")
        btnToggleMic.text = label
        btnToggleMic.setTextColor(color)
        pillQuickMuteBtn.text = if (isMicEnabled) "MIC ON" else "MUTED"
        pillQuickMuteBtn.setTextColor(color)
    }

    private fun updateDeafenUi() {
        btnToggleDeafen.text = if (isDeafened) "DEAF: ON" else "DEAF: OFF"
        btnToggleDeafen.setTextColor(
            Color.parseColor(if (isDeafened) "#FF3B5C" else "#E2E8F0")
        )
    }

    private fun rebuildSquadList() {
        val currentRoom = room ?: return
        val remotes = currentRoom.remoteParticipants.values.toList()

        // Update pill squad count (including yourself)
        pillSquadCount.text = "SQUAD [${remotes.size + 1}]"

        squadListContainer.removeAllViews()

        if (remotes.isEmpty()) {
            val emptyTxt = TextView(this).apply {
                text = "Waiting for squad..."
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            squadListContainer.addView(emptyTxt)
            return
        }

        remotes.forEach { participant ->
            val identity = participant.identity?.value ?: "Teammate"
            val currentVol = participantVolumes.getOrPut(identity) { 100 }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
                tag = "ROW_$identity"
            }

            val nameAndVolRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val speakerDot = View(this).apply {
                tag = "DOT_$identity"
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    marginEnd = dp(6)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#334155"))
                }
            }

            val nameTxt = TextView(this).apply {
                text = identity.uppercase()
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = android.graphics.Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val volTxt = TextView(this).apply {
                text = "$currentVol%"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = android.graphics.Typeface.MONOSPACE
            }

            nameAndVolRow.addView(speakerDot)
            nameAndVolRow.addView(nameTxt)
            nameAndVolRow.addView(volTxt)

            // 0% to 200% Individual Volume Boost Slider
            val slider = SeekBar(this).apply {
                max = 200
                progress = currentVol
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        participantVolumes[identity] = progress
                        volTxt.text = "$progress%"
                        setParticipantVolume(participant, progress)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }

            row.addView(nameAndVolRow)
            row.addView(slider)
            squadListContainer.addView(row)

            // Ensure initial volume is applied
            setParticipantVolume(participant, currentVol)
        }
    }

    private fun applyRemoteVolumes() {
        val currentRoom = room ?: return
        currentRoom.remoteParticipants.values.forEach { participant ->
            val identity = participant.identity?.value ?: return@forEach
            val savedVol = participantVolumes[identity] ?: 100
            setParticipantVolume(participant, savedVol)
        }
    }

    private fun setParticipantVolume(participant: RemoteParticipant, percentage: Int) {
        val effectiveGain = if (isDeafened) 0.0 else (percentage / 100.0)
        participant.audioTrackPublications.forEach { (_, track) ->
            (track as? RemoteAudioTrack)?.setVolume(effectiveGain)
        }
    }

    private fun highlightActiveSpeakers(speakers: List<Participant>) {
        val activeIds = speakers.mapNotNull { it.identity?.value }.toSet()

        // Pulse pill dot bright cyan when anyone is talking
        val isAnyoneTalking = activeIds.isNotEmpty()
        updateStatusColor(if (isAnyoneTalking) "#00F59B" else "#334155")

        // Highlight individual squad dots
        for (i in 0 until squadListContainer.childCount) {
            val child = squadListContainer.getChildAt(i)
            val rowTag = child.tag as? String ?: continue
            if (rowTag.startsWith("ROW_")) {
                val id = rowTag.removePrefix("ROW_")
                val dot = child.findViewWithTag<View>("DOT_$id")
                val bg = dot?.background as? GradientDrawable
                bg?.setColor(
                    Color.parseColor(if (activeIds.contains(id)) "#00F59B" else "#334155")
                )
            }
        }
    }

    private fun updateStatusColor(hex: String) {
        val color = Color.parseColor(hex)
        (pillStatusDot.background as? GradientDrawable)?.setColor(color)
        (headerStatusDot.background as? GradientDrawable)?.setColor(color)
    }

    private fun createDragTouchListener(
        params: WindowManager.LayoutParams,
        onTap: (() -> Unit)?
    ): View.OnTouchListener {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isDragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        overlayView?.let { windowManager.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onTap?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Pure Kotlin HMAC-SHA256 LiveKit JWT Generator.
     * Eliminates the need for a middleman backend server.
     */
    private fun generateLiveKitJwt(
        apiKey: String,
        apiSecret: String,
        roomName: String,
        identity: String
    ): String {
        val header = JSONObject().apply {
            put("alg", "HS256")
            put("typ", "JWT")
        }

        val nowSeconds = System.currentTimeMillis() / 1000
        val videoGrant = JSONObject().apply {
            put("roomJoin", true)
            put("room", roomName)
            put("canPublish", true)
            put("canSubscribe", true)
        }

        val payload = JSONObject().apply {
            put("iss", apiKey)
            put("sub", identity)
            put("name", identity)
            put("nbf", nowSeconds - 10)
            put("exp", nowSeconds + 86400) // Valid for 24 hours per session
            put("video", videoGrant)
        }

        val base64Flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        val encodedHeader = Base64.encodeToString(header.toString().toByteArray(Charsets.UTF_8), base64Flags)
        val encodedPayload = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), base64Flags)
        val signingInput = "$encodedHeader.$encodedPayload"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signatureBytes = mac.doFinal(signingInput.toByteArray(Charsets.UTF_8))
        val encodedSignature = Base64.encodeToString(signatureBytes, base64Flags)

        return "$signingInput.$encodedSignature"
    }

    private fun startMinimalForegroundNotification() {
        val channelId = "tactical_voice_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Squad Voice Comms",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("SquadComms Active")
            .setContentText("96kbps Opus HUD running")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        startForeground(101, notification)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun shutdownService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        room?.disconnect()
        room?.release()
        room = null
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
