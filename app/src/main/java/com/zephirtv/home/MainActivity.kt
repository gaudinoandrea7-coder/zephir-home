package com.zephirtv.home

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.tv.TvContract
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** Schermata iniziale minimale in stile Apple TV: sfondo naturale a rotazione + dock con app preferite. */
class MainActivity : Activity() {

    private class AppEntry(val pkg: String, val label: String, val intent: Intent, val icon: Drawable)

    private lateinit var prefs: SharedPreferences
    private lateinit var bgA: ImageView
    private lateinit var bgB: ImageView
    private lateinit var dock: LinearLayout
    private lateinit var dockScroll: ScrollView
    private var firstTile: View? = null

    /** App nascoste dal launcher (non disinstallate): se il nome del pacchetto o il titolo contiene una di queste parole. */
    private val hiddenKeywords = listOf("scores365", "365scores")
    private var frontIsA = true
    private var shown: Bitmap? = null
    private var wallpapers: List<File> = emptyList()
    private var wallIndex = 0
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var allDialog: Dialog? = null

    private val rotateRunnable = Runnable { rotate(false) }

    private val defaultFavs = listOf(
        "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.spotify.tv.android", "org.xbmc.kodi", "com.plexapp.android",
        "com.google.android.apps.tv.launcherx", "com.android.tv.settings"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        prefs = getSharedPreferences("zephir_home", Context.MODE_PRIVATE)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        bgA = bg(); bgB = bg(); bgB.alpha = 0f
        root.addView(bgA); root.addView(bgB)

        // Velatura in basso per leggibilita' del dock
        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xAA000000.toInt())
        )
        root.addView(scrim, FrameLayout.LayoutParams(-1, resources.displayMetrics.heightPixels * 2 / 5, Gravity.BOTTOM))

        // Barra in alto: Wi-Fi, Bluetooth, Impostazioni, ora
        val topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.HORIZONTAL
        topBar.gravity = Gravity.CENTER_VERTICAL
        topBar.setPadding(dp(16), dp(8), dp(22), dp(8))
        topBar.background = GradientDrawable().apply { setColor(0x66121820); cornerRadius = dp(30).toFloat() }
        topBar.addView(topButton(GlyphView(this, GlyphView.WIFI), Settings.ACTION_WIFI_SETTINGS))
        topBar.addView(topButton(GlyphView(this, GlyphView.BT), Settings.ACTION_BLUETOOTH_SETTINGS))
        topBar.addView(topButton(GlyphView(this, GlyphView.GEAR), Settings.ACTION_SETTINGS))
        val time = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextColor(Color.WHITE); textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(14), 0, 0, 0)
        }
        topBar.addView(time)
        val tlp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        tlp.setMargins(0, dp(28), dp(48), 0)
        root.addView(topBar, tlp)

        // Griglia delle app: 4 per riga, una nuova riga ogni 4 app in piu'.
        // Sta dentro una vista scorrevole: se le righe sono troppe scorre verticalmente seguendo la selezione.
        dock = LinearLayout(this)
        dock.orientation = LinearLayout.VERTICAL
        dock.setPadding(dp(22), dp(20), dp(22), dp(12))
        dock.clipChildren = false
        dockScroll = ScrollView(this)
        dockScroll.isVerticalScrollBarEnabled = false
        dockScroll.overScrollMode = View.OVER_SCROLL_NEVER
        dockScroll.clipChildren = false
        dockScroll.clipToPadding = false
        dockScroll.background = GradientDrawable().apply { setColor(0x66121820); cornerRadius = dp(38).toFloat() }
        dockScroll.addView(dock, FrameLayout.LayoutParams(-2, -2))
        val dlp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dlp.setMargins(dp(48), dp(104), dp(48), dp(40))
        root.addView(dockScroll, dlp)

        setContentView(root)
    }

    private fun bg() = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = FrameLayout.LayoutParams(-1, -1)
    }

    override fun onResume() {
        super.onResume()
        loadWallpaperFiles()
        buildDock()
        handler.removeCallbacks(rotateRunnable)
        rotate(true)
    }

    override fun onPause() {
        handler.removeCallbacks(rotateRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    @Deprecated("Il tasto Indietro non deve chiudere la home")
    override fun onBackPressed() { /* resta sulla home */ }

    // ---------------------------------------------------------------- sfondi

    private fun loadWallpaperFiles() {
        val dir = File(getExternalFilesDir(null), "wallpapers")
        val ok = setOf("jpg", "jpeg", "png", "webp")
        wallpapers = dir.listFiles { f -> f.isFile && f.extension.lowercase() in ok }?.sortedBy { it.name } ?: emptyList()
    }

    private fun nextBitmap(): Bitmap {
        if (wallpapers.isNotEmpty()) {
            val f = wallpapers[wallIndex % wallpapers.size]
            wallIndex++
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1920) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(f.path, opts)?.let { return it }
        }
        return Scenery.render(1920, 1080, System.nanoTime())
    }

    private fun rotate(first: Boolean) {
        worker.execute {
            val bmp = try { nextBitmap() } catch (t: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (bmp != null) crossfade(bmp, first)
                handler.removeCallbacks(rotateRunnable)
                handler.postDelayed(rotateRunnable, 10L * 60L * 1000L) // cambia ogni 10 minuti
            }
        }
    }

    private fun crossfade(bmp: Bitmap, first: Boolean) {
        val incoming = if (frontIsA) bgB else bgA
        val outgoing = if (frontIsA) bgA else bgB
        val old = shown
        shown = bmp
        incoming.setImageBitmap(bmp)
        incoming.alpha = 0f
        val d = if (first) 500L else 1800L
        incoming.animate().alpha(1f).setDuration(d).start()
        outgoing.animate().alpha(0f).setDuration(d).withEndAction {
            outgoing.setImageDrawable(null)
            old?.recycle()
        }.start()
        frontIsA = !frontIsA
    }

    // ------------------------------------------------------------------ app

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val label = ri.loadLabel(pm).toString()
                val hay = (pkg + " " + label).lowercase().replace(" ", "")
                if (hiddenKeywords.any { hay.contains(it) }) continue
                val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                out.add(AppEntry(pkg, label, i, ri.loadIcon(pm)))
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    private fun favorites(installed: Map<String, AppEntry>): List<String> {
        val saved = prefs.getString("favs", null)
        if (saved != null) return saved.split(",").filter { it.isNotBlank() && installed.containsKey(it) }
        val init = defaultFavs.filter { installed.containsKey(it) }
        prefs.edit().putString("favs", init.joinToString(",")).apply()
        return init
    }

    private fun toggleFav(pkg: String, label: String) {
        val cur = (prefs.getString("favs", "") ?: "").split(",").filter { it.isNotBlank() }.toMutableList()
        if (cur.contains(pkg)) { cur.remove(pkg); toast("$label rimossa dalla home") }
        else { cur.add(pkg); toast("$label aggiunta alla home") }
        prefs.edit().putString("favs", cur.joinToString(",")).apply()
        buildDock()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun launch(e: AppEntry) {
        try { startActivity(e.intent) } catch (t: Throwable) { toast("Impossibile aprire ${e.label}") }
    }

    private fun buildDock() {
        val apps = loadApps()
        val byPkg = apps.associateBy { it.pkg }

        val tiles = ArrayList<View>()
        tiles.add(tile("TV", GlyphView(this, GlyphView.TV), false) { openLiveTv(apps) })
        for (pkg in favorites(byPkg)) {
            val e = byPkg[pkg] ?: continue
            val icon = ImageView(this).apply {
                setImageDrawable(e.icon); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val t = tile(e.label, icon, false) { launch(e) }
            t.setOnLongClickListener { toggleFav(e.pkg, e.label); true }
            tiles.add(t)
        }
        tiles.add(tile("Tutte le app", GlyphView(this, GlyphView.GRID), false) { showAll(apps) })

        dock.removeAllViews()
        var row: LinearLayout? = null
        for ((i, t) in tiles.withIndex()) {
            if (i % 4 == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                r.clipChildren = false
                r.minimumWidth = dp(4 * 138)
                val rlp = LinearLayout.LayoutParams(-2, -2)
                if (i > 0) rlp.topMargin = dp(10)
                dock.addView(r, rlp)
                row = r
            }
            row?.addView(t)
        }
        firstTile = tiles[0]
        dockScroll.scrollTo(0, 0)
        dock.post { firstTile?.requestFocus() }
    }

    /** Apre la TV in diretta (canali dell'antenna): cerca l'app TV installata, altrimenti prova l'accesso ai canali di sistema. */
    private fun openLiveTv(apps: List<AppEntry>) {
        val known = listOf("com.google.android.tv", "com.android.tv", "com.google.android.apps.tv.livetv")
        val labelRx = Regex("^(tv|live ?tv|live channels|tv live|tv in diretta|canali tv|tv digitale|digital tv|dtv|antenna( tv)?|tuner)$")
        val app = apps.firstOrNull { known.contains(it.pkg) }
            ?: apps.firstOrNull { labelRx.matches(it.label.trim().lowercase()) }
        if (app != null) { launch(app); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            toast("App TV non trovata. Usa il tasto Sorgenti/Input del telecomando e scegli Antenna/TV.")
        }
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (t: Throwable) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (t2: Throwable) { toast("Impostazioni non disponibili") }
        }
    }

    /** Pulsante rotondo della barra in alto; si illumina e ingrandisce al focus. */
    private fun topButton(glyph: View, action: String): View {
        val box = FrameLayout(this)
        val bg = GradientDrawable().apply { setColor(0x00FFFFFF); cornerRadius = dp(22).toFloat() }
        box.background = bg
        box.addView(glyph, FrameLayout.LayoutParams(-1, -1))
        box.isFocusable = true
        box.isClickable = true
        box.setOnClickListener { openSettings(action) }
        box.setOnFocusChangeListener { _, focused ->
            bg.setColor(if (focused) 0x66FFFFFF else 0x00FFFFFF)
            val s = if (focused) 1.15f else 1f
            box.animate().scaleX(s).scaleY(s).setDuration(140).start()
        }
        box.layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(3), 0, dp(3), 0) }
        return box
    }

    /** Tessera con icona arrotondata; al focus si ingrandisce e mostra il nome (stile tvOS). */
    private fun tile(label: String, content: View, alwaysLabel: Boolean, interactive: Boolean = true, onClick: () -> Unit): LinearLayout {
        val box = FrameLayout(this)
        box.background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(20).toFloat() }
        box.clipToOutline = true
        box.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(20).toFloat()) }
        }
        box.addView(content, FrameLayout.LayoutParams(-1, -1))

        val tv = TextView(this).apply {
            text = label; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = if (alwaysLabel) 1f else 0f
            setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(box, LinearLayout.LayoutParams(dp(92), dp(92)))
        col.addView(tv, LinearLayout.LayoutParams(dp(116), dp(24)).apply { topMargin = dp(8) })
        val lp = LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4), 0, dp(4), 0)
        col.layoutParams = lp

        if (interactive) {
        col.isFocusable = true
        col.isClickable = true
        col.setOnClickListener { onClick() }
        col.setOnFocusChangeListener { _, focused ->
            val s = if (focused) 1.2f else 1f
            box.animate().scaleX(s).scaleY(s).setDuration(140).start()
            box.elevation = if (focused) dp(12).toFloat() else 0f
            if (!alwaysLabel) tv.animate().alpha(if (focused) 1f else 0f).setDuration(140).start()
        }
        }
        return col
    }

    private fun showAll(apps: List<AppEntry>) {
        allDialog?.dismiss()
        val dlg = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val frame = FrameLayout(this)
        frame.setBackgroundColor(0xE60E131A.toInt())
        frame.setPadding(dp(56), dp(40), dp(56), dp(24))
        val title = TextView(this).apply {
            text = "Tutte le app   ·   tieni premuto OK per aggiungere/togliere dalla home"
            setTextColor(0xCCFFFFFF.toInt()); textSize = 15f
        }
        frame.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        val grid = GridView(this).apply {
            numColumns = 6; verticalSpacing = dp(14); horizontalSpacing = dp(4)
            selector = GradientDrawable().apply { setColor(0x40FFFFFF); cornerRadius = dp(20).toFloat() }
            clipToPadding = false; setPadding(0, dp(12), 0, dp(40))
        }
        grid.adapter = object : ArrayAdapter<AppEntry>(this, 0, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val e = getItem(position)!!
                val icon = ImageView(context).apply {
                    setImageDrawable(e.icon); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                // Le tessere della griglia non sono focalizzabili: selezione, OK e pressione lunga li gestisce la GridView.
                return tile(e.label, icon, true, false) { }
            }
        }
        grid.setOnItemClickListener { _, _, pos, _ -> dlg.dismiss(); launch(apps[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ -> toggleFav(apps[pos].pkg, apps[pos].label); true }
        val glp = FrameLayout.LayoutParams(-1, -1)
        glp.topMargin = dp(36)
        frame.addView(grid, glp)
        dlg.setContentView(frame)
        dlg.setOnDismissListener { allDialog = null }
        allDialog = dlg
        dlg.show()
    }

    /** Glifi disegnati a mano per evitare caratteri mancanti nei font della TV. */
    private class GlyphView(ctx: Context, private val kind: Int) : View(ctx) {
        companion object { const val GRID = 0; const val GEAR = 1; const val WIFI = 2; const val BT = 3; const val TV = 4 }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (kind == TV) {
                p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.06f
                p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                c.drawRoundRect(RectF(w * 0.18f, h * 0.38f, w * 0.82f, h * 0.76f), w * 0.07f, w * 0.07f, p)
                c.drawLine(w * 0.5f, h * 0.38f, w * 0.36f, h * 0.2f, p)
                c.drawLine(w * 0.5f, h * 0.38f, w * 0.64f, h * 0.2f, p)
                c.drawLine(w * 0.36f, h * 0.86f, w * 0.64f, h * 0.86f, p)
            } else if (kind == GRID) {
                p.style = Paint.Style.FILL
                val r = w * 0.055f
                for (i in 0..2) for (j in 0..2) c.drawCircle(w * (0.28f + 0.22f * i), h * (0.28f + 0.22f * j), r, p)
            } else if (kind == WIFI) {
                p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.08f; p.strokeCap = Paint.Cap.ROUND
                val cx = w / 2; val cy = h * 0.74f
                for (r in floatArrayOf(w * 0.17f, w * 0.30f, w * 0.43f)) {
                    c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, w * 0.05f, p)
            } else if (kind == BT) {
                p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.07f
                p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
                val path = Path()
                path.moveTo(w * 0.30f, h * 0.34f)
                path.lineTo(w * 0.70f, h * 0.68f)
                path.lineTo(w * 0.50f, h * 0.84f)
                path.lineTo(w * 0.50f, h * 0.16f)
                path.lineTo(w * 0.70f, h * 0.32f)
                path.lineTo(w * 0.30f, h * 0.66f)
                c.drawPath(path, p)
            } else {
                p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.07f
                c.drawCircle(w / 2, h / 2, w * 0.17f, p)
                p.style = Paint.Style.FILL
                for (k in 0 until 8) {
                    c.save(); c.rotate(k * 45f, w / 2, h / 2)
                    c.drawRoundRect(w / 2 - w * 0.045f, h * 0.17f, w / 2 + w * 0.045f, h * 0.29f, 4f, 4f, p)
                    c.restore()
                }
            }
        }
    }
}
