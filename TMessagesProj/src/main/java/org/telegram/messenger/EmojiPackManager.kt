/*
 * Pluggable emoji image packs for Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * Pack format (intentionally simple, compatible with Twemoji / OpenMoji / Noto-style exports):
 *   <packs dir>/<packId>/<codepoints>.png|webp
 * where <codepoints> is the lower-case hex code points of the emoji joined with '-', for example
 *   1f600.png                    (grinning face)
 *   1f468-200d-1f469-200d-1f467.png
 * The loader tries the sequence as-is first and then with U+FE0F stripped.
 * Missing images fall back to the built-in Apple-style emoji.pack (EmojiPack), so partial packs work.
 *
 * Packs live in filesDir/emoji_packs/<packId>/ and come from two sources, both extracted by the same extractPack()
 * (same limits, same validation):
 *  - bundled inside the APK as assets/emoji_packs/<packId>.zip (see BUNDLED_PACKS), extracted on demand by
 *    installBundled();
 *  - a zip the user picks with the system file picker (importFromUri()).
 * Nothing is downloaded from the network and no storage permission is needed. Packs installed by older builds (for
 * example from the former Android/data/.../emoji_packs/ drop folder, which is no longer read) are still listed,
 * selectable and deletable.
 */
package org.telegram.messenger

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilenameFilter
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream

object EmojiPackManager {

    const val DEFAULT_PACK = ""
    private const val PREF_KEY = "emojiPackId"

    private const val MAX_ENTRIES = 5000
    // Entries of any kind (also the ones that are ignored because their name is not a codepoint png/webp) and the declared
    // size of the ignored ones are capped too: ZipInputStream has to inflate an ignored entry to get past it.
    private const val MAX_ENTRIES_SEEN = 20000
    private const val MAX_SKIPPED_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 1024L * 1024
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    // Larger source images (e.g. 512px Noto, 618px OpenMoji) are accepted; decode() always downsamples them to ~TARGET_SIDE,
    // so the in-memory cost per emoji stays the same as for 64px packs.
    private const val MAX_SIDE = 1024
    private const val INSTALLED_MARKER = ".installed"
    private const val TARGET_SIDE = 64

    // Packs shipped inside the APK: {id, display name}. The asset is assets/emoji_packs/<id>.zip and must not be compressed
    // again by aapt (see noCompress "zip" in TMessagesProj_App/build.gradle).
    private val BUNDLED_PACKS = arrayOf(
        arrayOf("blob", "Blob"),
    )
    private const val BUNDLED_ASSET_DIR = "emoji_packs/"
    private const val BUNDLED_STAMP_PREFIX = "bundled:"
    private const val IMPORTED_STAMP_PREFIX = "import:"
    private const val IMPORT_TMP_PREFIX = "emoji_import"
    // Serializes extraction (installBundled / importFromUri) and deletion without blocking getActivePackId() callers on the UI thread.
    private val INSTALL_LOCK = Any()

    private val SAFE_ID = Regex("[A-Za-z0-9_.-]{1,48}")
    private val PACK_FILE_NAME = Regex("[0-9a-f]+(-[0-9a-f]+)*\\.(png|webp)")
    private val UNSAFE_ID_CHARS = Regex("[^A-Za-z0-9_.-]")
    private val LEADING_DOTS = Regex("^\\.+")

    @Volatile
    private var activePackId: String? = null

    @Volatile
    private var activeDir: File? = null

    private fun prefs(): SharedPreferences = MessagesController.getGlobalMainSettings()

    @JvmStatic
    fun getPacksDir(): File = File(ApplicationLoader.getFilesDirFixed(), "emoji_packs")

    @JvmStatic
    fun isSafeId(id: String?): Boolean = id != null && SAFE_ID.matches(id) && !id.startsWith(".")

    /**
     * Name for the chooser: the nice title only for the copy extracted from the APK (isBundledInstall); an imported pack
     * with the same id, or any other pack, shows its id, so the two cannot be confused.
     */
    @JvmStatic
    fun getDisplayName(id: String): String {
        if (isBundledInstall(id)) {
            for (pack in BUNDLED_PACKS) {
                if (pack[0] == id) {
                    return pack[1]
                }
            }
        }
        return id
    }

    @JvmStatic
    @Synchronized
    fun getActivePackId(): String {
        val cached = activePackId
        if (cached != null) {
            return cached
        }
        val stored: String? = prefs().getString(PREF_KEY, DEFAULT_PACK)
        // A null preference fails isSafeId() and becomes DEFAULT_PACK, exactly like an unknown pack.
        val result = if (stored == null || DEFAULT_PACK != stored && (!isSafeId(stored) || !File(getPacksDir(), stored).isDirectory)) {
            DEFAULT_PACK
        } else {
            stored
        }
        activePackId = result
        activeDir = if (DEFAULT_PACK == result) null else File(getPacksDir(), result)
        return result
    }

    /** Switches pack and tells Emoji to drop its bitmap cache. Safe to call from the UI thread. */
    @JvmStatic
    @Synchronized
    fun setActivePack(packId: String?) {
        val id = packId ?: DEFAULT_PACK
        if (DEFAULT_PACK != id && (!isSafeId(id) || !File(getPacksDir(), id).isDirectory)) {
            return
        }
        prefs().edit().putString(PREF_KEY, id).apply()
        activePackId = id
        activeDir = if (DEFAULT_PACK == id) null else File(getPacksDir(), id)
        Emoji.onEmojiPackChanged()
    }

    @JvmStatic
    fun listInstalled(): ArrayList<String> {
        val result = ArrayList<String>()
        val dirs = getPacksDir().listFiles()
        if (dirs != null) {
            for (dir in dirs) {
                if (dir.isDirectory && isSafeId(dir.name)) {
                    result.add(dir.name)
                }
            }
        }
        java.util.Collections.sort(result)
        return result
    }

    /**
     * Extracts a pack zip stream into filesDir/emoji_packs/<id>/ (via a temp dir, then an atomic rename) and writes
     * `stamp` into the ".installed" marker. Used by both the file picker import and the bundled assets.
     */
    @Throws(IOException::class)
    private fun extractPack(source: InputStream, id: String, stamp: String) {
        if (!isSafeId(id)) {
            throw IOException("bad pack id")
        }
        synchronized(INSTALL_LOCK) {
            val root = getPacksDir()
            val tmp = File(root, ".$id.tmp")
            val target = File(root, id)
            deleteRecursive(tmp)
            if (!tmp.mkdirs()) {
                throw IOException("cannot create $tmp")
            }
            var entries = 0
            var entriesSeen = 0
            var skipped = 0L
            var total = 0L
            var ok = false
            try {
                ZipInputStream(BufferedInputStream(source)).use { zis ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (++entriesSeen > MAX_ENTRIES_SEEN) {
                            throw IOException("too many zip entries")
                        }
                        if (entry.isDirectory) {
                            continue
                        }
                        // flatten: only the base name is used, so ../ and nested paths cannot escape
                        var base = entry.name
                        base = base.substring(base.lastIndexOf('/') + 1).lowercase(Locale.US)
                        if (!PACK_FILE_NAME.matches(base)) {
                            // ZipInputStream inflates ignored entries anyway to get past them: count the real bytes (the
                            // declared size is -1 for streamed zips with data descriptors) and stop at the cap.
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                skipped += n
                                if (skipped > MAX_SKIPPED_BYTES) {
                                    throw IOException("zip has too much unrelated data")
                                }
                            }
                            continue
                        }
                        if (entry.size > MAX_ENTRY_BYTES) {
                            throw IOException("pack too large") // declared size, rejected before anything is written
                        }
                        if (++entries > MAX_ENTRIES) {
                            throw IOException("too many entries")
                        }
                        val out = File(tmp, base)
                        var size = 0L
                        FileOutputStream(out).use { fos ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n <= 0) break
                                size += n
                                total += n
                                if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                                    throw IOException("pack too large")
                                }
                                fos.write(buf, 0, n)
                            }
                        }
                    }
                    if (entries == 0) {
                        throw IOException("no emoji images in zip")
                    }
                    ok = true
                }
            } finally {
                if (!ok) {
                    deleteRecursive(tmp)
                }
            }
            FileOutputStream(File(tmp, INSTALLED_MARKER)).use { fos ->
                fos.write(stamp.toByteArray(Charsets.UTF_8))
            }
            // Replace an existing pack only after the new one was fully extracted and validated: the old directory is
            // moved aside (not deleted) first, so a failure at this point restores it.
            val old = File(root, ".$id.old")
            deleteRecursive(old)
            val hadTarget = target.exists()
            if (hadTarget && !target.renameTo(old)) {
                deleteRecursive(tmp)
                throw IOException("cannot replace the installed pack")
            }
            if (!tmp.renameTo(target)) {
                if (hadTarget) {
                    old.renameTo(target)
                }
                deleteRecursive(tmp)
                throw IOException("cannot move pack into place")
            }
            deleteRecursive(old)
        }
        if (id == activePackId) {
            Emoji.onEmojiPackChanged()
        }
    }

    /**
     * Installs the packs bundled in the APK assets if they are missing or were extracted by another app build / another
     * asset; returns the ids that were (re)installed. Blocking disk IO (a few seconds for the Blob pack the first time),
     * call it from a background queue (ThemeActivity uses its emojiPackQueue), never from the UI thread.
     * A pack with the same id that the user installed (marker not "bundled:") is left alone: the user's pack wins.
     */
    @JvmStatic
    fun installBundled(): ArrayList<String> {
        val installed = ArrayList<String>()
        val context: Context = ApplicationLoader.applicationContext ?: return installed
        cleanupLeftovers()
        for (pack in BUNDLED_PACKS) {
            val id = pack[0]
            val asset = "$BUNDLED_ASSET_DIR$id.zip"
            try {
                val stamp = bundledStamp(context, asset)
                    ?: continue // asset not shipped in this build
                val current = readMarker(id)
                if (stamp == current) {
                    continue // same app build, same asset: nothing to do
                }
                if (current != null && !current.startsWith(BUNDLED_STAMP_PREFIX)) {
                    continue // the user's own pack with this id: keep it
                }
                context.assets.open(asset).use { input -> extractPack(input, id, stamp) }
                installed.add(id)
            } catch (e: Exception) {
                FileLog.e(e)
            }
        }
        return installed
    }

    /**
     * Imports a pack zip chosen with the system file picker and returns the id it was installed under.
     * The stream is first copied to a cache file (never read into memory, capped at MAX_TOTAL_BYTES while copying), then
     * extracted by the same extractPack() as every other import (flattened names, codepoint-only file names, entry and size
     * limits, temp dir + replace-after-validation). An existing pack with the same id is overwritten. Blocking IO: call it
     * from a background queue (ThemeActivity uses its emojiPackQueue).
     */
    @JvmStatic
    @Throws(IOException::class)
    fun importFromUri(context: Context, uri: Uri): String {
        val id = idFromFileName(queryDisplayName(context, uri))
        val cacheDir: File? = context.cacheDir
        deleteStaleImportFiles(cacheDir)
        val cache = File.createTempFile(IMPORT_TMP_PREFIX, ".zip", cacheDir)
        try {
            var copied = 0L
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("cannot open the selected file")
            input.use { inp ->
                FileOutputStream(cache).use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        copied += n
                        if (copied > MAX_TOTAL_BYTES) {
                            throw IOException("pack too large")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            FileInputStream(cache).use { inp ->
                extractPack(inp, id, IMPORTED_STAMP_PREFIX + copied + ":" + System.currentTimeMillis())
            }
            return id
        } finally {
            cache.delete()
        }
    }

    /** Removes copies left behind by an import whose process was killed (older than 10 minutes, so a running one is safe). */
    private fun deleteStaleImportFiles(cacheDir: File?) {
        val old = cacheDir?.listFiles(FilenameFilter { _, n -> n.startsWith(IMPORT_TMP_PREFIX) })
        if (old != null) {
            val limit = System.currentTimeMillis() - 10 * 60 * 1000L
            for (f in old) {
                if (f.lastModified() < limit) {
                    f.delete()
                }
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                }
            }
        } catch (ignore: Exception) {
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    /**
     * "mypack.zip" -> "mypack". When the name has to be altered (characters outside isSafeId's alphabet are replaced by
     * '_', leading dots dropped, truncated to 48) a stable 6-hex suffix derived from the original name is appended, so
     * that different non-ASCII names do not collapse into the same id: "表情包A.zip" -> "___A_1c3f0e".
     */
    internal fun idFromFileName(fileName: String?): String {
        var name = fileName ?: ""
        name = name.substring(name.lastIndexOf('/') + 1)
        if (name.lowercase(Locale.US).endsWith(".zip")) {
            name = name.substring(0, name.length - 4)
        }
        var safe = LEADING_DOTS.replace(UNSAFE_ID_CHARS.replace(name, "_"), "")
        if (safe == name && safe.length <= 48 && isSafeId(safe)) {
            return safe
        }
        val hash = String.format(Locale.US, "_%06x", name.hashCode() and 0xFFFFFF) // String.hashCode() is specified, so stable
        if (safe.replace("_", "").isEmpty()) {
            safe = "pack"
        }
        if (safe.length > 48 - hash.length) {
            safe = safe.substring(0, 48 - hash.length)
        }
        return safe + hash
    }

    /** True if the installed pack `id` is the copy extracted from the APK (cannot be deleted; it is restored on demand). */
    @JvmStatic
    fun isBundledInstall(id: String): Boolean {
        val marker = readMarker(id)
        if (marker == null || !marker.startsWith(BUNDLED_STAMP_PREFIX)) {
            return false
        }
        for (pack in BUNDLED_PACKS) {
            if (pack[0] == id) {
                return true
            }
        }
        return false
    }

    /**
     * Deletes the extracted directory of an installed pack (never the APK asset, so a bundled pack whose directory was
     * replaced by an import comes back on the next installBundled()). The active pack falls back to the default one.
     * Returns false for the bundled copy itself, an unknown or unsafe id, or when the pack directory cannot be renamed to
     * ".<id>.del" (in that case nothing was changed: the pack stays installed and selected).
     */
    @JvmStatic
    fun deletePack(id: String?): Boolean {
        if (id == null || !isSafeId(id) || isBundledInstall(id)) {
            return false
        }
        val dir = File(getPacksDir(), id)
        if (!dir.isDirectory) {
            return false
        }
        val gone = File(getPacksDir(), ".$id.del")
        synchronized(INSTALL_LOCK) {
            // Take the pack out of the list in one step; if that fails nothing has changed.
            deleteRecursive(gone)
            if (gone.exists() || !dir.renameTo(gone)) {
                return false
            }
        }
        // Leave the pack if it was the active one (this also resets the emoji cache).
        if (id == getActivePackId()) {
            setActivePack(DEFAULT_PACK)
        }
        // Best effort: a leftover .del dir is invisible (dot name) and removed by cleanupLeftovers() later.
        synchronized(INSTALL_LOCK) {
            deleteRecursive(gone)
        }
        return true
    }

    /**
     * Repairs what an interrupted delete or extraction (process killed) left behind: hidden ".<id>.del" / ".<id>.tmp"
     * directories are removed; ".<id>.old" (the previous copy moved aside by extractPack() while replacing a pack) is
     * moved back to "<id>" if that directory is missing, because it is then the only copy, and removed otherwise.
     * Runs under INSTALL_LOCK, so no extraction is in progress.
     */
    private fun cleanupLeftovers() {
        synchronized(INSTALL_LOCK) {
            val root = getPacksDir()
            val stale = root.listFiles(FilenameFilter { _, n ->
                n.startsWith(".") && (n.endsWith(".del") || n.endsWith(".tmp") || n.endsWith(".old"))
            }) ?: return
            for (f in stale) {
                val name = f.name
                if (name.endsWith(".old")) {
                    val id = name.substring(1, name.length - 4)
                    val target = File(root, id)
                    if (isSafeId(id) && !target.exists()) {
                        if (!f.renameTo(target)) {
                            FileLog.e("cannot restore $target")
                        }
                        continue
                    }
                }
                deleteRecursive(f)
            }
        }
    }

    /** "bundled:<asset length>:<package lastUpdateTime>"; changes on every app update/reinstall. Null if the asset is absent. */
    private fun bundledStamp(context: Context, asset: String): String? {
        val length: Long = try {
            context.assets.openFd(asset).use { fd -> fd.length }
        } catch (e: IOException) {
            // either missing or (wrongly) compressed in the APK: openFd only works for stored assets
            try {
                context.assets.open(asset).use { -1L }
            } catch (e2: IOException) {
                return null
            }
        }
        var updated = 0L
        try {
            updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (ignore: Exception) {
        }
        return "$BUNDLED_STAMP_PREFIX$length:$updated"
    }

    /** Content of the ".installed" marker of pack `id`, or null if it is not installed / unreadable. */
    private fun readMarker(id: String): String? {
        val marker = File(File(getPacksDir(), id), INSTALLED_MARKER)
        if (!marker.isFile || marker.length() > 128) {
            return null
        }
        return try {
            FileInputStream(marker).use { input ->
                val data = ByteArray(marker.length().toInt())
                var off = 0
                while (off < data.size) {
                    val n = input.read(data, off, data.size - off)
                    if (n < 0) {
                        break
                    }
                    off += n
                }
                String(data, 0, off, Charsets.UTF_8)
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun deleteRecursive(f: File) {
        val children = f.listFiles()
        if (children != null) {
            for (c in children) {
                deleteRecursive(c)
            }
        }
        f.delete()
    }

    /**
     * Loads the image of EmojiData.data[page][index] from the active pack; null means "use built-in".
     * Called on Utilities.globalQueue, never on the UI thread. It can run concurrently with extractPack()/deletePack()
     * (emojiPackQueue): during a replace a file may briefly be missing, which only returns null (built-in fallback), and
     * the following Emoji.onEmojiPackChanged() bumps the generation and drops anything decoded in between.
     */
    @JvmStatic
    fun load(page: Int, index: Int): Bitmap? {
        getActivePackId()
        val dir = activeDir
        if (dir == null || page < 0 || page >= EmojiData.data.size || index < 0 || index >= EmojiData.data[page].size) {
            return null
        }
        val emoji: String = EmojiData.data[page][index]
        val name = fileBase(emoji, false)
        var f = findFile(dir, name)
        if (f == null) {
            val stripped = fileBase(emoji, true)
            if (stripped != name) {
                f = findFile(dir, stripped)
            }
        }
        return if (f == null) null else decode(f)
    }

    private fun findFile(dir: File, base: String): File? {
        var f = File(dir, "$base.png")
        if (f.isFile) {
            return f
        }
        f = File(dir, "$base.webp")
        return if (f.isFile) f else null
    }

    internal fun fileBase(emoji: CharSequence, stripFE0F: Boolean): String {
        val sb = StringBuilder()
        var i = 0
        while (i < emoji.length) {
            val cp = Character.codePointAt(emoji, i)
            i += Character.charCount(cp)
            if (stripFE0F && cp == 0xFE0F) {
                continue
            }
            if (sb.isNotEmpty()) {
                sb.append('-')
            }
            sb.append(Integer.toHexString(cp))
        }
        return sb.toString()
    }

    private fun decode(f: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            FileInputStream(f).use { input -> BitmapFactory.decodeStream(input, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_SIDE || bounds.outHeight > MAX_SIDE) {
                return null
            }
            val opts = BitmapFactory.Options()
            opts.inScaled = false
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_SIDE) {
                sample *= 2
            }
            opts.inSampleSize = sample
            FileInputStream(f).use { input -> BitmapFactory.decodeStream(input, null, opts) }
        } catch (e: Throwable) {
            FileLog.e(e)
            null
        }
    }
}
