// Copyright (C) 2026 Elisabetta Nova. GPL-2.0-or-later, see LICENSE.
package vr.gnaural

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SystemBase
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature

/**
 * Presets from the EEG band each state is associated with. The bands describe states; whether pulsing
 * at a band's rate brings the state on is weakly supported (Ingendoh et al. 2023, PLOS ONE).
 */
private enum class Mode(val label: Int, val note: Int, val beatHz: Double, val baseHz: Double, val flash: Boolean) {
    VISUALS(R.string.mode_visuals, R.string.note_visuals, 10.0, 200.0, true),
    MEDITATE(R.string.mode_meditate, R.string.note_meditate, 6.0, 200.0, true),
    DREAM(R.string.mode_dream, R.string.note_dream, 4.0, 150.0, true),
    FOCUS(R.string.mode_focus, R.string.note_focus, 14.0, 300.0, true),
    SLEEP(R.string.mode_sleep, R.string.note_sleep, 2.0, 100.0, false),
}

/** A paper on the about screen: who and where, what it found that this app uses, and a link to it. */
private class Paper(val cite: String, val finding: String, val url: String)

private val PAPERS = listOf(
    Paper("Amaya et al. 2023 · PLOS ONE", "steady 10 Hz flicker gave the strongest patterns; irregular flicker much weaker. light on 30% of each cycle. funded by Lumenate.", "https://pmc.ncbi.nlm.nih.gov/articles/PMC10089352/"),
    Paper("Bartossek, Kemmerer & Schmidt 2021 · PLOS ONE", "10 Hz beat 3 Hz for vivid simple visions; people who get absorbed easily saw more.", "https://pmc.ncbi.nlm.nih.gov/articles/PMC8248711/"),
    Paper("Shenyan et al. 2024 · Scientific Reports", "eyes-open red and black screen flicker at 10 Hz gave many simple visions, peaking after about 1.5 minutes.", "https://pmc.ncbi.nlm.nih.gov/articles/PMC10825158/"),
    Paper("Königsmark, Bergmann & Reeder 2021 · Cortex", "Ganzflicker: the eyes-open red and black flicker this app follows.", "https://research.edgehill.ac.uk/en/publications/ganzflicker-reveals-the-complex-relationship-between-visual-menta/"),
    Paper("Pearson et al. 2016 · eLife", "flicker visions peak near 11 Hz; flicker out of step between the eyes reads as double the rate.", "https://elifesciences.org/articles/17072"),
    Paper("Ingendoh, Posny & Heine 2023 · PLOS ONE", "review of binaural beats: 5 of 14 studies supported brain entrainment, 8 did not.", "https://pmc.ncbi.nlm.nih.gov/articles/PMC10198548/"),
    Paper("Bierman et al. 2011 · J. Biomedical Optics", "eyelids pass about 9% of red light and almost no green or blue: why the flash is red.", "https://www.lrc.rpi.edu/resources/newsroom/pdf/2011/eyelid8511.pdf"),
    Paper("Gnaural · Bret Logan · GPL", "the sound engine this app translates to Kotlin.", "https://gnaural.sourceforge.net/"),
)

class MainActivity : AppSystemActivity() {
    private val audio = Audio()
    private val handler = Handler(Looper.getMainLooper())
    private var flashSurface: Surface? = null
    private var startPanel: Entity? = null
    private var sessionPanel: Entity? = null
    private var startUi: View? = null
    private var sessionUi: View? = null
    private var mode: Mode? = Mode.VISUALS
    private var endsAtMs = 0L
    private var lengthMs = 0L
    private var sessionShown = false

    // Written on the UI thread, read by the systems every tick. null means "no session".
    @Volatile private var engine: Engine? = null
    @Volatile private var flash = true

    override fun registerFeatures(): List<SpatialFeature> = listOf(VRFeature(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        systemManager.registerSystem(FlashSystem())
        systemManager.registerSystem(ControllerSystem())
    }

    override fun onSceneReady() {
        super.onSceneReady()
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        scene.setLightingEnvironment(Vector3(0f), Vector3(0f), Vector3(0f, -1f, 0f))
        Entity.create(listOf(Panel(R.id.flash_layer), Transform(), Visible(true)))
        // FlashSystem moves the start panel in front of the head once tracking starts (placeInFront).
        val ahead = Pose(Vector3(0f, 1.2f, -1.2f), Quaternion(0f, 180f, 0f))
        startPanel = Entity.createPanelEntity(R.layout.start, Transform(ahead))
        sessionPanel = Entity.createPanelEntity(R.layout.session, Transform(ahead)).apply { setComponent(Visible(false)) }
        // A 10 Hz period is 100 ms: 12 frames at 120 Hz, 8 at 80, but 7.2 at the 72 Hz default.
        if (!scene.setPreferredDisplayRate(120f)) scene.setPreferredDisplayRate(80f)
    }

    // Both panels are 640 dp wide on a 1 m panel, the size the design was drawn at.
    override fun registerPanels() = listOf(
        // The flash: a 360° layer behind everything, its left half shown to the left eye and its right half
        // to the right eye, so each eye can be lit on its own (StereoMode.LeftRight). A plain skybox can't
        // do that: both eyes see the same mesh.
        VideoSurfacePanelRegistration(
            R.id.flash_layer,
            surfaceConsumer = { _, surface ->
                flashSurface = surface
                paint(left = false, right = false)
            },
            settingsCreator = {
                MediaPanelSettings(
                    shape = Equirect360ShapeOptions(radius = 300f),
                    display = PixelDisplayOptions(width = 64, height = 32),
                    rendering = MediaPanelRenderOptions(stereoMode = StereoMode.LeftRight, zIndex = -1),
                )
            },
        ),
        PanelRegistration(R.layout.start) {
            config {
                width = 1.0f
                height = 1.5f
                layoutWidthInDp = 640f
                layoutHeightInDp = 960f
                themeResourceId = R.style.PanelTransparent
            }
            panel { rootView?.let(::bindStart) }
        },
        PanelRegistration(R.layout.session) {
            config {
                width = 1.0f
                height = 0.656f
                layoutWidthInDp = 640f
                layoutHeightInDp = 420f
                themeResourceId = R.style.PanelTransparent
            }
            panel { rootView?.let(::bindSession) }
        },
    )

    override fun onPause() {
        stop() // taking the headset off or opening the Meta menu ends the session
        super.onPause()
    }

    private fun bindStart(root: View) {
        startUi = root
        // The warning comes first on every launch; only "i understand" leads to the menu.
        root.findViewById<View>(R.id.accept).setOnClickListener { showScreen(root, R.id.menu) }
        root.findViewById<View>(R.id.exit).setOnClickListener { finish() }
        root.findViewById<View>(R.id.open_about).setOnClickListener { showScreen(root, R.id.about) }
        root.findViewById<View>(R.id.close_about).setOnClickListener { showScreen(root, R.id.menu) }
        val papers = root.findViewById<LinearLayout>(R.id.papers)
        for (paper in PAPERS) {
            val row = layoutInflater.inflate(R.layout.paper, papers, false)
            row.findViewById<TextView>(R.id.cite).text = paper.cite
            row.findViewById<TextView>(R.id.finding).text = paper.finding
            row.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(paper.url))) }
            papers.addView(row)
        }
        val modes = root.findViewById<LinearLayout>(R.id.modes)
        for (m in Mode.entries) {
            val pill = layoutInflater.inflate(R.layout.mode_pill, modes, false)
            pill.findViewById<TextView>(R.id.hz).text = m.beatHz.toInt().toString()
            pill.findViewById<TextView>(R.id.name).setText(m.label)
            pill.setOnClickListener { applyMode(m) }
            if (m.ordinal > 0) (pill.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
            modes.addView(pill)
        }
        root.slider(R.id.beat, R.id.beat_value) { getString(R.string.hz, hzText(beatHz(it))) }
        root.slider(R.id.base, R.id.base_value) { getString(R.string.hz, baseHz(it).toInt().toString()) }
        root.slider(R.id.minutes, R.id.minutes_value) { getString(R.string.min, it + 1) }
        root.findViewById<View>(R.id.flash).setOnClickListener {
            it.isActivated = !it.isActivated
            flash = it.isActivated
        }
        root.findViewById<View>(R.id.alternate).setOnClickListener {
            it.isActivated = !it.isActivated
            engine?.alternate = it.isActivated
        }
        root.findViewById<View>(R.id.sound_beeps).setOnClickListener { setBinaural(root, false) }
        root.findViewById<View>(R.id.sound_binaural).setOnClickListener { setBinaural(root, true) }
        setBinaural(root, false)
        root.findViewById<Button>(R.id.start).setOnClickListener { if (engine == null) start() else stop() }
        root.findViewById<SeekBar>(R.id.minutes).progress = 19
        applyMode(Mode.VISUALS)
    }

    private fun showScreen(root: View, screen: Int) {
        for (id in listOf(R.id.warning, R.id.menu, R.id.about)) {
            root.findViewById<View>(id).visibility = if (id == screen) View.VISIBLE else View.GONE
        }
    }

    /** Binaural beats are a steady tone in each ear, so there is nothing to cross: that switch greys out. */
    private fun setBinaural(root: View, on: Boolean) {
        root.findViewById<View>(R.id.sound_beeps).isSelected = !on
        root.findViewById<View>(R.id.sound_binaural).isSelected = on
        root.findViewById<View>(R.id.alternate).apply {
            isEnabled = !on
            alpha = if (on) 0.4f else 1f
        }
        engine?.binaural = on
    }

    private fun bindSession(root: View) {
        sessionUi = root
        root.findViewById<Button>(R.id.hide).setOnClickListener { showSession(false) }
        root.findViewById<Button>(R.id.stop).setOnClickListener { stop() }
    }

    /** Shows a slider's value and, during a session, applies it to the engine at once. */
    private fun View.slider(bar: Int, value: Int, format: (Int) -> String) {
        val label = findViewById<TextView>(value)
        findViewById<SeekBar>(bar).setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = format(progress)
                val beat = beatHz(findViewById<SeekBar>(R.id.beat).progress)
                val base = baseHz(findViewById<SeekBar>(R.id.base).progress)
                if (mode?.let { it.beatHz != beat || it.baseHz != base } == true) showMode(null)
                engine?.let {
                    it.beatHz = beat
                    it.baseHz = base
                }
                refresh()
            }
            override fun onStartTrackingTouch(s: SeekBar) = Unit
            override fun onStopTrackingTouch(s: SeekBar) = Unit
        })
        label.text = format(findViewById<SeekBar>(bar).progress)
    }

    private fun applyMode(m: Mode) {
        val root = startUi ?: return
        root.findViewById<SeekBar>(R.id.beat).progress = ((m.beatHz - 0.5) / 0.5).toInt()
        root.findViewById<SeekBar>(R.id.base).progress = ((m.baseHz - 40) / 10).toInt()
        root.findViewById<View>(R.id.flash).isActivated = m.flash
        flash = m.flash
        showMode(m)
    }

    private fun showMode(m: Mode?) {
        mode = m
        val root = startUi ?: return
        val pills = root.findViewById<LinearLayout>(R.id.modes)
        for (i in 0 until pills.childCount) pills.getChildAt(i).isSelected = Mode.entries[i] == m
        root.findViewById<TextView>(R.id.mode_note).setText(m?.note ?: R.string.note_custom)
    }

    private fun start() {
        val root = startUi ?: return
        val minutes = root.findViewById<SeekBar>(R.id.minutes).progress + 1
        val e = Engine(
            Audio.SAMPLE_RATE,
            baseHz = baseHz(root.findViewById<SeekBar>(R.id.base).progress),
            beatHz = beatHz(root.findViewById<SeekBar>(R.id.beat).progress),
        ).apply {
            alternate = root.findViewById<View>(R.id.alternate).isActivated
            binaural = root.findViewById<View>(R.id.sound_binaural).isSelected
        }
        audio.start(e)
        engine = e
        lengthMs = minutes * 60_000L
        endsAtMs = SystemClock.uptimeMillis() + lengthMs
        root.findViewById<Button>(R.id.start).apply { setText(R.string.stop); isActivated = true }
        startPanel?.setComponent(Visible(false))
        handler.postAtTime(::stop, endsAtMs)
        handler.post(ticker)
        Log.i(TAG, "start beat=${e.beatHz} base=${e.baseHz} min=$minutes display=${scene.getConfirmedFrameRate()}Hz")
    }

    private fun stop() {
        if (engine == null) return
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        engine = null
        startUi?.findViewById<Button>(R.id.start)?.apply { setText(R.string.start); isActivated = false }
        showSession(false)
        placeInFront(startPanel)
        startPanel?.setComponent(Visible(true))
        refresh()
        Log.i(TAG, "stop audioClock=${audio.frame0Ns != Long.MAX_VALUE} display=${scene.getConfirmedFrameRate()}Hz")
    }

    // Kept here, not read back from the entity: reading Visible from a panel that never had it set
    // throws inside the SDK (the A-button crash of 2026-10-28).
    private fun showSession(show: Boolean) {
        sessionShown = show
        if (show) {
            refresh()
            placeInFront(sessionPanel)
        }
        sessionPanel?.setComponent(Visible(show))
    }

    /**
     * Puts a panel 1.2 m ahead of where the head faces, level, turned toward the viewer. A fixed spot
     * in the room put the panel behind the user whenever they launched facing another way (2026-10-28).
     */
    private fun placeInFront(entity: Entity?) {
        val head = scene.getViewerPose()
        val ahead = head.forward().apply { y = 0f }.normalize().times(1.2f)
        entity?.setComponent(Transform(Pose(head.t.plus(ahead), Quaternion.lookRotationAroundY(ahead))))
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    /** Updates the "now playing" pill on the start panel and everything on the session panel. */
    private fun refresh() {
        val root = startUi ?: return
        val beat = getString(R.string.hz, hzText(beatHz(root.findViewById<SeekBar>(R.id.beat).progress)))
        root.findViewById<TextView>(R.id.now).text = beat
        val s = sessionUi ?: return
        val leftMs = (endsAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0)
        s.findViewById<TextView>(R.id.live).text = getString(R.string.live, getString(mode?.label ?: R.string.custom))
        s.findViewById<TextView>(R.id.time_left).text = getString(R.string.left, leftMs / 60_000, leftMs / 1000 % 60)
        s.findViewById<TextView>(R.id.live_beat).text = bigHz(hzText(beatHz(root.findViewById<SeekBar>(R.id.beat).progress)))
        s.findViewById<TextView>(R.id.live_base).text = bigHz(baseHz(root.findViewById<SeekBar>(R.id.base).progress).toInt().toString())
        if (lengthMs > 0) s.findViewById<ProgressBar>(R.id.progress).progress = (1000 - leftMs * 1000 / lengthMs).toInt()
    }

    /** "10.5 Hz" with the unit at half size, as on the design's session panel. */
    private fun bigHz(number: String) = SpannableString("$number Hz").apply {
        setSpan(RelativeSizeSpan(0.46f), number.length, length, 0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun step(bar: Int, by: Int) {
        startUi?.findViewById<SeekBar>(bar)?.let { it.progress += by }
    }

    private fun paint(left: Boolean, right: Boolean) {
        val surface = flashSurface ?: return
        val canvas = surface.lockHardwareCanvas()
        canvas.drawColor(if (left) RED else BLACK)
        canvas.clipRect(canvas.width / 2, 0, canvas.width, canvas.height)
        canvas.drawColor(if (right) RED else BLACK)
        surface.unlockCanvasAndPost(canvas)
    }

    /**
     * Lights the eyes from the frame the headphones are playing, so light and pulse match: both eyes
     * during a pulse, or, when the ears alternate, the eye opposite the ear that has the tone.
     */
    private inner class FlashSystem : SystemBase() {
        private var litLeft = false
        private var litRight = false
        private var placed = false

        override fun execute() {            // The head pose is still the floor origin on the first ticks; wait until it has a height.
            if (!placed && scene.getViewerPose().t.y > 0.5f) {
                placed = true
                placeInFront(startPanel)
            }
            val frame = audio.heardFrame(System.nanoTime())
            val r = engine?.rhythm?.at(frame)
            val on = flash && r != null && r.isOn(frame)
            val leftEar = r != null && r.alternate && r.leftEar(frame)
            val left = on && (r?.alternate != true || !leftEar)
            val right = on && (r?.alternate != true || leftEar)
            if (left == litLeft && right == litRight) return
            litLeft = left
            litRight = right
            paint(left, right)
        }
    }

    /** One slider step per flick: right stick the pulse, left stick the pitch. A toggles the session panel, B stops. */
    private inner class ControllerSystem : SystemBase() {
        override fun execute() {
            for (entity in Query.where { has(Controller.id) }.eval()) {
                val c = entity.getComponent<Controller>()
                if (!c.isActive) continue
                val pressed = c.changedButtons and c.buttonState
                if (pressed == 0) continue
                runOnUiThread {
                    if (pressed and ButtonBits.ButtonThumbRU != 0) step(R.id.beat, 1)
                    if (pressed and ButtonBits.ButtonThumbRD != 0) step(R.id.beat, -1)
                    if (pressed and ButtonBits.ButtonThumbLU != 0) step(R.id.base, 1)
                    if (pressed and ButtonBits.ButtonThumbLD != 0) step(R.id.base, -1)
                    if (pressed and ButtonBits.ButtonB != 0) stop()
                    if (pressed and ButtonBits.ButtonA != 0 && engine != null) showSession(!sessionShown)
                }
            }
        }
    }

    private companion object {
        const val TAG = "gnaural"
        const val BLACK = Color.BLACK
        // Red on black, as in Ganzflicker (Königsmark, Bergmann and Reeder 2021), which is viewed eyes open.
        const val RED = Color.RED

        fun beatHz(progress: Int) = 0.5 + progress * 0.5
        fun hzText(hz: Double) = if (hz % 1.0 == 0.0) hz.toInt().toString() else hz.toString()
        fun baseHz(progress: Int) = 40.0 + progress * 10
    }
}
