package com.squad.tacticalvoice

import android.animation.ValueAnimator
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
import android.content.res.ColorStateList
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

        // Preset Accent Colors
        private const val THEME_MINT = 0xFF00F58A.toInt()
        private const val THEME_CYAN = 0xFF00D2FF.toInt()
        private const val THEME_AMBER = 0xFFFF9500.toInt()
        private const val THEME_RED = 0xFFFF2A4B.toInt()
        private const val THEME_VIOLET = 0xFFB366FF.toInt()
        private const val THEME_GHOST = 0xFFE0E0E0.toInt()

        // Standard Tactical Colors
        private const val COLOR_MUTED = 0xFF7C869B.toInt()
        private const val COLOR_WHITE = 0xFFF0F4F8.toInt()
        private const val COLOR_DARK_ROW = 0xFF464E62.toInt()
        private const val COLOR_DARK_BORDER = 0xFF262C3A.toInt()
        private const val COLOR_BG_ACTIVE = 0xFF1F1410.toInt()
        private const val COLOR_BG_INACTIVE = 0xFF181C26.toInt()
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

    // Audio & Routing State
    private var isMicEnabled = true
    private var isDeafened = false
    private var isSpeakerphone = true
    private val participantVolumes = mutableMapOf<String, Int>()

    // Top-Tier Customizations State
    private var currentAccentColor = THEME_MINT
    private var currentChassisOpacity = 100
    private var currentDeckScale = 1.0f
    private var isGhostFadeEnabled = true
    private var handleWidthDp = 18
    private var handleHeightDp = 64
    private var dockingSnapMode = "auto" // "auto", "left", "right"
    private var currentAudioBitrate = 24_000
    private var chimeMode = "beeps" // "beeps", "clicks", "mute"
    private var hapticLevel = 2 // 0: off, 1: subtle, 2: tactile
    private var pingAlertMode = "full" // "full", "flash", "mute"
    private var autoCollapseSeconds = 0 // 0: off, 5, 10
    private var swipeSensitivityDp = 20
    private var isVoiceBlinkEnabled = true
    private var currentDeckTab = 0
    private val allSegmentItemUpdaters = mutableListOf<(Int) -> Unit>()

    // UI References: Edge Slider Handle & Expanded Command Deck
    private lateinit var layoutCollapsed: View
    private lateinit var edgeTabIndicator: View
    private lateinit var layoutExpanded: View
    private lateinit var tabRailColumn: LinearLayout
    private lateinit var deckContentWrapper: LinearLayout
    private var isDockedOnRight = false

    private lateinit var tabBtnRoster: ImageView
    private lateinit var tabBtnChannels: ImageView
    private lateinit var tabBtnSettings: ImageView

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

    private lateinit var deckTabContentRoster: LinearLayout
    private lateinit var deckRosterContainer: LinearLayout
    private lateinit var deckBtnCopyInvite: TextView
    private lateinit var deckPingReadout: TextView

    private lateinit var deckTabContentChannels: LinearLayout
    private lateinit var deckActiveRoomTag: TextView
    private lateinit var inputOverlayRoomCode: EditText
    private lateinit var btnOverlayRandCode: ImageView
    private lateinit var btnOverlayJoinCode: TextView
    private lateinit var deckRecentChannelsContainer: LinearLayout

    private lateinit var deckTabContentSettings: ScrollView

    // Specific Person Mutual Mute Registry
    private val locallyMutedParticipants = mutableSetOf<String>()
    private val remotelyMutedMe = mutableSetOf<String>()

    // Direct OTA In-App Update Engine
    private lateinit var tabBadgeUpdateDot: View
    private lateinit var txtOverlayCurrentVersion: TextView
    private lateinit var txtUpdateStatus: TextView
    private lateinit var btnActionUpdate: TextView
    private var latestUpdateInfo: AppUpdateManager.UpdateInfo? = null
    private var isDownloadingUpdate = false

    private val speakerLastActiveTime = mutableMapOf<String, Long>()
    private var hangoverCheckRunnable: Runnable? = null
    private var ghostFadeRunnable: Runnable? = null
    private var autoCollapseRunnable: Runnable? = null
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
                    resetGhostFadeTimer()
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

        loadCustomizationPreferences()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        startMinimalForegroundNotification()
        spawnTacticalOverlay()
    }

    private fun loadCustomizationPreferences() {
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        currentAccentColor = prefs.getInt("custom_accent", THEME_MINT)
        currentChassisOpacity = prefs.getInt("custom_opacity", 100)
        currentDeckScale = prefs.getFloat("custom_scale", 1.0f)
        isGhostFadeEnabled = prefs.getBoolean("ghost_fade", true)
        handleWidthDp = prefs.getInt("handle_width", 18)
        handleHeightDp = prefs.getInt("handle_height", 64)
        dockingSnapMode = prefs.getString("dock_mode", "auto") ?: "auto"
        currentAudioBitrate = prefs.getInt("audio_bitrate", 24_000)
        chimeMode = prefs.getString("chime_mode", "beeps") ?: "beeps"
        hapticLevel = prefs.getInt("haptic_level", 2)
        pingAlertMode = prefs.getString("ping_alert_mode", "full") ?: "full"
        autoCollapseSeconds = prefs.getInt("auto_collapse_sec", 0)
        swipeSensitivityDp = prefs.getInt("swipe_sensitivity", 20)
        isVoiceBlinkEnabled = prefs.getBoolean("pref_voice_blink", true)

        val savedMutes = prefs.getStringSet("locally_muted_users", null) ?: emptySet()
        locallyMutedParticipants.clear()
        locallyMutedParticipants.addAll(savedMutes)
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
                audioBitrate = currentAudioBitrate,
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
                        deckPingReadout.setTextColor(currentAccentColor)
                        currentRoom.localParticipant.setMicrophoneEnabled(isMicEnabled)
                        rebuildSquadList()
                        broadcastMuteSync()
                    }
                    is RoomEvent.Reconnecting -> {
                        deckPingReadout.text = "RECON"
                        deckPingReadout.setTextColor(THEME_AMBER)
                    }
                    is RoomEvent.Reconnected -> {
                        deckPingReadout.text = "LIVE"
                        deckPingReadout.setTextColor(currentAccentColor)
                        currentRoom.localParticipant.setMicrophoneEnabled(isMicEnabled)
                        broadcastMuteSync()
                    }
                    is RoomEvent.ParticipantConnected -> {
                        val name = event.participant.identity?.value ?: "OPERATOR"
                        Toast.makeText(this@VoiceOverlayService, "[+] $name JOINED SQUAD", Toast.LENGTH_SHORT).show()
                        playChime(ToneGenerator.TONE_PROP_BEEP2, 110)
                        rebuildSquadList()
                        broadcastMuteSync()
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        val name = event.participant.identity?.value ?: "OPERATOR"
                        Toast.makeText(this@VoiceOverlayService, "[-] $name LEFT SQUAD", Toast.LENGTH_SHORT).show()
                        playChime(ToneGenerator.TONE_PROP_PROMPT, 130)
                        speakerLastActiveTime.remove(name)
                        remotelyMutedMe.remove(name)
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
                    deckPingReadout.setTextColor(THEME_RED)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun spawnTacticalOverlay() {
        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
        isDockedOnRight = prefs.getBoolean("docked_on_right", false)
        val metrics = resources.displayMetrics
        val handleW = dp(handleWidthDp)
        val initialX = if (isDockedOnRight) maxOf(0, metrics.widthPixels - handleW) else 0

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
        tabRailColumn = root.findViewById(R.id.tab_rail_column)
        deckContentWrapper = root.findViewById(R.id.deck_content_wrapper)

        tabBtnRoster = root.findViewById(R.id.tab_btn_roster)
        tabBtnChannels = root.findViewById(R.id.tab_btn_channels)
        tabBtnSettings = root.findViewById(R.id.tab_btn_settings)

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

        deckTabContentRoster = root.findViewById(R.id.deck_tab_content_roster)
        deckRosterContainer = root.findViewById(R.id.deck_roster_container)
        deckBtnCopyInvite = root.findViewById(R.id.deck_btn_copy_invite)
        deckPingReadout = root.findViewById(R.id.deck_ping_readout)

        deckTabContentChannels = root.findViewById(R.id.deck_tab_content_channels)
        deckActiveRoomTag = root.findViewById(R.id.deck_active_room_tag)
        inputOverlayRoomCode = root.findViewById(R.id.input_overlay_room_code)
        btnOverlayRandCode = root.findViewById(R.id.btn_overlay_rand_code)
        btnOverlayJoinCode = root.findViewById(R.id.btn_overlay_join_code)
        deckRecentChannelsContainer = root.findViewById(R.id.deck_recent_channels_container)

        deckTabContentSettings = root.findViewById(R.id.deck_tab_content_settings)

        tabBadgeUpdateDot = root.findViewById(R.id.tab_badge_update_dot)
        txtOverlayCurrentVersion = root.findViewById(R.id.txt_overlay_current_version)
        txtUpdateStatus = root.findViewById(R.id.txt_update_status)
        btnActionUpdate = root.findViewById(R.id.btn_action_update)

        // 1. Edge Slider Handle Drag & Slide
        attachEdgeSliderTouch(layoutCollapsed, params)

        // 2. Drag on expanded deck header bar & Swipe anywhere on panel to close
        attachDeckHeaderTouch(deckHeaderDragZone, params)
        attachDeckHeaderTouch(deckDragHandle, params)
        attachDeckHeaderTouch(deckChannelTitle, params)
        attachPanelSwipeToClose(layoutExpanded, params)
        attachPanelSwipeToClose(deckTabContentRoster, params)
        attachPanelSwipeToClose(deckTabContentChannels, params)
        attachPanelSwipeToClose(deckTabContentSettings, params)

        attachSpringTouch(deckBtnCollapse) { collapseDeckAnimated(root, params) }

        // Vertical 3-Tab Column Navigation (Icon-Only)
        attachSpringTouch(tabBtnRoster) { selectDeckTab(0) }
        attachSpringTouch(tabBtnChannels) { selectDeckTab(1) }
        attachSpringTouch(tabBtnSettings) { selectDeckTab(2) }

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
                selectDeckTab(0)
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
                    selectDeckTab(0)
                }
                true
            } else false
        }

        // 3. Tactile 1-Tap Controls
        attachSpringTouch(btnToggleMic) { toggleMic() }
        attachSpringTouch(btnToggleDeafen) { toggleDeafen() }
        attachSpringTouch(btnToggleRoute) { toggleAudioRoute() }
        attachSpringTouch(btnDisconnect) { shutdownService() }

        // 1-Tap Copy Squad Link
        attachSpringTouch(deckBtnCopyInvite) {
            copySquadInviteToClipboard()
        }

        setupSettingsControls(root)

        updateDockSideUi()
        applyAccentColor(currentAccentColor)
        applyChassisOpacity(currentChassisOpacity)
        applyDeckScale(currentDeckScale)
        applyHandleDimensions()

        windowManager.addView(root, params)
        updateMicUi()
        updateDeafenUi()
        resetGhostFadeTimer()
    }

    private fun setupSettingsControls(root: View) {
        // Feature 1: Accent Themes
        val swatches = listOf(
            Pair(R.id.btn_theme_mint, THEME_MINT),
            Pair(R.id.btn_theme_cyan, THEME_CYAN),
            Pair(R.id.btn_theme_amber, THEME_AMBER),
            Pair(R.id.btn_theme_red, THEME_RED),
            Pair(R.id.btn_theme_violet, THEME_VIOLET),
            Pair(R.id.btn_theme_ghost, THEME_GHOST)
        )

        val customColorPreview = root.findViewById<View>(R.id.custom_color_preview)
        val editCustomHex = root.findViewById<EditText>(R.id.edit_custom_hex)
        val btnApplyHex = root.findViewById<TextView>(R.id.btn_apply_hex)
        val seekbarCustomHue = root.findViewById<SeekBar>(R.id.seekbar_custom_hue)

        swatches.forEach { (viewId, color) ->
            root.findViewById<View>(viewId)?.let { v ->
                attachSpringTouch(v) {
                    currentAccentColor = color
                    getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                        .edit().putInt("custom_accent", color).apply()

                    val hsv = FloatArray(3)
                    Color.colorToHSV(color, hsv)
                    seekbarCustomHue?.progress = hsv[0].toInt().coerceIn(0, 360)
                    customColorPreview?.background = createSwatchDrawable(color)
                    editCustomHex?.setText(String.format("#%06X", (0xFFFFFF and color)))

                    applyAccentColor(color)
                    vibrateClick()
                }
            }
        }

        // Feature 1: Custom Accent Spectrum & Hex Input
        val rainbowColors = intArrayOf(
            0xFFFF0000.toInt(),
            0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(),
            0xFF0000FF.toInt(),
            0xFFFF00FF.toInt(),
            0xFFFF0000.toInt()
        )
        val rainbowTrack = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, rainbowColors).apply {
            cornerRadius = dp(2).toFloat()
        }
        seekbarCustomHue?.progressDrawable = rainbowTrack

        val initialHsv = FloatArray(3)
        Color.colorToHSV(currentAccentColor, initialHsv)
        seekbarCustomHue?.progress = initialHsv[0].toInt().coerceIn(0, 360)
        customColorPreview?.background = createSwatchDrawable(currentAccentColor)
        editCustomHex?.setText(String.format("#%06X", (0xFFFFFF and currentAccentColor)))

        seekbarCustomHue?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val color = Color.HSVToColor(floatArrayOf(progress.toFloat(), 0.90f, 1.0f))
                    currentAccentColor = color
                    customColorPreview?.background = createSwatchDrawable(color)
                    editCustomHex?.setText(String.format("#%06X", (0xFFFFFF and color)))
                    applyAccentColor(color)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("custom_accent", currentAccentColor).apply()
                vibrateClick()
            }
        })

        editCustomHex?.setOnFocusChangeListener { _, hasFocus ->
            setOverlayFocusable(hasFocus)
        }

        editCustomHex?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyCustomHexFromInput(editCustomHex, customColorPreview, seekbarCustomHue)
                true
            } else false
        }

        btnApplyHex?.let { btn ->
            attachSpringTouch(btn) {
                applyCustomHexFromInput(editCustomHex, customColorPreview, seekbarCustomHue)
            }
        }

        // Feature 1: Opacity Slider
        val txtOpacity = root.findViewById<TextView>(R.id.txt_opacity_label)
        val sbOpacity = root.findViewById<SeekBar>(R.id.seekbar_chassis_opacity)
        sbOpacity?.progress = currentChassisOpacity
        txtOpacity?.text = "CHASSIS OPACITY: $currentChassisOpacity%"
        sbOpacity?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(35, 100)
                currentChassisOpacity = clamped
                txtOpacity?.text = "CHASSIS OPACITY: $clamped%"
                applyChassisOpacity(clamped)
                if (fromUser) {
                    getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                        .edit().putInt("custom_opacity", clamped).apply()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { vibrateClick() }
        })

        // Feature 1: Deck Scale
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_scale_compact), 0.85f, currentDeckScale == 0.85f),
                Triple(root.findViewById(R.id.btn_scale_normal), 1.0f, currentDeckScale == 1.0f),
                Triple(root.findViewById(R.id.btn_scale_expanded), 1.15f, currentDeckScale == 1.15f)
            )
        ) { scale ->
            currentDeckScale = scale
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putFloat("custom_scale", scale).apply()
            applyDeckScale(scale)
        }

        // Feature 2: Ghost Fade
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_fade_on), true, isGhostFadeEnabled),
                Triple(root.findViewById(R.id.btn_fade_off), false, !isGhostFadeEnabled)
            )
        ) { enabled ->
            isGhostFadeEnabled = enabled
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("ghost_fade", enabled).apply()
            resetGhostFadeTimer()
        }

        // Feature 2: Voice Speaking Blink
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_voice_blink_on), true, isVoiceBlinkEnabled),
                Triple(root.findViewById(R.id.btn_voice_blink_off), false, !isVoiceBlinkEnabled)
            )
        ) { enabled ->
            isVoiceBlinkEnabled = enabled
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("pref_voice_blink", enabled).apply()
            if (!enabled) {
                applyHandleSpeakingUi(false)
            }
        }

        // Feature 2: Handle Width
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_handle_slim), 14, handleWidthDp == 14),
                Triple(root.findViewById(R.id.btn_handle_std), 18, handleWidthDp == 18),
                Triple(root.findViewById(R.id.btn_handle_wide), 24, handleWidthDp == 24)
            )
        ) { w ->
            handleWidthDp = w
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putInt("handle_width", w).apply()
            applyHandleDimensions()
        }

        // Feature 2: Handle Height
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_height_short), 48, handleHeightDp == 48),
                Triple(root.findViewById(R.id.btn_height_std), 64, handleHeightDp == 64),
                Triple(root.findViewById(R.id.btn_height_tall), 88, handleHeightDp == 88)
            )
        ) { h ->
            handleHeightDp = h
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putInt("handle_height", h).apply()
            applyHandleDimensions()
        }

        // Feature 2: Docking Mode
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_dock_auto), "auto", dockingSnapMode == "auto"),
                Triple(root.findViewById(R.id.btn_dock_left), "left", dockingSnapMode == "left"),
                Triple(root.findViewById(R.id.btn_dock_right), "right", dockingSnapMode == "right")
            )
        ) { mode ->
            dockingSnapMode = mode
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putString("dock_mode", mode).apply()
            if (mode == "left") {
                isDockedOnRight = false
                updateDockSideUi()
            } else if (mode == "right") {
                isDockedOnRight = true
                updateDockSideUi()
            }
        }

        // Feature 3: Audio Bitrate
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_bitrate_16k), 16_000, currentAudioBitrate == 16_000),
                Triple(root.findViewById(R.id.btn_bitrate_24k), 24_000, currentAudioBitrate == 24_000),
                Triple(root.findViewById(R.id.btn_bitrate_32k), 32_000, currentAudioBitrate == 32_000)
            )
        ) { bitrate ->
            currentAudioBitrate = bitrate
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putInt("audio_bitrate", bitrate).apply()
            deckBitrateBadge.text = "VOX // ${bitrate / 1000}K"
        }

        // Feature 3: Chimes Mode
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_chime_beeps), "beeps", chimeMode == "beeps"),
                Triple(root.findViewById(R.id.btn_chime_clicks), "clicks", chimeMode == "clicks"),
                Triple(root.findViewById(R.id.btn_chime_mute), "mute", chimeMode == "mute")
            )
        ) { mode ->
            chimeMode = mode
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putString("chime_mode", mode).apply()
        }

        // Feature 4: Haptic Strength
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_haptic_off), 0, hapticLevel == 0),
                Triple(root.findViewById(R.id.btn_haptic_subtle), 1, hapticLevel == 1),
                Triple(root.findViewById(R.id.btn_haptic_tactile), 2, hapticLevel == 2)
            )
        ) { level ->
            hapticLevel = level
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putInt("haptic_level", level).apply()
        }

        // Feature 4: Ping Alerts
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_ping_full), "full", pingAlertMode == "full"),
                Triple(root.findViewById(R.id.btn_ping_flash), "flash", pingAlertMode == "flash"),
                Triple(root.findViewById(R.id.btn_ping_mute), "mute", pingAlertMode == "mute")
            )
        ) { mode ->
            pingAlertMode = mode
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putString("ping_alert_mode", mode).apply()
        }

        // Feature 5: Auto-Collapse
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_collapse_off), 0, autoCollapseSeconds == 0),
                Triple(root.findViewById(R.id.btn_collapse_5s), 5, autoCollapseSeconds == 5),
                Triple(root.findViewById(R.id.btn_collapse_10s), 10, autoCollapseSeconds == 10)
            )
        ) { sec ->
            autoCollapseSeconds = sec
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putInt("auto_collapse_sec", sec).apply()
        }

        // Feature 5: Swipe Sensitivity
        setupSegmentGroup(
            listOf(
                Triple(root.findViewById(R.id.btn_swipe_fast), 20, swipeSensitivityDp == 20),
                Triple(root.findViewById(R.id.btn_swipe_deep), 40, swipeSensitivityDp == 40)
            )
        ) { dist ->
            swipeSensitivityDp = dist
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putInt("swipe_sensitivity", dist).apply()
        }

        // Pillar 6: Direct In-App OTA Update Engine
        txtOverlayCurrentVersion.text = "v${BuildConfig.VERSION_NAME}"
        txtUpdateStatus.text = "STATUS: READY"
        btnActionUpdate.text = "CHECK FOR UPDATES"
        btnActionUpdate.setTextColor(currentAccentColor)
        btnActionUpdate.background = createSegmentChipDrawable(false, currentAccentColor)

        attachSpringTouch(btnActionUpdate) {
            vibrateClick()
            if (isDownloadingUpdate) return@attachSpringTouch
            val info = latestUpdateInfo
            if (info != null) {
                startOtaDownloadAndInstall(info)
            } else {
                checkOtaUpdates(silent = false)
            }
        }

        // Silent background check on boot
        checkOtaUpdates(silent = true)
    }

    private fun checkOtaUpdates(silent: Boolean) {
        if (!silent) {
            txtUpdateStatus.text = "CONNECTING TO GITHUB OTA..."
            btnActionUpdate.text = "CHECKING..."
        }
        serviceScope.launch(Dispatchers.Main) {
            when (val result = AppUpdateManager.checkForUpdate(BuildConfig.VERSION_NAME)) {
                is AppUpdateManager.UpdateResult.Available -> {
                    latestUpdateInfo = result.updateInfo
                    txtUpdateStatus.text = "UPDATE AVAILABLE: v${result.updateInfo.versionName}"
                    txtUpdateStatus.setTextColor(COLOR_WHITE)
                    btnActionUpdate.text = "DOWNLOAD & INSTALL"
                    btnActionUpdate.setTextColor(COLOR_WHITE)
                    btnActionUpdate.background = createSegmentChipDrawable(true, THEME_MINT)
                    tabBadgeUpdateDot.visibility = View.VISIBLE
                    if (!silent) {
                        playChime(ToneGenerator.TONE_PROP_PROMPT, 80)
                        vibrateAlert()
                        Toast.makeText(this@VoiceOverlayService, "[UPDATE] VOX v${result.updateInfo.versionName} AVAILABLE!", Toast.LENGTH_SHORT).show()
                    }
                }
                is AppUpdateManager.UpdateResult.UpToDate -> {
                    latestUpdateInfo = null
                    txtUpdateStatus.text = "VOX IS UP TO DATE (v${BuildConfig.VERSION_NAME})"
                    txtUpdateStatus.setTextColor(COLOR_MUTED)
                    btnActionUpdate.text = "CHECK FOR UPDATES"
                    btnActionUpdate.setTextColor(currentAccentColor)
                    btnActionUpdate.background = createSegmentChipDrawable(false, currentAccentColor)
                    tabBadgeUpdateDot.visibility = View.GONE
                    if (!silent) {
                        playChime(ToneGenerator.TONE_PROP_ACK, 40)
                        Toast.makeText(this@VoiceOverlayService, "VOX is up to date!", Toast.LENGTH_SHORT).show()
                    }
                }
                is AppUpdateManager.UpdateResult.Error -> {
                    if (!silent) {
                        txtUpdateStatus.text = "OTA: ${result.message.take(24)}"
                        txtUpdateStatus.setTextColor(THEME_RED)
                        btnActionUpdate.text = "RETRY CHECK"
                        Toast.makeText(this@VoiceOverlayService, "Update check: ${result.message}", Toast.LENGTH_SHORT).show()
                    } else {
                        txtUpdateStatus.text = "VOX v${BuildConfig.VERSION_NAME} READY"
                    }
                }
            }
        }
    }

    private fun startOtaDownloadAndInstall(info: AppUpdateManager.UpdateInfo) {
        if (isDownloadingUpdate) return
        isDownloadingUpdate = true
        txtUpdateStatus.text = "DOWNLOADING OTA ARCHIVE..."
        txtUpdateStatus.setTextColor(THEME_AMBER)
        btnActionUpdate.text = "STARTING..."

        serviceScope.launch(Dispatchers.Main) {
            try {
                val apkFile = AppUpdateManager.downloadApk(this@VoiceOverlayService, info.apkDownloadUrl) { pct, _, _ ->
                    if (pct >= 0) {
                        btnActionUpdate.text = "DOWNLOADING: $pct%"
                    } else {
                        btnActionUpdate.text = "DOWNLOADING..."
                    }
                }
                isDownloadingUpdate = false
                txtUpdateStatus.text = "DOWNLOAD COMPLETE. READY TO FLASH."
                txtUpdateStatus.setTextColor(THEME_MINT)
                btnActionUpdate.text = "LAUNCH INSTALLER"
                btnActionUpdate.background = createSegmentChipDrawable(true, THEME_MINT)
                AppUpdateManager.installApk(this@VoiceOverlayService, apkFile)
            } catch (e: Exception) {
                isDownloadingUpdate = false
                txtUpdateStatus.text = "DOWNLOAD FAILED"
                txtUpdateStatus.setTextColor(THEME_RED)
                btnActionUpdate.text = "RETRY DOWNLOAD"
                Toast.makeText(this@VoiceOverlayService, "OTA download error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyCustomHexFromInput(
        editCustomHex: EditText?,
        customColorPreview: View?,
        seekbarCustomHue: SeekBar?
    ) {
        val raw = editCustomHex?.text?.toString()?.trim() ?: return
        try {
            val hex = if (raw.startsWith("#")) raw else "#$raw"
            val parsedColor = Color.parseColor(hex)
            currentAccentColor = parsedColor
            getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                .edit().putInt("custom_accent", parsedColor).apply()

            val hsv = FloatArray(3)
            Color.colorToHSV(parsedColor, hsv)
            seekbarCustomHue?.progress = hsv[0].toInt().coerceIn(0, 360)
            customColorPreview?.background = createSwatchDrawable(parsedColor)
            applyAccentColor(parsedColor)
            vibrateClick()
            setOverlayFocusable(false)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(editCustomHex.windowToken, 0)
        } catch (_: Exception) {
            Toast.makeText(this, "INVALID HEX (USE #RRGGBB)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun <T> setupSegmentGroup(
        items: List<Triple<TextView?, T, Boolean>>,
        onSelect: (T) -> Unit
    ) {
        var currentSelectedValue: T? = items.firstOrNull { it.third }?.second

        val updater: (Int) -> Unit = { accent ->
            items.forEach { (view, value, _) ->
                if (view != null) {
                    val isSelected = (value == currentSelectedValue)
                    view.isSelected = isSelected
                    view.setTextColor(if (isSelected) accent else COLOR_MUTED)
                    view.background = createSegmentChipDrawable(isSelected, accent)
                }
            }
        }
        allSegmentItemUpdaters.add(updater)

        items.forEach { (view, value, initialSelected) ->
            if (view == null) return@forEach
            view.isSelected = initialSelected
            view.setTextColor(if (initialSelected) currentAccentColor else COLOR_MUTED)
            view.background = createSegmentChipDrawable(initialSelected, currentAccentColor)

            attachSpringTouch(view) {
                currentSelectedValue = value
                updater(currentAccentColor)
                vibrateClick()
                onSelect(value)
            }
        }
    }

    private fun createSwatchDrawable(color: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3).toFloat()
            setColor(color)
            setStroke(dp(1), 0xFFFFFFFF.toInt())
        }
    }

    private fun createSegmentChipDrawable(isSelected: Boolean, accentColor: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            if (isSelected) {
                setColor(0xFF141923.toInt())
                setStroke(dp(1), accentColor)
            } else {
                setColor(0xFF181C26.toInt())
                setStroke(dp(1), COLOR_DARK_BORDER)
            }
        }
    }

    private fun createTabButtonDrawable(isSelected: Boolean, accentColor: Int): Drawable {
        return if (isSelected) {
            val base = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0xFF1A202C.toInt())
                setStroke(dp(1), accentColor)
            }
            val line = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor)
            }
            val ld = LayerDrawable(arrayOf(base, line))
            ld.setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            ld.setLayerHeight(1, dp(2))
            ld
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0xFF12151C.toInt())
                setStroke(dp(1), COLOR_DARK_BORDER)
            }
        }
    }

    private fun createChassisDrawable(isRight: Boolean, accentColor: Int): Drawable {
        val outerRadii = if (isRight) {
            floatArrayOf(dp(8).toFloat(), dp(8).toFloat(), 0f, 0f, 0f, 0f, dp(8).toFloat(), dp(8).toFloat())
        } else {
            floatArrayOf(0f, 0f, dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), 0f, 0f)
        }

        val outerBorder = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = outerRadii
            setColor(0xFF090A0D.toInt())
            setStroke(dp(1), accentColor)
        }

        val accentLip = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(1).toFloat()
            setColor(accentColor)
        }

        val ld = LayerDrawable(arrayOf(outerBorder, accentLip))
        val lipGravity = if (isRight) (Gravity.LEFT or Gravity.CENTER_VERTICAL) else (Gravity.RIGHT or Gravity.CENTER_VERTICAL)
        ld.setLayerGravity(1, lipGravity)
        ld.setLayerSize(1, dp(2), dp(40))
        if (isRight) {
            ld.setLayerInsetLeft(1, dp(1))
        } else {
            ld.setLayerInsetRight(1, dp(1))
        }
        return ld
    }

    private fun createCollapsedHandleDrawable(isRight: Boolean, accentColor: Int, isSpeaking: Boolean): Drawable {
        val radii = if (isRight) {
            floatArrayOf(dp(10).toFloat(), dp(10).toFloat(), 0f, 0f, 0f, 0f, dp(10).toFloat(), dp(10).toFloat())
        } else {
            floatArrayOf(0f, 0f, dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), 0f, 0f)
        }

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
            setColor(0xFF090A0D.toInt())
            if (isSpeaking && isVoiceBlinkEnabled) {
                setStroke(dp(2), accentColor)
            } else {
                setStroke(dp(1), 0xFF3A4358.toInt())
            }
        }
    }

    private fun createLedDrawable(color: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(1).toFloat()
            setColor(color)
        }
    }

    private fun createHudPillDrawable(accentColor: Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(0xFF090A0D.toInt())
            setStroke(dp(1), accentColor)
        }
    }

    private fun createRosterRowDrawable(isSpeaker: Boolean, accentColor: Int): Drawable {
        return if (isSpeaker) {
            val base = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0xFF11141B.toInt())
                setStroke(dp(1), accentColor)
            }
            val rail = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(accentColor)
                cornerRadii = floatArrayOf(dp(4).toFloat(), dp(4).toFloat(), 0f, 0f, 0f, 0f, dp(4).toFloat(), dp(4).toFloat())
            }
            val ld = LayerDrawable(arrayOf(base, rail))
            ld.setLayerGravity(1, Gravity.LEFT or Gravity.FILL_VERTICAL)
            ld.setLayerWidth(1, dp(3))
            ld
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0xFF11141B.toInt())
                setStroke(dp(1), COLOR_DARK_BORDER)
            }
        }
    }

    private fun createKeycapDrawable(active: Boolean, accent: Int): Drawable {
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(0xFF181C26.toInt())
            setStroke(dp(1), if (active) accent else COLOR_DARK_BORDER)
        }
        val topStrip = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(1).toFloat()
            setColor(accent)
        }
        val ld = LayerDrawable(arrayOf(base, topStrip))
        ld.setLayerGravity(1, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        ld.setLayerSize(1, dp(20), dp(2))
        ld.setLayerInsetTop(1, dp(1))
        return ld
    }

    private fun updateTabButtonsUi(activeTabIndex: Int, accentColor: Int) {
        val tabs = listOf(tabBtnRoster, tabBtnChannels, tabBtnSettings)
        tabs.forEachIndexed { index, imgView ->
            val isSelected = (index == activeTabIndex)
            imgView.isSelected = isSelected
            imgView.background = createTabButtonDrawable(isSelected, accentColor)
            imgView.setColorFilter(if (isSelected) accentColor else COLOR_MUTED, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun updateActionButtonsTheme(accentColor: Int) {
        if (isMicEnabled) {
            btnToggleMic.background = createKeycapDrawable(active = true, accent = accentColor)
            iconStateMic.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
            txtStateMic.setTextColor(accentColor)
        } else {
            btnToggleMic.background = createKeycapDrawable(active = false, accent = THEME_RED)
            iconStateMic.setColorFilter(THEME_RED, PorterDuff.Mode.SRC_IN)
            txtStateMic.setTextColor(THEME_RED)
        }

        if (isDeafened) {
            btnToggleDeafen.background = createKeycapDrawable(active = false, accent = THEME_RED)
            iconStateDeafen.setColorFilter(THEME_RED, PorterDuff.Mode.SRC_IN)
            txtStateDeafen.setTextColor(THEME_RED)
        } else {
            btnToggleDeafen.background = createKeycapDrawable(active = false, accent = COLOR_MUTED)
            iconStateDeafen.setColorFilter(COLOR_MUTED, PorterDuff.Mode.SRC_IN)
            txtStateDeafen.setTextColor(COLOR_MUTED)
        }

        btnToggleRoute.background = createKeycapDrawable(active = isSpeakerphone, accent = if (isSpeakerphone) accentColor else COLOR_MUTED)
        iconStateRoute.setColorFilter(if (isSpeakerphone) accentColor else COLOR_MUTED, PorterDuff.Mode.SRC_IN)
        txtStateRoute.setTextColor(if (isSpeakerphone) accentColor else COLOR_MUTED)
    }

    private fun applyHandleSpeakingUi(speaking: Boolean) {
        val shouldBlink = speaking && isVoiceBlinkEnabled
        layoutCollapsed.isActivated = shouldBlink
        edgeTabIndicator.isActivated = shouldBlink
        layoutCollapsed.background = createCollapsedHandleDrawable(isDockedOnRight, currentAccentColor, shouldBlink)
        edgeTabIndicator.background = createLedDrawable(if (shouldBlink) currentAccentColor else COLOR_DARK_ROW)
    }

    private fun applyAccentColor(accentColor: Int) {
        currentAccentColor = accentColor

        // 1. Chassis & Edge Pill
        layoutExpanded.background = createChassisDrawable(isDockedOnRight, accentColor)
        layoutCollapsed.background = createCollapsedHandleDrawable(isDockedOnRight, accentColor, isSpeaking = false)
        edgeTabIndicator.background = createLedDrawable(COLOR_DARK_ROW)

        // 2. Navigation Tab Rail
        updateTabButtonsUi(currentDeckTab, accentColor)

        // 3. Header Telemetry & Status
        deckBitrateBadge.setTextColor(accentColor)
        deckActiveRoomTag.setTextColor(accentColor)
        if (deckPingReadout.text == "LIVE") {
            deckPingReadout.setTextColor(accentColor)
        }
        deckPingReadout.background = createHudPillDrawable(accentColor)

        // 4. Quick Actions
        updateActionButtonsTheme(accentColor)

        // 5. Settings Tab Elements
        allSegmentItemUpdaters.forEach { it(accentColor) }

        val sbOpacity = layoutExpanded.findViewById<SeekBar>(R.id.seekbar_chassis_opacity)
        sbOpacity?.progressTintList = ColorStateList.valueOf(accentColor)
        sbOpacity?.thumbTintList = ColorStateList.valueOf(accentColor)

        val sbHue = layoutExpanded.findViewById<SeekBar>(R.id.seekbar_custom_hue)
        sbHue?.thumbTintList = ColorStateList.valueOf(accentColor)

        layoutExpanded.findViewById<View>(R.id.custom_color_preview)?.background = createSwatchDrawable(accentColor)
        layoutExpanded.findViewById<EditText>(R.id.edit_custom_hex)?.setText(String.format("#%06X", (0xFFFFFF and accentColor)))

        if (latestUpdateInfo == null && !isDownloadingUpdate) {
            btnActionUpdate.setTextColor(accentColor)
            btnActionUpdate.background = createSegmentChipDrawable(false, accentColor)
        }

        // 6. Roster Tab Elements
        for (i in 0 until deckRosterContainer.childCount) {
            val child = deckRosterContainer.getChildAt(i)
            val rowTag = child.tag as? String ?: continue
            if (rowTag.startsWith("ROW_")) {
                val id = rowTag.removePrefix("ROW_")
                val isSpeaker = child.isActivated
                child.background = createRosterRowDrawable(isSpeaker, accentColor)
                child.findViewWithTag<View>("LED_$id")?.background = createLedDrawable(if (isSpeaker) accentColor else COLOR_MUTED)

                val isMuted = locallyMutedParticipants.contains(id) || remotelyMutedMe.contains(id)
                val sb = child.findViewWithTag<SeekBar>("SEEKBAR_$id")
                val tint = if (isMuted) THEME_RED else accentColor
                sb?.progressTintList = ColorStateList.valueOf(tint)
                sb?.thumbTintList = ColorStateList.valueOf(tint)

                val pingBtn = child.findViewWithTag<TextView>("PING_$id")
                pingBtn?.setTextColor(accentColor)

                val muteBtn = child.findViewWithTag<TextView>("MUTE_BTN_$id")
                if (!isMuted) {
                    muteBtn?.setTextColor(COLOR_MUTED)
                    muteBtn?.background = createSegmentChipDrawable(false, accentColor)
                }

                val gainReadout = child.findViewWithTag<TextView>("GAIN_$id")
                val vol = participantVolumes[id] ?: 100
                if (!isMuted && vol == 100 && gainReadout != null) {
                    gainReadout.setTextColor(accentColor)
                }
            }
        }

        // 7. Channels Tab Elements
        rebuildRecentChannelsList()
    }

    private fun applyChassisOpacity(opacity: Int) {
        layoutExpanded.alpha = (opacity / 100f).coerceIn(0.35f, 1.0f)
    }

    private fun applyDeckScale(scale: Float) {
        val root = overlayView ?: return
        val pivotX = if (isDockedOnRight) dp(286).toFloat() else 0f
        layoutExpanded.pivotX = pivotX
        layoutExpanded.pivotY = dp(40).toFloat()
        layoutExpanded.scaleX = scale
        layoutExpanded.scaleY = scale
    }

    private fun applyHandleDimensions() {
        val params = overlayParams ?: return
        val root = overlayView ?: return
        val lp = layoutCollapsed.layoutParams
        lp.width = dp(handleWidthDp)
        lp.height = dp(handleHeightDp)
        layoutCollapsed.layoutParams = lp

        val metrics = resources.displayMetrics
        if (isDockedOnRight) {
            params.x = maxOf(0, metrics.widthPixels - dp(handleWidthDp))
        } else {
            params.x = 0
        }
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {}
    }

    private fun resetGhostFadeTimer() {
        ghostFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        layoutCollapsed.alpha = 1.0f
        if (isGhostFadeEnabled) {
            val r = Runnable {
                if (layoutCollapsed.visibility == View.VISIBLE) {
                    layoutCollapsed.animate().alpha(0.25f).setDuration(350).start()
                }
            }
            ghostFadeRunnable = r
            mainHandler.postDelayed(r, 3000L)
        }
    }

    private fun resetAutoCollapseTimer() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        if (autoCollapseSeconds > 0 && layoutExpanded.visibility == View.VISIBLE) {
            val r = Runnable {
                val root = overlayView ?: return@Runnable
                val params = overlayParams ?: return@Runnable
                collapseDeckAnimated(root, params)
            }
            autoCollapseRunnable = r
            mainHandler.postDelayed(r, autoCollapseSeconds * 1000L)
        }
    }

    private fun updateDockSideUi() {
        val metrics = resources.displayMetrics
        val handleWidth = dp(handleWidthDp)
        val deckWidth = dp(286)
        val params = overlayParams ?: return
        val root = overlayView ?: return

        val rail = tabRailColumn
        val contentWrapper = deckContentWrapper
        val expandedLayout = layoutExpanded as LinearLayout

        expandedLayout.removeView(rail)
        expandedLayout.removeView(contentWrapper)

        val contentLp = contentWrapper.layoutParams as LinearLayout.LayoutParams

        if (isDockedOnRight) {
            // Docked on right edge of screen: Content on left, Tab Rail on right!
            contentLp.leftMargin = 0
            contentLp.rightMargin = dp(6)
            expandedLayout.addView(contentWrapper)
            expandedLayout.addView(rail)

            layoutCollapsed.background = createCollapsedHandleDrawable(isRight = true, accentColor = currentAccentColor, isSpeaking = false)
            layoutExpanded.background = createChassisDrawable(isRight = true, accentColor = currentAccentColor)
            if (layoutCollapsed.visibility == View.VISIBLE) {
                params.x = maxOf(0, metrics.widthPixels - handleWidth)
            } else {
                params.x = maxOf(0, metrics.widthPixels - deckWidth)
            }
            deckBtnCollapse.rotation = 180f
        } else {
            // Docked on left edge of screen: Tab Rail on left, Content on right!
            contentLp.leftMargin = dp(6)
            contentLp.rightMargin = 0
            expandedLayout.addView(rail)
            expandedLayout.addView(contentWrapper)

            layoutCollapsed.background = createCollapsedHandleDrawable(isRight = false, accentColor = currentAccentColor, isSpeaking = false)
            layoutExpanded.background = createChassisDrawable(isRight = false, accentColor = currentAccentColor)
            params.x = 0
            deckBtnCollapse.rotation = 0f
        }

        applyDeckScale(currentDeckScale)

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
            val handleWidth = dp(handleWidthDp)
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
                    resetGhostFadeTimer()
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
                        val shouldDockRight = when (dockingSnapMode) {
                            "left" -> false
                            "right" -> true
                            else -> event.rawX >= (metrics.widthPixels / 2)
                        }
                        isDockedOnRight = shouldDockRight
                        val prefs = getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putBoolean("docked_on_right", isDockedOnRight).apply()
                        vibrateClick()

                        params.x = if (isDockedOnRight) maxX else 0
                        updateDockSideUi()
                        resetGhostFadeTimer()
                    } else {
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
                    resetGhostFadeTimer()
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
            resetAutoCollapseTimer()
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialX
                    val dy = event.rawY - initialY
                    val closeThreshold = dp(swipeSensitivityDp)
                    val isCloseSwipe = if (isDockedOnRight) (dx > closeThreshold) else (dx < -closeThreshold)
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
            resetAutoCollapseTimer()
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

                    val closeThreshold = dp(swipeSensitivityDp + 10)
                    val isCloseSwipe = if (isDockedOnRight) (dx > closeThreshold) else (dx < -closeThreshold)
                    if (isCloseSwipe && abs(dx) > abs(dy) * 1.2f) {
                        collapseDeckAnimated(overlayView ?: v, params)
                        return@setOnTouchListener true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - initialTouchX
                    val closeThreshold = dp(swipeSensitivityDp)
                    val isCloseSwipe = if (isDockedOnRight) (dx > closeThreshold) else (dx < -closeThreshold)
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
        ghostFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        val metrics = resources.displayMetrics
        val deckWidth = dp(286)
        params.x = if (isDockedOnRight) maxOf(0, metrics.widthPixels - deckWidth) else 0

        layoutCollapsed.visibility = View.GONE
        layoutExpanded.visibility = View.VISIBLE
        selectDeckTab(0)
        updateDockSideUi()
        windowManager.updateViewLayout(root, params)

        val slideDistance = if (isDockedOnRight) deckWidth.toFloat() else -deckWidth.toFloat()
        layoutExpanded.translationX = slideDistance
        layoutExpanded.alpha = 0f
        layoutExpanded.animate()
            .translationX(0f)
            .alpha(currentChassisOpacity / 100f)
            .setDuration(190)
            .setInterpolator(DecelerateInterpolator())
            .start()

        vibrateClick()
        resetAutoCollapseTimer()
    }

    private fun collapseDeckAnimated(root: View, params: WindowManager.LayoutParams) {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        setOverlayFocusable(false)
        val metrics = resources.displayMetrics
        val handleWidth = dp(handleWidthDp)
        val deckWidth = dp(286)
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
                resetGhostFadeTimer()
            }.start()

        vibrateClick()
    }

    private fun attachSpringTouch(view: View, onClick: () -> Unit) {
        view.setOnTouchListener { v, event ->
            resetAutoCollapseTimer()
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
        deckBtnCopyInvite.setTextColor(currentAccentColor)
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

    private fun selectDeckTab(tabIndex: Int) {
        vibrateClick()
        resetAutoCollapseTimer()
        currentDeckTab = tabIndex

        updateTabButtonsUi(tabIndex, currentAccentColor)

        deckTabContentRoster.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        deckTabContentChannels.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        deckTabContentSettings.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE

        setOverlayFocusable(tabIndex == 1)

        when (tabIndex) {
            0 -> rebuildSquadList()
            1 -> {
                deckActiveRoomTag.text = "#$activeRoomCode"
                rebuildRecentChannelsList()
            }
            2 -> {
                // Focus on settings controls
            }
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
                background = if (isCurrent) createRosterRowDrawable(true, currentAccentColor) else createSegmentChipDrawable(false, currentAccentColor)
                setPadding(dp(10), dp(4), dp(8), dp(4))
                isClickable = true
                isFocusable = false
            }

            val titleView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "#$roomCode"
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(if (isCurrent) currentAccentColor else COLOR_WHITE)
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
                setTextColor(if (isCurrent) currentAccentColor else COLOR_MUTED)
                includeFontPadding = false
            }

            row.addView(titleView)
            row.addView(badgeView)

            attachSpringTouch(row) {
                if (!isCurrent) {
                    switchToRoom(roomCode)
                    selectDeckTab(0)
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
        btn.setTextColor(currentAccentColor)
        btn.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80).withEndAction {
            btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
        }.start()

        mainHandler.postDelayed({
            btn.text = "PING"
            btn.setTextColor(currentAccentColor)
        }, 1500)

        serviceScope.launch(Dispatchers.IO) {
            try {
                room?.localParticipant?.publishData(
                    data = "PING:$targetIdentity".toByteArray(Charsets.UTF_8)
                )
            } catch (_: Exception) {}
        }
    }

    private fun toggleParticipantMute(identity: String, participant: RemoteParticipant?) {
        val currentlyLocallyMuted = locallyMutedParticipants.contains(identity)
        if (currentlyLocallyMuted) {
            locallyMutedParticipants.remove(identity)
            broadcastMuteState(identity, false)
            vibrateClick()
            playChime(ToneGenerator.TONE_PROP_ACK, 40)
            Toast.makeText(this, "[+] UNMUTED $identity", Toast.LENGTH_SHORT).show()
        } else {
            locallyMutedParticipants.add(identity)
            broadcastMuteState(identity, true)
            vibrateAlert()
            playChime(ToneGenerator.TONE_PROP_PROMPT, 60)
            Toast.makeText(this, "[!] MUTED $identity (MUTUAL MUTE ACTIVE)", Toast.LENGTH_SHORT).show()
        }

        getSharedPreferences("tactical_prefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("locally_muted_users", locallyMutedParticipants.toSet())
            .apply()

        participant?.let {
            val vol = participantVolumes[identity] ?: 100
            setParticipantVolume(it, vol)
        }
        rebuildSquadList()
    }

    private fun broadcastMuteState(targetIdentity: String, isMuted: Boolean) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val flag = if (isMuted) "1" else "0"
                room?.localParticipant?.publishData(
                    data = "MUTE:$targetIdentity:$flag".toByteArray(Charsets.UTF_8)
                )
            } catch (_: Exception) {}
        }
    }

    private fun broadcastMuteSync() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val list = locallyMutedParticipants.joinToString(",")
                room?.localParticipant?.publishData(
                    data = "MUTE_SYNC:$list".toByteArray(Charsets.UTF_8)
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
        } else if (payload.startsWith("MUTE:")) {
            val parts = payload.removePrefix("MUTE:").split(":")
            if (parts.size >= 2) {
                val target = parts[0]
                val isMuted = parts[1] == "1"
                if (target.equals(currentCallsign, ignoreCase = true)) {
                    mainHandler.post {
                        if (isMuted) {
                            remotelyMutedMe.add(sender)
                            vibrateAlert()
                            playChime(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 120)
                            Toast.makeText(this@VoiceOverlayService, "[!] $sender MUTED YOU (MUTUAL MUTE ACTIVE)", Toast.LENGTH_SHORT).show()
                        } else {
                            remotelyMutedMe.remove(sender)
                            playChime(ToneGenerator.TONE_PROP_ACK, 40)
                            Toast.makeText(this@VoiceOverlayService, "[+] $sender UNMUTED YOU", Toast.LENGTH_SHORT).show()
                        }
                        applyRemoteVolumes()
                        rebuildSquadList()
                    }
                }
            }
        } else if (payload.startsWith("MUTE_SYNC:")) {
            val raw = payload.removePrefix("MUTE_SYNC:").trim()
            val targetList = if (raw.isEmpty()) emptyList() else raw.split(",").map { it.trim() }
            val mutedMe = targetList.any { it.equals(currentCallsign, ignoreCase = true) }
            mainHandler.post {
                val stateChanged = if (mutedMe) remotelyMutedMe.add(sender) else remotelyMutedMe.remove(sender)
                if (stateChanged) {
                    applyRemoteVolumes()
                    rebuildSquadList()
                }
            }
        }
    }

    private fun triggerSquadAlert(sender: String) {
        if (pingAlertMode == "mute") return

        mainHandler.post {
            if (pingAlertMode == "full") {
                playChime(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 280)
                vibrateAlert()
                Toast.makeText(this, "[PING] ALERT FROM $sender!", Toast.LENGTH_SHORT).show()
            }
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
        if (hapticLevel == 0) return
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
        if (chimeMode == "mute") return
        try {
            if (chimeMode == "clicks") {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 25)
            } else {
                toneGenerator?.startTone(toneType, durationMs)
            }
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
        txtStateRoute.setTextColor(if (isSpeakerphone) currentAccentColor else COLOR_MUTED)
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
        txtStateMic.setTextColor(if (isMicEnabled) currentAccentColor else THEME_RED)

        txtStateMic.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            txtStateMic.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()

        updateActionButtonsTheme(currentAccentColor)
    }

    private fun updateDeafenUi() {
        btnToggleDeafen.isSelected = isDeafened
        iconStateDeafen.setImageResource(if (isDeafened) R.drawable.ic_headset_off else R.drawable.ic_headset)
        txtStateDeafen.text = if (isDeafened) "DEAF" else "NORM"
        txtStateDeafen.setTextColor(if (isDeafened) THEME_RED else COLOR_MUTED)

        txtStateDeafen.animate().scaleX(1.18f).scaleY(1.18f).setDuration(80).withEndAction {
            txtStateDeafen.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()

        updateActionButtonsTheme(currentAccentColor)
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

            val isLocallyMuted = locallyMutedParticipants.contains(identity)
            val isMutedByThem = remotelyMutedMe.contains(identity)
            val isMutuallyMuted = isLocallyMuted || isMutedByThem

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
                orientation = LinearLayout.VERTICAL
                background = createRosterRowDrawable(false, currentAccentColor)
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
                background = createLedDrawable(COLOR_MUTED)
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

            val muteBadge = TextView(this).apply {
                tag = "MUTE_BADGE_$identity"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(1), dp(4), dp(1))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(6)
                }
                when {
                    isMutedByThem -> {
                        text = "MUTED YOU"
                        setTextColor(COLOR_WHITE)
                        background = createSegmentChipDrawable(true, THEME_RED)
                        visibility = View.VISIBLE
                    }
                    isLocallyMuted -> {
                        text = "MUTUAL MUTE"
                        setTextColor(COLOR_WHITE)
                        background = createSegmentChipDrawable(true, THEME_RED)
                        visibility = View.VISIBLE
                    }
                    else -> {
                        visibility = View.GONE
                    }
                }
            }

            val pingBtn = TextView(this).apply {
                tag = "PING_$identity"
                text = "PING"
                setTextColor(currentAccentColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                background = createSegmentChipDrawable(false, currentAccentColor)
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
                if (isMutuallyMuted) {
                    text = "MUTED"
                    setTextColor(THEME_RED)
                } else {
                    updateGainText(this, savedVol)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
            }

            topBar.addView(led)
            topBar.addView(callsignTxt)
            topBar.addView(muteBadge)
            topBar.addView(pingBtn)
            topBar.addView(gainReadout)

            var lastSnapped = false

            val sliderRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(22)
                ).apply { topMargin = dp(2) }
            }

            val muteBtn = TextView(this).apply {
                tag = "MUTE_BTN_$identity"
                text = if (isMutuallyMuted) "MUTED" else "MUTE"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                if (isMutuallyMuted) {
                    setTextColor(COLOR_WHITE)
                    background = createSegmentChipDrawable(true, THEME_RED)
                } else {
                    setTextColor(COLOR_MUTED)
                    background = createSegmentChipDrawable(false, currentAccentColor)
                }
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(18)
                ).apply { rightMargin = dp(6) }

                attachSpringTouch(this) {
                    toggleParticipantMute(identity, participant)
                }
            }

            val seekbar = SeekBar(this).apply {
                tag = "SEEKBAR_$identity"
                max = 200
                progress = savedVol
                progressDrawable = getDrawable(R.drawable.seekbar_gain_track)
                thumb = getDrawable(R.drawable.seekbar_gain_thumb)
                val tint = if (isMutuallyMuted) THEME_RED else currentAccentColor
                progressTintList = ColorStateList.valueOf(tint)
                thumbTintList = ColorStateList.valueOf(tint)
                splitTrack = false
                isEnabled = !isMutuallyMuted
                alpha = if (isMutuallyMuted) 0.35f else 1.0f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    dp(18),
                    1f
                )
                setPadding(dp(4), 0, dp(4), 0)

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (isMutuallyMuted) return
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
                        if (!isMutuallyMuted) sb?.animate()?.scaleY(1.25f)?.setDuration(100)?.start()
                    }

                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        if (!isMutuallyMuted) sb?.animate()?.scaleY(1.0f)?.setDuration(120)?.start()
                    }
                })
            }

            sliderRow.addView(muteBtn)
            sliderRow.addView(seekbar)

            row.addView(topBar)
            row.addView(sliderRow)
            deckRosterContainer.addView(row)

            setParticipantVolume(participant, savedVol)
        }
    }

    private fun updateGainText(textView: TextView, progress: Int) {
        if (progress > 100) {
            textView.text = "$progress% BOOST"
            textView.setTextColor(THEME_AMBER)
        } else if (progress == 100) {
            textView.text = "100%"
            textView.setTextColor(currentAccentColor)
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
        val identity = participant.identity?.value ?: return
        val isMuted = locallyMutedParticipants.contains(identity) || remotelyMutedMe.contains(identity)
        val effectiveGain = if (isDeafened || isMuted) 0.0 else (percentage / 100.0)
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
        val audibleActiveIds = activeIds.filterNot { locallyMutedParticipants.contains(it) || remotelyMutedMe.contains(it) }.toSet()
        val isAnyoneTalking = audibleActiveIds.isNotEmpty()

        // Edge slider tab live speaking state
        val shouldBlink = isVoiceBlinkEnabled && isAnyoneTalking
        applyHandleSpeakingUi(shouldBlink)
        if (isAnyoneTalking) {
            resetGhostFadeTimer()
        }

        // Roster row highlight states (0 dot animators, 0 allocation overhead)
        for (i in 0 until deckRosterContainer.childCount) {
            val child = deckRosterContainer.getChildAt(i)
            val rowTag = child.tag as? String ?: continue
            if (rowTag.startsWith("ROW_")) {
                val id = rowTag.removePrefix("ROW_")
                val isSpeaker = audibleActiveIds.contains(id)
                child.isActivated = isSpeaker
                child.background = createRosterRowDrawable(isSpeaker, currentAccentColor)
                val led = child.findViewWithTag<View>("LED_$id")
                led?.isActivated = isSpeaker
                led?.background = createLedDrawable(if (isSpeaker) currentAccentColor else COLOR_MUTED)
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
        if (hapticLevel == 0) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val effect = if (hapticLevel == 1)
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                else
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (hapticLevel == 1) 8 else 18)
            }
        } catch (_: Exception) {}
    }

    private fun vibrateDetent() {
        if (hapticLevel == 0) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (hapticLevel == 1) 12 else 25)
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
        ghostFadeRunnable?.let { mainHandler.removeCallbacks(it) }
        ghostFadeRunnable = null
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = null
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
