/*
 * PoC: pluggable emoji image packs for Telegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * NOT COMPILED / NOT TESTED on a device (see telegram-research-report.md, section 3).
 *
 * Pack format (intentionally simple, compatible with Twemoji / OpenMoji / Noto-style exports):
 *   <packs dir>/<packId>/<codepoints>.png|webp
 * where <codepoints> is the lower-case hex code points of the emoji joined with '-', for example
 *   1f600.png                    (grinning face)
 *   1f468-200d-1f469-200d-1f467.png
 * The loader tries the sequence as-is first and then with U+FE0F stripped.
 * Missing images fall back to the built-in Apple-style emoji.pack (EmojiPack), so partial packs work.
 *
 * Packs are installed from zip files dropped into
 *   Android/data/<applicationId>/files/emoji_packs/<packId>.zip
 * (app-specific external storage, no permission needed). Nothing is downloaded from the network.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

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
    private static final long MAX_ENTRY_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    // Larger source images (e.g. 512px Noto, 618px OpenMoji) are accepted; decode() always downsamples them to ~TARGET_SIDE,
    // so the in-memory cost per emoji stays the same as for 64px packs.
    private static final int MAX_SIDE = 1024;
    private static final String INSTALLED_MARKER = ".installed";
    private static final int TARGET_SIDE = 64;

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

    /** Directory users drop *.zip packs into (app-specific external dir, may be null). */
    public static File getImportDir() {
        Context context = ApplicationLoader.applicationContext;
        File base = context.getExternalFilesDir(null);
        return base == null ? null : new File(base, "emoji_packs");
    }

    public static boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.-]{1,48}") && !id.startsWith(".");
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

    /** Installs every zip found in the import dir; returns the ids that were installed. */
    public static ArrayList<String> installPendingZips() {
        ArrayList<String> installed = new ArrayList<>();
        File importDir = getImportDir();
        File[] zips = importDir == null ? null : importDir.listFiles((d, n) -> n.toLowerCase(Locale.US).endsWith(".zip"));
        if (zips == null) {
            return installed;
        }
        for (File zip : zips) {
            String name = zip.getName();
            String id = name.substring(0, name.length() - 4);
            if (!isSafeId(id)) {
                continue;
            }
            try {
                if (isUpToDate(zip, id)) {
                    continue; // already installed from this exact zip: no IO, no cache reset
                }
                installZip(zip, id);
                installed.add(id);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return installed;
    }

    public static void installZip(File zip, String id) throws IOException {
        if (!isSafeId(id)) {
            throw new IOException("bad pack id");
        }
        File root = getPacksDir();
        File tmp = new File(root, "." + id + ".tmp");
        File target = new File(root, id);
        deleteRecursive(tmp);
        if (!tmp.mkdirs()) {
            throw new IOException("cannot create " + tmp);
        }
        int entries = 0;
        long total = 0;
        boolean ok = false;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                // flatten: only the base name is used, so ../ and nested paths cannot escape
                String base = entry.getName();
                base = base.substring(base.lastIndexOf('/') + 1).toLowerCase(Locale.US);
                if (!base.matches("[0-9a-f]+(-[0-9a-f]+)*\\.(png|webp)")) {
                    continue;
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
            fos.write(zipStamp(zip).getBytes("UTF-8"));
        }
        deleteRecursive(target);
        if (!tmp.renameTo(target)) {
            deleteRecursive(tmp);
            throw new IOException("cannot move pack into place");
        }
        if (id.equals(activePackId)) {
            Emoji.onEmojiPackChanged();
        }
    }

    private static String zipStamp(File zip) {
        return zip.length() + ":" + zip.lastModified();
    }

    /** True if pack {@code id} is installed and was extracted from a zip with the same size and mtime as {@code zip}. */
    private static boolean isUpToDate(File zip, String id) {
        File marker = new File(new File(getPacksDir(), id), INSTALLED_MARKER);
        if (!marker.isFile() || marker.length() > 128) {
            return false;
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
            return zipStamp(zip).equals(new String(data, 0, off, "UTF-8"));
        } catch (IOException e) {
            return false;
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
     * Called on Utilities.globalQueue, never on the UI thread.
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
