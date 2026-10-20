package com.squad.tacticalvoice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
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
        private const val LIVEKIT_WS_URL = "wss://vcpanel-a7dk28u7.livekit.cloud"
        private const val LIVEKIT_API_KEY = "APImAwZ8BNUqRc2"
        private const val LIVEKIT_API_SECRET = "frhN1e6pWcL15tpQtH3Dx0a5Q6oOgJNg0oW9hyrOAP1"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var room: Room? = null
    private var activeRoomCode = "SQ-884"
    private var currentCallsign = "OPERATOR"

    // State flags
    private var isMicEnabled = true
    private var isDeafened = false
    private var isSpeakerphone = true
    private var isPttMode = false
    private val participantVolumes = mutableMapOf<String, Int>()

    // UI References
    private lateinit var layoutCollapsed: View
    private lateinit var layoutExpanded: View
    private lateinit var pillVoiceLed: View
    private lateinit var pillSquadCount: TextView
    private lateinit var pillTelemetryReadout: TextView
    private lateinit var pillBtnQuickMute: TextView

    private lateinit var deckHeaderDragZone: View
    private lateinit var deckChannelTitle: TextView
    private lateinit var deckBitrateBadge: TextView
    private lateinit var deckBtnCollapse: TextView

    private lateinit var btnToggleMic: View
    private lateinit var txtStateMic: TextView
    private lateinit var btnToggleDeafen: View
    private lateinit var txtStateDeafen: TextView
    private lateinit var btnToggleRoute: View
    private lateinit var txtStateRoute: TextView
    private lateinit var btnDisconnect: View

    private lateinit var deckRosterContainer: LinearLayout
    private lateinit var deckBtnCopyInvite: TextView
    private lateinit var deckPingReadout: TextView

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 65)
        } catch (_: Exception) {}

        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        isPttMode = prefs.getBoolean("ptt_mode", false)
        if (isPttMode) {
            isMicEnabled = false
        }

        startMinimalForegroundNotification()
        spawnTacticalOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callsign = intent?.getStringExtra("CALLSIGN") ?: "OPERATOR"
        val roomCode = intent?.getStringExtra("ROOM_CODE") ?: "SQ-884"
        currentCallsign = callsign
        activeRoomCode = roomCode
        deckChannelTitle.text = "CH // $roomCode"
        deckBtnCopyInvite.text = "[+] COPY // #$roomCode"
        connectToVoiceBackend(callsign, roomCode)
        return START_STICKY
    }

    private fun connectToVoiceBackend(callsign: String, roomName: String) {
        if (room != null) return

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
                audioBitrate = 96_000,
                dtx = true,
                red = true
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

        serviceScope.launch {
            currentRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        deckPingReadout.text = "LIVE"
                        deckPingReadout.setTextColor(Color.parseColor("#00F58A"))
                        currentRoom.localParticipant.setMicrophoneEnabled(isMicEnabled)
                        rebuildSquadList()
                    }
                    is RoomEvent.Reconnecting -> {
                        deckPingReadout.text = "RECON"
                        deckPingReadout.setTextColor(Color.parseColor("#FFB300"))
                    }
                    is RoomEvent.Reconnected -> {
                        deckPingReadout.text = "LIVE"
                        deckPingReadout.setTextColor(Color.parseColor("#00F58A"))
                        currentRoom.localParticipant.setMicrophoneEnabled(isMicEnabled)
                    }
                    is RoomEvent.ParticipantConnected -> {
                        playChime(ToneGenerator.TONE_PROP_BEEP2, 110)
                        rebuildSquadList()
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        playChime(ToneGenerator.TONE_PROP_PROMPT, 130)
                        rebuildSquadList()
                    }
                    is RoomEvent.DataReceived -> {
                        handleDataReceived(event)
                    }
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
                    deckPingReadout.text = "ERR"
                    deckPingReadout.setTextColor(Color.parseColor("#FF2A4B"))
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 160
        }
        overlayParams = params

        layoutCollapsed = root.findViewById(R.id.layout_hud_collapsed)
        layoutExpanded = root.findViewById(R.id.layout_hud_expanded)
        pillVoiceLed = root.findViewById(R.id.pill_voice_led)
        pillSquadCount = root.findViewById(R.id.pill_squad_count)
        pillTelemetryReadout = root.findViewById(R.id.pill_telemetry_readout)
        pillBtnQuickMute = root.findViewById(R.id.pill_btn_quick_mute)

        deckHeaderDragZone = root.findViewById(R.id.deck_header_drag_zone)
        deckChannelTitle = root.findViewById(R.id.deck_channel_title)
        deckBitrateBadge = root.findViewById(R.id.deck_bitrate_badge)
        deckBtnCollapse = root.findViewById(R.id.deck_btn_collapse)

        btnToggleMic = root.findViewById(R.id.btn_toggle_mic)
        txtStateMic = root.findViewById(R.id.txt_state_mic)
        btnToggleDeafen = root.findViewById(R.id.btn_toggle_deafen)
        txtStateDeafen = root.findViewById(R.id.txt_state_deafen)
        btnToggleRoute = root.findViewById(R.id.btn_toggle_route)
        txtStateRoute = root.findViewById(R.id.txt_state_route)
        btnDisconnect = root.findViewById(R.id.btn_disconnect)

        deckRosterContainer = root.findViewById(R.id.deck_roster_container)
        deckBtnCopyInvite = root.findViewById(R.id.deck_btn_copy_invite)
        deckPingReadout = root.findViewById(R.id.deck_ping_readout)

        // Fluid expand on single-tap of collapsed pill
        val dragTouchListener = createDragTouchListener(params) {
            if (layoutCollapsed.visibility == View.VISIBLE) {
                expandDeckAnimated(root, params)
            }
        }

        layoutCollapsed.setOnTouchListener(dragTouchListener)
        deckHeaderDragZone.setOnTouchListener(createDragTouchListener(params, onTap = null))

        // Fluid collapse animation
        attachSpringTouch(deckBtnCollapse) {
            collapseDeckAnimated(root, params)
        }

        // Tactile Mic Controls (Dual Mode: Open Mic & PTT Hold-to-Talk)
        setupMicControl(pillBtnQuickMute, isPill = true)
        setupMicControl(btnToggleMic, isPill = false)
        attachSpringTouch(btnToggleDeafen) { toggleDeafen() }
        attachSpringTouch(btnToggleRoute) { toggleAudioRoute() }
        attachSpringTouch(btnDisconnect) { shutdownService() }

        // Mode switch (Open Mic // 96K <-> PTT // 96K)
        attachSpringTouch(deckBitrateBadge) { togglePttMode() }

        // In-overlay Tactical Channel Cycler (Frequency switch on-the-fly)
        attachSpringTouch(deckChannelTitle) { cycleToNextChannel() }

        // 1-Tap Copy Squad Link with tactile morph
        attachSpringTouch(deckBtnCopyInvite) {
            copySquadInviteToClipboard()
        }

        windowManager.addView(root, params)
        updateModeBadge()
        updateMicUi()
        updateDeafenUi()
    }

    private fun expandDeckAnimated(root: View, params: WindowManager.LayoutParams) {
        layoutCollapsed.animate()
            .scaleX(0.85f).scaleY(0.85f).alpha(0f)
            .setDuration(90)
            .withEndAction {
                layoutCollapsed.visibility = View.GONE
                layoutExpanded.visibility = View.VISIBLE
                rebuildSquadList()
                windowManager.updateViewLayout(root, params)

                layoutExpanded.scaleX = 0.88f
                layoutExpanded.scaleY = 0.88f
                layoutExpanded.alpha = 0f
                layoutExpanded.animate()
                    .scaleX(1.0f).scaleY(1.0f).alpha(1.0f)
                    .setDuration(160)
                    .setInterpolator(OvershootInterpolator(1.3f))
                    .start()
            }.start()
        vibrateClick()
    }

    private fun collapseDeckAnimated(root: View, params: WindowManager.LayoutParams) {
        layoutExpanded.animate()
            .scaleX(0.88f).scaleY(0.88f).alpha(0f)
            .setDuration(100)
            .withEndAction {
                layoutExpanded.visibility = View.GONE
                layoutCollapsed.visibility = View.VISIBLE
                windowManager.updateViewLayout(root, params)

                layoutCollapsed.scaleX = 0.85f
                layoutCollapsed.scaleY = 0.85f
                layoutCollapsed.alpha = 0f
                layoutCollapsed.animate()
                    .scaleX(1.0f).scaleY(1.0f).alpha(1.0f)
                    .setDuration(140)
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .start()
            }.start()
        vibrateClick()
    }

    private fun attachSpringTouch(view: View, onClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f)
                        .setDuration(140)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                    onClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
            }
            true
        }
    }

    private fun copySquadInviteToClipboard() {
        val link = "vcpanel://join?room=$activeRoomCode"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("SquadComms Channel", link))

        // Morphing success micro-interaction
        deckBtnCopyInvite.text = "[✓] LINK COPIED!"
        deckBtnCopyInvite.setTextColor(Color.parseColor("#00F58A"))
        deckBtnCopyInvite.animate()
            .scaleX(1.05f).scaleY(1.05f)
            .setDuration(100)
            .withEndAction {
                deckBtnCopyInvite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }.start()

        mainHandler.postDelayed({
            deckBtnCopyInvite.text = "[+] COPY // #$activeRoomCode"
            deckBtnCopyInvite.setTextColor(Color.parseColor("#F0F4F8"))
        }, 1500)

        vibrateClick()
    }

    private fun toggleMic() {
        if (isPttMode) return
        isMicEnabled = !isMicEnabled
        if (isMicEnabled && isDeafened) {
            isDeafened = false
            applyRemoteVolumes()
            updateDeafenUi()
        }
        serviceScope.launch {
            room?.localParticipant?.setMicrophoneEnabled(isMicEnabled)
        }
        playChime(if (isMicEnabled) ToneGenerator.TONE_CDMA_PIP else ToneGenerator.TONE_PROP_ACK, 40)
        updateMicUi()
        vibrateClick()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMicControl(view: View, isPill: Boolean) {
        view.setOnTouchListener { v, event ->
            if (isPttMode) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start()
                        if (!isDeafened) {
                            setPttSpeaking(true)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f)
                            .setDuration(120)
                            .setInterpolator(OvershootInterpolator(2.0f))
                            .start()
                        setPttSpeaking(false)
                        true
                    }
                    else -> false
                }
            } else {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f)
                            .setDuration(140)
                            .setInterpolator(OvershootInterpolator(2.2f))
                            .start()
                        toggleMic()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun setPttSpeaking(speaking: Boolean) {
        if (speaking) {
            isMicEnabled = true
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(true) }
            playChime(ToneGenerator.TONE_CDMA_PIP, 45)
            vibrateDetent()

            pillBtnQuickMute.isSelected = false
            pillBtnQuickMute.text = "TALK"
            pillVoiceLed.isActivated = true

            btnToggleMic.isSelected = false
            btnToggleMic.isActivated = true
            txtStateMic.text = "TALK"
            txtStateMic.setTextColor(Color.parseColor("#00F58A"))
        } else {
            isMicEnabled = false
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(false) }
            playChime(ToneGenerator.TONE_PROP_ACK, 45)

            pillBtnQuickMute.isSelected = true
            pillBtnQuickMute.text = "PTT"
            pillVoiceLed.isActivated = false

            btnToggleMic.isSelected = true
            btnToggleMic.isActivated = false
            txtStateMic.text = "HOLD"
            txtStateMic.setTextColor(Color.parseColor("#FFB300"))
        }
    }

    private fun togglePttMode() {
        isPttMode = !isPttMode
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("ptt_mode", isPttMode).apply()
        if (isPttMode) {
            isMicEnabled = false
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(false) }
            playChime(ToneGenerator.TONE_PROP_BEEP2, 100)
            Toast.makeText(this, "PTT: Press & hold to speak", Toast.LENGTH_SHORT).show()
        } else {
            isMicEnabled = true
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(true) }
            playChime(ToneGenerator.TONE_PROP_BEEP, 100)
            Toast.makeText(this, "OPEN MIC: Always transmitting", Toast.LENGTH_SHORT).show()
        }
        updateModeBadge()
        deckBitrateBadge.animate().scaleX(1.15f).scaleY(1.15f).setDuration(90).withEndAction {
            deckBitrateBadge.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
        }.start()
        updateMicUi()
        vibrateClick()
    }

    private fun updateModeBadge() {
        if (isPttMode) {
            deckBitrateBadge.text = "PTT // 96K"
            deckBitrateBadge.setTextColor(Color.parseColor("#FFB300"))
        } else {
            deckBitrateBadge.text = "OPEN // 96K"
            deckBitrateBadge.setTextColor(Color.parseColor("#00F58A"))
        }
    }

    private fun cycleToNextChannel() {
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        val channels = mutableListOf<String>()
        for (i in 0..3) {
            val ch = prefs.getString("chip_$i", null)
            if (!ch.isNullOrBlank() && !channels.contains(ch)) {
                channels.add(ch)
            }
        }
        val defaultList = listOf("SQ-884", "SCRIM-A", "APEX-01", "DUO-99")
        for (d in defaultList) {
            if (!channels.contains(d)) channels.add(d)
        }

        var idx = channels.indexOf(activeRoomCode)
        if (idx == -1) idx = 0
        val nextRoom = channels[(idx + 1) % channels.size]
        switchToRoom(nextRoom)
    }

    private fun switchToRoom(newRoomCode: String) {
        if (newRoomCode == activeRoomCode) return
        activeRoomCode = newRoomCode
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("room", newRoomCode).putString("chip_0", newRoomCode).apply()

        deckChannelTitle.text = "CH // $newRoomCode"
        deckBtnCopyInvite.text = "[+] COPY // #$newRoomCode"
        playChime(ToneGenerator.TONE_DTMF_D, 90)
        vibrateClick()
        Toast.makeText(this, "Frequency switched: #$newRoomCode", Toast.LENGTH_SHORT).show()

        val oldRoom = room
        room = null
        serviceScope.launch {
            try {
                oldRoom?.disconnect()
                oldRoom?.release()
            } catch (_: Exception) {}
            connectToVoiceBackend(currentCallsign, newRoomCode)
        }
    }

    private fun sendSquadPing(targetIdentity: String, btn: TextView) {
        vibrateClick()
        playChime(ToneGenerator.TONE_PROP_ACK, 40)
        btn.text = "SENT"
        btn.setTextColor(Color.parseColor("#00F58A"))
        btn.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80).withEndAction {
            btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
        }.start()

        mainHandler.postDelayed({
            btn.text = "PING"
            btn.setTextColor(Color.parseColor("#FFB300"))
        }, 1500)

        serviceScope.launch(Dispatchers.IO) {
            try {
                room?.localParticipant?.publishData(
                    data = "PING:$targetIdentity".toByteArray(Charsets.UTF_8)
                )
            } catch (_: Exception) {}
        }
    }

    private fun handleDataReceived(event: RoomEvent.DataReceived) {
        val payload = try {
            String(event.data, Charsets.UTF_8)
        } catch (_: Exception) { "" }

        val sender = event.participant?.identity?.value ?: "SQUADMATE"
        if (payload.startsWith("PING:")) {
            val target = payload.removePrefix("PING:")
            if (target.equals(currentCallsign, ignoreCase = true) || target == "ALL") {
                triggerSquadAlert(sender)
            }
        }
    }

    private fun triggerSquadAlert(sender: String) {
        mainHandler.post {
            playChime(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 280)
            vibrateAlert()
            Toast.makeText(this, "🚨 PING FROM $sender!", Toast.LENGTH_SHORT).show()

            pillTelemetryReadout.text = "! PING: $sender"
            pillTelemetryReadout.setTextColor(Color.parseColor("#FF2A4B"))
            pillVoiceLed.isActivated = true
            pillVoiceLed.animate()
                .scaleX(1.6f).scaleY(1.6f)
                .setDuration(120)
                .withEndAction {
                    pillVoiceLed.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }.start()

            mainHandler.postDelayed({
                pillTelemetryReadout.setTextColor(Color.parseColor("#F0F4F8"))
                val hasActiveSpeakers = room?.remoteParticipants?.values?.any { it.isSpeaking } == true
                if (!hasActiveSpeakers) {
                    pillTelemetryReadout.text = "STANDBY"
                    pillVoiceLed.isActivated = false
                }
            }, 3000)
        }
    }

    private fun vibrateAlert() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 70, 100, 70, 180), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(280)
            }
        } catch (_: Exception) {}
    }

    private fun playChime(toneType: Int, durationMs: Int) {
        try {
            toneGenerator?.startTone(toneType, durationMs)
        } catch (_: Exception) {}
    }

    private fun toggleDeafen() {
        isDeafened = !isDeafened
        if (isDeafened) {
            isMicEnabled = false
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(false) }
            playChime(ToneGenerator.TONE_PROP_PROMPT, 90)
        } else {
            playChime(ToneGenerator.TONE_PROP_BEEP, 80)
        }
        applyRemoteVolumes()
        updateMicUi()
        updateDeafenUi()
        vibrateClick()
    }

    private fun toggleAudioRoute() {
        isSpeakerphone = !isSpeakerphone
        audioManager.isSpeakerphoneOn = isSpeakerphone
        txtStateRoute.text = if (isSpeakerphone) "SPKR" else "EAR"
        txtStateRoute.setTextColor(Color.parseColor(if (isSpeakerphone) "#00F58A" else "#7C869B"))
        btnToggleRoute.isActivated = isSpeakerphone

        txtStateRoute.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            txtStateRoute.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()

        playChime(ToneGenerator.TONE_PROP_BEEP, 50)
        vibrateClick()
    }

    private fun updateMicUi() {
        if (isPttMode) {
            pillBtnQuickMute.isSelected = true
            pillBtnQuickMute.text = "PTT"

            btnToggleMic.isSelected = true
            btnToggleMic.isActivated = false
            txtStateMic.text = "HOLD"
            txtStateMic.setTextColor(Color.parseColor("#FFB300"))
        } else {
            pillBtnQuickMute.isSelected = !isMicEnabled
            pillBtnQuickMute.text = if (isMicEnabled) "MIC" else "MUTED"

            btnToggleMic.isSelected = !isMicEnabled
            btnToggleMic.isActivated = isMicEnabled
            txtStateMic.text = if (isMicEnabled) "LIVE" else "MUTED"
            txtStateMic.setTextColor(Color.parseColor(if (isMicEnabled) "#00F58A" else "#FF2A4B"))

            // Pop pulse on state text
            txtStateMic.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
                txtStateMic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
    }

    private fun updateDeafenUi() {
        btnToggleDeafen.isSelected = isDeafened
        txtStateDeafen.text = if (isDeafened) "DEAF" else "NORM"
        txtStateDeafen.setTextColor(Color.parseColor(if (isDeafened) "#FF2A4B" else "#7C869B"))

        txtStateDeafen.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            txtStateDeafen.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun rebuildSquadList() {
        val currentRoom = room ?: return
        val remotes = currentRoom.remoteParticipants.values.toList()

        val totalMembers = remotes.size + 1
        pillSquadCount.text = String.format("%02d/%02d", totalMembers, totalMembers)

        deckRosterContainer.removeAllViews()

        if (remotes.isEmpty()) {
            val emptyTxt = TextView(this).apply {
                text = "NO SQUAD OPERATORS DETECTED"
                setTextColor(Color.parseColor("#464E62"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(0, dp(8), 0, dp(8))
            }
            deckRosterContainer.addView(emptyTxt)
            return
        }

        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)

        remotes.forEach { participant ->
            val identity = participant.identity?.value ?: "OPERATOR"
            val savedVol = participantVolumes.getOrPut(identity) {
                prefs.getInt("vol_$identity", 100)
            }

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_roster_row)
                setPadding(dp(10), dp(6), dp(8), dp(6))
                tag = "ROW_$identity"
            }

            val topBar = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }

            val led = View(this).apply {
                tag = "LED_$identity"
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6))
                setBackgroundResource(R.drawable.indicator_voice_led)
            }

            val callsignTxt = TextView(this).apply {
                text = identity.uppercase()
                setTextColor(Color.parseColor("#F0F4F8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = android.graphics.Typeface.MONOSPACE
                paint.isFakeBoldText = true
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(6)
                }
            }

            // P2P Squad Ping Trigger Button
            val pingBtn = TextView(this).apply {
                text = "PING"
                setTextColor(Color.parseColor("#FFB300"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = android.graphics.Typeface.MONOSPACE
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_tactile_ping)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(20)
                ).apply { rightMargin = dp(8) }

                setOnClickListener {
                    sendSquadPing(identity, this)
                }
            }

            val gainReadout = TextView(this).apply {
                tag = "GAIN_$identity"
                updateGainText(this, savedVol)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = android.graphics.Typeface.MONOSPACE
                paint.isFakeBoldText = true
            }

            topBar.addView(led)
            topBar.addView(callsignTxt)
            topBar.addView(pingBtn)
            topBar.addView(gainReadout)

            var lastSnapped = false

            val seekbar = SeekBar(this).apply {
                max = 200
                progress = savedVol
                progressDrawable = getDrawable(R.drawable.seekbar_gain_track)
                thumb = getDrawable(R.drawable.seekbar_gain_thumb)
                splitTrack = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(18)
                ).apply { topMargin = dp(2) }
                setPadding(dp(6), 0, dp(6), 0)

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        var effectiveProgress = progress
                        // Magnetic 100% Unity Snap + Detent Haptic Click
                        if (fromUser && progress in 96..104) {
                            effectiveProgress = 100
                            sb?.progress = 100
                            if (!lastSnapped) {
                                vibrateDetent()
                                lastSnapped = true
                            }
                        } else {
                            lastSnapped = false
                        }

                        participantVolumes[identity] = effectiveProgress
                        prefs.edit().putInt("vol_$identity", effectiveProgress).apply()
                        updateGainText(gainReadout, effectiveProgress)
                        setParticipantVolume(participant, effectiveProgress)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {
                        sb?.animate()?.scaleY(1.25f)?.setDuration(100)?.start()
                    }

                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        sb?.animate()?.scaleY(1.0f)?.setDuration(120)?.start()
                    }
                })
            }

            row.addView(topBar)
            row.addView(seekbar)
            deckRosterContainer.addView(row)

            setParticipantVolume(participant, savedVol)
        }
    }

    private fun updateGainText(textView: TextView, progress: Int) {
        if (progress > 100) {
            textView.text = "$progress% BOOST"
            textView.setTextColor(Color.parseColor("#FFB300"))
        } else if (progress == 100) {
            textView.text = "100%"
            textView.setTextColor(Color.parseColor("#00F58A"))
        } else {
            textView.text = "$progress%"
            textView.setTextColor(Color.parseColor("#7C869B"))
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
        val isAnyoneTalking = activeIds.isNotEmpty()

        // Collapsed pill reactive states
        layoutCollapsed.isActivated = isAnyoneTalking
        pillVoiceLed.isActivated = isAnyoneTalking

        if (isAnyoneTalking) {
            val firstSpeaker = activeIds.first()
            pillTelemetryReadout.text = firstSpeaker.uppercase()
            // Breathing LED pulse on voice transmission
            pillVoiceLed.animate()
                .scaleX(1.35f).scaleY(1.35f)
                .setDuration(100)
                .withEndAction {
                    pillVoiceLed.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
                }.start()
        } else {
            pillTelemetryReadout.text = "STANDBY"
        }

        // Roster row highlight states
        for (i in 0 until deckRosterContainer.childCount) {
            val child = deckRosterContainer.getChildAt(i)
            val rowTag = child.tag as? String ?: continue
            if (rowTag.startsWith("ROW_")) {
                val id = rowTag.removePrefix("ROW_")
                val isSpeaker = activeIds.contains(id)
                child.isActivated = isSpeaker
                val led = child.findViewWithTag<View>("LED_$id")
                led?.isActivated = isSpeaker
            }
        }
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

    private fun vibrateDetent() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25)
            }
        } catch (_: Exception) {}
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

        return View.OnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).start()
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
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).setInterpolator(OvershootInterpolator(2.0f)).start()
                    if (!isDragging) {
                        onTap?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                    true
                }
                else -> false
            }
        }
    }

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
            put("exp", nowSeconds + 86400)
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
            .setContentText("Tactical HUD running")
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
        toneGenerator?.release()
        toneGenerator = null
        room?.disconnect()
        room?.release()
        room = null
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
