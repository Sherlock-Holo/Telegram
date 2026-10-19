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
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class EmojiPackManager {

    public static final String DEFAULT_PACK = "";
    private static final String PREF_KEY = "emojiPackId";

    private static final int MAX_ENTRIES = 5000;
    // Entries of any kind (also the ones that are ignored because their name is not a codepoint png/webp) and the declared
    // size of the ignored ones are capped too: ZipInputStream has to inflate an ignored entry to get past it.
    private static final int MAX_ENTRIES_SEEN = 20000;
    private static final long MAX_SKIPPED_BYTES = 512L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    // Larger source images (e.g. 512px Noto, 618px OpenMoji) are accepted; decode() always downsamples them to ~TARGET_SIDE,
    // so the in-memory cost per emoji stays the same as for 64px packs.
    private static final int MAX_SIDE = 1024;
    private static final String INSTALLED_MARKER = ".installed";
    private static final int TARGET_SIDE = 64;

    // Packs shipped inside the APK: {id, display name}. The asset is assets/emoji_packs/<id>.zip and must not be compressed
    // again by aapt (see noCompress "zip" in TMessagesProj_App/build.gradle).
    private static final String[][] BUNDLED_PACKS = {
            {"blob", "Blob"},
    };
    private static final String BUNDLED_ASSET_DIR = "emoji_packs/";
    private static final String BUNDLED_STAMP_PREFIX = "bundled:";
    private static final String IMPORTED_STAMP_PREFIX = "import:";
    private static final String IMPORT_TMP_PREFIX = "emoji_import";
    // Serializes extraction (installBundled / importFromUri) and deletion without blocking getActivePackId() callers on the UI thread.
    private static final Object INSTALL_LOCK = new Object();

    private static volatile String activePackId;
    private static volatile File activeDir;

    private EmojiPackManager() {
    }

    private static SharedPreferences prefs() {
        return MessagesController.getGlobalMainSettings();
    }

    public static File getPacksDir() {
        return new File(ApplicationLoader.getFilesDirFixed(), "emoji_packs");
    }

    public static boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.-]{1,48}") && !id.startsWith(".");
    }

    /**
     * Name for the chooser: the nice title only for the copy extracted from the APK (isBundledInstall); an imported pack
     * with the same id, or any other pack, shows its id, so the two cannot be confused.
     */
    public static String getDisplayName(String id) {
        if (isBundledInstall(id)) {
            for (String[] pack : BUNDLED_PACKS) {
                if (pack[0].equals(id)) {
                    return pack[1];
                }
            }
        }
        return id;
    }

    public static synchronized String getActivePackId() {
        if (activePackId == null) {
            String id = prefs().getString(PREF_KEY, DEFAULT_PACK);
            if (!DEFAULT_PACK.equals(id) && (!isSafeId(id) || !new File(getPacksDir(), id).isDirectory())) {
                id = DEFAULT_PACK;
            }
            activePackId = id;
            activeDir = DEFAULT_PACK.equals(id) ? null : new File(getPacksDir(), id);
        }
        return activePackId;
    }

    /** Switches pack and tells Emoji to drop its bitmap cache. Safe to call from the UI thread. */
    public static synchronized void setActivePack(String id) {
        if (id == null) {
            id = DEFAULT_PACK;
        }
        if (!DEFAULT_PACK.equals(id) && (!isSafeId(id) || !new File(getPacksDir(), id).isDirectory())) {
            return;
        }
        prefs().edit().putString(PREF_KEY, id).apply();
        activePackId = id;
        activeDir = DEFAULT_PACK.equals(id) ? null : new File(getPacksDir(), id);
        Emoji.onEmojiPackChanged();
    }

    public static ArrayList<String> listInstalled() {
        ArrayList<String> result = new ArrayList<>();
        File[] dirs = getPacksDir().listFiles();
        if (dirs != null) {
            for (File dir : dirs) {
                if (dir.isDirectory() && isSafeId(dir.getName())) {
                    result.add(dir.getName());
                }
            }
        }
        java.util.Collections.sort(result);
        return result;
    }

    /**
     * Extracts a pack zip stream into filesDir/emoji_packs/<id>/ (via a temp dir, then an atomic rename) and writes
     * {@code stamp} into the ".installed" marker. Used by both the file picker import and the bundled assets.
     */
    private static void extractPack(InputStream source, String id, String stamp) throws IOException {
        if (!isSafeId(id)) {
            throw new IOException("bad pack id");
        }
        synchronized (INSTALL_LOCK) {
            File root = getPacksDir();
            File tmp = new File(root, "." + id + ".tmp");
            File target = new File(root, id);
            deleteRecursive(tmp);
            if (!tmp.mkdirs()) {
                throw new IOException("cannot create " + tmp);
            }
            int entries = 0;
            int entriesSeen = 0;
            long skipped = 0;
            long total = 0;
            boolean ok = false;
            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(source))) {
                ZipEntry entry;
                byte[] buf = new byte[8192];
                while ((entry = zis.getNextEntry()) != null) {
                    if (++entriesSeen > MAX_ENTRIES_SEEN) {
                        throw new IOException("too many zip entries");
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    // flatten: only the base name is used, so ../ and nested paths cannot escape
                    String base = entry.getName();
                    base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.US);
                    if (!base.matches("[0-9a-f]+(-[0-9a-f]+)*\\.(png|webp)")) {
                        // ZipInputStream inflates ignored entries anyway to get past them: count the real bytes (the
                        // declared size is -1 for streamed zips with data descriptors) and stop at the cap.
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            skipped += n;
                            if (skipped > MAX_SKIPPED_BYTES) {
                                throw new IOException("zip has too much unrelated data");
                            }
                        }
                        continue;
                    }
                    if (entry.getSize() > MAX_ENTRY_BYTES) {
                        throw new IOException("pack too large"); // declared size, rejected before anything is written
                    }
                    if (++entries > MAX_ENTRIES) {
                        throw new IOException("too many entries");
                    }
                    File out = new File(tmp, base);
                    long size = 0;
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            size += n;
                            total += n;
                            if (size > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                                throw new IOException("pack too large");
                            }
                            fos.write(buf, 0, n);
                        }
                    }
                }
                if (entries == 0) {
                    throw new IOException("no emoji images in zip");
                }
                ok = true;
            } finally {
                if (!ok) {
                    deleteRecursive(tmp);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(new File(tmp, INSTALLED_MARKER))) {
                fos.write(stamp.getBytes("UTF-8"));
            }
            // Replace an existing pack only after the new one was fully extracted and validated: the old directory is
            // moved aside (not deleted) first, so a failure at this point restores it.
            File old = new File(root, "." + id + ".old");
            deleteRecursive(old);
            boolean hadTarget = target.exists();
            if (hadTarget && !target.renameTo(old)) {
                deleteRecursive(tmp);
                throw new IOException("cannot replace the installed pack");
            }
            if (!tmp.renameTo(target)) {
                if (hadTarget) {
                    old.renameTo(target);
                }
                deleteRecursive(tmp);
                throw new IOException("cannot move pack into place");
            }
            deleteRecursive(old);
        }
        if (id.equals(activePackId)) {
            Emoji.onEmojiPackChanged();
        }
    }

    /**
     * Installs the packs bundled in the APK assets if they are missing or were extracted by another app build / another
     * asset; returns the ids that were (re)installed. Blocking disk IO (a few seconds for the Blob pack the first time),
     * call it from a background queue (ThemeActivity uses its emojiPackQueue), never from the UI thread.
     * A pack with the same id that the user installed (marker not "bundled:") is left alone: the user's pack wins.
     */
    public static ArrayList<String> installBundled() {
        ArrayList<String> installed = new ArrayList<>();
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return installed;
        }
        cleanupLeftovers();
        for (String[] pack : BUNDLED_PACKS) {
            String id = pack[0];
            String asset = BUNDLED_ASSET_DIR + id + ".zip";
            try {
                String stamp = bundledStamp(context, asset);
                if (stamp == null) {
                    continue; // asset not shipped in this build
                }
                String current = readMarker(id);
                if (stamp.equals(current)) {
                    continue; // same app build, same asset: nothing to do
                }
                if (current != null && !current.startsWith(BUNDLED_STAMP_PREFIX)) {
                    continue; // the user's own pack with this id: keep it
                }
                try (InputStream is = context.getAssets().open(asset)) {
                    extractPack(is, id, stamp);
                }
                installed.add(id);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return installed;
    }

    /**
     * Imports a pack zip chosen with the system file picker and returns the id it was installed under.
     * The stream is first copied to a cache file (never read into memory, capped at MAX_TOTAL_BYTES while copying), then
     * extracted by the same extractPack() as every other import (flattened names, codepoint-only file names, entry and size
     * limits, temp dir + replace-after-validation). An existing pack with the same id is overwritten. Blocking IO: call it
     * from a background queue (ThemeActivity uses its emojiPackQueue).
     */
    public static String importFromUri(Context context, Uri uri) throws IOException {
        String id = idFromFileName(queryDisplayName(context, uri));
        File cacheDir = context.getCacheDir();
        deleteStaleImportFiles(cacheDir);
        File cache = File.createTempFile(IMPORT_TMP_PREFIX, ".zip", cacheDir);
        try {
            long copied = 0;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("cannot open the selected file");
                }
                try (FileOutputStream out = new FileOutputStream(cache)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        copied += n;
                        if (copied > MAX_TOTAL_BYTES) {
                            throw new IOException("pack too large");
                        }
                        out.write(buf, 0, n);
                    }
                }
            }
            try (InputStream is = new FileInputStream(cache)) {
                extractPack(is, id, IMPORTED_STAMP_PREFIX + copied + ":" + System.currentTimeMillis());
            }
            return id;
        } finally {
            cache.delete();
        }
    }

    /** Removes copies left behind by an import whose process was killed (older than 10 minutes, so a running one is safe). */
    private static void deleteStaleImportFiles(File cacheDir) {
        File[] old = cacheDir == null ? null : cacheDir.listFiles((d, n) -> n.startsWith(IMPORT_TMP_PREFIX));
        if (old != null) {
            long limit = System.currentTimeMillis() - 10 * 60 * 1000L;
            for (File f : old) {
                if (f.lastModified() < limit) {
                    f.delete();
                }
            }
        }
    }

    private static String queryDisplayName(Context context, Uri uri) {
        String name = null;
        try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Exception ignore) {
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }
        return name;
    }

    /**
     * "mypack.zip" -> "mypack". When the name has to be altered (characters outside isSafeId's alphabet are replaced by
     * '_', leading dots dropped, truncated to 48) a stable 6-hex suffix derived from the original name is appended, so
     * that different non-ASCII names do not collapse into the same id: "表情包A.zip" -> "___A_1c3f0e".
     */
    static String idFromFileName(String fileName) {
        String name = fileName == null ? "" : fileName;
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.toLowerCase(Locale.US).endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }
        String safe = name.replaceAll("[^A-Za-z0-9_.-]", "_").replaceAll("^\\.+", "");
        if (safe.equals(name) && safe.length() <= 48 && isSafeId(safe)) {
            return safe;
        }
        String hash = String.format(Locale.US, "_%06x", name.hashCode() & 0xFFFFFF); // String.hashCode() is specified, so stable
        if (safe.replace("_", "").isEmpty()) {
            safe = "pack";
        }
        if (safe.length() > 48 - hash.length()) {
            safe = safe.substring(0, 48 - hash.length());
        }
        return safe + hash;
    }

    /** True if the installed pack {@code id} is the copy extracted from the APK (cannot be deleted; it is restored on demand). */
    public static boolean isBundledInstall(String id) {
        String marker = readMarker(id);
        if (marker == null || !marker.startsWith(BUNDLED_STAMP_PREFIX)) {
            return false;
        }
        for (String[] pack : BUNDLED_PACKS) {
            if (pack[0].equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deletes the extracted directory of an installed pack (never the APK asset, so a bundled pack whose directory was
     * replaced by an import comes back on the next installBundled()). The active pack falls back to the default one.
     * Returns false for the bundled copy itself, an unknown or unsafe id, or when the pack directory cannot be renamed to
     * ".<id>.del" (in that case nothing was changed: the pack stays installed and selected).
     */
    public static boolean deletePack(String id) {
        if (!isSafeId(id) || isBundledInstall(id)) {
            return false;
        }
        File dir = new File(getPacksDir(), id);
        if (!dir.isDirectory()) {
            return false;
        }
        File gone = new File(getPacksDir(), "." + id + ".del");
        synchronized (INSTALL_LOCK) {
            // Take the pack out of the list in one step; if that fails nothing has changed.
            deleteRecursive(gone);
            if (gone.exists() || !dir.renameTo(gone)) {
                return false;
            }
        }
        // Leave the pack if it was the active one (this also resets the emoji cache).
        if (id.equals(getActivePackId())) {
            setActivePack(DEFAULT_PACK);
        }
        // Best effort: a leftover .del dir is invisible (dot name) and removed by cleanupLeftovers() later.
        synchronized (INSTALL_LOCK) {
            deleteRecursive(gone);
        }
        return true;
    }

    /**
     * Repairs what an interrupted delete or extraction (process killed) left behind: hidden ".<id>.del" / ".<id>.tmp"
     * directories are removed; ".<id>.old" (the previous copy moved aside by extractPack() while replacing a pack) is
     * moved back to "<id>" if that directory is missing, because it is then the only copy, and removed otherwise.
     * Runs under INSTALL_LOCK, so no extraction is in progress.
     */
    private static void cleanupLeftovers() {
        synchronized (INSTALL_LOCK) {
            File root = getPacksDir();
            File[] stale = root.listFiles((d, n) -> n.startsWith(".") && (n.endsWith(".del") || n.endsWith(".tmp") || n.endsWith(".old")));
            if (stale == null) {
                return;
            }
            for (File f : stale) {
                String name = f.getName();
                if (name.endsWith(".old")) {
                    String id = name.substring(1, name.length() - 4);
                    File target = new File(root, id);
                    if (isSafeId(id) && !target.exists()) {
                        if (!f.renameTo(target)) {
                            FileLog.e("cannot restore " + target);
                        }
                        continue;
                    }
                }
                deleteRecursive(f);
            }
        }
    }

    /** "bundled:<asset length>:<package lastUpdateTime>"; changes on every app update/reinstall. Null if the asset is absent. */
    private static String bundledStamp(Context context, String asset) {
        long length;
        try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd(asset)) {
            length = fd.getLength();
        } catch (IOException e) {
            // either missing or (wrongly) compressed in the APK: openFd only works for stored assets
            try (InputStream is = context.getAssets().open(asset)) {
                length = -1;
            } catch (IOException e2) {
                return null;
            }
        }
        long updated = 0;
        try {
            updated = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignore) {
        }
        return BUNDLED_STAMP_PREFIX + length + ":" + updated;
    }

    /** Content of the ".installed" marker of pack {@code id}, or null if it is not installed / unreadable. */
    private static String readMarker(String id) {
        File marker = new File(new File(getPacksDir(), id), INSTALLED_MARKER);
        if (!marker.isFile() || marker.length() > 128) {
            return null;
        }
        try (InputStream is = new FileInputStream(marker)) {
            byte[] data = new byte[(int) marker.length()];
            int off = 0;
            while (off < data.length) {
                int n = is.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return new String(data, 0, off, "UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    /**
     * Loads the image of EmojiData.data[page][index] from the active pack; null means "use built-in".
     * Called on Utilities.globalQueue, never on the UI thread. It can run concurrently with extractPack()/deletePack()
     * (emojiPackQueue): during a replace a file may briefly be missing, which only returns null (built-in fallback), and
     * the following Emoji.onEmojiPackChanged() bumps the generation and drops anything decoded in between.
     */
    public static Bitmap load(int page, int index) {
        getActivePackId();
        File dir = activeDir;
        if (dir == null || page < 0 || page >= EmojiData.data.length || index < 0 || index >= EmojiData.data[page].length) {
            return null;
        }
        String emoji = EmojiData.data[page][index];
        String name = fileBase(emoji, false);
        File f = findFile(dir, name);
        if (f == null) {
            String stripped = fileBase(emoji, true);
            if (!stripped.equals(name)) {
                f = findFile(dir, stripped);
            }
        }
        return f == null ? null : decode(f);
    }

    private static File findFile(File dir, String base) {
        File f = new File(dir, base + ".png");
        if (f.isFile()) {
            return f;
        }
        f = new File(dir, base + ".webp");
        return f.isFile() ? f : null;
    }

    static String fileBase(CharSequence emoji, boolean stripFE0F) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < emoji.length(); ) {
            int cp = Character.codePointAt(emoji, i);
            i += Character.charCount(cp);
            if (stripFE0F && cp == 0xFE0F) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('-');
            }
            sb.append(Integer.toHexString(cp));
        }
        return sb.toString();
    }

    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = new FileInputStream(f)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_SIDE || bounds.outHeight > MAX_SIDE) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= TARGET_SIDE) {
                sample *= 2;
            }
            opts.inSampleSize = sample;
            try (InputStream is = new FileInputStream(f)) {
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}
