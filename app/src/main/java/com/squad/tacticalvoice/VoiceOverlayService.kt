package com.squad.tacticalvoice

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
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
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaRecorder
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
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
    private var isPttSpeaking = false
    private val participantVolumes = mutableMapOf<String, Int>()

    // UI References: Edge Slider Handle & Expanded Command Deck
    private lateinit var layoutCollapsed: View
    private lateinit var edgeTabIndicator: View
    private lateinit var layoutExpanded: View
    private var isDockedOnRight = false

    // Floating Speaking Dots Overlay (Dynamic Constellation Cluster)
    private var dotsOverlayView: View? = null
    private var dotsContainer: FrameLayout? = null
    private var dotsParams: WindowManager.LayoutParams? = null
    private var isDotsEnabled = true

    private val MEMBER_DOT_COLORS = listOf(
        "#00F58A", // Self / Neon Mint
        "#00D2FF", // Electric Cyan
        "#FF9500", // Avionics Amber
        "#B366FF", // Neon Violet
        "#FF3B69", // Coral Rose
        "#FFEA00", // Laser Yellow
        "#FF85D0", // Cyber Pink
        "#4DEEEA"  // Turquoise
    )

    private val CONSTELLATION_OFFSETS = listOf(
        Pair(2, 6),    // Member 0 (Self)
        Pair(14, 0),   // Member 1
        Pair(24, 10),  // Member 2
        Pair(10, 18),  // Member 3
        Pair(22, 22),  // Member 4
        Pair(34, 4),   // Member 5
        Pair(4, 28),   // Member 6
        Pair(32, 16)   // Member 7
    )

    private lateinit var deckHeaderDragZone: View
    private lateinit var deckDragHandle: View
    private lateinit var deckChannelTitle: TextView
    private lateinit var deckBtnToggleDots: TextView
    private lateinit var deckBitrateBadge: TextView
    private lateinit var deckBtnCollapse: ImageView

    private lateinit var btnToggleMic: View
    private lateinit var iconStateMic: ImageView
    private lateinit var txtStateMic: TextView
    private lateinit var btnToggleDeafen: View
    private lateinit var iconStateDeafen: ImageView
    private lateinit var txtStateDeafen: TextView
    private lateinit var btnToggleRoute: View
    private lateinit var iconStateRoute: ImageView
    private lateinit var txtStateRoute: TextView
    private lateinit var btnDisconnect: View

    private lateinit var deckRosterContainer: LinearLayout
    private lateinit var deckBtnCopyInvite: TextView
    private lateinit var deckPingReadout: TextView

    private lateinit var tabBtnRoster: TextView
    private lateinit var tabBtnChannels: TextView
    private lateinit var deckTabContentRoster: LinearLayout
    private lateinit var deckTabContentChannels: LinearLayout
    private lateinit var deckActiveRoomTag: TextView
    private lateinit var inputOverlayRoomCode: EditText
    private lateinit var btnOverlayRandCode: ImageView
    private lateinit var btnOverlayJoinCode: TextView
    private lateinit var deckRecentChannelsContainer: LinearLayout

    private var alertBannerRunnable: Runnable? = null

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
        spawnSpeakingDotsOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callsign = intent?.getStringExtra("CALLSIGN") ?: "OPERATOR"
        val roomCode = (intent?.getStringExtra("ROOM_CODE") ?: "SQ-884").trim().uppercase()
        currentCallsign = callsign
        activeRoomCode = roomCode
        recordChannelJoined(roomCode)
        deckChannelTitle.text = "CH // $roomCode"
        deckBtnCopyInvite.text = "[+] COPY // #$roomCode"
        deckActiveRoomTag.text = "#$roomCode"
        rebuildSpeakingDots()
        connectToVoiceBackend(callsign, roomCode)
        return START_STICKY
    }

    private fun connectToVoiceBackend(callsign: String, roomName: String) {
        if (room != null) return

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {}

        val roomOptions = RoomOptions(
            adaptiveStream = false,
            dynacast = false,
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = false,
                highPassFilter = true,
                typingNoiseDetection = true
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
                    javaAudioDeviceModuleCustomizer = { builder ->
                        builder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        builder.setUseHardwareAcousticEchoCanceler(true)
                        builder.setUseHardwareNoiseSuppressor(true)
                    }
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
                        val name = event.participant.identity?.value ?: "OPERATOR"
                        Toast.makeText(this@VoiceOverlayService, "[+] $name JOINED SQUAD", Toast.LENGTH_SHORT).show()
                        playChime(ToneGenerator.TONE_PROP_BEEP2, 110)
                        rebuildSquadList()
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        val name = event.participant.identity?.value ?: "OPERATOR"
                        Toast.makeText(this@VoiceOverlayService, "[-] $name LEFT SQUAD", Toast.LENGTH_SHORT).show()
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
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        isDockedOnRight = prefs.getBoolean("docked_on_right", false)
        val metrics = resources.displayMetrics
        val handleWidth = dp(18)
        val initialX = if (isDockedOnRight) maxOf(0, metrics.widthPixels - handleWidth) else 0

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
            x = initialX
            y = dp(140)
        }
        overlayParams = params

        layoutCollapsed = root.findViewById(R.id.layout_hud_collapsed)
        edgeTabIndicator = root.findViewById(R.id.edge_tab_indicator)
        layoutExpanded = root.findViewById(R.id.layout_hud_expanded)

        deckHeaderDragZone = root.findViewById(R.id.deck_header_drag_zone)
        deckDragHandle = root.findViewById(R.id.deck_drag_handle)
        deckChannelTitle = root.findViewById(R.id.deck_channel_title)
        deckBtnToggleDots = root.findViewById(R.id.deck_btn_toggle_dots)
        deckBitrateBadge = root.findViewById(R.id.deck_bitrate_badge)
        deckBtnCollapse = root.findViewById(R.id.deck_btn_collapse)

        btnToggleMic = root.findViewById(R.id.btn_toggle_mic)
        iconStateMic = root.findViewById(R.id.icon_state_mic)
        txtStateMic = root.findViewById(R.id.txt_state_mic)
        btnToggleDeafen = root.findViewById(R.id.btn_toggle_deafen)
        iconStateDeafen = root.findViewById(R.id.icon_state_deafen)
        txtStateDeafen = root.findViewById(R.id.txt_state_deafen)
        btnToggleRoute = root.findViewById(R.id.btn_toggle_route)
        iconStateRoute = root.findViewById(R.id.icon_state_route)
        txtStateRoute = root.findViewById(R.id.txt_state_route)
        btnDisconnect = root.findViewById(R.id.btn_disconnect)

        deckRosterContainer = root.findViewById(R.id.deck_roster_container)
        deckBtnCopyInvite = root.findViewById(R.id.deck_btn_copy_invite)
        deckPingReadout = root.findViewById(R.id.deck_ping_readout)

        tabBtnRoster = root.findViewById(R.id.tab_btn_roster)
        tabBtnChannels = root.findViewById(R.id.tab_btn_channels)
        deckTabContentRoster = root.findViewById(R.id.deck_tab_content_roster)
        deckTabContentChannels = root.findViewById(R.id.deck_tab_content_channels)
        deckActiveRoomTag = root.findViewById(R.id.deck_active_room_tag)
        inputOverlayRoomCode = root.findViewById(R.id.input_overlay_room_code)
        btnOverlayRandCode = root.findViewById(R.id.btn_overlay_rand_code)
        btnOverlayJoinCode = root.findViewById(R.id.btn_overlay_join_code)
        deckRecentChannelsContainer = root.findViewById(R.id.deck_recent_channels_container)

        // 1. Edge Slider Handle Drag & Slide
        attachEdgeSliderTouch(layoutCollapsed, params)

        // 2. Seamless Drag on expanded deck header bar & Swipe anywhere on panel to close
        attachDeckHeaderTouch(deckHeaderDragZone, params)
        attachDeckHeaderTouch(deckDragHandle, params)
        attachDeckHeaderTouch(deckChannelTitle, params)
        attachPanelSwipeToClose(layoutExpanded, params)
        attachPanelSwipeToClose(deckTabContentRoster, params)
        attachPanelSwipeToClose(deckTabContentChannels, params)

        setupDotsToggle(deckBtnToggleDots)
        attachSpringTouch(deckBitrateBadge) { togglePttMode() }
        attachSpringTouch(deckBtnCollapse) { collapseDeckAnimated(root, params) }

        // Dual-Tab Navigation & Overlay Channel Switcher
        attachSpringTouch(tabBtnRoster) {
            selectDeckTab(isRoster = true)
        }
        attachSpringTouch(tabBtnChannels) {
            selectDeckTab(isRoster = false)
        }

        attachSpringTouch(btnOverlayRandCode) {
            vibrateClick()
            btnOverlayRandCode.animate()
                .rotationBy(360f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
            val prefixes = listOf("SQ", "VEX", "APEX", "GHOST", "VIPER", "TITAN")
            val randCode = "${prefixes.random()}-${(100..999).random()}"
            inputOverlayRoomCode.setText(randCode)
            inputOverlayRoomCode.setSelection(randCode.length)
        }

        attachSpringTouch(btnOverlayJoinCode) {
            val code = inputOverlayRoomCode.text.toString().trim().uppercase()
            if (code.isNotEmpty()) {
                switchToRoom(code)
                inputOverlayRoomCode.text?.clear()
                selectDeckTab(isRoster = true)
            } else {
                Toast.makeText(this, "Enter valid room code", Toast.LENGTH_SHORT).show()
            }
        }

        inputOverlayRoomCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val code = inputOverlayRoomCode.text.toString().trim().uppercase()
                if (code.isNotEmpty()) {
                    switchToRoom(code)
                    inputOverlayRoomCode.text?.clear()
                    selectDeckTab(isRoster = true)
                }
                true
            } else false
        }

        // 3. Tactile Controls
        setupMicControl(btnToggleMic)
        attachSpringTouch(btnToggleDeafen) { toggleDeafen() }
        attachSpringTouch(btnToggleRoute) { toggleAudioRoute() }
        attachSpringTouch(btnDisconnect) { shutdownService() }

        // 1-Tap Copy Squad Link with tactile morph
        attachSpringTouch(deckBtnCopyInvite) {
            copySquadInviteToClipboard()
        }

        updateDockSideUi()
        windowManager.addView(root, params)
        updateModeBadge()
        updateMicUi()
        updateDeafenUi()
    }

    private fun updateDockSideUi() {
        val metrics = resources.displayMetrics
        val handleWidth = dp(18)
        val deckWidth = dp(272)
        val params = overlayParams ?: return
        val root = overlayView ?: return

        if (isDockedOnRight) {
            layoutCollapsed.setBackgroundResource(R.drawable.bg_edge_tab_right)
            layoutExpanded.setBackgroundResource(R.drawable.bg_hud_chassis_right)
            if (layoutCollapsed.visibility == View.VISIBLE) {
                params.x = maxOf(0, metrics.widthPixels - handleWidth)
            } else {
                params.x = maxOf(0, metrics.widthPixels - deckWidth)
            }
            deckBtnCollapse.rotation = 180f
        } else {
            layoutCollapsed.setBackgroundResource(R.drawable.bg_edge_tab_left)
            layoutExpanded.setBackgroundResource(R.drawable.bg_hud_chassis_left)
            params.x = 0
            deckBtnCollapse.rotation = 0f
        }

        dotsParams?.let { dParams ->
            dParams.gravity = Gravity.TOP or (if (isDockedOnRight) Gravity.START else Gravity.END)
            dotsOverlayView?.let { windowManager.updateViewLayout(it, dParams) }
        }

        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {}
    }

    private fun spawnSpeakingDotsOverlay() {
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        isDotsEnabled = prefs.getBoolean("dots_enabled", true)

        val inflater = LayoutInflater.from(this)
        val dotsRoot = inflater.inflate(R.layout.layout_speaking_dots, null)
        dotsOverlayView = dotsRoot
        dotsContainer = dotsRoot.findViewById(R.id.speaking_dots_cluster)

        val cornerGravity = Gravity.TOP or (if (isDockedOnRight) Gravity.START else Gravity.END)
        val dParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = cornerGravity
            x = dp(14)
            y = dp(24)
        }
        dotsParams = dParams

        dotsRoot.visibility = if (isDotsEnabled) View.VISIBLE else View.GONE
        try {
            windowManager.addView(dotsRoot, dParams)
        } catch (_: Exception) {}
    }

    private fun setupDotsToggle(btn: TextView) {
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        isDotsEnabled = prefs.getBoolean("dots_enabled", true)
        updateDotsToggleUi(btn)

        attachSpringTouch(btn) {
            toggleSpeakingDots(btn)
        }
    }

    private fun toggleSpeakingDots(btn: TextView) {
        isDotsEnabled = !isDotsEnabled
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("dots_enabled", isDotsEnabled).apply()
        updateDotsToggleUi(btn)
        dotsOverlayView?.visibility = if (isDotsEnabled) View.VISIBLE else View.GONE
        vibrateClick()
    }

    private fun updateDotsToggleUi(btn: TextView) {
        if (isDotsEnabled) {
            btn.text = "DOTS:ON"
            btn.setTextColor(Color.parseColor("#00F58A"))
        } else {
            btn.text = "DOTS:OFF"
            btn.setTextColor(Color.parseColor("#7C869B"))
        }
    }

    private fun rebuildSpeakingDots() {
        val container = dotsContainer ?: return
        container.removeAllViews()

        val currentRoom = room
        val members = mutableListOf<String>()
        if (currentCallsign.isNotEmpty()) {
            members.add(currentCallsign)
        }
        currentRoom?.remoteParticipants?.values?.forEach { p ->
            p.identity?.value?.let { members.add(it) }
        }

        val dotSize = dp(9)

        members.forEachIndexed { index, identity ->
            val colorHex = MEMBER_DOT_COLORS[index % MEMBER_DOT_COLORS.size]
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(5).toFloat()
                setColor(Color.parseColor(colorHex))
            }

            val dot = View(this).apply {
                tag = "DOT_$identity"
                background = circle
                alpha = 0.45f
                isSelected = false
            }

            val offset = CONSTELLATION_OFFSETS[index % CONSTELLATION_OFFSETS.size]
            val lParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                leftMargin = dp(offset.first)
                topMargin = dp(offset.second)
            }
            container.addView(dot, lParams)

            // Cute ambient floating drift animation
            ObjectAnimator.ofFloat(dot, "translationY", -dp(2).toFloat(), dp(2).toFloat()).apply {
                duration = 1600L + (index * 240L)
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }

        dotsOverlayView?.visibility = if (isDotsEnabled && members.isNotEmpty()) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachEdgeSliderTouch(tab: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var isPttEngaged = false
        var downTime = 0L

        tab.setOnTouchListener { v, event ->
            val metrics = resources.displayMetrics
            val handleWidth = dp(18)
            val maxX = maxOf(0, metrics.widthPixels - handleWidth)
            val maxY = metrics.heightPixels - dp(90)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isPttEngaged = false
                    downTime = System.currentTimeMillis()

                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start()

                    // If in PTT mode, engage PTT after a quick touch hold
                    if (isPttMode && !isDeafened) {
                        mainHandler.postDelayed({
                            if (!isDragging && tab.isPressed) {
                                isPttEngaged = true
                                setPttSpeaking(true)
                            }
                        }, 120)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (abs(dx) > touchSlop || abs(dy) > touchSlop || isDragging) {
                        if (!isDragging) {
                            isDragging = true
                            if (isPttEngaged) {
                                isPttEngaged = false
                                setPttSpeaking(false)
                            }
                        }
                        // Freely follow finger in both X and Y
                        params.x = (initialX + dx.toInt()).coerceIn(0, maxX)
                        params.y = (initialY + dy.toInt()).coerceIn(dp(40), maxY)
                        overlayView?.let { windowManager.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120)
                        .setInterpolator(OvershootInterpolator(2.0f)).start()

                    val duration = System.currentTimeMillis() - downTime
                    val dx = event.rawX - initialTouchX

                    if (isPttEngaged) {
                        isPttEngaged = false
                        setPttSpeaking(false)
                    } else if (isDragging) {
                        // Dragged across screen: Snap smoothly to nearest edge (left or right)
                        val screenMiddle = metrics.widthPixels / 2
                        val shouldDockRight = event.rawX >= screenMiddle
                        isDockedOnRight = shouldDockRight
                        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("docked_on_right", isDockedOnRight).apply()
                        vibrateClick()

                        params.x = if (isDockedOnRight) maxX else 0
                        updateDockSideUi()
                    } else {
                        // Tap or quick swipe inward: Expand deck
                        val isSwipeInward = if (isDockedOnRight) (dx < -dp(14)) else (dx > dp(14))
                        if (isSwipeInward || duration < 320) {
                            expandDeckAnimated(overlayView ?: v, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    if (isPttEngaged) {
                        isPttEngaged = false
                        setPttSpeaking(false)
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPanelSwipeToClose(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0f
        var initialY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialX
                    val dy = event.rawY - initialY
                    val isCloseSwipe = if (isDockedOnRight) (dx > dp(28)) else (dx < -dp(28))
                    if (isCloseSwipe && abs(dx) > abs(dy)) {
                        collapseDeckAnimated(overlayView ?: v, params)
                        return@setOnTouchListener true
                    }
                    false
                }
                else -> false
            }
        }
    }

    private fun attachDeckHeaderTouch(view: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDraggingY = false

        view.setOnTouchListener { v, event ->
            val metrics = resources.displayMetrics
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDraggingY = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (abs(dy) > touchSlop || isDraggingY) {
                        isDraggingY = true
                        val maxY = metrics.heightPixels - dp(180)
                        params.y = (initialY + dy.toInt()).coerceIn(dp(40), maxY)
                        overlayView?.let { windowManager.updateViewLayout(it, params) }
                    }

                    // Horizontal swipe towards edge collapses panel immediately
                    val isCloseSwipe = if (isDockedOnRight) (dx > dp(32)) else (dx < -dp(32))
                    if (isCloseSwipe && abs(dx) > abs(dy) * 1.2f) {
                        collapseDeckAnimated(overlayView ?: v, params)
                        return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val isCloseSwipe = if (isDockedOnRight) (dx > dp(28)) else (dx < -dp(28))
                    if (isCloseSwipe) {
                        collapseDeckAnimated(overlayView ?: v, params)
                        return@setOnTouchListener true
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun expandDeckAnimated(root: View, params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val deckWidth = dp(272)
        params.x = if (isDockedOnRight) maxOf(0, metrics.widthPixels - deckWidth) else 0

        layoutCollapsed.visibility = View.GONE
        layoutExpanded.visibility = View.VISIBLE
        selectDeckTab(isRoster = true)
        updateDockSideUi()
        windowManager.updateViewLayout(root, params)

        val slideDistance = if (isDockedOnRight) deckWidth.toFloat() else -deckWidth.toFloat()
        layoutExpanded.translationX = slideDistance
        layoutExpanded.alpha = 0f
        layoutExpanded.animate()
            .translationX(0f)
            .alpha(1.0f)
            .setDuration(190)
            .setInterpolator(DecelerateInterpolator())
            .start()

        vibrateClick()
    }

    private fun collapseDeckAnimated(root: View, params: WindowManager.LayoutParams) {
        setOverlayFocusable(false)
        val metrics = resources.displayMetrics
        val handleWidth = dp(18)
        val deckWidth = dp(272)
        val slideDistance = if (isDockedOnRight) deckWidth.toFloat() else -deckWidth.toFloat()

        // 1. Immediately prepare edge slider handle so both views animate simultaneously
        layoutCollapsed.visibility = View.VISIBLE
        layoutCollapsed.alpha = 0f
        layoutCollapsed.translationX = if (isDockedOnRight) handleWidth.toFloat() else -handleWidth.toFloat()
        layoutCollapsed.animate()
            .translationX(0f)
            .alpha(1.0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 2. Animate deck sliding back into the border in unison
        layoutExpanded.animate()
            .translationX(slideDistance)
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                layoutExpanded.visibility = View.GONE
                params.x = if (isDockedOnRight) maxOf(0, metrics.widthPixels - handleWidth) else 0
                updateDockSideUi()
                windowManager.updateViewLayout(root, params)
            }.start()

        vibrateClick()
    }

    private fun attachSpringTouch(view: View, onClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    val targetScale = if (isInside) 0.92f else 1.0f
                    v.animate().scaleX(targetScale).scaleY(targetScale).setDuration(60).start()
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f)
                        .setDuration(120)
                        .setInterpolator(OvershootInterpolator(2.2f))
                        .start()
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (isInside) {
                        onClick()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
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
        deckBtnCopyInvite.text = "COPIED!"
        deckBtnCopyInvite.setTextColor(Color.parseColor("#00F58A"))
        deckBtnCopyInvite.animate()
            .scaleX(1.05f).scaleY(1.05f)
            .setDuration(100)
            .withEndAction {
                deckBtnCopyInvite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }.start()

        mainHandler.postDelayed({
            deckBtnCopyInvite.text = "COPY LINK"
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
        try {
            audioManager.isMicrophoneMute = !isMicEnabled
        } catch (_: Exception) {}
        serviceScope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(isMicEnabled)
            } catch (_: Exception) {}
        }
        playChime(if (isMicEnabled) ToneGenerator.TONE_CDMA_PIP else ToneGenerator.TONE_PROP_ACK, 40)
        updateMicUi()
        vibrateClick()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMicControl(view: View) {
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
                    MotionEvent.ACTION_MOVE -> {
                        val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                        if (!isInside && isPttSpeaking) {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(60).start()
                            setPttSpeaking(false)
                        } else if (isInside && !isPttSpeaking && !isDeafened) {
                            v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start()
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
                    MotionEvent.ACTION_MOVE -> {
                        val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                        val targetScale = if (isInside) 0.92f else 1.0f
                        v.animate().scaleX(targetScale).scaleY(targetScale).setDuration(60).start()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f)
                            .setDuration(140)
                            .setInterpolator(OvershootInterpolator(2.2f))
                            .start()
                        val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                        if (isInside) {
                            toggleMic()
                        }
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
        isPttSpeaking = speaking
        isMicEnabled = speaking && !isDeafened

        try {
            audioManager.isMicrophoneMute = !isMicEnabled
        } catch (_: Exception) {}

        serviceScope.launch {
            try {
                room?.localParticipant?.setMicrophoneEnabled(isMicEnabled)
            } catch (_: Exception) {}
        }

        if (speaking) {
            playChime(ToneGenerator.TONE_CDMA_PIP, 45)
            vibrateDetent()

            edgeTabIndicator.isActivated = true
            btnToggleMic.isSelected = false
            btnToggleMic.isActivated = true
            iconStateMic.setImageResource(R.drawable.ic_mic_on)
            txtStateMic.text = "TALK"
            txtStateMic.setTextColor(Color.parseColor("#00F58A"))
        } else {
            playChime(ToneGenerator.TONE_PROP_ACK, 45)

            edgeTabIndicator.isActivated = false
            btnToggleMic.isSelected = true
            btnToggleMic.isActivated = false
            iconStateMic.setImageResource(R.drawable.ic_mic_off)
            txtStateMic.text = "HOLD"
            txtStateMic.setTextColor(Color.parseColor("#FFB300"))
        }
        highlightActiveSpeakers(room?.activeSpeakers ?: emptyList())
    }

    private fun togglePttMode() {
        isPttMode = !isPttMode
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("ptt_mode", isPttMode).apply()
        if (isPttMode) {
            isPttSpeaking = false
            isMicEnabled = false
            try {
                audioManager.isMicrophoneMute = true
            } catch (_: Exception) {}
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(false) }
            playChime(ToneGenerator.TONE_PROP_BEEP2, 100)
            Toast.makeText(this, "PTT: Hold slider or keycap to talk", Toast.LENGTH_SHORT).show()
        } else {
            isMicEnabled = true
            try {
                audioManager.isMicrophoneMute = false
            } catch (_: Exception) {}
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

    private fun setOverlayFocusable(focusable: Boolean) {
        val params = overlayParams ?: return
        val root = overlayView ?: return
        val currentlyFocusable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (currentlyFocusable == focusable) return

        if (focusable) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(root.windowToken, 0)
        }
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {}
    }

    private fun selectDeckTab(isRoster: Boolean) {
        vibrateClick()
        if (isRoster) {
            tabBtnRoster.isSelected = true
            tabBtnRoster.setTextColor(Color.parseColor("#FF5500"))
            tabBtnChannels.isSelected = false
            tabBtnChannels.setTextColor(Color.parseColor("#7C869B"))

            deckTabContentChannels.visibility = View.GONE
            deckTabContentRoster.visibility = View.VISIBLE

            setOverlayFocusable(false)
            rebuildSquadList()
        } else {
            tabBtnChannels.isSelected = true
            tabBtnChannels.setTextColor(Color.parseColor("#FF5500"))
            tabBtnRoster.isSelected = false
            tabBtnRoster.setTextColor(Color.parseColor("#7C869B"))

            deckTabContentRoster.visibility = View.GONE
            deckTabContentChannels.visibility = View.VISIBLE

            setOverlayFocusable(true)
            deckActiveRoomTag.text = "#$activeRoomCode"
            rebuildRecentChannelsList()
        }
    }

    private fun getChannelHistory(): MutableList<String> {
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("channel_history_v2", null)
        val list = mutableListOf<String>()
        if (!raw.isNullOrBlank()) {
            raw.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.forEach {
                if (!list.contains(it)) list.add(it)
            }
        }
        for (i in 0..3) {
            val chip = prefs.getString("chip_$i", null)?.trim()?.uppercase()
            if (!chip.isNullOrBlank() && !list.contains(chip)) {
                list.add(chip)
            }
        }
        val defaultList = listOf("SQ-884", "SCRIM-A", "APEX-01", "DUO-99")
        for (d in defaultList) {
            if (!list.contains(d)) list.add(d)
        }
        return list
    }

    private fun recordChannelJoined(roomCode: String) {
        val clean = roomCode.trim().uppercase()
        if (clean.isBlank()) return
        val list = getChannelHistory()
        list.remove(clean)
        list.add(0, clean)
        if (list.size > 8) {
            list.subList(8, list.size).clear()
        }
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("channel_history_v2", list.joinToString(","))
            .putString("room", clean)
            .apply()
    }

    private fun rebuildRecentChannelsList() {
        deckRecentChannelsContainer.removeAllViews()
        val history = getChannelHistory()
        if (!history.contains(activeRoomCode.uppercase())) {
            history.add(0, activeRoomCode.uppercase())
        }

        history.take(6).forEach { roomCode ->
            val isCurrent = roomCode.equals(activeRoomCode, ignoreCase = true)

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(30)
                ).apply { bottomMargin = dp(4) }
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(if (isCurrent) R.drawable.bg_roster_row else R.drawable.bg_chip_channel)
                setPadding(dp(10), dp(4), dp(8), dp(4))
                isClickable = true
                isFocusable = false
            }

            val titleView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "#$roomCode"
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor(if (isCurrent) "#00F58A" else "#F0F4F8"))
                includeFontPadding = false
            }

            val badgeView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = if (isCurrent) "[ACTIVE]" else "[HOP]"
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(Color.parseColor(if (isCurrent) "#00F58A" else "#7C869B"))
                includeFontPadding = false
            }

            row.addView(titleView)
            row.addView(badgeView)

            attachSpringTouch(row) {
                if (!isCurrent) {
                    switchToRoom(roomCode)
                    selectDeckTab(isRoster = true)
                }
            }

            deckRecentChannelsContainer.addView(row)
        }
    }

    private fun cycleToNextChannel() {
        val list = getChannelHistory()
        if (!list.contains(activeRoomCode.uppercase())) {
            list.add(0, activeRoomCode.uppercase())
        }
        var idx = list.indexOf(activeRoomCode.uppercase())
        if (idx == -1) idx = 0
        val nextRoom = list[(idx + 1) % list.size]
        switchToRoom(nextRoom)
    }

    private fun switchToRoom(newRoomCode: String) {
        val cleanRoomCode = newRoomCode.trim().uppercase()
        if (cleanRoomCode == activeRoomCode) return
        activeRoomCode = cleanRoomCode
        recordChannelJoined(cleanRoomCode)

        deckChannelTitle.text = "CH // $cleanRoomCode"
        deckBtnCopyInvite.text = "[+] COPY // #$cleanRoomCode"
        deckActiveRoomTag.text = "#$cleanRoomCode"
        playChime(ToneGenerator.TONE_DTMF_D, 90)
        vibrateClick()
        Toast.makeText(this, "Frequency switched: #$cleanRoomCode", Toast.LENGTH_SHORT).show()

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
            Toast.makeText(this, "[PING] ALERT FROM $sender!", Toast.LENGTH_SHORT).show()
            edgeTabIndicator.isActivated = true
            edgeTabIndicator.animate()
                .scaleX(1.6f).scaleY(1.6f)
                .setDuration(120)
                .withEndAction {
                    edgeTabIndicator.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }.start()

            mainHandler.postDelayed({
                val hasActiveSpeakers = room?.remoteParticipants?.values?.any { it.isSpeaking } == true
                if (!hasActiveSpeakers) {
                    edgeTabIndicator.isActivated = false
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
            try {
                audioManager.isMicrophoneMute = true
            } catch (_: Exception) {}
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(false) }
            playChime(ToneGenerator.TONE_PROP_PROMPT, 90)
        } else {
            if (!isPttMode) {
                isMicEnabled = true
                try {
                    audioManager.isMicrophoneMute = false
                } catch (_: Exception) {}
                serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(true) }
            }
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
        iconStateRoute.setImageResource(if (isSpeakerphone) R.drawable.ic_speaker else R.drawable.ic_earpiece)
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
            btnToggleMic.isSelected = true
            btnToggleMic.isActivated = isPttSpeaking
            iconStateMic.setImageResource(if (isPttSpeaking) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
            txtStateMic.text = if (isPttSpeaking) "TALK" else "HOLD"
            txtStateMic.setTextColor(Color.parseColor(if (isPttSpeaking) "#00F58A" else "#FFB300"))
        } else {
            btnToggleMic.isSelected = !isMicEnabled
            btnToggleMic.isActivated = isMicEnabled
            iconStateMic.setImageResource(if (isMicEnabled) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
            txtStateMic.text = if (isMicEnabled) "LIVE" else "MUTED"
            txtStateMic.setTextColor(Color.parseColor(if (isMicEnabled) "#00F58A" else "#FF2A4B"))

            // Pop pulse on state text
            txtStateMic.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
                txtStateMic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
        edgeTabIndicator.isActivated = if (isPttMode) isPttSpeaking else isMicEnabled
    }

    private fun updateDeafenUi() {
        btnToggleDeafen.isSelected = isDeafened
        iconStateDeafen.setImageResource(if (isDeafened) R.drawable.ic_headset_off else R.drawable.ic_headset)
        txtStateDeafen.text = if (isDeafened) "DEAF" else "NORM"
        txtStateDeafen.setTextColor(Color.parseColor(if (isDeafened) "#FF2A4B" else "#7C869B"))

        txtStateDeafen.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            txtStateDeafen.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun rebuildSquadList() {
        val currentRoom = room ?: return
        val remotes = currentRoom.remoteParticipants.values.toList()

        rebuildSpeakingDots()

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

                attachSpringTouch(this) {
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
        val activeIds = speakers.mapNotNull { it.identity?.value }.toMutableSet()
        if (isMicEnabled && !isDeafened && (room?.localParticipant?.isSpeaking == true || (isPttMode && isPttSpeaking))) {
            activeIds.add(currentCallsign)
        }
        val isAnyoneTalking = activeIds.isNotEmpty()

        // Edge slider tab live speaking state
        layoutCollapsed.isActivated = isAnyoneTalking
        edgeTabIndicator.isActivated = isAnyoneTalking

        // Dynamic Speaking Dots in screen corner with Circle-to-Square Morph and Spring Physics
        dotsContainer?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val id = child.tag?.toString()?.removePrefix("DOT_") ?: continue
                val isSpeaker = activeIds.contains(id)

                if (isSpeaker && !child.isSelected) {
                    child.isSelected = true
                    // Morphs from circle (5dp radius) to tactical rounded square (1.5dp radius)
                    ValueAnimator.ofFloat(dp(5).toFloat(), dp(1.5f).toFloat()).apply {
                        duration = 110
                        addUpdateListener { va ->
                            (child.background as? GradientDrawable)?.cornerRadius = va.animatedValue as Float
                        }
                        start()
                    }
                    child.animate()
                        .scaleX(1.55f)
                        .scaleY(1.55f)
                        .rotation(45f)
                        .alpha(1.0f)
                        .setDuration(120)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                } else if (!isSpeaker && child.isSelected) {
                    child.isSelected = false
                    // Morphs back from square (1.5dp radius) to circle (5dp radius)
                    ValueAnimator.ofFloat(dp(1.5f).toFloat(), dp(5).toFloat()).apply {
                        duration = 140
                        addUpdateListener { va ->
                            (child.background as? GradientDrawable)?.cornerRadius = va.animatedValue as Float
                        }
                        start()
                    }
                    child.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .rotation(0f)
                        .alpha(0.45f)
                        .setDuration(160)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
            }
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

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSeamlessDrag(
        touchSurface: View,
        params: WindowManager.LayoutParams,
        onClick: (() -> Unit)? = null
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        touchSurface.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (isDragging || abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                        val displayMetrics = resources.displayMetrics
                        val rootWidth = if ((overlayView?.width ?: 0) > 0) overlayView!!.width else dp(200)
                        val rootHeight = if ((overlayView?.height ?: 0) > 0) overlayView!!.height else dp(45)
                        val maxX = displayMetrics.widthPixels - rootWidth
                        val maxY = displayMetrics.heightPixels - rootHeight
                        params.x = (initialX + dx).coerceIn(0, maxOf(0, maxX))
                        params.y = (initialY + dy).coerceIn(0, maxOf(0, maxY))
                        overlayView?.let { windowManager.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f)
                        .setDuration(120)
                        .setInterpolator(OvershootInterpolator(2.0f))
                        .start()
                    val isInside = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (!isDragging && isInside) {
                        onClick?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                    isDragging = false
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
            .setContentTitle("VOX Tactical HUD")
            .setContentText("In-Game Comms Active")
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
        setOverlayFocusable(false)
        alertBannerRunnable?.let { mainHandler.removeCallbacks(it) }
        alertBannerRunnable = null
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        toneGenerator?.release()
        toneGenerator = null
        room?.disconnect()
        room?.release()
        room = null
        dotsOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        dotsOverlayView = null
        dotsContainer = null
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        serviceScope.cancel()
        super.onDestroy()
    }
}
