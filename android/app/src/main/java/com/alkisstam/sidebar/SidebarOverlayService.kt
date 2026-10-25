package com.alkisstam.sidebar

import android.animation.*
import android.app.*
import android.content.*
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.*
import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.accessibilityservice.AccessibilityService
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.app.usage.UsageStatsManager
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.os.*
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import rikka.shizuku.Shizuku

class SidebarOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var handleViewAlt: View? = null
    private var pillInnerView: View? = null
    private var pillInnerViewAlt: View? = null
    private var lastActiveSide = "right"
    private var showingDrawer = false
    private var shown = false

    private var vibrationEnabled = true

    private val iconTypeface: android.graphics.Typeface by lazy {
        android.graphics.Typeface.createFromAsset(assets, "fonts/MaterialIcons-Regular.ttf")
    }

    private val appLoadExecutor = Executors.newSingleThreadExecutor()
    // Keyed by package name; populated on service start so panel and drawer open instantly.
    private val appIconCache = ConcurrentHashMap<String, Pair<String, Drawable>>()
    @Volatile private var cachedAllApps: List<Triple<String, String, Drawable>>? = null
    @Volatile private var cachedRecentApps: List<String> = emptyList()

    // Fullscreen detection — only active when auto-hide-fullscreen pref is on.
    // 2 s interval is plenty; tighter polling prevents deep-sleep unnecessarily.
    private val fsHandler = Handler(Looper.getMainLooper())
    private val fsRunnable = object : Runnable {
        override fun run() {
            if (!shown) {
                val vis = if (isSystemFullscreen()) View.INVISIBLE else View.VISIBLE
                handleView?.visibility = vis
                handleViewAlt?.visibility = vis
            }
            fsHandler.postDelayed(this, 2000)
        }
    }

    // Torch state
    private var torchEnabled = false
    private var torchCameraId: String? = null
    private var cameraManager: CameraManager? = null
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) torchEnabled = enabled
        }
    }

    // ── Freeform Chrome ───────────────────────────────────────────────────────
    private val freeformTitles      = mutableMapOf<Int, View>()
    private val freeformTitleParams = mutableMapOf<Int, WindowManager.LayoutParams>()
    private val freeformResizes     = mutableMapOf<Int, View>()
    private val freeformResizeParams= mutableMapOf<Int, WindowManager.LayoutParams>()
    private val freeformBounds      = mutableMapOf<Int, Rect>()
    private val freeformPkgs        = mutableMapOf<Int, String>()
    private val freeformComponents  = mutableMapOf<Int, String>()
    private val freeformRootTasks   = mutableMapOf<Int, Int>()
    @Volatile private var savedFreeformSettings: Map<String, String>? = null
    private val chromePollHandler   = Handler(Looper.getMainLooper())
    private val chromePollRunnable  = object : Runnable {
        override fun run() {
            pollFreeformTasks()
            if (freeformTitles.isNotEmpty()) chromePollHandler.postDelayed(this, 2000)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                // No point polling for fullscreen state while the screen is off.
                fsHandler.removeCallbacks(fsRunnable)
                // Locking the phone closes everything and hides the handle until it is unlocked.
                Handler(Looper.getMainLooper()).post {
                    closeAllNow()
                    setHandlesVisible(false)
                }
                return
            }
            if (!Settings.canDrawOverlays(context)) return
            Handler(Looper.getMainLooper()).post {
                val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                if (intent.action == Intent.ACTION_USER_PRESENT || !keyguard.isKeyguardLocked) {
                    setHandlesVisible(true)
                }
                if (handleView?.windowToken == null) {
                    addHandle()
                } else {
                    val autoHide = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
                        .getBoolean(Prefs.AUTO_HIDE_FULLSCREEN, false)
                    if (autoHide) {
                        fsHandler.removeCallbacks(fsRunnable)
                        fsHandler.post(fsRunnable)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)

        cameraManager = getSystemService(CAMERA_SERVICE) as? CameraManager
        cameraManager?.let { cm ->
            try {
                torchCameraId = cm.cameraIdList.firstOrNull { id ->
                    cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                torchCameraId?.let {
                    cm.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
                }
            } catch (e: Exception) { Log.w(TAG, "Torch init failed", e) }
        }

        if (Settings.canDrawOverlays(this)) {
            Handler(Looper.getMainLooper()).post { addHandle() }
        }
        appLoadExecutor.submit { prefetchAppData() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Settings.canDrawOverlays(this)) {
            Handler(Looper.getMainLooper()).post { refreshHandle() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        fsHandler.removeCallbacks(fsRunnable)
        appLoadExecutor.shutdown()
        runCatching { unregisterReceiver(screenReceiver) }
        torchCameraId?.let { runCatching { cameraManager?.unregisterTorchCallback(torchCallback) } }
        Handler(Looper.getMainLooper()).post {
            handleView?.let { runCatching { wm.removeView(it) } }
            handleViewAlt?.let { runCatching { wm.removeView(it) } }
            listOfNotNull(panelWindow, controlsPanel, volumePanel, drawerWindow, menuWindow)
                .forEach { it.dismissNow() }
            listOfNotNull(panelBackdrop, drawerBackdrop, menuBackdrop)
                .forEach { runCatching { it.dialog.dismiss() } }
            freeformTitles.keys.toList().forEach { removeFreeformChrome(it) }
        }
        chromePollHandler.removeCallbacks(chromePollRunnable)
    }

    private fun setHandlesVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        handleView?.visibility = v
        handleViewAlt?.visibility = v
    }

    /** Closes the panel, its sub-panels, All Apps and the long-press menu at once, without animation. */
    private fun closeAllNow() {
        hideContextMenu()
        drawerWindow?.dismissNow()
        drawerWindow = null
        drawerBackdrop?.let { runCatching { it.dialog.dismiss() } }
        drawerBackdrop = null
        showingDrawer = false
        if (shown) {
            shown = false
            listOfNotNull(controlsPanel, volumePanel, panelWindow).forEach { it.dismissNow() }
            controlsPanel = null
            volumePanel = null
            panelWindow = null
            toolsButton = null
            panelBackdrop?.let { runCatching { it.dialog.dismiss() } }
            panelBackdrop = null
        }
    }

    private fun refreshHandle() {
        fsHandler.removeCallbacks(fsRunnable)
        handleView?.let { runCatching { wm.removeView(it) } }
        handleViewAlt?.let { runCatching { wm.removeView(it) } }
        handleView = null; handleViewAlt = null
        pillInnerView = null; pillInnerViewAlt = null
        addHandle()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ── Prefs ─────────────────────────────────────────────────────────────────

    data class PillPrefs(
        val height: Int, val width: Int, val position: Float,
        val side: String, val opacity: Float
    )

    data class OverlayPrefs(
        val autoHideFullscreen: Boolean,
        val showLabels: Boolean,
        val vibration: Boolean,
        val sensitivity: Int,
        val quickControlsEnabled: Boolean,
        val showTorch: Boolean,
        val showAutoRotate: Boolean,
        val showAutoBrightness: Boolean,
        val showRingerMode: Boolean,
        val showAllApps: Boolean,
        val showEdit: Boolean,
        val gesturesEnabled: Boolean,
        val gestureSwipeIn: String,
        val gestureSwipeUp: String,
        val gestureSwipeDown: String,
        val gestureDoubleTap: String,
        val showBrightnessSlider: Boolean,
        val showQuickShare: Boolean,
        val showRecentApps: Boolean,
        val showKeyboardOnAllApps: Boolean,
        val showPower: Boolean,
        val showQr: Boolean,
        val showDnd: Boolean,
        val showVolume: Boolean,
        val controlTilesOrder: List<String>,
        val freeformWindows: Boolean,
        val blurBehind: Boolean,
        val blurRadius: Int
    )

    private fun pillPrefs(): PillPrefs {
        val p = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        return PillPrefs(
            p.getInt(Prefs.PILL_HEIGHT, 80), p.getInt(Prefs.PILL_WIDTH, 10),
            p.getFloat(Prefs.PILL_POSITION, 0.5f),
            p.getString(Prefs.PILL_SIDE, "right") ?: "right",
            p.getFloat(Prefs.PILL_OPACITY, 1.0f)
        )
    }

    /** Active M3 scheme: wallpaper or custom seed colors, in the chosen light/dark appearance. */
    private fun scheme(): M3Scheme = SidebarColors.resolve(this)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Handler(Looper.getMainLooper()).post {
            if (shown) hidePanel()
            refreshHandle()
        }
    }

    private fun overlayPrefs(): OverlayPrefs {
        val p = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        return OverlayPrefs(
            p.getBoolean(Prefs.AUTO_HIDE_FULLSCREEN, false),
            p.getBoolean(Prefs.SHOW_LABELS, true),
            p.getBoolean(Prefs.VIBRATION, true),
            p.getInt(Prefs.SWIPE_SENSITIVITY, 16),
            p.getBoolean(Prefs.QUICK_CONTROLS_ENABLED, true),
            p.getBoolean(Prefs.SHOW_TORCH, true),
            p.getBoolean(Prefs.SHOW_AUTO_ROTATE, true),
            p.getBoolean(Prefs.SHOW_AUTO_BRIGHTNESS, true),
            p.getBoolean(Prefs.SHOW_RINGER_MODE, true),
            p.getBoolean(Prefs.SHOW_ALL_APPS, true),
            p.getBoolean(Prefs.SHOW_EDIT, true),
            p.getBoolean(Prefs.GESTURES_ENABLED, true),
            p.getString(Prefs.GESTURE_SWIPE_IN, "panel") ?: "panel",
            p.getString(Prefs.GESTURE_SWIPE_UP, "none") ?: "none",
            p.getString(Prefs.GESTURE_SWIPE_DOWN, "notifications") ?: "notifications",
            p.getString(Prefs.GESTURE_DOUBLE_TAP, "none") ?: "none",
            p.getBoolean(Prefs.SHOW_BRIGHTNESS_SLIDER, false),
            p.getBoolean(Prefs.SHOW_QUICK_SHARE, false),
            p.getBoolean(Prefs.SHOW_RECENT_APPS, false),
            p.getBoolean(Prefs.SHOW_KEYBOARD_ON_ALL_APPS, false),
            p.getBoolean(Prefs.SHOW_POWER, false),
            p.getBoolean(Prefs.SHOW_QR, false),
            p.getBoolean(Prefs.SHOW_DND, false),
            p.getBoolean(Prefs.SHOW_VOLUME, true),
            run {
                val default = listOf("torch","rotate","brightness","ringer","share","power","qr","dnd")
                val json = p.getString(Prefs.CONTROL_TILES_ORDER, null) ?: return@run default
                try {
                    val arr = org.json.JSONArray(json)
                    (0 until arr.length()).map { arr.getString(it) }
                } catch (_: Exception) { default }
            },
            p.getBoolean(Prefs.FREEFORM_WINDOWS, false),
            p.getBoolean(Prefs.BLUR_BEHIND, false),
            p.getInt(Prefs.BLUR_RADIUS, 20)
        )
    }

    // ── Motion (springs) ──────────────────────────────────────────────────────

    private val emphasizedDecelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    private val emphasizedAccelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    private val viewSprings =
        java.util.WeakHashMap<View, HashMap<DynamicAnimation.ViewProperty, SpringAnimation>>()

    /** Springs a view property to [target], re-targeting smoothly if it is already moving. */
    private fun View.springTo(
        prop: DynamicAnimation.ViewProperty, target: Float,
        stiffness: Float = SPATIAL_STIFFNESS, damping: Float = SPATIAL_DAMPING
    ) {
        val anim = viewSprings.getOrPut(this) { HashMap() }.getOrPut(prop) {
            SpringAnimation(this, prop).setSpring(SpringForce())
        }
        anim.spring.setStiffness(stiffness).setDampingRatio(damping)
        anim.animateToFinalPosition(target)
    }

    private fun View.cancelSprings() {
        viewSprings.remove(this)?.values?.forEach { it.cancel() }
    }

    /** Spring-driven float for values that aren't view properties (corner radius, fill length). */
    private class FloatSpring(
        start: Float, stiffness: Float, damping: Float,
        private val onUpdate: (Float) -> Unit
    ) {
        private val holder = FloatValueHolder(start)
        private val anim = SpringAnimation(holder)
            .setSpring(SpringForce().setStiffness(stiffness).setDampingRatio(damping))
            .addUpdateListener { _, value, _ -> onUpdate(value) }

        fun animateTo(target: Float) = anim.animateToFinalPosition(target)

        fun snapTo(value: Float) {
            anim.cancel()
            holder.value = value
            onUpdate(value)
        }
    }

    private fun withAlpha(color: Int, alpha: Int) = ColorUtils.setAlphaComponent(color, alpha)

    // ── Glass windows (frosted surfaces + blurred backdrop) ───────────────────

    private fun blurAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled

    private fun blurPx(oPrefs: OverlayPrefs): Int =
        if (oPrefs.blurBehind && blurAvailable()) dp(oPrefs.blurRadius) else 0

    /**
     * An overlay window backed by a Dialog. Unlike a bare `WindowManager.addView`, a Dialog
     * window can use Android 12+ window background blur (`Window.setBackgroundBlurRadius`),
     * which frosts whatever is behind the window's own rounded background — the same
     * mechanism the system uses for its panels on Android 16/17.
     */
    private fun overlayDialog(
        content: View, width: Int, height: Int,
        gravity: Int, x: Int, y: Int,
        background: Drawable, focusable: Boolean
    ): Dialog {
        val dialog = Dialog(this, R.style.SidebarSurface)
        val w = dialog.window!!
        w.setType(overlayType())
        dialog.setContentView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (height == ViewGroup.LayoutParams.WRAP_CONTENT) ViewGroup.LayoutParams.WRAP_CONTENT
            else ViewGroup.LayoutParams.MATCH_PARENT
        ))
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.setBackgroundDrawable(background)
        w.setLayout(width, height)
        w.attributes = w.attributes.apply {
            this.gravity = gravity
            this.x = x
            this.y = y
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (!focusable) w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(focusable)
        return dialog
    }

    private fun setWindowBlur(dialog: Dialog, px: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dialog.window?.setBackgroundBlurRadius(px)
    }

    /** Full-screen touch catcher placed under a panel; can dim the apps behind it ([animateTo] ramps it). */
    private inner class Backdrop(private val dim: Float, private val blurPx: Int, onTap: () -> Unit) {
        private val bg = ColorDrawable(Color.TRANSPARENT)
        val dialog = overlayDialog(
            View(this@SidebarOverlayService).apply { setOnClickListener { onTap() } },
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            Gravity.TOP or Gravity.START, 0, 0, bg, focusable = false
        )
        private var progress = 0f
        private var anim: ValueAnimator? = null

        init { dialog.show() }

        private fun apply(p: Float) {
            progress = p
            bg.color = Color.argb((dim * 255 * p).toInt(), 0, 0, 0)
            if (blurPx > 0) setWindowBlur(dialog, (blurPx * p).toInt())
        }

        fun animateTo(target: Float, duration: Long, interp: TimeInterpolator, end: (() -> Unit)? = null) {
            anim?.cancel()
            if (dim <= 0f && blurPx <= 0) { progress = target; end?.invoke(); return }
            anim = ValueAnimator.ofFloat(progress, target).apply {
                this.duration = duration
                interpolator = interp
                addUpdateListener { a -> apply(a.animatedValue as Float) }
                end?.let { onEnd ->
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) = onEnd()
                    })
                }
                start()
            }
        }

        fun dismiss() = animateTo(0f, 200, emphasizedAccelerate) { runCatching { dialog.dismiss() } }
    }

    /** Touch catcher under a panel. It never blurs the apps behind — only the panels themselves are frosted. */
    private fun addBackdrop(scrim: Float, onTap: () -> Unit): Backdrop =
        Backdrop(scrim, 0, onTap).also { it.animateTo(1f, 320, emphasizedDecelerate) }

    /**
     * A floating panel surface. With blur it starts opaque and turns frosted as the blur ramps
     * up; animate [decor] (it carries the rounded background, so the blur region follows it).
     */
    private inner class GlassWindow(
        content: View,
        width: Int, height: Int,
        gravity: Int, x: Int, y: Int,
        radius: Float,
        private val color: Int,
        private val blurPx: Int,
        focusable: Boolean = false
    ) {
        private val bg = GradientDrawable().apply { setColor(color); cornerRadius = radius }
        val dialog: Dialog
        val decor: View
        private var anim: ValueAnimator? = null

        init {
            content.clipToOutline = true
            content.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            dialog = overlayDialog(content, width, height, gravity, x, y, bg, focusable)
            if (blurPx <= 0) dialog.window?.setElevation(dp(6).toFloat())
            decor = dialog.window!!.decorView
            dialog.show()
        }

        /** Ramps the frost in: blur radius up, surface from opaque to translucent. */
        fun frost(delay: Long = 0) {
            if (blurPx <= 0) return
            anim?.cancel()
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = delay
                duration = 320
                interpolator = emphasizedDecelerate
                addUpdateListener { a ->
                    val p = a.animatedValue as Float
                    bg.setColor(ColorUtils.setAlphaComponent(color, (255 - (255 - FROST_ALPHA) * p).toInt()))
                    setWindowBlur(dialog, (blurPx * p).toInt())
                }
                start()
            }
        }

        /** Fades the surface out (with [decorAnim] moving it), closes the window, then runs [onEnd]. */
        fun close(onEnd: (() -> Unit)? = null, decorAnim: ViewPropertyAnimator.() -> ViewPropertyAnimator = { this }) {
            anim?.cancel()
            decor.cancelSprings()
            decor.animate().alpha(0f).decorAnim()
                .setDuration(160).setInterpolator(emphasizedAccelerate)
                .withEndAction {
                    runCatching { dialog.dismiss() }
                    onEnd?.invoke()
                }
                .start()
        }

        fun dismissNow() = runCatching { dialog.dismiss() }
    }

    // ── Handle ────────────────────────────────────────────────────────────────

    private fun handleParams(prefs: PillPrefs, side: String): WindowManager.LayoutParams {
        val pillHeight = dp(prefs.height)
        val screenHeight = resources.displayMetrics.heightPixels
        val yPos = (prefs.position * screenHeight - pillHeight / 2).toInt()
            .coerceIn(0, screenHeight - pillHeight)
        return WindowManager.LayoutParams(
            maxOf(dp(prefs.width), dp(24)), pillHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = if (side == "left") Gravity.START or Gravity.TOP
                      else Gravity.END or Gravity.TOP
            x = dp(2)
            y = yPos
        }
    }

    private fun buildHandle(prefs: PillPrefs, oPrefs: OverlayPrefs, side: String): Pair<View, View> {
        val pill = View(this).apply {
            background = PullTabDrawable(scheme().primary)
            alpha = prefs.opacity
        }
        val container = FrameLayout(this).apply {
            addView(pill, FrameLayout.LayoutParams(dp(prefs.width), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = if (side == "left") Gravity.START else Gravity.END
            })
        }
        installSwipeListener(container, pill, oPrefs.sensitivity, side)
        wm.addView(container, handleParams(prefs, side))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            container.post {
                container.systemGestureExclusionRects = listOf(
                    android.graphics.Rect(0, 0, container.width, container.height)
                )
            }
        }
        return Pair(container, pill)
    }

    private fun addHandle() {
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        vibrationEnabled = oPrefs.vibration
        val primarySide = if (prefs.side == "both") "left" else prefs.side
        val (c, p) = buildHandle(prefs, oPrefs, primarySide)
        handleView = c; pillInnerView = p
        if (prefs.side == "both") {
            val (cR, pR) = buildHandle(prefs, oPrefs, "right")
            handleViewAlt = cR; pillInnerViewAlt = pR
            lastActiveSide = "right"
        }
        if (oPrefs.autoHideFullscreen) {
            fsHandler.removeCallbacks(fsRunnable)
            fsHandler.post(fsRunnable)
        }
    }

    private fun installSwipeListener(handle: View, pill: View, sensitivityDp: Int = 16, triggerSide: String = "right") {
        var downX = 0f
        var downY = 0f
        var lastTapTime = 0L
        val inward = if (triggerSide == "left") 1f else -1f
        val maxPull = dp(24).toFloat()

        fun release() {
            pill.springTo(DynamicAnimation.TRANSLATION_X, 0f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
            pill.springTo(DynamicAnimation.SCALE_X, 1f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
            pill.springTo(DynamicAnimation.SCALE_Y, 1f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
        }

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    // Touch feedback: the pill swells from the screen edge.
                    pill.pivotX = if (triggerSide == "left") 0f else pill.width.toFloat()
                    pill.springTo(DynamicAnimation.SCALE_X, 1.4f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Elastic pull: the pill follows the finger inward a little, with resistance.
                    val pull = ((event.rawX - downX) * inward).coerceIn(0f, maxPull)
                    pill.springTo(DynamicAnimation.TRANSLATION_X, inward * pull * 0.35f, 1400f, 1f)
                    pill.springTo(DynamicAnimation.SCALE_Y, 1f + 0.08f * pull / maxPull, 1400f, 1f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    release()
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    val adx = Math.abs(dx)
                    val ady = Math.abs(dy)
                    val threshold = dp(sensitivityDp)
                    val oPrefs = overlayPrefs()
                    when {
                        adx >= threshold && adx > ady -> {
                            lastActiveSide = triggerSide
                            if (oPrefs.gesturesEnabled) executeGestureAction(oPrefs.gestureSwipeIn)
                            else toggle()
                        }
                        oPrefs.gesturesEnabled && ady >= threshold && ady > adx && dy < 0 -> executeGestureAction(oPrefs.gestureSwipeUp)
                        oPrefs.gesturesEnabled && ady >= threshold && ady > adx && dy > 0 -> executeGestureAction(oPrefs.gestureSwipeDown)
                        adx < dp(8) && ady < dp(8) -> {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 300L) {
                                if (oPrefs.gesturesEnabled) executeGestureAction(oPrefs.gestureDoubleTap)
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { release(); true }
                else -> false
            }
        }
    }

    private fun executeGestureAction(action: String) {
        when (action) {
            "panel"          -> toggle()
            "notifications"  -> expandStatusBar(quick = false)
            "quick_settings" -> expandStatusBar(quick = true)
            "all_apps"       -> { if (!shown && !showingDrawer) showAllAppsDrawer() }
        }
    }

    private fun expandStatusBar(quick: Boolean) {
        try {
            val sbm = getSystemService("statusbar")
            val method = if (quick) "expandSettingsPanel" else "expandNotificationsPanel"
            sbm?.javaClass?.getMethod(method)?.invoke(sbm)
        } catch (e: Exception) { Log.w(TAG, "expandStatusBar($quick) failed", e) }
    }

    private fun vibrate() {
        if (!vibrationEnabled) return
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vib.vibrate(30)
        }
    }

    private fun toggle() {
        vibrate()
        if (shown) hidePanel() else showPanel()
    }

    // ── Floating surface helpers ──────────────────────────────────────────────

    private fun surfaceBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun iconText(icon: String, sizeSp: Float, color: Int) = TextView(this).apply {
        text = icon
        textSize = sizeSp
        gravity = Gravity.CENTER
        typeface = iconTypeface
        setTextColor(color)
        includeFontPadding = false
    }

    private val mediumTypeface: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    /**
     * Icon button that morphs between a circle and a rounded square (selected), squeezes while
     * pressed and cross-fades its colors — the M3 Expressive shape-morphing icon button.
     */
    private inner class MorphButton(
        icon: String,
        private val s: M3Scheme,
        private val size: Int,
        description: String,
        onClick: () -> Unit
    ) {
        private val bg = GradientDrawable().apply { setColor(s.primaryContainer); cornerRadius = size / 2f }
        val view: TextView = iconText(icon, 22f, s.onPrimaryContainer).apply {
            background = bg
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { onClick() }
        }
        private var selected = false
        private var pressed = false
        private var shownBg = s.primaryContainer
        private var shownFg = s.onPrimaryContainer
        private var colorAnim: ValueAnimator? = null
        private val radius = FloatSpring(size / 2f, SPATIAL_STIFFNESS, 0.85f) { bg.cornerRadius = it }

        init {
            view.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressed = true; updateShape()
                        v.springTo(DynamicAnimation.SCALE_X, 0.92f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                        v.springTo(DynamicAnimation.SCALE_Y, 0.92f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        pressed = false; updateShape()
                        v.springTo(DynamicAnimation.SCALE_X, 1f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                        v.springTo(DynamicAnimation.SCALE_Y, 1f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                    }
                }
                false
            }
        }

        fun setSelected(value: Boolean) {
            if (selected == value) return
            selected = value
            updateShape()
            val toBg = if (value) s.primary else s.primaryContainer
            val toFg = if (value) s.onPrimary else s.onPrimaryContainer
            val fromBg = shownBg
            val fromFg = shownFg
            colorAnim?.cancel()
            colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = emphasizedDecelerate
                addUpdateListener { a ->
                    shownBg = ColorUtils.blendARGB(fromBg, toBg, a.animatedFraction)
                    shownFg = ColorUtils.blendARGB(fromFg, toFg, a.animatedFraction)
                    bg.setColor(shownBg)
                    view.setTextColor(shownFg)
                }
                start()
            }
        }

        private fun updateShape() = radius.animateTo(
            when {
                pressed -> size * 0.22f
                selected -> size * 0.3f
                else -> size / 2f
            }
        )
    }

    // ── Panel ─────────────────────────────────────────────────────────────────

    private var panelWindow: GlassWindow? = null
    private var panelBackdrop: Backdrop? = null
    private var controlsPanel: GlassWindow? = null
    private var volumePanel: GlassWindow? = null
    private var drawerWindow: GlassWindow? = null
    private var drawerBackdrop: Backdrop? = null
    private var toolsButton: MorphButton? = null
    private var panelFromLeft = false

    private fun showPanel() {
        if (shown) return
        shown = true
        val prefs = pillPrefs()
        val oPrefs = overlayPrefs()
        val s = scheme()
        val pkgs = loadFavorites()
        val pm = packageManager
        val effectiveSide = if (prefs.side == "both") lastActiveSide else prefs.side
        val fromLeft = effectiveSide == "left"
        panelFromLeft = fromLeft

        val screenHeight = resources.displayMetrics.heightPixels
        val recents = if (oPrefs.showRecentApps) cachedRecentApps.take(4) else emptyList()
        // Tools block + list padding (80dp) + one row per app, capped at 6 rows; the list scrolls past that.
        val rowH = if (oPrefs.showLabels) dp(80) else dp(64)
        val rows = maxOf(1, recents.size + pkgs.size)
        val dividerH = if (recents.isNotEmpty() && pkgs.isNotEmpty()) dp(14) else 0
        val panelHeight = (dp(80) + minOf(rows, 6) * rowH + dividerH)
            .coerceAtMost(screenHeight - dp(16))
        val handleCenterY = (prefs.position * screenHeight).toInt()
        val panelY = (handleCenterY - panelHeight / 2).coerceIn(dp(8), maxOf(dp(8), screenHeight - panelHeight - dp(8)))

        panelBackdrop = addBackdrop(0f) { hidePanel() }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val tools = MorphButton("", s, dp(48), "Tools") {
            toggleControlsPanel(oPrefs, fromLeft, panelY)
        }
        toolsButton = tools
        column.addView(tools.view, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(8) })

        fun divider() = View(this).apply {
            background = surfaceBackground(s.outlineVariant, 1)
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(2)).apply {
                topMargin = dp(6); bottomMargin = dp(6); gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        column.addView(divider().apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = dp(10); bottomMargin = dp(4) }
        })

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val cells = mutableListOf<View>()

        fun loadCell(pkg: String): View? {
            val cached = appIconCache[pkg]
            val (name, icon) = cached ?: try {
                val info = pm.getApplicationInfo(pkg, 0)
                Pair(pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(pkg))
            } catch (e: Exception) { return null }
            return makeAppCell(pkg, name, icon, s, showLabel = oPrefs.showLabels, circular = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        }

        for (pkg in recents) loadCell(pkg)?.let { list.addView(it); cells.add(it) }
        if (recents.isNotEmpty() && pkgs.isNotEmpty()) list.addView(divider())
        for (pkg in pkgs) loadCell(pkg)?.let { list.addView(it); cells.add(it) }

        if (cells.isEmpty()) {
            // Empty state: a single "add" button that opens the Apps tab in settings.
            val add = MorphButton("", s, dp(48), "Add apps") { openEditor() }
            list.addView(add.view, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8)
            })
        }

        scroll.addView(list)
        column.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val gravity = (if (fromLeft) Gravity.START else Gravity.END) or Gravity.TOP
        val glass = GlassWindow(column, dp(64), panelHeight, gravity, dp(8), panelY,
            dp(32).toFloat(), s.surfaceContainer, blurPx(oPrefs))
        panelWindow = glass
        handleView?.visibility = View.INVISIBLE
        handleViewAlt?.visibility = View.INVISIBLE

        // Enter: the column grows out of the handle edge and settles without bouncing…
        val decor = glass.decor
        val edgeOffset = (if (fromLeft) -dp(32) else dp(32)).toFloat()
        decor.pivotX = if (fromLeft) 0f else dp(64).toFloat()
        decor.pivotY = (handleCenterY - panelY).toFloat().coerceIn(0f, panelHeight.toFloat())
        decor.translationX = edgeOffset
        decor.scaleX = 0.7f
        decor.scaleY = 0.9f
        decor.alpha = 0f
        decor.animate().alpha(1f).setDuration(150).setInterpolator(emphasizedDecelerate).start()
        decor.springTo(DynamicAnimation.TRANSLATION_X, 0f, 600f, 0.9f)
        decor.springTo(DynamicAnimation.SCALE_X, 1f, 600f, 0.88f)
        decor.springTo(DynamicAnimation.SCALE_Y, 1f, 600f, 0.9f)
        glass.frost(delay = 60)

        // …and the first icons follow in a short cascade.
        cells.take(8).forEachIndexed { i, cell ->
            cell.alpha = 0f
            cell.translationX = edgeOffset / 4
            cell.animate().alpha(1f).translationX(0f)
                .setStartDelay(50L + i * 18L).setDuration(240)
                .setInterpolator(emphasizedDecelerate).start()
        }
    }

    private fun openEditor() {
        hidePanel()
        getSharedPreferences(Prefs.FILE, MODE_PRIVATE).edit().putString(Prefs.LAUNCH_TAB, "apps").apply()
        packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) }
            ?.let { startActivity(it) }
    }

    /** Places a Tools/Volume surface beside the sidebar column and grows it out of the Tools button. */
    private fun showSubSurface(content: View, oPrefs: OverlayPrefs, fromLeft: Boolean, panelY: Int, estimatedHeight: Int): GlassWindow {
        val s = scheme()
        val screenHeight = resources.displayMetrics.heightPixels
        val width = dp(232)
        val y = panelY.coerceIn(dp(8), maxOf(dp(8), screenHeight - estimatedHeight - dp(8)))
        val gravity = (if (fromLeft) Gravity.START else Gravity.END) or Gravity.TOP
        val glass = GlassWindow(content, width, WindowManager.LayoutParams.WRAP_CONTENT, gravity, dp(80), y,
            dp(28).toFloat(), s.surfaceContainer, blurPx(oPrefs))

        val decor = glass.decor
        decor.pivotX = if (fromLeft) 0f else width.toFloat()
        decor.pivotY = (panelY - y + dp(32)).toFloat()
        decor.alpha = 0f
        decor.scaleX = 0.85f
        decor.scaleY = 0.85f
        decor.animate().alpha(1f).setDuration(140).setInterpolator(emphasizedDecelerate).start()
        decor.springTo(DynamicAnimation.SCALE_X, 1f, 600f, 0.88f)
        decor.springTo(DynamicAnimation.SCALE_Y, 1f, 600f, 0.88f)
        glass.frost(delay = 40)
        return glass
    }

    private fun closeSubSurface(glass: GlassWindow?) = glass?.close { scaleX(0.92f).scaleY(0.92f) }

    private fun toggleControlsPanel(oPrefs: OverlayPrefs, fromLeft: Boolean, panelY: Int) {
        closeSubSurface(volumePanel)
        volumePanel = null

        controlsPanel?.let {
            closeSubSurface(it)
            controlsPanel = null
            toolsButton?.setSelected(false)
            return
        }

        val s = scheme()
        val ctrl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        if (oPrefs.showBrightnessSlider) {
            val slider = PillSlider(
                vertical = false, icon = "",
                initial = getBrightness(), min = 0, max = 255, s = s
            ) { setBrightness(it) }
            ctrl.addView(slider, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { bottomMargin = dp(6); marginStart = dp(3); marginEnd = dp(3) })
        }

        val tileBuilders: Map<String, (() -> View)?> = mapOf(
            "torch"      to (if (oPrefs.showTorch)         ({ makeControlTile("Torch",      { "" }, s, { torchEnabled },             { if (torchEnabled) "On" else "Off" })                    { toggleTorch() } }) else null),
            "rotate"     to (if (oPrefs.showAutoRotate)    ({ makeControlTile("Rotate",     { "" }, s, { isAutoRotateEnabled() },     { if (isAutoRotateEnabled()) "On" else "Off" })           { toggleAutoRotate() } }) else null),
            "brightness" to (if (oPrefs.showAutoBrightness)({ makeControlTile("Brightness", { "" }, s, { isAutoBrightnessEnabled() }, { if (isAutoBrightnessEnabled()) "Auto" else "Manual" })  { toggleAutoBrightness() } }) else null),
            "ringer"     to (if (oPrefs.showRingerMode)    ({ makeControlTile("Ringer",     { getRingerIcon() }, s, { isRingerActive() },  { getRingerSubtitle() })                                  { toggleRingerMode() } }) else null),
            "share"      to (if (oPrefs.showQuickShare)    ({ makeControlTile("Share",      { "" }, s, { false },                     { "" })                                                   { launchQuickShare() } }) else null),
            "power"      to (if (oPrefs.showPower)         ({ makeControlTile("Power",      { "" }, s, { false },                     { "" })                                                   { openPowerMenu() } }) else null),
            "qr"         to (if (oPrefs.showQr)            ({ makeControlTile("QR Scan",    { "" }, s, { false },                     { "" })                                                   { launchQrScanner() } }) else null),
            "dnd"        to (if (oPrefs.showDnd)           ({ makeControlTile("DND",        { if (isDndEnabled()) "" else "" }, s, { isDndEnabled() }, { if (isDndEnabled()) "On" else "Off" }) { toggleDnd() } }) else null),
        )
        val knownOrder = oPrefs.controlTilesOrder
        val tileViews = mutableListOf<View>()
        for (id in knownOrder) tileBuilders[id]?.invoke()?.let { tileViews.add(it) }
        for ((id, builder) in tileBuilders) {
            if (id !in knownOrder) builder?.invoke()?.let { tileViews.add(it) }
        }

        fun row(first: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (!first) topMargin = dp(6) }
        }

        tileViews.chunked(3).forEachIndexed { idx, chunk ->
            val r = row(first = idx == 0 && !oPrefs.showBrightnessSlider)
            chunk.forEach { r.addView(it) }
            repeat(3 - chunk.size) {
                r.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                        marginStart = dp(3); marginEnd = dp(3)
                    }
                })
            }
            ctrl.addView(r)
        }

        val actRow = row(first = tileViews.isEmpty() && !oPrefs.showBrightnessSlider)
        if (oPrefs.showVolume) actRow.addView(makeControlTile("Volume", { "" }, s,
            { false }, { "" }, TileStyle.ACTION) { toggleVolumePanel(oPrefs, fromLeft, panelY) })
        actRow.addView(makeControlTile("All Apps", { "" }, s,
            { false }, { "" }, TileStyle.ACTION) { showAllAppsDrawer() })
        actRow.addView(makeControlTile("Edit", { "" }, s,
            { false }, { "" }, TileStyle.ACTION) { openEditor() })
        ctrl.addView(actRow)

        val rowsCount = (tileViews.size + 2) / 3 + 1
        val estimated = dp(20) + (if (oPrefs.showBrightnessSlider) dp(54) else 0) + rowsCount * dp(70)
        controlsPanel = showSubSurface(ctrl, oPrefs, fromLeft, panelY, estimated)
        toolsButton?.setSelected(true)
    }

    private fun toggleVolumePanel(oPrefs: OverlayPrefs, fromLeft: Boolean, panelY: Int) {
        closeSubSurface(controlsPanel)
        controlsPanel = null

        volumePanel?.let {
            closeSubSurface(it)
            volumePanel = null
            toolsButton?.setSelected(false)
            return
        }

        val s = scheme()
        val am = getSystemService(AUDIO_SERVICE) as AudioManager

        val ctrl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(14), dp(8), dp(12))
        }

        val streams = listOf(
            Triple(AudioManager.STREAM_MUSIC,        "", "Media"),
            Triple(AudioManager.STREAM_RING,         "", "Ring"),
            Triple(AudioManager.STREAM_NOTIFICATION, "", "Notif"),
            Triple(AudioManager.STREAM_ALARM,        "", "Alarm")
        )

        for ((stream, icon, label) in streams) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val slider = PillSlider(
                vertical = true, icon = icon,
                initial = am.getStreamVolume(stream), min = 0, max = am.getStreamMaxVolume(stream), s = s
            ) { level ->
                // Ring/notification volume 0 flips the ringer mode, which needs DND access.
                try { am.setStreamVolume(stream, level, 0) }
                catch (e: SecurityException) { Log.w(TAG, "setStreamVolume($stream) denied", e) }
            }
            col.addView(slider, LinearLayout.LayoutParams(dp(44), dp(176)))
            col.addView(TextView(this).apply {
                text = label
                textSize = 11f
                typeface = mediumTypeface
                gravity = Gravity.CENTER
                setTextColor(s.onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            })
            ctrl.addView(col)
        }

        volumePanel = showSubSurface(ctrl, oPrefs, fromLeft, panelY, dp(240))
        toolsButton?.setSelected(true)
    }

    private fun hidePanel() {
        if (!shown) return
        shown = false
        appLoadExecutor.submit { cachedRecentApps = getRecentApps(4) }
        closeSubSurface(controlsPanel)
        controlsPanel = null
        closeSubSurface(volumePanel)
        volumePanel = null
        toolsButton = null

        panelBackdrop?.dismiss()
        panelBackdrop = null

        val glass = panelWindow
        panelWindow = null
        val handle = handleView
        val handleAlt = handleViewAlt

        fun restoreHandles() {
            if (showingDrawer) return
            for (h in listOfNotNull(handle, handleAlt)) {
                h.alpha = 0f
                h.visibility = View.VISIBLE
                h.animate().alpha(1f).setDuration(200).start()
            }
        }

        if (glass == null) {
            restoreHandles()
            return
        }
        val toX = (if (panelFromLeft) -dp(40) else dp(40)).toFloat()
        glass.close(onEnd = { restoreHandles() }) { translationX(toX).scaleX(0.8f).scaleY(0.92f) }
    }

    // ── All Apps drawer ───────────────────────────────────────────────────────

    private fun showAllAppsDrawer() {
        showingDrawer = true
        hidePanel()
        vibrate()
        val oPrefs = overlayPrefs()
        val s = scheme()
        val dm = resources.displayMetrics
        val drawerW = (dm.widthPixels * 0.86).toInt().coerceAtMost(dp(560))
        val drawerH = (dm.heightPixels * 0.72).toInt()

        drawerBackdrop = addBackdrop(0.32f) { hideAllAppsDrawer() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(12), dp(4))
        }
        header.addView(TextView(this).apply {
            text = "All apps"
            textSize = 22f
            typeface = mediumTypeface
            setTextColor(s.onSurface)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(iconText("", 20f, s.onSurfaceVariant).apply {
            background = surfaceBackground(s.surfaceContainerHighest, 20)
            contentDescription = "Close"
            isClickable = true; isFocusable = true
            setOnClickListener { hideAllAppsDrawer() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header)

        // Search bar
        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = surfaceBackground(withAlpha(s.surfaceContainerHighest, 230), 26)
            setPadding(dp(16), 0, dp(16), 0)
        }
        searchBar.addView(iconText("", 20f, s.onSurfaceVariant), LinearLayout.LayoutParams(dp(24), dp(24)))
        val searchBox = android.widget.EditText(this).apply {
            hint = "Search apps"
            textSize = 16f
            setTextColor(s.onSurface)
            setHintTextColor(s.onSurfaceVariant)
            background = null
            setPadding(dp(12), 0, 0, 0)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        }
        searchBar.addView(searchBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(searchBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { setMargins(dp(16), dp(12), dp(16), dp(8)) })

        // Scrollable grid
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(16))
        }
        grid.addView(TextView(this).apply {
            text = "Loading…"
            setTextColor(s.onSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val glass = GlassWindow(root, drawerW, drawerH, Gravity.CENTER, 0, 0,
            dp(28).toFloat(), s.surfaceContainer, blurPx(oPrefs), focusable = true)
        if (oPrefs.showKeyboardOnAllApps) {
            glass.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        // Back closes the drawer; the dialog has already dismissed itself by then.
        glass.dialog.setOnCancelListener { hideAllAppsDrawer(animate = false) }
        drawerWindow = glass

        val decor = glass.decor
        decor.alpha = 0f
        decor.scaleX = 0.92f
        decor.scaleY = 0.92f
        decor.translationY = dp(16).toFloat()
        decor.animate().alpha(1f).setDuration(160).setInterpolator(emphasizedDecelerate).start()
        decor.springTo(DynamicAnimation.SCALE_X, 1f, 500f, 0.9f)
        decor.springTo(DynamicAnimation.SCALE_Y, 1f, 500f, 0.9f)
        decor.springTo(DynamicAnimation.TRANSLATION_Y, 0f, 500f, 0.92f)
        glass.frost(delay = 60)

        if (oPrefs.showKeyboardOnAllApps) {
            searchBox.post {
                searchBox.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        val pm = packageManager
        val appList = mutableListOf<Triple<String, String, Drawable>>()
        var currentFiltered = listOf<Triple<String, String, Drawable>>()

        fun rebuildGrid(query: String) {
            val filtered = if (query.isEmpty()) appList
                           else appList.filter { it.second.lowercase().contains(query.lowercase()) }
            currentFiltered = filtered
            grid.removeAllViews()
            if (filtered.isEmpty()) {
                grid.addView(TextView(this).apply {
                    text = if (query.isEmpty()) "" else "No results"
                    setTextColor(s.onSurfaceVariant)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(40), 0, dp(40))
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                return
            }
            var row: LinearLayout? = null
            for ((i, app) in filtered.withIndex()) {
                if (i % 4 == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    grid.addView(row)
                }
                val (pkg, name, icon) = app
                row?.addView(makeAppCell(pkg, name, icon, s, showLabel = true,
                    isHighlighted = query.isNotEmpty() && i == 0) {
                    hideAllAppsDrawer()
                    doLaunch(pkg)
                })
            }
            val rem = filtered.size % 4
            if (rem != 0) repeat(4 - rem) {
                row?.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }

        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { rebuildGrid(s?.toString() ?: "") }
        })

        searchBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                currentFiltered.firstOrNull()?.let { (pkg, _, _) ->
                    hideAllAppsDrawer()
                    doLaunch(pkg)
                }
                true
            } else false
        }

        val snapshot = cachedAllApps
        if (snapshot != null) {
            appList.addAll(snapshot)
            rebuildGrid(searchBox.text.toString())
        } else {
            appLoadExecutor.submit {
                try {
                    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                    @Suppress("DEPRECATION")
                    val rawApps: List<ResolveInfo> = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
                    val entries = rawApps.sortedBy { it.loadLabel(pm).toString().lowercase() }.mapNotNull { app ->
                        val pkg = app.activityInfo.packageName
                        val name = app.loadLabel(pm).toString()
                        try { Triple(pkg, name, pm.getApplicationIcon(pkg)) }
                        catch (e: Exception) { Log.w(TAG, "No icon for $pkg", e); null }
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (drawerWindow !== glass) return@post
                        appList.addAll(entries)
                        rebuildGrid(searchBox.text.toString())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load app list", e)
                }
            }
        }
    }

    private fun hideAllAppsDrawer(animate: Boolean = true) {
        val glass = drawerWindow ?: return
        drawerWindow = null
        showingDrawer = false
        drawerBackdrop?.dismiss()
        drawerBackdrop = null
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(glass.decor.windowToken, 0)
        handleView?.visibility = View.VISIBLE
        handleViewAlt?.visibility = View.VISIBLE
        if (animate) glass.close { scaleX(0.94f).scaleY(0.94f).translationY(dp(16).toFloat()) }
        else glass.dismissNow()
    }

    // ── Control tiles ─────────────────────────────────────────────────────────

    private enum class TileStyle { TOGGLE, ACTION }

    /**
     * Quick-settings style tile. Toggles fill with primary and morph from a rounded square
     * to a pill when on; action tiles use the secondary container. Pressing squeezes the shape.
     */
    private fun makeControlTile(
        label: String,
        getIcon: () -> String,
        s: M3Scheme,
        getActive: () -> Boolean,
        getSubtitle: () -> String,
        style: TileStyle = TileStyle.TOGGLE,
        onClick: () -> Unit
    ): LinearLayout {
        val h = dp(64)
        val bg = GradientDrawable()
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            layoutParams = LinearLayout.LayoutParams(0, h, 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            }
            isClickable = true
            isFocusable = true
            contentDescription = label
        }

        val iconTv = iconText("", 20f, s.onSurface)
        val labelTv = TextView(this).apply {
            text = label
            textSize = 10f
            typeface = mediumTypeface
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(3), dp(4), 0)
        }
        val subtitleTv = TextView(this).apply {
            textSize = 9f
            gravity = Gravity.CENTER
            maxLines = 1
        }

        fun colorsFor(active: Boolean): Pair<Int, Int> = when {
            style == TileStyle.ACTION -> s.secondaryContainer to s.onSecondaryContainer
            active -> s.primary to s.onPrimary
            else -> s.surfaceContainerHighest to s.onSurface
        }
        fun applyFg(c: Int) {
            iconTv.setTextColor(c)
            labelTv.setTextColor(c)
            subtitleTv.setTextColor(withAlpha(c, 190))
        }

        var active = getActive()
        var pressed = false
        var shownColors = colorsFor(active)
        var colorAnim: ValueAnimator? = null
        bg.setColor(shownColors.first)
        applyFg(shownColors.second)

        fun targetRadius() = when {
            pressed -> dp(12).toFloat()
            active && style == TileStyle.TOGGLE -> h / 2f
            else -> dp(20).toFloat()
        }
        bg.cornerRadius = targetRadius()
        val radius = FloatSpring(targetRadius(), SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING) { bg.cornerRadius = it }

        fun refresh() {
            active = getActive()
            iconTv.text = getIcon()
            subtitleTv.text = getSubtitle()
            subtitleTv.visibility = if (subtitleTv.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            radius.animateTo(targetRadius())
            val target = colorsFor(active)
            if (target == shownColors) return
            val from = shownColors
            colorAnim?.cancel()
            colorAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = emphasizedDecelerate
                addUpdateListener { a ->
                    val t = a.animatedFraction
                    shownColors = ColorUtils.blendARGB(from.first, target.first, t) to
                            ColorUtils.blendARGB(from.second, target.second, t)
                    bg.setColor(shownColors.first)
                    applyFg(shownColors.second)
                }
                start()
            }
        }
        refresh()

        tile.setOnClickListener {
            onClick()
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 150)
        }
        var touchDownX = 0f; var touchDownY = 0f; var touchSwiped = false
        tile.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX; touchDownY = event.rawY; touchSwiped = false
                    pressed = true
                    radius.animateTo(targetRadius())
                    v.springTo(DynamicAnimation.SCALE_X, 0.94f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                    v.springTo(DynamicAnimation.SCALE_Y, 0.94f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                }
                MotionEvent.ACTION_MOVE ->
                    if (Math.abs(event.rawX - touchDownX) > dp(8) ||
                        Math.abs(event.rawY - touchDownY) > dp(8)) touchSwiped = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    radius.animateTo(targetRadius())
                    v.springTo(DynamicAnimation.SCALE_X, 1f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                    v.springTo(DynamicAnimation.SCALE_Y, 1f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                }
            }
            touchSwiped
        }

        tile.addView(iconTv)
        tile.addView(labelTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tile.addView(subtitleTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return tile
    }

    // ── Control tile state & toggles ──────────────────────────────────────────

    private fun isAutoRotateEnabled(): Boolean = try {
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
    } catch (_: Settings.SettingNotFoundException) { false }

    private fun isAutoBrightnessEnabled(): Boolean = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    } catch (_: Settings.SettingNotFoundException) { false }

    private fun toggleTorch() {
        val cid = torchCameraId ?: return
        try { cameraManager?.setTorchMode(cid, !torchEnabled) }
        catch (e: Exception) { Log.w(TAG, "Torch toggle failed", e) }
    }

    private fun toggleAutoRotate() {
        if (!Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION,
            if (isAutoRotateEnabled()) 0 else 1)
    }

    private fun toggleAutoBrightness() {
        if (!Settings.System.canWrite(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        val new = if (isAutoBrightnessEnabled()) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                  else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, new)
    }

    // ── Ringer helpers ────────────────────────────────────────────────────────

    private fun getRingerMode(): Int =
        (getSystemService(AUDIO_SERVICE) as AudioManager).ringerMode

    private fun getRingerIcon(): String = when (getRingerMode()) {
        AudioManager.RINGER_MODE_SILENT  -> "\uE7F6"  // notifications_off
        AudioManager.RINGER_MODE_VIBRATE -> "\uE62D"  // vibration
        else                             -> "\uE7F4"  // notifications
    }

    private fun getRingerSubtitle(): String = when (getRingerMode()) {
        AudioManager.RINGER_MODE_SILENT  -> "Silent"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
        else                             -> "Normal"
    }

    private fun isRingerActive(): Boolean =
        getRingerMode() != AudioManager.RINGER_MODE_NORMAL

    private fun toggleRingerMode() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val next = when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL  -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE ->
                if (nm.isNotificationPolicyAccessGranted) AudioManager.RINGER_MODE_SILENT
                else AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try { am.ringerMode = next } catch (e: Exception) { Log.w(TAG, "Ringer toggle failed", e) }
    }

    private fun launchQuickShare() {
        hidePanel()
        val candidates = listOf(
            Intent("com.google.android.gms.RECEIVE_NEARBY").setType("*/*"),
            Intent("com.google.android.gms.nearby.sharing.UNIFIED").setType("*/*"),
            Intent().setComponent(android.content.ComponentName(
                "com.google.android.gms",
                "com.google.android.gms.nearby.sharing.main.MainActivity"
            )),
            Intent("android.settings.NEARBY_SHARING_SETTINGS")
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent); return }
            catch (e: Exception) { Log.w(TAG, "QuickShare intent failed: $intent — ${e.message}") }
        }
    }

    private fun openPowerMenu() {
        hidePanel()
        val svc = SidebarAccessibilityService.instance
        if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
        else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun launchQrScanner() {
        hidePanel()
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("zxing://scan/")),
            Intent().setComponent(ComponentName("com.google.ar.lens",
                "com.google.vr.apps.ornament.app.lens.LensLauncherActivity")),
            Intent().setComponent(ComponentName("com.oppo.camera",
                "com.oppo.camera.activity.OppoCameraActivity")),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(intent); return }
            catch (e: Exception) { Log.w(TAG, "QR intent failed: ${e.message}") }
        }
    }

    private fun isDndEnabled(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun toggleDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        val next = if (isDndEnabled()) NotificationManager.INTERRUPTION_FILTER_ALL
                   else NotificationManager.INTERRUPTION_FILTER_NONE
        try { nm.setInterruptionFilter(next) } catch (e: Exception) { Log.w(TAG, "DND toggle failed", e) }
    }

    // ── Brightness helpers ────────────────────────────────────────────────────

    private fun getBrightness(): Int = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Settings.SettingNotFoundException) { 127 }

    private fun setBrightness(value: Int) {
        if (!Settings.System.canWrite(this)) {
            hidePanel()
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            return
        }
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    // ── Pill slider (brightness, volume streams) ─────────────────────────────

    /**
     * Thick pill slider in the quick-settings style, drawn by a single view: track, rounded
     * fill and icon. Dragging only invalidates (no relayout), and the fill glides to taps.
     */
    private inner class PillSlider(
        private val vertical: Boolean,
        private val icon: String,
        initial: Int,
        private val min: Int,
        private val max: Int,
        s: M3Scheme,
        private val onChange: (Int) -> Unit
    ) : View(this@SidebarOverlayService) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.surfaceContainerHighest }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.primary }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.onPrimary
            typeface = iconTypeface
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, resources.displayMetrics)
        }
        private val rect = RectF()
        private var current = initial
        private var fraction = fractionOf(initial)
        private val spring = FloatSpring(fraction, 900f, 0.9f) { fraction = it; invalidate() }

        private fun fractionOf(v: Int) = ((v - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f)

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val thickness = if (vertical) w else h
            val r = thickness / 2f
            val length = if (vertical) h else w

            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, trackPaint)

            val fill = thickness + fraction.coerceIn(0f, 1f) * (length - thickness)
            if (vertical) rect.set(0f, h - fill, w, h) else rect.set(0f, 0f, fill, h)
            canvas.drawRoundRect(rect, r, r, fillPaint)

            val cx = if (vertical) w / 2f else r
            val cy = if (vertical) h - r else h / 2f
            val fm = iconPaint.fontMetrics
            canvas.drawText(icon, cx, cy - (fm.ascent + fm.descent) / 2f, iconPaint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val thickness = (if (vertical) width else height).toFloat()
            val length = (if (vertical) height else width).toFloat()
            if (length <= thickness) return false
            val pos = if (vertical) length - e.y else e.x
            val frac = ((pos - thickness / 2f) / (length - thickness)).coerceIn(0f, 1f)
            val value = (min + frac * (max - min)).roundToInt()
            return when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (value != current) {
                        current = value
                        onChange(value)
                    }
                    // While dragging the fill tracks the finger smoothly, not in volume steps.
                    spring.animateTo(if (e.actionMasked == MotionEvent.ACTION_MOVE) frac else fractionOf(value))
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    spring.animateTo(fractionOf(current))
                    true
                }
                else -> false
            }
        }
    }

    // ── App cell ──────────────────────────────────────────────────────────────

    private fun makeAppCell(
        pkg: String, name: String, icon: Drawable, s: M3Scheme,
        showLabel: Boolean = true,
        isHighlighted: Boolean = false,
        circular: Boolean = false,
        onClick: () -> Unit = { launch(pkg) }
    ): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = name
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (isHighlighted) background = surfaceBackground(s.primaryContainer, 20)
            setOnClickListener { onClick() }
            setOnLongClickListener { showAppContextMenu(pkg, name, it); true }
        }
        val iv = ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (circular) {
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
        }
        cell.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
        // The icon (not the label) dips on press and bounces back on release.
        cell.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    iv.springTo(DynamicAnimation.SCALE_X, 0.86f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                    iv.springTo(DynamicAnimation.SCALE_Y, 0.86f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    iv.springTo(DynamicAnimation.SCALE_X, 1f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                    iv.springTo(DynamicAnimation.SCALE_Y, 1f, SPATIAL_FAST_STIFFNESS, SPATIAL_FAST_DAMPING)
                }
            }
            false
        }
        if (showLabel) {
            cell.addView(TextView(this).apply {
                text = name
                setTextColor(if (isHighlighted) s.onPrimaryContainer else s.onSurface)
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5) }
            })
        }
        return cell
    }

    private fun launch(pkg: String) {
        hidePanel()
        doLaunch(pkg)
    }

    private fun doLaunch(pkg: String) {
        vibrate()
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w(TAG, "No launch intent for $pkg")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Can't open this app", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val launchFullscreen = {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch $pkg", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, "Failed to launch app", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (overlayPrefs().freeformWindows && tryShizukuFreeformLaunch(intent) {
                Handler(Looper.getMainLooper()).post { launchFullscreen() }
            }) return
        launchFullscreen()
    }

    private fun readGlobalSetting(key: String): String {
        val proc = Shizuku.newProcess(arrayOf("settings", "get", "global", key), null, null)
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        return out
    }

    @Synchronized
    private fun enableFreeformSettings() {
        val keys = listOf("enable_freeform_support", "force_resizable_activities")
        val current = keys.associateWith { readGlobalSetting(it) }
        if (savedFreeformSettings == null) savedFreeformSettings = current
        for ((key, value) in current) {
            if (value != "1") Shizuku.newProcess(arrayOf("settings", "put", "global", key, "1"), null, null).waitFor()
        }
    }

    // Puts the device-wide freeform flags back to what they were before we touched them.
    @Synchronized
    private fun restoreFreeformSettings() {
        val saved = savedFreeformSettings ?: return
        savedFreeformSettings = null
        for ((key, value) in saved) {
            if (value == "1") continue
            val cmd = if (value == "null") arrayOf("settings", "delete", "global", key)
                      else arrayOf("settings", "put", "global", key, value)
            runCatching { Shizuku.newProcess(cmd, null, null).waitFor() }
        }
    }

    private fun tryShizukuFreeformLaunch(intent: Intent, onFailed: () -> Unit): Boolean {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku: binder not available")
                return false
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Shizuku: permission not granted — enable freeform in settings to trigger auth")
                return false
            }
            val component = intent.component?.flattenToString()
            val pkg = intent.component?.packageName
            if (component == null || pkg == null) {
                Log.w(TAG, "Shizuku: null component for $intent")
                return false
            }
            Log.d(TAG, "Shizuku: launching $component in freeform")
            Thread {
                try {
                    enableFreeformSettings()
                    val proc = Shizuku.newProcess(
                        arrayOf("am", "start", "--windowingMode", "5", "-n", component),
                        null, null
                    )
                    val exit = proc.waitFor()
                    val err = proc.errorStream.bufferedReader().readText()
                    Log.d(TAG, "Shizuku: am start exit=$exit err='$err'")
                    if (exit != 0) {
                        onFailed()
                    } else {
                        Thread.sleep(900)
                        val taskInfo = findFreeformTask(pkg)
                        Log.d(TAG, "Shizuku: freeform task for $pkg = $taskInfo")
                        if (taskInfo != null) {
                            val (taskId, rootTaskId, bounds) = taskInfo
                            Shizuku.newProcess(arrayOf("am", "task", "resizeable", "$taskId", "2"), null, null).waitFor()
                            // Move into desktop windowing so tapping a background fullscreen app
                            // just unfocuses this task rather than finishing its activity.
                            runCatching {
                                Shizuku.newProcess(arrayOf("wm", "shell", "desktopmode", "moveToDesktop", "$taskId"), null, null).waitFor()
                            }.onFailure { Log.w(TAG, "desktopmode moveToDesktop unavailable: ${it.message}") }
                            Handler(Looper.getMainLooper()).post {
                                freeformComponents[taskId] = component
                                freeformRootTasks[taskId] = rootTaskId
                                attachFreeformChrome(taskId, pkg, bounds)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Shizuku freeform process failed: ${e.message}")
                    onFailed()
                }
            }.start()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku freeform launch failed: ${e.message}")
            return false
        }
    }

    // ── Freeform Chrome ───────────────────────────────────────────────────────

    private fun findFreeformTask(pkg: String): Triple<Int, Int, Rect>? = try {
        val proc = Shizuku.newProcess(arrayOf("am", "stack", "list"), null, null)
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val blocks = output.split(Regex("(?=RootTask id=)"))
        var result: Triple<Int, Int, Rect>? = null
        for (block in blocks) {
            if (!block.contains("mWindowingMode=freeform")) continue
            val taskLine = block.lines().firstOrNull { it.contains("taskId=") && it.contains(pkg) } ?: continue
            val tid = Regex("""taskId=(\d+)""").find(taskLine)?.groupValues?.get(1)?.toInt() ?: continue
            val rid = Regex("""RootTask id=(\d+)""").find(block)?.groupValues?.get(1)?.toInt() ?: tid
            val bm  = Regex("""bounds=\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(taskLine) ?: continue
            result = Triple(tid, rid, Rect(bm.groupValues[1].toInt(), bm.groupValues[2].toInt(),
                                           bm.groupValues[3].toInt(), bm.groupValues[4].toInt()))
        }
        result
    } catch (e: Exception) { Log.w(TAG, "findFreeformTask: ${e.message}"); null }

    private fun attachFreeformChrome(taskId: Int, pkg: String, bounds: Rect) {
        if (freeformTitles.containsKey(taskId)) return
        val appName = try { packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        val appIcon: Drawable? = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
        val titleH  = dp(44)
        val resizeSz = dp(32)
        val s = scheme()

        // Title bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                setColor(withAlpha(s.surfaceContainerHigh, 240))
                cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        appIcon?.let {
            bar.addView(ImageView(this).apply {
                setImageDrawable(it)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
        }
        bar.addView(TextView(this).apply {
            text = appName; textSize = 13f
            typeface = mediumTypeface
            setTextColor(s.onSurface)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(iconText("\uE5D0", 18f, s.onSurfaceVariant).apply {
            contentDescription = "Maximize"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                val component = freeformComponents[taskId]
                removeFreeformChrome(taskId)
                Thread {
                    runCatching {
                        if (component != null) {
                            Shizuku.newProcess(arrayOf("am", "start", "--windowingMode", "1", "-n", component), null, null).waitFor()
                        }
                    }
                }.start()
            }
        })
        bar.addView(iconText("\uE5CD", 18f, s.onSurfaceVariant).apply {
            contentDescription = "Close"
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                val rootTaskId = freeformRootTasks[taskId] ?: taskId
                removeFreeformChrome(taskId)
                // Same as swiping the task away in Recents; force-stop would kill the app and lose state.
                Thread { runCatching { Shizuku.newProcess(arrayOf("am", "stack", "remove", "$rootTaskId"), null, null).waitFor() } }.start()
            }
        })

        val tp = WindowManager.LayoutParams(
            bounds.width(), titleH, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.TOP; x = bounds.left; y = bounds.top - titleH }
        wm.addView(bar, tp)

        var dX = 0f; var dY = 0f; var dL = 0; var dT = 0
        bar.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    Thread { runCatching { Shizuku.newProcess(
                        arrayOf("wm", "shell", "desktopmode", "moveTaskToFront", "$taskId"),
                        null, null).waitFor() } }.start()
                    dX = ev.rawX; dY = ev.rawY
                    val b = freeformBounds[taskId] ?: bounds; dL = b.left; dT = b.top; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - dX).toInt(); val dy = (ev.rawY - dY).toInt()
                    freeformTitleParams[taskId]?.let  { p -> p.x = dL + dx; p.y = dT - titleH + dy; runCatching { wm.updateViewLayout(bar, p) } }
                    freeformResizeParams[taskId]?.let { p ->
                        val b = freeformBounds[taskId] ?: return@let
                        p.x = dL + dx + b.width() - resizeSz; p.y = dT + dy + b.height() - resizeSz
                        freeformResizes[taskId]?.let { rv -> runCatching { wm.updateViewLayout(rv, p) } }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = (ev.rawX - dX).toInt(); val dy = (ev.rawY - dY).toInt()
                    val b = freeformBounds[taskId] ?: return@setOnTouchListener true
                    val nb = Rect(dL + dx, dT + dy, dL + dx + b.width(), dT + dy + b.height())
                    freeformBounds[taskId] = nb
                    freeformTitleParams[taskId]?.let  { p -> p.x = nb.left; p.y = nb.top - titleH; runCatching { wm.updateViewLayout(bar, p) } }
                    freeformResizeParams[taskId]?.let { p ->
                        p.x = nb.right - resizeSz; p.y = nb.bottom - resizeSz
                        freeformResizes[taskId]?.let { rv -> runCatching { wm.updateViewLayout(rv, p) } }
                    }
                    Thread { runCatching { Shizuku.newProcess(arrayOf("am", "task", "resize", "$taskId",
                        "${nb.left}", "${nb.top}", "${nb.right}", "${nb.bottom}"), null, null).waitFor() } }.start()
                    true
                }
                else -> false
            }
        }

        // Resize handle
        val rh = View(this).apply {
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(withAlpha(s.primary, 200)) }
        }
        val rp = WindowManager.LayoutParams(
            resizeSz, resizeSz, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.START or Gravity.TOP; x = bounds.right - resizeSz; y = bounds.bottom - resizeSz }
        wm.addView(rh, rp)

        var rX = 0f; var rY = 0f; var rR = 0; var rB = 0
        rh.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    rX = ev.rawX; rY = ev.rawY
                    val b = freeformBounds[taskId] ?: return@setOnTouchListener false; rR = b.right; rB = b.bottom; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - rX).toInt(); val dy = (ev.rawY - rY).toInt()
                    freeformResizeParams[taskId]?.let { p -> p.x = rR + dx - resizeSz; p.y = rB + dy - resizeSz; runCatching { wm.updateViewLayout(rh, p) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = (ev.rawX - rX).toInt(); val dy = (ev.rawY - rY).toInt()
                    val b = freeformBounds[taskId] ?: return@setOnTouchListener true
                    val minPx = dp(220)
                    val nb = Rect(b.left, b.top, maxOf(b.left + minPx, rR + dx), maxOf(b.top + minPx, rB + dy))
                    freeformBounds[taskId] = nb
                    freeformTitleParams[taskId]?.let  { p -> p.width = nb.width(); p.x = nb.left; runCatching { wm.updateViewLayout(bar, p) } }
                    freeformResizeParams[taskId]?.let { p -> p.x = nb.right - resizeSz; p.y = nb.bottom - resizeSz; runCatching { wm.updateViewLayout(rh, p) } }
                    Thread { runCatching { Shizuku.newProcess(arrayOf("am", "task", "resize", "$taskId",
                        "${nb.left}", "${nb.top}", "${nb.right}", "${nb.bottom}"), null, null).waitFor() } }.start()
                    true
                }
                else -> false
            }
        }

        freeformTitles[taskId]       = bar
        freeformTitleParams[taskId]  = tp
        freeformResizes[taskId]      = rh
        freeformResizeParams[taskId] = rp
        freeformBounds[taskId]       = bounds
        freeformPkgs[taskId]         = pkg
        if (freeformTitles.size == 1) {
            chromePollHandler.postDelayed(chromePollRunnable, 2000)
            // Enable desktop mode globally as a fallback; keeps freeform tasks alive
            // when a fullscreen app takes focus (moveToDesktop per-task may not exist on all builds).
            Thread { runCatching { Shizuku.newProcess(arrayOf("wm", "shell", "desktopmode", "active", "1"), null, null).waitFor() } }.start()
        }
    }

    private fun removeFreeformChrome(taskId: Int) {
        freeformTitles.remove(taskId)?.let       { runCatching { wm.removeView(it) } }
        freeformResizes.remove(taskId)?.let      { runCatching { wm.removeView(it) } }
        freeformTitleParams.remove(taskId)
        freeformResizeParams.remove(taskId)
        freeformBounds.remove(taskId)
        freeformPkgs.remove(taskId)
        freeformComponents.remove(taskId)
        freeformRootTasks.remove(taskId)
        if (freeformTitles.isEmpty()) {
            chromePollHandler.removeCallbacks(chromePollRunnable)
            Thread {
                runCatching { Shizuku.newProcess(arrayOf("wm", "shell", "desktopmode", "active", "0"), null, null).waitFor() }
                restoreFreeformSettings()
            }.start()
        }
    }

    private fun pollFreeformTasks() {
        if (freeformTitles.isEmpty()) return
        val ids = freeformTitles.keys.toList()
        val bounds = ids.mapNotNull { id -> freeformBounds[id]?.let { id to Rect(it) } }.toMap()
        Thread {
            try {
                val proc = Shizuku.newProcess(arrayOf("am", "stack", "list"), null, null)
                val output = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                val gone = mutableListOf<Int>()
                val behind = mutableListOf<Int>()
                for (id in ids) {
                    val line = output.lines().firstOrNull { it.contains("taskId=$id ") || it.endsWith("taskId=$id") }
                    if (line == null) gone.add(id) else if (line.contains("visible=false")) behind.add(id)
                }
                if (gone.isNotEmpty()) Handler(Looper.getMainLooper()).post { gone.forEach { removeFreeformChrome(it) } }
                for (id in behind) {
                    val b = bounds[id] ?: continue
                    runCatching { Shizuku.newProcess(arrayOf("am", "task", "resizeable", "$id", "2"), null, null).waitFor() }
                    runCatching { Shizuku.newProcess(arrayOf("am", "task", "resize", "$id",
                        "${b.left}", "${b.top}", "${b.right}", "${b.bottom}"), null, null).waitFor() }
                }
            } catch (_: Exception) {}
        }.start()
    }

    private var menuWindow: GlassWindow? = null
    private var menuBackdrop: Backdrop? = null

    private fun showAppContextMenu(pkg: String, name: String, anchor: View) {
        hideContextMenu()
        val s = scheme()
        val oPrefs = overlayPrefs()

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val dm = resources.displayMetrics
        val menuW = dp(200)
        val mx = (loc[0] + anchor.width / 2 - menuW / 2).coerceIn(dp(8), dm.widthPixels - menuW - dp(8))
        val my = (loc[1] + anchor.height / 2).coerceIn(dp(8), dm.heightPixels - dp(160))

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        menu.addView(TextView(this).apply {
            text = name
            textSize = 12f
            typeface = mediumTypeface
            setTextColor(s.onSurfaceVariant)
            setPadding(dp(16), dp(4), dp(16), dp(8))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        fun item(icon: String, label: String, action: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(withAlpha(s.onSurface, 30)), null,
                ColorDrawable(Color.WHITE)
            )
            addView(iconText(icon, 20f, s.onSurfaceVariant), LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(TextView(this@SidebarOverlayService).apply {
                text = label
                textSize = 14f
                setTextColor(s.onSurface)
                setPadding(dp(12), 0, 0, 0)
            })
            setOnClickListener { hideContextMenu(); action() }
        }
        menu.addView(item("", "Open") {
            if (showingDrawer) hideAllAppsDrawer()
            launch(pkg)
        })
        menu.addView(item("", "App info") {
            if (showingDrawer) hideAllAppsDrawer()
            hidePanel()
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })

        menuBackdrop = Backdrop(0f, 0) { hideContextMenu() }
        val glass = GlassWindow(menu, menuW, WindowManager.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START, mx, my, dp(16).toFloat(), s.surfaceContainer, blurPx(oPrefs))
        menuWindow = glass
        val decor = glass.decor
        decor.pivotX = menuW / 2f
        decor.pivotY = 0f
        decor.alpha = 0f
        decor.scaleX = 0.92f
        decor.scaleY = 0.7f
        decor.animate().alpha(1f).setDuration(120).setInterpolator(emphasizedDecelerate).start()
        decor.springTo(DynamicAnimation.SCALE_X, 1f, 700f, 0.9f)
        decor.springTo(DynamicAnimation.SCALE_Y, 1f, 700f, 0.9f)
        glass.frost()
    }

    private fun hideContextMenu() {
        menuWindow?.dismissNow()
        menuWindow = null
        menuBackdrop?.let { runCatching { it.dialog.dismiss() } }
        menuBackdrop = null
    }

    private fun prefetchAppData() {
        try {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            @Suppress("DEPRECATION")
            val entries = pm.queryIntentActivities(intent, PackageManager.GET_META_DATA)
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .mapNotNull { app ->
                    val pkg = app.activityInfo.packageName
                    val name = app.loadLabel(pm).toString()
                    try {
                        val icon = pm.getApplicationIcon(pkg)
                        appIconCache[pkg] = Pair(name, icon)
                        Triple(pkg, name, icon)
                    } catch (e: Exception) { null }
                }
            cachedAllApps = entries
        } catch (e: Exception) {
            Log.e(TAG, "Prefetch failed", e)
        }
        cachedRecentApps = getRecentApps(4)
    }

    private fun loadFavorites(): List<String> {
        val json = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
            .getString(Prefs.FAVORITES, "[]") ?: "[]"
        return buildList {
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) add(arr.getString(i))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse favorites JSON", e)
            }
        }
    }

    private fun getRecentApps(count: Int): List<String> {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        if (mode != AppOpsManager.MODE_ALLOWED) return emptyList()
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val start = end - 7 * 24 * 60 * 60 * 1000L
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .filter { it.packageName != packageName && it.lastTimeUsed > 0 }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }
            .filter { pkg -> appIconCache.containsKey(pkg) }
            .distinct()
            .take(count)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isSystemFullscreen(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = wm.currentWindowMetrics.windowInsets
                .getInsets(android.view.WindowInsets.Type.systemBars())
            insets.top == 0 && insets.bottom == 0
        } else {
            val real = resources.displayMetrics.heightPixels
            val app = DisplayMetrics().also {
                @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(it)
            }
            app.heightPixels >= real
        }
    } catch (_: Exception) { false }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("sidebar_svc", "Floating Panel", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, "sidebar_svc")
            .setContentTitle("Floating Panel")
            .setContentText("Pull-tab active on screen edge")
            .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class PullTabDrawable(color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val rect = RectF()
        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            canvas.drawRoundRect(rect, rect.width() / 2f, rect.width() / 2f, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val TAG = "SidebarOverlayService"
        private const val SHIZUKU_PERM_CODE = 1001

        // Spring tokens, close to critically damped so nothing visibly bounces.
        private const val SPATIAL_STIFFNESS = 500f
        private const val SPATIAL_DAMPING = 0.9f
        private const val SPATIAL_FAST_STIFFNESS = 900f
        private const val SPATIAL_FAST_DAMPING = 0.78f

        /** Surface alpha once frosted: enough tint to read, enough glass to see the blur. */
        private const val FROST_ALPHA = 165
    }
}
