package com.squad.tacticalvoice

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
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
import kotlinx.coroutines.Job
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

        // Zero-allocation pre-computed @ColorInt constants
        private const val COLOR_MINT = 0xFF00F58A.toInt()
        private const val COLOR_CYAN = 0xFF00D2FF.toInt()
        private const val COLOR_AMBER = 0xFFFFB300.toInt()
        private const val COLOR_RED = 0xFFFF2A4B.toInt()
        private const val COLOR_MUTED = 0xFF7C869B.toInt()
        private const val COLOR_WHITE = 0xFFF0F4F8.toInt()
        private const val COLOR_ORANGE = 0xFFFF5500.toInt()
        private const val COLOR_DARK_ROW = 0xFF464E62.toInt()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null

    // Single unified overlay window (0 secondary EGL swapchains, 0 GLES fallback)
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var room: Room? = null
    private var roomEventsJob: Job? = null
    private var roomConnectJob: Job? = null
    private var activeRoomCode = "SQ-884"
    private var currentCallsign = "OPERATOR"

    // State flags
    private var isMicEnabled = true
    private var isDeafened = false
    private var isSpeakerphone = true
    private val participantVolumes = mutableMapOf<String, Int>()

    // UI References: Edge Slider Handle & Expanded Command Deck
    private lateinit var layoutCollapsed: View
    private lateinit var edgeTabIndicator: View
    private lateinit var layoutExpanded: View
    private var isDockedOnRight = false

    private lateinit var deckHeaderDragZone: View
    private lateinit var deckDragHandle: View
    private lateinit var deckChannelTitle: TextView
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

    private val speakerLastActiveTime = mutableMapOf<String, Long>()
    private var hangoverCheckRunnable: Runnable? = null
    private var isScreenOn = true

    // Power-saving screen lifecycle receiver: 0% CPU/GPU overhead when screen is off
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    hangoverCheckRunnable?.let { mainHandler.removeCallbacks(it) }
                    overlayView?.visibility = View.GONE
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    overlayView?.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 65)
        } catch (_: Exception) {}

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        startMinimalForegroundNotification()
        spawnTacticalOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        val callsign = intent?.getStringExtra("CALLSIGN")
            ?: prefs.getString("callsign", "OPERATOR")
            ?: "OPERATOR"
        val roomCode = (intent?.getStringExtra("ROOM_CODE")
            ?: prefs.getString("room", "SQ-884")
            ?: "SQ-884").trim().uppercase()
        currentCallsign = callsign
        activeRoomCode = roomCode
        recordChannelJoined(roomCode)
        deckChannelTitle.text = "CH // $roomCode"
        deckBtnCopyInvite.text = "[+] COPY // #$roomCode"
        deckActiveRoomTag.text = "#$roomCode"
        connectToVoiceBackend(callsign, roomCode)
        return START_STICKY
    }

    private fun connectToVoiceBackend(callsign: String, roomName: String) {
        if (room != null) return

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {}

        // Ultra-lightweight Opus voice profile: 24kbps wideband, DTX silence gating, hardware AEC
        val roomOptions = RoomOptions(
            adaptiveStream = false,
            dynacast = false,
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = false,
                highPassFilter = true,
                typingNoiseDetection = false
            ),
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = 24_000,
                dtx = true,
                red = false
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

        roomEventsJob?.cancel()
        roomEventsJob = serviceScope.launch {
            currentRoom.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        deckPingReadout.text = "LIVE"
                        deckPingReadout.setTextColor(COLOR_MINT)
                        currentRoom.localParticipant.setMicrophoneEnabled(isMicEnabled)
                        rebuildSquadList()
                    }
                    is RoomEvent.Reconnecting -> {
                        deckPingReadout.text = "RECON"
                        deckPingReadout.setTextColor(COLOR_AMBER)
                    }
                    is RoomEvent.Reconnected -> {
                        deckPingReadout.text = "LIVE"
                        deckPingReadout.setTextColor(COLOR_MINT)
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
                        speakerLastActiveTime.remove(name)
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

        roomConnectJob?.cancel()
        roomConnectJob = serviceScope.launch(Dispatchers.IO) {
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
                    deckPingReadout.setTextColor(COLOR_RED)
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

        // 2. Drag on expanded deck header bar & Swipe anywhere on panel to close
        attachDeckHeaderTouch(deckHeaderDragZone, params)
        attachDeckHeaderTouch(deckDragHandle, params)
        attachDeckHeaderTouch(deckChannelTitle, params)
        attachPanelSwipeToClose(layoutExpanded, params)
        attachPanelSwipeToClose(deckTabContentRoster, params)
        attachPanelSwipeToClose(deckTabContentChannels, params)

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

        // 3. Tactile 1-Tap Controls (Zero PTT overhead)
        attachSpringTouch(btnToggleMic) { toggleMic() }
        attachSpringTouch(btnToggleDeafen) { toggleDeafen() }
        attachSpringTouch(btnToggleRoute) { toggleAudioRoute() }
        attachSpringTouch(btnDisconnect) { shutdownService() }

        // 1-Tap Copy Squad Link with tactile morph
        attachSpringTouch(deckBtnCopyInvite) {
            copySquadInviteToClipboard()
        }

        updateDockSideUi()
        windowManager.addView(root, params)
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

        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachEdgeSliderTouch(tab: View, params: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
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
                    downTime = System.currentTimeMillis()
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (abs(dx) > touchSlop || abs(dy) > touchSlop || isDragging) {
                        isDragging = true
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

                    if (isDragging) {
                        // Dragged across screen: Snap smoothly to nearest edge (left or right)
                        val screenMiddle = metrics.widthPixels / 2
                        isDockedOnRight = event.rawX >= screenMiddle
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

        layoutCollapsed.visibility = View.VISIBLE
        layoutCollapsed.alpha = 0f
        layoutCollapsed.translationX = if (isDockedOnRight) handleWidth.toFloat() else -handleWidth.toFloat()
        layoutCollapsed.animate()
            .translationX(0f)
            .alpha(1.0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()

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

        deckBtnCopyInvite.text = "COPIED!"
        deckBtnCopyInvite.setTextColor(COLOR_MINT)
        deckBtnCopyInvite.animate()
            .scaleX(1.05f).scaleY(1.05f)
            .setDuration(100)
            .withEndAction {
                deckBtnCopyInvite.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
            }.start()

        mainHandler.postDelayed({
            deckBtnCopyInvite.text = "COPY LINK"
            deckBtnCopyInvite.setTextColor(COLOR_WHITE)
        }, 1500)

        vibrateClick()
    }

    private fun toggleMic() {
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
            tabBtnRoster.setTextColor(COLOR_ORANGE)
            tabBtnChannels.isSelected = false
            tabBtnChannels.setTextColor(COLOR_MUTED)

            deckTabContentChannels.visibility = View.GONE
            deckTabContentRoster.visibility = View.VISIBLE

            setOverlayFocusable(false)
            rebuildSquadList()
        } else {
            tabBtnChannels.isSelected = true
            tabBtnChannels.setTextColor(COLOR_ORANGE)
            tabBtnRoster.isSelected = false
            tabBtnChannels.setTextColor(COLOR_ORANGE)
            tabBtnRoster.setTextColor(COLOR_MUTED)

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
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (isCurrent) COLOR_MINT else COLOR_WHITE)
                includeFontPadding = false
            }

            val badgeView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = if (isCurrent) "[ACTIVE]" else "[HOP]"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTextColor(if (isCurrent) COLOR_MINT else COLOR_MUTED)
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
        roomEventsJob?.cancel()
        roomEventsJob = null
        roomConnectJob?.cancel()
        roomConnectJob = null
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
        btn.setTextColor(COLOR_MINT)
        btn.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80).withEndAction {
            btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
        }.start()

        mainHandler.postDelayed({
            btn.text = "PING"
            btn.setTextColor(COLOR_AMBER)
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
            isMicEnabled = true
            try {
                audioManager.isMicrophoneMute = false
            } catch (_: Exception) {}
            serviceScope.launch { room?.localParticipant?.setMicrophoneEnabled(true) }
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
        txtStateRoute.setTextColor(if (isSpeakerphone) COLOR_MINT else COLOR_MUTED)
        btnToggleRoute.isActivated = isSpeakerphone

        txtStateRoute.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            txtStateRoute.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()

        playChime(ToneGenerator.TONE_PROP_BEEP, 50)
        vibrateClick()
    }

    private fun updateMicUi() {
        btnToggleMic.isSelected = !isMicEnabled
        btnToggleMic.isActivated = isMicEnabled
        iconStateMic.setImageResource(if (isMicEnabled) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
        txtStateMic.text = if (isMicEnabled) "LIVE" else "MUTED"
        txtStateMic.setTextColor(if (isMicEnabled) COLOR_MINT else COLOR_RED)

        txtStateMic.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            txtStateMic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()

        edgeTabIndicator.isActivated = isMicEnabled
    }

    private fun updateDeafenUi() {
        btnToggleDeafen.isSelected = isDeafened
        iconStateDeafen.setImageResource(if (isDeafened) R.drawable.ic_headset_off else R.drawable.ic_headset)
        txtStateDeafen.text = if (isDeafened) "DEAF" else "NORM"
        txtStateDeafen.setTextColor(if (isDeafened) COLOR_RED else COLOR_MUTED)

        txtStateDeafen.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            txtStateDeafen.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
    }

    private fun rebuildSquadList() {
        val currentRoom = room ?: return
        val remotes = currentRoom.remoteParticipants.values.toList()

        deckRosterContainer.removeAllViews()

        if (remotes.isEmpty()) {
            val emptyTxt = TextView(this).apply {
                text = "NO SQUAD OPERATORS DETECTED"
                setTextColor(COLOR_DARK_ROW)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
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
                setTextColor(COLOR_WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(6)
                }
            }

            val pingBtn = TextView(this).apply {
                text = "PING"
                setTextColor(COLOR_AMBER)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.MONOSPACE
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
                typeface = Typeface.MONOSPACE
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
            textView.setTextColor(COLOR_AMBER)
        } else if (progress == 100) {
            textView.text = "100%"
            textView.setTextColor(COLOR_MINT)
        } else {
            textView.text = "$progress%"
            textView.setTextColor(COLOR_MUTED)
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
        if (!isScreenOn) return

        val now = System.currentTimeMillis()
        speakers.forEach { p ->
            p.identity?.value?.let { speakerLastActiveTime[it] = now }
        }
        if (isMicEnabled && !isDeafened && room?.localParticipant?.isSpeaking == true) {
            speakerLastActiveTime[currentCallsign] = now
        }

        val activeIds = speakerLastActiveTime.filter { (now - it.value) < 400L }.keys.toSet()
        val isAnyoneTalking = activeIds.isNotEmpty()

        // Edge slider tab live speaking state
        layoutCollapsed.isActivated = isAnyoneTalking
        edgeTabIndicator.isActivated = isAnyoneTalking

        // Roster row highlight states (0 dot animators, 0 allocation overhead)
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

        // Schedule hangover check
        hangoverCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        if (isAnyoneTalking) {
            val checkRunnable = Runnable {
                val updatedNow = System.currentTimeMillis()
                val remainingActive = speakerLastActiveTime.filter { (updatedNow - it.value) < 400L }.keys.toSet()
                if (remainingActive.isEmpty()) {
                    highlightActiveSpeakers(emptyList())
                }
            }
            hangoverCheckRunnable = checkRunnable
            mainHandler.postDelayed(checkRunnable, 420L)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                101,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(101, notification)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun dpF(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )

    private fun shutdownService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        setOverlayFocusable(false)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        hangoverCheckRunnable?.let { mainHandler.removeCallbacks(it) }
        hangoverCheckRunnable = null
        roomEventsJob?.cancel()
        roomEventsJob = null
        roomConnectJob?.cancel()
        roomConnectJob = null
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
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
