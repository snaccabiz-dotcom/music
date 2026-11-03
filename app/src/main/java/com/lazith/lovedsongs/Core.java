package com.lazith.lovedsongs;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.UUID;

/** One server + one code + one key for the whole life of the app process. */
final class Core {
    static SongServer server;
    static int port;
    static String code;
    static String key;
    static File dir;

    private Core() {}

    static synchronized void init(final Context ctx) throws IOException {
        if (server != null) return;
        final Context app = ctx.getApplicationContext();
        android.content.SharedPreferences sp = app.getSharedPreferences("loved", Context.MODE_PRIVATE);
        code = sp.getString("code", null);
        if (code == null) {
            code = String.format("%06d", new SecureRandom().nextInt(1000000));
            sp.edit().putString("code", code).apply();
        }
        key = UUID.randomUUID().toString();
        dir = new File(app.getFilesDir(), "library");
        SongServer s = new SongServer(dir, new SongServer.Assets() {
            @Override
            public byte[] read(String name) throws IOException {
                InputStream in = app.getAssets().open(name);
                try {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    return bo.toByteArray();
                } finally {
                    in.close();
                }
            }
        }, code, key);
        port = s.start(8765, 8774);
        server = s;
    }
}
