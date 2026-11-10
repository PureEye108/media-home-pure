package app.playerpure.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Environment
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.playerpure.Hub
import app.playerpure.R
import app.playerpure.audio.Native
import app.playerpure.library.Album
import app.playerpure.library.Covers
import app.playerpure.library.Library
import app.playerpure.library.Tags
import app.playerpure.library.Track
import app.playerpure.ui.MainActivity.Companion.NAV_ALBUMS
import app.playerpure.ui.MainActivity.Companion.NAV_ARTISTS
import app.playerpure.ui.MainActivity.Companion.NAV_FOLDERS
import app.playerpure.ui.MainActivity.Companion.NAV_QUEUE
import app.playerpure.ui.MainActivity.Companion.NAV_SETTINGS
import app.playerpure.ui.Ui.MATCH
import app.playerpure.ui.Ui.WRAP
import app.playerpure.ui.Ui.dp
import app.playerpure.ui.Ui.lp
import java.io.File

/** Runs [work] off the main thread and hands the result back to [done] on it (if the page is still shown). */
private fun <T> Page.load(work: () -> T, done: (T) -> Unit) {
    val v = view
    Thread { val r = try { work() } catch (_: Exception) { null }; if (r != null) v.post { done(r) } }.start()
}

private fun header(act: MainActivity, title: String, sub: String, back: Boolean): Triple<LinearLayout, TextView, TextView> {
    val t = Ui.text(act, title, 32f, Ui.TEXT, Ui.MEDIUM)
    val s = Ui.text(act, sub, 15f, Ui.DIM)
    val row = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        if (back) addView(Ui.roundButton(act, R.drawable.pp_ic_back, "Back", 52, 28) { act.back() }, lp(dp(act, 52), dp(act, 52)).apply { rightMargin = dp(act, 12) })
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(t); addView(s, lp(WRAP, WRAP).apply { topMargin = dp(act, 6) })
        }, lp(0, WRAP, 1f))
    }
    return Triple(row, t, s)
}

private fun searchBox(act: MainActivity, hint: String, onChange: (String) -> Unit): EditText = EditText(act).apply {
    this.hint = hint; setHintTextColor(Ui.FAINT); setTextColor(Ui.TEXT); textSize = 16f
    isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
    background = Ui.rounded(act, Ui.SURFACE2, 999, Ui.STROKE, 1)
    setPadding(dp(act, 18), 0, dp(act, 18), 0)
    setCompoundDrawablesRelativeWithIntrinsicBounds(act.getDrawable(R.drawable.pp_ic_search)?.apply { setTint(Ui.FAINT) }, null, null, null)
    compoundDrawablePadding = dp(act, 10)
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString() ?: "") }
    })
}

private fun albumMenu(act: MainActivity, anchor: View, tracks: () -> List<Track>) {
    PopupMenu(act, anchor).apply {
        menu.add(0, 1, 0, "Play"); menu.add(0, 2, 1, "Shuffle"); menu.add(0, 3, 2, "Play next"); menu.add(0, 4, 3, "Add to queue")
        setOnMenuItemClickListener { mi ->
            Thread {
                val t = tracks()
                anchor.post {
                    when (mi.itemId) {
                        1 -> act.play(t); 2 -> act.play(t.shuffled())
                        3 -> { Hub.player.enqueue(t, true); toast(act, "Plays next") }
                        4 -> { Hub.player.enqueue(t, false); toast(act, "Added to the queue") }
                    }
                }
            }.start(); true
        }
        show()
    }
}

private fun toast(act: MainActivity, s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

private fun list(act: MainActivity): ListView = ListView(act).apply {
    divider = ColorDrawable(0x14FFFFFF); dividerHeight = 1
    selector = ColorDrawable(Color.TRANSPARENT); cacheColorHint = Color.TRANSPARENT
    isVerticalScrollBarEnabled = true; clipToPadding = false
}

/** "Allow access to music files" / "No music found" / "Scanning" messages. */
private fun emptyState(act: MainActivity): Pair<LinearLayout, (String, String, String?, () -> Unit) -> Unit> {
    val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
    val t = Ui.text(act, "", 24f, Ui.TEXT, Ui.MEDIUM, 2).apply { gravity = Gravity.CENTER }
    val s = Ui.text(act, "", 16f, Ui.DIM, lines = 4).apply { gravity = Gravity.CENTER }
    val btnHolder = FrameLayout(act)
    box.addView(Ui.icon(act, R.drawable.pp_ic_album, 64, Ui.FAINT))
    box.addView(t, lp(WRAP, WRAP).apply { topMargin = dp(act, 18) })
    box.addView(s, lp(dp(act, 560), WRAP).apply { topMargin = dp(act, 10) })
    box.addView(btnHolder, lp(WRAP, WRAP).apply { topMargin = dp(act, 22) })
    return box to { title, sub, button, action ->
        t.text = title; s.text = sub; btnHolder.removeAllViews()
        if (button != null) btnHolder.addView(Ui.pill(act, button, primary = true) { action() })
    }
}

// ============================================================ Albums
class AlbumsPage(act: MainActivity, private val artist: String? = null) : Page(act) {
    override val nav = if (artist != null) NAV_ARTISTS else NAV_ALBUMS
    private var albums = listOf<Album>()
    private var filter = ""
    private var sort = Library.Sort.entries.getOrElse(Hub.prefs.getInt(Hub.KEY_SORT, 0)) { Library.Sort.ARTIST }
    private val hdr = header(act, artist ?: "Albums", "", artist != null)
    private val sortRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val empty = emptyState(act)
    private val cell = dp(act, 196)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = albums.size
        override fun getItem(p: Int) = albums[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: card()
            val a = albums[p]
            val cover = (v.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView
            (v.getChildAt(1) as TextView).text = a.title.ifBlank { "Unknown album" }
            (v.getChildAt(2) as TextView).text = a.artist.ifBlank { "Unknown artist" }
            (v.getChildAt(3) as TextView).text = listOf(if (a.year > 0) a.year.toString() else "", Track.formatLabel(a.codec, a.maxRate, a.maxBits)).filter { it.isNotBlank() }.joinToString(" · ")
            v.contentDescription = "${a.title} by ${a.artist}"
            Covers.load(cover, a.art, a.firstPath, cell) { it.setImageBitmap(Ui.placeholder(act, a.title, 256)) }
            v.setOnClickListener { act.show(AlbumPage(act, a)) }
            v.setOnLongClickListener { albumMenu(act, it) { Hub.library.albumTracks(a.key) }; true }
            return v
        }
        private fun card() = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true; isFocusable = true
            background = Ui.pressable(act, 0, 16)
            setPadding(dp(act, 6), dp(act, 6), dp(act, 6), dp(act, 10))
            addView(SquareFrame(act).apply { addView(Ui.cover(act, 12), FrameLayout.LayoutParams(MATCH, MATCH)) }, lp(MATCH, WRAP))
            addView(Ui.text(act, "", 16f, Ui.TEXT, Ui.MEDIUM), lp(MATCH, WRAP).apply { topMargin = dp(act, 10) })
            addView(Ui.text(act, "", 14f, Ui.DIM), lp(MATCH, WRAP).apply { topMargin = dp(act, 4) })
            addView(Ui.text(act, "", 12.5f, Ui.FAINT), lp(MATCH, WRAP).apply { topMargin = dp(act, 4) })
        }
    }

    private val grid = GridView(act).apply {
        numColumns = GridView.AUTO_FIT; columnWidth = cell; stretchMode = GridView.STRETCH_COLUMN_WIDTH
        horizontalSpacing = dp(act, 14); verticalSpacing = dp(act, 14)
        selector = ColorDrawable(Color.TRANSPARENT); clipToPadding = false
        setPadding(dp(act, 22), dp(act, 4), dp(act, 22), dp(act, 24))
        adapter = this@AlbumsPage.adapter
    }

    override val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(act, 28), dp(act, 24), dp(act, 28), dp(act, 14))
            addView(hdr.first, lp(0, WRAP, 1f))
            addView(searchBox(act, "Search albums") { filter = it; refresh() }, lp(dp(act, 300), dp(act, 50)))
        })
        if (artist == null) addView(sortRow, lp(MATCH, WRAP).apply { leftMargin = dp(act, 28); bottomMargin = dp(act, 8) })
        addView(FrameLayout(act).apply {
            addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(empty.first, FrameLayout.LayoutParams(MATCH, MATCH))
        }, lp(MATCH, 0, 1f))
        buildSort()
    }

    private fun buildSort() {
        sortRow.removeAllViews()
        sortRow.addView(Ui.text(act, "Sort", 14f, Ui.FAINT), lp(WRAP, WRAP).apply { rightMargin = dp(act, 12) })
        for (s in Library.Sort.entries) sortRow.addView(Ui.pill(act, s.label, selected = s == sort) {
            sort = s; Hub.prefs.edit().putInt(Hub.KEY_SORT, s.ordinal).apply(); buildSort(); refresh()
        }, lp(WRAP, dp(act, 44)).apply { rightMargin = dp(act, 10) })
    }

    override fun refresh() {
        val f = filter; val s = sort
        load({ Hub.library.albums(s, f, artist) }) { list ->
            albums = list
            adapter.notifyDataSetChanged()
            val scan = Hub.scanning
            hdr.third.text = when {
                scan != null -> "Scanning… ${scan.files} files, ${scan.added} new"
                artist != null -> "${list.size} album${if (list.size == 1) "" else "s"}"
                else -> "${list.size} albums"
            }
            val (box, set) = empty
            box.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            when {
                !act.hasFileAccess() -> set("Allow Player Pure to read your music", "Android asks once: turn on \"Allow access to manage all files\". Player Pure only reads music, CUE sheets and cover pictures.", "Allow access") { act.askFileAccess() }
                scan != null -> set("Looking for music…", "${scan.files} files checked.\n${scan.folder}", null) {}
                f.isNotBlank() -> set("Nothing matches \"$f\"", "Try part of the album or artist name.", null) {}
                else -> set("No music found yet", "Put music in a Music folder on the tablet or the USB drive (Music\\Artist\\Album), then scan.", "Scan now") { Hub.scan() }
            }
        }
    }
}

// ============================================================ One album
class AlbumPage(act: MainActivity, private val album: Album) : Page(act) {
    override val nav = NAV_ALBUMS
    private var tracks = listOf<Track>()
    private val coverView = Ui.cover(act, 20)
    private val meta = Ui.text(act, "", 15f, Ui.DIM, lines = 2)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = tracks.size
        override fun getItem(p: Int) = tracks[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: row()
            val t = tracks[p]
            val playing = Hub.player.current()?.let { it.id == t.id } == true
            val multiDisc = tracks.map { it.discNo }.distinct().size > 1
            (v.getChildAt(0) as TextView).text = if (playing) "▶" else if (multiDisc && t.discNo > 0) "${t.discNo}.${t.trackNo}" else if (t.trackNo > 0) t.trackNo.toString() else "·"
            val col = v.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).apply { text = t.title; typeface = if (playing) Ui.MEDIUM else Ui.REGULAR; setTextColor(if (playing) Ui.ACCENT else Ui.TEXT) }
            val differs = t.displayArtist != album.artist && album.artist != ""
            (col.getChildAt(1) as TextView).apply { text = if (playing) "Playing" + (if (differs) " · ${t.displayArtist}" else "") else t.displayArtist; visibility = if (differs || playing) View.VISIBLE else View.GONE }
            (v.getChildAt(2) as TextView).text = t.formatLabel
            (v.getChildAt(3) as TextView).text = Ui.time(t.durationMs)
            v.contentDescription = "${t.title}, ${Ui.time(t.durationMs)}" + if (playing) ", playing" else ""
            v.setOnClickListener { act.play(tracks, p) }
            v.setOnLongClickListener { albumMenu(act, it) { listOf(t) }; true }
            return v
        }
        private fun row() = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(act, 64); isClickable = true; isFocusable = true
            background = Ui.pressable(act, 0, 12)
            setPadding(dp(act, 12), dp(act, 8), dp(act, 16), dp(act, 8))
            addView(Ui.text(act, "", 16f, Ui.FAINT).apply { gravity = Gravity.CENTER }, lp(dp(act, 48), WRAP))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(act, "", 18f)); addView(Ui.text(act, "", 14f, Ui.DIM), lp(WRAP, WRAP).apply { topMargin = dp(act, 3) })
            }, lp(0, WRAP, 1f).apply { leftMargin = dp(act, 8) })
            addView(Ui.text(act, "", 13f, Ui.FAINT), lp(WRAP, WRAP).apply { rightMargin = dp(act, 18) })
            addView(Ui.text(act, "", 16f, Ui.DIM))
        }
    }

    override val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(act, 28), dp(act, 24), dp(act, 20), 0)
        val left = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(SquareFrame(act).apply { addView(coverView, FrameLayout.LayoutParams(MATCH, MATCH)); elevation = dp(act, 10).toFloat() }, lp(MATCH, WRAP))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.pill(act, "Play", R.drawable.pp_ic_play, primary = true) { act.play(tracks, 0, open = true) }, lp(0, dp(act, 52), 1f).apply { rightMargin = dp(act, 10) })
                addView(Ui.pill(act, "Shuffle", R.drawable.pp_ic_shuffle) { act.play(tracks.shuffled(), 0, open = true) }, lp(0, dp(act, 52), 1f))
            }, lp(MATCH, WRAP).apply { topMargin = dp(act, 20) })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Ui.pill(act, "Play next", R.drawable.pp_ic_queue) { Hub.player.enqueue(tracks, true); toast(act, "Plays next") }, lp(0, dp(act, 52), 1f).apply { rightMargin = dp(act, 10) })
                addView(Ui.pill(act, "Add", R.drawable.pp_ic_add) { Hub.player.enqueue(tracks, false); toast(act, "Added to the queue") }, lp(0, dp(act, 52), 1f))
            }, lp(MATCH, WRAP).apply { topMargin = dp(act, 10) })
        }
        addView(left, lp(dp(act, 340), MATCH))
        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val (h, _, sub) = header(act, album.title.ifBlank { "Unknown album" }, album.artist, true)
        sub.textSize = 20f; sub.setTextColor(Ui.TEXT)
        sub.setOnClickListener { if (album.artist.isNotBlank() && album.artist != "Various artists") act.show(AlbumsPage(act, album.artist)) }
        right.addView(h)
        right.addView(meta, lp(MATCH, WRAP).apply { topMargin = dp(act, 10); leftMargin = dp(act, 64); bottomMargin = dp(act, 10) })
        right.addView(list(act).apply { adapter = this@AlbumPage.adapter; setPadding(0, 0, 0, dp(act, 24)) }, lp(MATCH, 0, 1f))
        addView(right, lp(0, MATCH, 1f).apply { leftMargin = dp(act, 32) })
    }

    init { Covers.load(coverView, album.art, album.firstPath, dp(act, 520)) { it.setImageBitmap(Ui.placeholder(act, album.title, 512)) } }

    override fun refresh() {
        load({ Hub.library.albumTracks(album.key) }) { list ->
            tracks = list
            val total = list.sumOf { it.durationMs }
            val formats = list.map { it.formatLabel }.distinct()
            meta.text = listOf(if (album.year > 0) album.year.toString() else "", "${list.size} tracks", Ui.time(total),
                if (formats.size == 1) formats[0] else formats.take(2).joinToString(", ") + if (formats.size > 2) "…" else "").filter { it.isNotBlank() }.joinToString("  ·  ")
            adapter.notifyDataSetChanged()
        }
    }
}

// ============================================================ Artists
class ArtistsPage(act: MainActivity) : Page(act) {
    override val nav = NAV_ARTISTS
    private var all = listOf<Pair<String, Int>>()
    private var shown = listOf<Pair<String, Int>>()
    private val hdr = header(act, "Artists", "", false)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(act, 72); isClickable = true; isFocusable = true
                background = Ui.pressable(act, 0, 14); setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8))
                addView(Ui.text(act, "", 20f, Ui.TEXT, Ui.MEDIUM).apply {
                    gravity = Gravity.CENTER; background = Ui.rounded(act, Ui.SURFACE2, 999, Ui.STROKE, 1)
                }, lp(dp(act, 52), dp(act, 52)))
                addView(Ui.text(act, "", 19f), lp(0, WRAP, 1f).apply { leftMargin = dp(act, 18) })
                addView(Ui.text(act, "", 15f, Ui.DIM))
            }
            val (name, n) = shown[p]
            (v.getChildAt(0) as TextView).text = name.trim().take(1).uppercase()
            (v.getChildAt(1) as TextView).text = name
            (v.getChildAt(2) as TextView).text = "$n album${if (n == 1) "" else "s"}"
            v.contentDescription = "$name, $n albums"
            v.setOnClickListener { act.show(AlbumsPage(act, name)) }
            return v
        }
    }
    private var filter = ""
    override val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(act, 28), dp(act, 24), dp(act, 28), 0)
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(hdr.first, lp(0, WRAP, 1f))
            addView(searchBox(act, "Search artists") { filter = it; apply() }, lp(dp(act, 300), dp(act, 50)))
        })
        addView(list(act).apply { adapter = this@ArtistsPage.adapter; setPadding(0, dp(act, 12), 0, dp(act, 24)); isFastScrollEnabled = true }, lp(MATCH, 0, 1f))
    }
    private fun apply() {
        shown = if (filter.isBlank()) all else all.filter { it.first.contains(filter.trim(), true) }
        hdr.third.text = "${shown.size} artists"
        adapter.notifyDataSetChanged()
    }
    override fun refresh() { load({ Hub.library.artists() }) { all = it; apply() } }
}

// ============================================================ Folders
class FoldersPage(act: MainActivity, private val dir: File?) : Page(act) {
    override val nav = NAV_FOLDERS
    private class Entry(val file: File?, val label: String, val sub: String, val folder: Boolean)
    private var entries = listOf<Entry>()
    private val hdr = header(act, dir?.name ?: "Folders", dir?.path ?: "The tablet and connected drives", dir != null)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(p: Int) = entries[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(act, 64); isClickable = true; isFocusable = true
                background = Ui.pressable(act, 0, 12); setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 8))
                addView(Ui.icon(act, R.drawable.pp_ic_folder, 28, Ui.DIM))
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(act, "", 18f)); addView(Ui.text(act, "", 13f, Ui.FAINT), lp(WRAP, WRAP).apply { topMargin = dp(act, 3) })
                }, lp(0, WRAP, 1f).apply { leftMargin = dp(act, 18) })
            }
            val e = entries[p]
            (v.getChildAt(0) as ImageView).setImageResource(if (e.folder) R.drawable.pp_ic_folder else R.drawable.pp_ic_note)
            val col = v.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = e.label
            (col.getChildAt(1) as TextView).apply { text = e.sub; visibility = if (e.sub.isEmpty()) View.GONE else View.VISIBLE }
            v.contentDescription = (if (e.folder) "Folder " else "") + e.label
            v.setOnClickListener {
                val f = e.file ?: return@setOnClickListener
                if (e.folder) act.show(FoldersPage(act, f))
                else playFrom(f)
            }
            return v
        }
    }
    override val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(act, 28), dp(act, 24), dp(act, 28), 0)
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(hdr.first, lp(0, WRAP, 1f))
            if (dir != null) addView(Ui.pill(act, "Play this folder", R.drawable.pp_ic_play, primary = true) { playFolder() }, lp(WRAP, dp(act, 50)))
        })
        addView(list(act).apply { adapter = this@FoldersPage.adapter; setPadding(0, dp(act, 12), 0, dp(act, 24)) }, lp(MATCH, 0, 1f))
    }

    private fun tracksHere(): List<Track> {
        val d = dir ?: return emptyList()
        val indexed = Hub.library.tracksIn(d.path)
        if (indexed.isNotEmpty()) return indexed
        // not scanned yet: read the files directly
        return d.listFiles()?.filter { it.isFile && Tags.isAudio(it.name) }?.sortedBy { it.name.lowercase() }?.mapIndexed { i, f ->
            val t = Tags.read(f)
            Track(-1L - i, f.path, t.title, t.artist, t.album, t.albumArtist, t.genre, t.year, t.track, t.disc, t.durationMs, t.rate, t.bits, t.channels, t.codec, art = if (t.hasArt) "emb" else null)
        } ?: emptyList()
    }

    private fun playFolder() { load({ tracksHere() }) { if (it.isEmpty()) toast(act, "No music in this folder") else act.play(it, 0, open = true) } }

    private fun playFrom(f: File) {
        load({ tracksHere().filter { it.folder == f.parent } }) { list ->
            val i = list.indexOfFirst { it.path == f.path }.coerceAtLeast(0)
            act.play(list, i, open = true)
        }
    }

    override fun refresh() {
        load({
            if (dir == null) Library.roots(act).map { Entry(File(it.path), it.label, it.path, true) }
            else {
                val all = dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
                all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.map { Entry(it, it.name, "", true) } +
                    all.filter { it.isFile && Tags.isAudio(it.name) }.sortedBy { it.name.lowercase() }.map {
                        Entry(it, it.name, it.name.substringAfterLast('.').uppercase() + " · " + "%.1f MB".format(it.length() / 1048576.0), false)
                    }
            }
        }) { entries = it; adapter.notifyDataSetChanged(); if (dir != null) hdr.third.text = "${entries.count { e -> !e.folder }} music files · ${entries.count { e -> e.folder }} folders" }
    }
}

// ============================================================ Queue
class QueuePage(act: MainActivity) : Page(act) {
    override val nav = NAV_QUEUE
    private val hdr = header(act, "Queue", "", false)
    private val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var items = Hub.player.queue()
    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].uid
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(act, 68); isClickable = true; isFocusable = true
                background = Ui.pressable(act, 0, 12); setPadding(dp(act, 12), dp(act, 6), dp(act, 6), dp(act, 6))
                addView(Ui.text(act, "", 15f, Ui.FAINT).apply { gravity = Gravity.CENTER }, lp(dp(act, 44), WRAP))
                addView(Ui.cover(act, 8), lp(dp(act, 52), dp(act, 52)).apply { leftMargin = dp(act, 6) })
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(act, "", 17f)); addView(Ui.text(act, "", 14f, Ui.DIM), lp(WRAP, WRAP).apply { topMargin = dp(act, 3) })
                }, lp(0, WRAP, 1f).apply { leftMargin = dp(act, 16) })
                addView(Ui.text(act, "", 13f, Ui.FAINT), lp(WRAP, WRAP).apply { rightMargin = dp(act, 16) })
                addView(Ui.text(act, "", 15f, Ui.DIM), lp(WRAP, WRAP).apply { rightMargin = dp(act, 8) })
                addView(Ui.roundButton(act, R.drawable.pp_ic_close, "Remove", 48, 22) {})
            }
            val q = items[p]; val t = q.track
            val cur = Hub.player.currentIndex() == p
            (v.getChildAt(0) as TextView).text = if (cur) "▶" else (p + 1).toString()
            Covers.load(v.getChildAt(1) as ImageView, t.art, t.path, dp(act, 52)) { it.setImageBitmap(Ui.placeholder(act, t.album, 64)) }
            val col = v.getChildAt(2) as LinearLayout
            (col.getChildAt(0) as TextView).apply { text = t.title; setTextColor(if (cur) Ui.ACCENT else Ui.TEXT); typeface = if (cur) Ui.MEDIUM else Ui.REGULAR }
            (col.getChildAt(1) as TextView).text = (if (cur) "Playing · " else "") + "${t.displayArtist} · ${t.album}"
            (v.getChildAt(3) as TextView).text = t.formatLabel
            (v.getChildAt(4) as TextView).text = Ui.time(t.durationMs)
            v.getChildAt(5).setOnClickListener { Hub.player.remove(p) }
            v.getChildAt(5).contentDescription = "Remove ${t.title}"
            v.contentDescription = "${p + 1}. ${t.title}" + if (cur) ", playing" else ""
            v.setOnClickListener { Hub.player.playAt(p) }
            return v
        }
    }
    private val listView = list(act).apply { adapter = this@QueuePage.adapter; setPadding(0, dp(act, 12), 0, dp(act, 24)); transcriptMode = AbsListView.TRANSCRIPT_MODE_DISABLED }
    private val empty = emptyState(act)

    override val view: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(act, 28), dp(act, 24), dp(act, 28), 0)
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(hdr.first, lp(0, WRAP, 1f)); addView(buttons)
        })
        addView(FrameLayout(act).apply {
            addView(listView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(empty.first, FrameLayout.LayoutParams(MATCH, MATCH))
        }, lp(MATCH, 0, 1f))
    }

    override fun refresh() {
        val p = Hub.player
        val before = items.size
        items = p.queue()
        hdr.third.text = if (items.isEmpty()) "Empty" else "${items.size} tracks · ${Ui.time(items.sumOf { it.track.durationMs })}"
        buttons.removeAllViews()
        buttons.addView(Ui.pill(act, if (p.shuffle) "Shuffle on" else "Shuffle", R.drawable.pp_ic_shuffle, selected = p.shuffle) { p.setShuffle(!p.shuffle) }, lp(WRAP, dp(act, 48)).apply { rightMargin = dp(act, 10) })
        buttons.addView(Ui.pill(act, when (p.repeatMode) { 1 -> "Repeat all"; 2 -> "Repeat one"; else -> "Repeat off" },
            if (p.repeatMode == 2) R.drawable.pp_ic_repeat_one else R.drawable.pp_ic_repeat, selected = p.repeatMode > 0) { p.setRepeat((p.repeatMode + 1) % 3) },
            lp(WRAP, dp(act, 48)).apply { rightMargin = dp(act, 10) })
        if (items.isNotEmpty()) buttons.addView(Ui.pill(act, "Clear", R.drawable.pp_ic_close) { p.clear() }, lp(WRAP, dp(act, 48)))
        adapter.notifyDataSetChanged()
        if (before == 0 && items.isNotEmpty()) listView.setSelection((p.currentIndex() - 2).coerceAtLeast(0))
        empty.first.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.second("The queue is empty", "Play an album, or press and hold one and choose Add to queue.", null) {}
    }
}

// ============================================================ pages made of cards (Settings, DAC)
abstract class CardPage(act: MainActivity) : Page(act) {
    protected val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(act, 28), dp(act, 24), dp(act, 28), dp(act, 40)) }
    override val view: View = ScrollView(act).apply { addView(body); isFillViewport = true }

    protected fun card(title: String, build: LinearLayout.() -> Unit) {
        body.addView(Ui.label(act, title), lp(MATCH, WRAP).apply { topMargin = dp(act, 22); bottomMargin = dp(act, 10) })
        body.addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(act, Ui.SURFACE, 20, Ui.STROKE, 1)
            setPadding(dp(act, 22), dp(act, 18), dp(act, 22), dp(act, 18))
            build()
        }, lp(MATCH, WRAP))
    }

    protected fun LinearLayout.line(title: String, sub: String? = null, size: Float = 18f) {
        addView(Ui.text(act, title, size, Ui.TEXT, Ui.MEDIUM, 3), lp(MATCH, WRAP).apply { topMargin = dp(act, 6) })
        if (sub != null) addView(Ui.text(act, sub, 15f, Ui.DIM, lines = 8), lp(MATCH, WRAP).apply { topMargin = dp(act, 6) })
    }

    protected fun LinearLayout.toggle(title: String, sub: String, on: Boolean, change: (Boolean) -> Unit) {
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(act, 72); isClickable = true; isFocusable = true
            background = Ui.pressable(act, 0, 14)
            val sw = Switch(act).apply { isChecked = on; isClickable = false; isFocusable = false }
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.text(act, title, 18f, Ui.TEXT, lines = 2)); addView(Ui.text(act, sub, 14f, Ui.DIM, lines = 4), lp(WRAP, WRAP).apply { topMargin = dp(act, 4) })
            }, lp(0, WRAP, 1f))
            addView(Ui.text(act, if (on) "On" else "Off", 15f, Ui.DIM), lp(WRAP, WRAP).apply { rightMargin = dp(act, 10) })
            addView(sw)
            contentDescription = "$title, ${if (on) "on" else "off"}"
            setOnClickListener { change(!on) }
        }, lp(MATCH, WRAP).apply { topMargin = dp(act, 8) })
    }

    protected fun LinearLayout.buttons(vararg b: View) {
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            for (v in b) addView(v, lp(WRAP, dp(act, 50)).apply { rightMargin = dp(act, 10) })
        }, lp(MATCH, WRAP).apply { topMargin = dp(act, 14) })
    }
}

// ============================================================ Settings
class SettingsPage(act: MainActivity) : CardPage(act) {
    override val nav = NAV_SETTINGS

    override fun refresh() {
        body.removeAllViews()
        // ---------------- output
        card("Output") {
            val link = Hub.link
            line(when { link != null -> "USB DAC: ${link.name}"; Hub.dacName != null -> "USB DAC: ${Hub.dacName}"; else -> "No USB DAC connected" },
                Hub.dacStatus.takeIf { link != null || Hub.dacName != null })
            if (link != null) addView(Ui.text(act, "Player Pure talks to the DAC itself, like HiBy's exclusive mode: Android's mixer and resampler are bypassed, " +
                "the DAC is switched to each file's own sample rate and bit depth, and the samples arrive unchanged. The DAC screen (rail on the left) shows what it receives.", 15f, Ui.DIM, lines = 5), lp(MATCH, WRAP).apply { topMargin = dp(act, 8) })
            else addView(Ui.text(act, "Without a DAC, music plays through the tablet's speakers, headphones or HDMI. Android resamples that path, so it is not bit-perfect.", 15f, Ui.DIM, lines = 4), lp(MATCH, WRAP).apply { topMargin = dp(act, 8) })
            when {
                link == null && Hub.dacName != null && Hub.useUsb -> buttons(Ui.pill(act, "Allow Player Pure to use the DAC", R.drawable.pp_ic_usb, primary = true) { Hub.connectDac(ask = true) })
                link != null -> buttons(Ui.pill(act, "Open the DAC screen", R.drawable.pp_ic_usb, primary = true) { act.show(DacPage(act), true) },
                    Ui.pill(act, "Give the DAC back to Android", R.drawable.pp_ic_usb) { Hub.releaseDac() })
            }
            toggle("Use the USB DAC directly (bit-perfect)", "Off: the DAC is left to Android, like any other app.", Hub.useUsb) { Hub.useUsb = it; refresh() }
            toggle("Digital volume", "Lets the tablet change the volume when the DAC has no volume control over USB. Turns bit-perfect off for PCM. Off: set the volume on the DAC or amplifier.", Hub.softVolume) { Hub.softVolume = it; refresh() }
        }
        // ---------------- library
        card("Library") {
            val (tracks, albums) = Hub.library.counts()
            line("$albums albums, $tracks tracks", Hub.scanning?.let { "Scanning… ${it.files} files checked, ${it.added} new or changed. ${it.folder}" }
                ?: "Formats: FLAC, ALAC, WAV, AIFF, DSF, DFF, MP3, AAC, Opus, Ogg, and CUE sheets. Folders with a .nomedia file are skipped.")
            if (!act.hasFileAccess()) buttons(Ui.pill(act, "Allow access to music files", primary = true) { act.askFileAccess() })
            for (r in Library.roots(act)) {
                val key = "scan:" + r.path
                toggle("Scan ${r.label}", r.scanPath, Hub.prefs.getBoolean(key, true)) { Hub.prefs.edit().putBoolean(key, it).apply(); refresh() }
            }
            buttons(Ui.pill(act, if (Hub.scanning != null) "Scanning…" else "Scan for new music", primary = Hub.scanning == null) { Hub.scan() },
                Ui.pill(act, "Clear cover cache") { Covers.clear(); File(act.cacheDir, "covers").deleteRecursively(); toast(act, "Covers will be read again") })
        }
        // ---------------- about
        card("About") {
            val v = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (_: Exception) { "" }
            line("Player Pure (Media Home Pure $v)", "Open source, MIT licence. Native decoders: dr_flac and dr_wav (public domain / MIT-0), Apple ALAC decoder (Apache 2.0). Icons: Material Icons (Apache 2.0). " +
                "Native audio library: " + (if (Native.loaded) "loaded" else "missing"), 17f)
        }
    }
}

// ============================================================ DAC screen (a big display for the DAC, e.g. when its own is small or broken)
class DacPage(act: MainActivity) : CardPage(act) {
    override val nav = MainActivity.NAV_DAC

    // the "display": big and plain, like the DAC's own screen
    private val dInput = Ui.text(act, "", 22f, Ui.TEXT, Ui.MEDIUM)
    private val dRate = Ui.text(act, "", 76f, Ui.TEXT, Ui.LIGHT)
    private val dUnit = Ui.text(act, "", 24f, Ui.DIM, Ui.LIGHT)
    private val dFormat = Ui.text(act, "", 22f, Ui.TEXT)
    private val dVolume = Ui.text(act, "", 44f, Ui.TEXT, Ui.LIGHT).apply { gravity = Gravity.END }
    private val dVolLabel = Ui.text(act, "", 14f, Ui.FAINT, Ui.MEDIUM).apply { gravity = Gravity.END; letterSpacing = 0.12f }
    private val dMute = Ui.text(act, "MUTED", 15f, Ui.ACCENT, Ui.MEDIUM).apply { letterSpacing = 0.14f; setPadding(dp(act, 12), dp(act, 4), dp(act, 12), dp(act, 4)) }
    private val dBadge = Ui.text(act, "", 14f, Ui.ACCENT, Ui.MEDIUM).apply { letterSpacing = 0.12f; setPadding(dp(act, 12), dp(act, 5), dp(act, 12), dp(act, 5)) }
    private val dState = Ui.text(act, "", 16f, Ui.DIM)
    private val volText = Ui.text(act, "", 16f, Ui.DIM)
    private val stats = Ui.text(act, "", 14f, Ui.DIM, lines = 6)
    private var volBar: SeekBar? = null
    private var muteBtn: LinearLayout? = null
    private val tick = object : Runnable { override fun run() { Hub.refreshDac(); updateDisplay(); updateStats(); if (view.isAttachedToWindow) view.postDelayed(this, 1000) } }

    private val displayView: View by lazy { display() }
    private var built: List<Any?>? = null

    /** What decides the page's layout; anything else only updates the display and statistics in place. */
    private fun shape(): List<Any?> {
        val d = Hub.dacDisplay()
        return listOf(d.connected, d.name, d.hasVolume, d.hasMute, d.nativeDsd, Hub.dsdOut, Hub.dsdSwap, Hub.dacName, Hub.useUsb,
            if (d.connected) null else Hub.dacStatus)
    }

    override fun onHubChanged() { if (shape() != built) refresh() else { updateDisplay(); updateStats() } }

    private fun <V : View> V.free(): V = also { (parent as? android.view.ViewGroup)?.removeView(it) }

    private fun display(): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(act, 0xFF000000.toInt(), 22, 0xFF2A2D35.toInt(), 2)
        setPadding(dp(act, 28), dp(act, 22), dp(act, 28), dp(act, 22))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(Ui.label(act, "Input"))
                addView(dInput, lp(WRAP, WRAP).apply { topMargin = dp(act, 6) })
            }, lp(dp(act, 150), WRAP))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
                    addView(dRate); addView(dUnit, lp(WRAP, WRAP).apply { leftMargin = dp(act, 10); bottomMargin = dp(act, 14) })
                })
                addView(dFormat)
            }, lp(0, WRAP, 1f))
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                addView(dVolLabel, lp(WRAP, WRAP))
                addView(dVolume, lp(WRAP, WRAP))
                addView(dMute, lp(WRAP, WRAP).apply { topMargin = dp(act, 6) })
            }, lp(WRAP, WRAP))
        })
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(dBadge); addView(dState, lp(0, WRAP, 1f).apply { leftMargin = dp(act, 14) })
        }, lp(MATCH, WRAP).apply { topMargin = dp(act, 18) })
    }

    private fun updateDisplay() {
        val d = Hub.dacDisplay()
        val link = Hub.link
        val db = d.volumeDb
        dInput.text = d.input
        dRate.text = d.rate; dUnit.text = d.unit; dFormat.text = d.format
        dVolLabel.text = "VOLUME"
        dVolume.text = d.volume
        dVolume.textSize = if (db != null) 44f else 22f
        dMute.visibility = if (d.muted) View.VISIBLE else View.GONE
        dMute.background = Ui.rounded(act, 0, 999, Ui.ACCENT, 1.5f)
        val badge = d.badge; val strong = d.bitPerfect
        dBadge.text = badge; dBadge.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        dBadge.setTextColor(if (strong) Ui.ACCENT else Ui.DIM)
        dBadge.background = Ui.rounded(act, 0, 999, if (strong) Ui.ACCENT else Ui.STROKE, if (strong) 1.5f else 1f)
        dState.text = d.state
        view.contentDescription = "DAC display: input ${d.input}, ${d.rate} ${d.unit}, ${d.format}, volume ${d.volume}" +
            (if (d.muted) ", muted" else "") + ", $badge"
        volText.text = db?.let { "%.1f dB".format(java.util.Locale.ROOT, it) } ?: ""
        val r = link?.volumeRangeDb
        if (r != null && db != null) volBar?.let { if (!it.isPressed) it.progress = (Hub.dbToLevel(db, r.first, r.second) * 100).toInt() }
        muteBtn?.let { (it.getChildAt(it.childCount - 1) as TextView).text = if (Hub.dacMuted == true) "Unmute" else "Mute" }
    }

    override fun refresh() {
        body.removeAllViews()
        val dd = Hub.dacDisplay()
        val (h, _, sub) = header(act, dd.name.ifBlank { "USB DAC" }, "", false)
        sub.text = if (dd.connected) "What the DAC receives from Player Pure, live" else Hub.dacStatus
        body.addView(h)
        built = shape(); volBar = null; muteBtn = null
        body.addView(displayView.free(), lp(MATCH, WRAP).apply { topMargin = dp(act, 18) })
        if (!dd.connected && Hub.dacName != null && Hub.useUsb)
            body.addView(Ui.pill(act, "Allow Player Pure to use the DAC", R.drawable.pp_ic_usb, primary = true) { Hub.connectDac(ask = true) }, lp(WRAP, dp(act, 52)).apply { topMargin = dp(act, 16) })

        // ---------------- volume and mute
        card("Volume") {
            if (dd.connected && dd.hasVolume) {
                line("DAC volume over USB", "Changes the DAC's own volume (the samples Player Pure sends stay untouched). Start low: the DAC drives your amplifier directly.", 17f)
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.pill(act, "−1 dB") { nudge(-1.0) }, lp(WRAP, dp(act, 50)))
                    volBar = SeekBar(act).apply {
                        max = 100; contentDescription = "DAC volume"
                        progress = (Hub.volume * 100).toInt()
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {}
                            override fun onStartTrackingTouch(s: SeekBar) {}
                            override fun onStopTrackingTouch(s: SeekBar) { Hub.volume = s.progress / 100f }
                        })
                    }
                    addView(volBar, lp(0, dp(act, 48), 1f).apply { leftMargin = dp(act, 10); rightMargin = dp(act, 10) })
                    addView(Ui.pill(act, "+1 dB") { nudge(1.0) }, lp(WRAP, dp(act, 50)))
                    addView(volText.free(), lp(dp(act, 110), WRAP).apply { leftMargin = dp(act, 14) })
                }, lp(MATCH, WRAP).apply { topMargin = dp(act, 12) })
            } else if (dd.connected) {
                line("No volume control over USB", "This DAC doesn't offer volume over USB. Use its own volume buttons or remote (in PRE mode), or the amplifier's volume.", 17f)
            } else line("Connect the DAC", "Volume and mute appear here when Player Pure has the DAC.", 17f)
            if (dd.connected && dd.hasMute) {
                muteBtn = Ui.pill(act, if (Hub.dacMuted == true) "Unmute" else "Mute", R.drawable.pp_ic_volume) { Hub.setDacMute(Hub.dacMuted != true) }
                buttons(muteBtn!!)
            }
        }

        // ---------------- DSD
        card("DSD files") {
            val native = if (dd.connected) dd.nativeDsd else null
            line("How DSD goes to the DAC", when (native) {
                true -> "This DAC has a native DSD setting over USB. Native DSD sends the DSD bitstream unchanged (bit-perfect). Use DoP only if the DAC's manual says USB supports it."
                false -> "This DAC shows no native DSD setting over USB. Convert to PCM works with every DAC; use DoP only if the DAC's manual says USB supports it."
                null -> "Native DSD sends the DSD bitstream unchanged. Convert to PCM works with every DAC. Use DoP only if the DAC's manual says USB supports it."
            }, 17f)
            val cur = Hub.dsdOut
            buttons(
                Ui.pill(act, "Convert to PCM", selected = cur == app.playerpure.audio.DsdOut.PCM) { Hub.dsdOut = app.playerpure.audio.DsdOut.PCM; refresh() },
                Ui.pill(act, "Native DSD", selected = cur == app.playerpure.audio.DsdOut.NATIVE) { Hub.dsdOut = app.playerpure.audio.DsdOut.NATIVE; refresh() },
                Ui.pill(act, "DoP", selected = cur == app.playerpure.audio.DsdOut.DOP) { Hub.dsdOut = app.playerpure.audio.DsdOut.DOP; refresh() },
            )
            if (cur == app.playerpure.audio.DsdOut.NATIVE) {
                addView(Ui.text(act, "First time: turn the volume down, play the DSD64 test tone below. You should hear a clean, quiet 1 kHz tone. " +
                    "Hiss or noise instead: switch on \"Swap byte order\" and try again.", 15f, Ui.DIM, lines = 4), lp(MATCH, WRAP).apply { topMargin = dp(act, 12) })
                toggle("Swap byte order (native DSD)", "Only if the DSD test tone sounds like noise.", Hub.dsdSwap) { Hub.dsdSwap = it; refresh() }
            }
        }

        // ---------------- test
        card("DAC test") {
            line("Test tones", "A 1 kHz tone at -20 dB for 10 seconds. Start with the volume low. Listen for clicks or dropouts, then save the report.", 17f)
            buttons(
                Ui.pill(act, "44.1 kHz") { playTone(44100) }, Ui.pill(act, "96 kHz") { playTone(96000) },
                Ui.pill(act, "192 kHz") { playTone(192000) }, Ui.pill(act, "384 kHz") { playTone(384000) },
                Ui.pill(act, "DSD64") { playDsdTone(2822400) }, Ui.pill(act, "DSD256") { playDsdTone(11289600) },
            )
            addView(stats.free(), lp(MATCH, WRAP).apply { topMargin = dp(act, 12) })
            buttons(Ui.pill(act, "Save DAC report", R.drawable.pp_ic_usb) { saveReport() })
        }

        // ---------------- what only the DAC itself can do
        card("On the DAC itself") {
            line("Input, filter, output, power, brightness",
                "Most DACs take these only from their own buttons or infrared remote, not over USB. Volume and mute work here when the DAC offers them over USB.", 17f)
        }
        updateDisplay(); updateStats()
        view.removeCallbacks(tick); view.post(tick)
    }

    private fun nudge(stepDb: Double) { Hub.nudgeDacVolume(stepDb) }

    private fun playTone(rate: Int) {
        val t = Track(-100L - rate, "tone:$rate:24", "Test tone 1 kHz, -20 dBFS", "Player Pure", "DAC test", "Player Pure",
            durationMs = 10_000, rate = rate, bits = 24, channels = 2, codec = "PCM")
        act.play(listOf(t))
    }

    private fun playDsdTone(rate: Int) {
        val t = Track(-200L - rate / 1000, "tone:dsd:$rate", "DSD test tone 1 kHz, -20 dB", "Player Pure", "DAC test", "Player Pure",
            durationMs = 10_000, rate = rate, bits = 1, channels = 2, codec = "DSD")
        act.play(listOf(t))
    }

    private fun updateStats() {
        val s = Hub.player.usbStats()
        stats.text = if (s == null) "USB statistics appear while music plays through the DAC."
        else "Sent ${s[2]} packets. Dropouts: ${s[1]}. Clock feedback from the DAC: ${s[3]} accepted, ${s[4]} rejected" +
            (if (s[5] != 0L) " (last raw value 0x%08x)".format(java.util.Locale.ROOT, s[5]) else "") + ". Buffered: ${s[6]} frames."
    }

    private fun saveReport() {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PlayerPure")
        val text = buildString {
            append("Player Pure DAC report ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.UK).format(java.util.Date())).append('\n')
            val v = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (_: Exception) { "" }
            append("Player Pure ").append(v).append('\n')
            append("Status: ").append(Hub.dacStatus).append('\n')
            append("Output: ").append(Hub.player.outputName).append(" | ").append(Hub.player.outputLine).append(" | bit-perfect=").append(Hub.player.bitPerfect).append('\n')
            append("DSD setting: ").append(Hub.dsdOut).append(if (Hub.dsdSwap) " (swapped)" else "").append('\n')
            append("DAC volume: ").append(Hub.dacVolumeDb?.let { "%.2f dB".format(java.util.Locale.ROOT, it) } ?: "n/a")
                .append(", muted: ").append(Hub.dacMuted ?: "n/a").append('\n')
            append("Stats: ").append(stats.text).append('\n')
            append(Hub.link?.report() ?: Hub.lastDacReport ?: "No DAC opened.").append('\n')
        }
        try {
            dir.mkdirs()
            File(dir, "dac-report.txt").writeText(text)
            toast(act, "Saved to Download/PlayerPure/dac-report.txt")
        } catch (e: Exception) { toast(act, "Could not save: ${e.message}") }
    }
}

private fun UsbOutRate(r: Int) = app.playerpure.audio.UsbOut.fmtRate(r)
