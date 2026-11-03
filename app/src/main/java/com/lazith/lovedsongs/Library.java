package com.lazith.lovedsongs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Reads and writes the songs on the device (same files the sync server uses). */
final class Library {
    private Library() {}

    static List<Song> load() {
        List<Song> out = new ArrayList<Song>();
        if (Core.server == null) return out;
        for (String id : Core.server.songIds()) {
            String m = Core.server.metaText(id);
            if (m == null) continue;
            Song s = Song.parse(id, m);
            if (s != null) out.add(s);
        }
        Collections.sort(out, new Comparator<Song>() {
            @Override public int compare(Song a, Song b) { return a.addedAt == b.addedAt ? 0 : (a.addedAt < b.addedAt ? 1 : -1); }
        });
        return out;
    }

    static void save(Song s) throws Exception {
        s.updatedAt = System.currentTimeMillis();
        Core.server.saveMeta(s.id, s.toJson(), s.updatedAt, s.addedAt, s.type);
    }

    static void delete(String id) { Core.server.removeSong(id); }

    private static String displayName(Context c, Uri u) {
        Cursor q = null;
        try {
            q = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (q != null && q.moveToFirst()) return q.getString(0);
        } catch (Exception ignored) {
        } finally { if (q != null) q.close(); }
        return "Untitled";
    }

    /** Copies the file into the app, reads its tags (title, artist, embedded cover) and saves it. */
    static Song importUri(Context c, Uri u, long addedAt) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        File dest = Core.server.audioFile(id);
        dest.getParentFile().mkdirs();
        InputStream in = c.getContentResolver().openInputStream(u);
        if (in == null) throw new Exception("cannot open");
        try {
            FileOutputStream o = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            } finally { o.close(); }
        } finally { in.close(); }

        Song s = new Song();
        s.id = id;
        s.addedAt = addedAt;
        String name = displayName(c, u);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String mime = c.getContentResolver().getType(u);
        s.type = mime != null && mime.startsWith("audio/") ? mime : "audio/mpeg";
        s.title = base;
        s.artist = "";
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(dest.getAbsolutePath());
            String t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            if (a == null) a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (t != null && t.trim().length() > 0) s.title = t.trim();
            if (a != null && a.trim().length() > 0) s.artist = a.trim();
            if (d != null) s.duration = Long.parseLong(d) / 1000.0;
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null) {
                Bitmap b = squareFromBytes(pic, 400);
                if (b != null) s.cover = toDataUrl(b);
            }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        save(s);
        return s;
    }

    static String toDataUrl(Bitmap b) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 86, bo);
        return "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    private static Bitmap crop(Bitmap src, int size) {
        int w = src.getWidth(), h = src.getHeight(), side = Math.min(w, h);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, new Rect((w - side) / 2, (h - side) / 2, (w - side) / 2 + side, (h - side) / 2 + side),
                new Rect(0, 0, size, size), new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private static int sample(int w, int h, int target) {
        int s = 1;
        while (Math.min(w, h) / (s * 2) >= target) s *= 2;
        return s;
    }

    static Bitmap squareFromBytes(byte[] d, int size) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(d, 0, d.length, o);
        if (o.outWidth <= 0) return null;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample(o.outWidth, o.outHeight, size);
        Bitmap b = BitmapFactory.decodeByteArray(d, 0, d.length, o2);
        return b == null ? null : crop(b, size);
    }

    /** A picked picture, centre-cropped to a 1:1 square. */
    static Bitmap squareFromUri(Context c, Uri u, int size) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(u);
            BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (o.outWidth <= 0) return null;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample(o.outWidth, o.outHeight, size);
            in = c.getContentResolver().openInputStream(u);
            Bitmap b = BitmapFactory.decodeStream(in, null, o2);
            in.close();
            return b == null ? null : crop(b, size);
        } catch (Exception e) {
            return null;
        }
    }

    /** Small bitmap for a list thumbnail, from the saved data URL. */
    static Bitmap thumb(Song s, int px) {
        try {
            if (s.cover == null) return null;
            int i = s.cover.indexOf("base64,");
            if (i < 0) return null;
            byte[] d = Base64.decode(s.cover.substring(i + 7), Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample(Math.max(1, o.outWidth), Math.max(1, o.outHeight), px);
            return BitmapFactory.decodeByteArray(d, 0, d.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }
}
