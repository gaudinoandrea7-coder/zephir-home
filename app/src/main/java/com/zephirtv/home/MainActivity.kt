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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
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

        // Orologio e data in alto a destra
        val clockBox = LinearLayout(this)
        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.gravity = Gravity.END
        val time = TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            setTextColor(Color.WHITE); textSize = 40f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE d MMMM"; format12Hour = "EEEE d MMMM"
            setTextColor(0xDDFFFFFF.toInt()); textSize = 16f
            setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
        }
        clockBox.addView(time); clockBox.addView(date)
        val clp = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        clp.setMargins(0, dp(36), dp(56), 0)
        root.addView(clockBox, clp)

        // Dock
        dock = LinearLayout(this)
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER
        dock.setPadding(dp(22), dp(18), dp(22), dp(10))
        dock.background = GradientDrawable().apply { setColor(0x66121820); cornerRadius = dp(38).toFloat() }
        val dlp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        dlp.bottomMargin = dp(48)
        root.addView(dock, dlp)

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
                val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                out.add(AppEntry(pkg, ri.loadLabel(pm).toString(), i, ri.loadIcon(pm)))
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
        if (cur.contains(pkg)) { cur.remove(pkg); toast("$label rimossa dal dock") }
        else { cur.add(pkg); toast("$label aggiunta al dock") }
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
        dock.removeAllViews()
        for (pkg in favorites(byPkg)) {
            val e = byPkg[pkg] ?: continue
            val icon = ImageView(this).apply {
                setImageDrawable(e.icon); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val t = tile(e.label, icon, false) { launch(e) }
            t.setOnLongClickListener { toggleFav(e.pkg, e.label); true }
            dock.addView(t)
        }
        dock.addView(tile("Tutte le app", GlyphView(this, GlyphView.GRID), false) { showAll(apps) })
        dock.addView(tile("Impostazioni", GlyphView(this, GlyphView.GEAR), false) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (t: Throwable) { toast("Impostazioni non disponibili") }
        })
        dock.post { dock.getChildAt(0)?.requestFocus() }
    }

    /** Tessera con icona arrotondata; al focus si ingrandisce e mostra il nome (stile tvOS). */
    private fun tile(label: String, content: View, alwaysLabel: Boolean, onClick: () -> Unit): LinearLayout {
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

        col.isFocusable = true
        col.isClickable = true
        col.setOnClickListener { onClick() }
        col.setOnFocusChangeListener { _, focused ->
            val s = if (focused) 1.2f else 1f
            box.animate().scaleX(s).scaleY(s).setDuration(140).start()
            box.elevation = if (focused) dp(12).toFloat() else 0f
            if (!alwaysLabel) tv.animate().alpha(if (focused) 1f else 0f).setDuration(140).start()
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
            text = "Tutte le app   ·   tieni premuto OK per aggiungere/togliere dal dock"
            setTextColor(0xCCFFFFFF.toInt()); textSize = 15f
        }
        frame.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        val grid = GridView(this).apply {
            numColumns = 6; verticalSpacing = dp(14); horizontalSpacing = dp(4)
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false; setPadding(0, dp(12), 0, dp(40))
        }
        grid.adapter = object : ArrayAdapter<AppEntry>(this, 0, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val e = getItem(position)!!
                val icon = ImageView(context).apply {
                    setImageDrawable(e.icon); scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                val t = tile(e.label, icon, true) { dlg.dismiss(); launch(e) }
                t.setOnLongClickListener { toggleFav(e.pkg, e.label); true }
                return t
            }
        }
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
        companion object { const val GRID = 0; const val GEAR = 1 }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (kind == GRID) {
                val r = w * 0.055f
                for (i in 0..2) for (j in 0..2) c.drawCircle(w * (0.28f + 0.22f * i), h * (0.28f + 0.22f * j), r, p)
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
