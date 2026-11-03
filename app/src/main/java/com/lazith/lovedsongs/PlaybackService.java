package com.lazith.lovedsongs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The music player itself. It runs as a foreground "media playback" service, so Android keeps it
 * alive with the screen off, plays the next song by itself, and shows the lock-screen / notification player.
 */
public class PlaybackService extends Service {
    interface Cmd { void go(PlaybackService s); }

    static final String CHANNEL = "music";
    static final int NID = 7;
    static final String A_PLAY = "com.lazith.lovedsongs.PLAY";
    static final String A_PAUSE = "com.lazith.lovedsongs.PAUSE";
    static final String A_NEXT = "com.lazith.lovedsongs.NEXT";
    static final String A_PREV = "com.lazith.lovedsongs.PREV";

    static volatile PlaybackService inst;
    private static final List<Cmd> pending = new ArrayList<Cmd>();

    // what the page reads (updated on the main thread)
    static volatile String vId = "";
    static volatile boolean vPlaying = false;
    static volatile int vPos = 0, vDur = 0, vRepeat = 0; static volatile long vAt = 0;
    static volatile boolean vShuffle = false;

    static void run(final Cmd c) {
        synchronized (pending) {
            final PlaybackService s = inst;
            if (s != null) s.main.post(new Runnable() { public void run() { c.go(s); } });
            else pending.add(c);
        }
    }

    static String stateJson() {
        return "{\"id\":\"" + vId + "\",\"playing\":" + vPlaying + ",\"pos\":" + vPos + ",\"dur\":" + vDur
                + ",\"shuffle\":" + vShuffle + ",\"repeat\":" + vRepeat + "}";
    }

    final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager am;
    private NotificationManager nm;
    private PowerManager.WakeLock lock;
    private final List<String> queue = new ArrayList<String>();
    private final ArrayList<String> hist = new ArrayList<String>();
    private final Random rnd = new Random();
    private String curId = "";
    private boolean playing, prepared, wantPlay, resumeOnFocus, shuffle;
    private int repeat, token;
    private String title = "Music", artist = "", coverKey = "";
    private Bitmap art, defaultArt;
    private long dur;

    // ------------------------------------------------------------------ lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        try { Core.init(this); } catch (Exception ignored) {}
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lovedsongs:play");
        lock.setReferenceCounted(false);
        defaultArt = BitmapFactory.decodeResource(getResources(), R.drawable.default_art);
        art = defaultArt;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }

        session = new MediaSession(this, "Music");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(false); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seek((int) pos); }
            @Override public void onStop() { pause(); }
        });
        session.setActive(true);
        IntentFilter nf = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, nf, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, nf);

        synchronized (pending) {
            inst = this;
            for (final Cmd c : pending) main.post(new Runnable() { public void run() { c.go(PlaybackService.this); } });
            pending.clear();
        }
        updateState();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_PLAY.equals(a)) resume();
        else if (A_PAUSE.equals(a)) pause();
        else if (A_NEXT.equals(a)) next(false);
        else if (A_PREV.equals(a)) prev();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        inst = null;
        token++;
        releasePlayer();
        if (lock.isHeld()) lock.release();
        try { unregisterReceiver(noisy); } catch (Exception ignored) {}
        session.setActive(false);
        session.release();
        super.onDestroy();
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { pause(); }
    };

    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) { resumeOnFocus = false; pause(); }
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { if (playing) { resumeOnFocus = true; pause(); } }
            else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { if (mp != null) mp.setVolume(0.25f, 0.25f); }
            else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                if (mp != null) mp.setVolume(1f, 1f);
                if (resumeOnFocus) { resumeOnFocus = false; resume(); }
            }
        }
    };

    // ------------------------------------------------------------------ commands (main thread)
    void cmdPlay(String id) {
        if (id == null || id.isEmpty()) return;
        startTrack(id, true);
    }

    void toggle() {
        if (curId.isEmpty()) return;
        if (playing || (wantPlay && !prepared)) pause(); else resume();
    }

    void resume() {
        if (curId.isEmpty()) {
            if (queue.isEmpty()) return;
            startTrack(queue.get(0), false);
            return;
        }
        wantPlay = true;
        if (prepared && mp != null) startPlayback();
        publish();
    }

    void pause() {
        wantPlay = false;
        if (mp != null && prepared) { try { vPos = mp.getCurrentPosition(); vAt = android.os.SystemClock.elapsedRealtime(); if (mp.isPlaying()) mp.pause(); } catch (Exception ignored) {} }
        playing = false;
        if (lock.isHeld()) lock.release();
        publish();
    }

    void seek(int ms) {
        if (mp != null && prepared) { try { mp.seekTo(ms); } catch (Exception ignored) {} }
        vPos = ms; vAt = android.os.SystemClock.elapsedRealtime();
        updateState();
    }

    void next(boolean auto) {
        String id = nextId(auto);
        if (id == null) {
            // end of the list: rest at the start of this song
            if (mp != null && prepared) { try { mp.seekTo(0); } catch (Exception ignored) {} }
            vPos = 0; vAt = android.os.SystemClock.elapsedRealtime(); wantPlay = false; playing = false;
            if (lock.isHeld()) lock.release();
            publish();
            return;
        }
        startTrack(id, true);
    }

    void prev() {
        if (curId.isEmpty()) return;
        if (vPos > 3000 || queue.size() < 2) { seek(0); return; }
        if (!hist.isEmpty()) { startTrack(hist.remove(hist.size() - 1), false); return; }
        int i = queue.indexOf(curId);
        int n = i <= 0 ? queue.size() - 1 : i - 1;
        startTrack(queue.get(n), false);
    }

    void setShuffle(boolean v) { shuffle = v; vShuffle = v; }
    void setRepeat(int v) { repeat = v; vRepeat = v; }

    void setQueue(String csv) {
        queue.clear();
        if (csv != null && !csv.isEmpty()) for (String s : csv.split(",")) if (!s.isEmpty()) queue.add(s);
        if (!curId.isEmpty()) {
            if (!queue.contains(curId)) { stopAll(); return; }
            if (loadMeta(curId)) publish();   // title / artist / cover edited
        }
    }

    void stopAll() {
        token++;
        releasePlayer();
        curId = ""; playing = false; wantPlay = false; prepared = false;
        vId = ""; vPlaying = false; vPos = 0; vAt = android.os.SystemClock.elapsedRealtime(); vDur = 0;
        if (lock.isHeld()) lock.release();
        am.abandonAudioFocus(focus);
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        nm.cancel(NID);
        updateState();
    }

    // ------------------------------------------------------------------ playing
    private String nextId(boolean auto) {
        if (queue.isEmpty()) return null;
        int i = queue.indexOf(curId);
        if (shuffle && queue.size() > 1) {
            int n;
            do { n = rnd.nextInt(queue.size()); } while (n == i);
            return queue.get(n);
        }
        int n = i + 1;
        if (n >= queue.size()) {
            if (repeat == 1 || !auto) n = 0; else return null;
        }
        return queue.get(n);
    }

    private void startTrack(String id, boolean pushHist) {
        final int my = ++token;
        releasePlayer();
        File f = new File(Core.dir, "audio/" + id);
        if (!f.isFile()) { stopAll(); return; }
        if (pushHist && !curId.isEmpty() && !curId.equals(id)) {
            hist.add(curId);
            if (hist.size() > 100) hist.remove(0);
        }
        curId = id;
        loadMeta(id);
        prepared = false; wantPlay = true; playing = false;
        vPos = 0; vAt = android.os.SystemClock.elapsedRealtime(); dur = 0;
        try {
            MediaPlayer m = new MediaPlayer();
            mp = m;
            m.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            m.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            FileInputStream fis = new FileInputStream(f);
            try { m.setDataSource(fis.getFD()); } finally { fis.close(); }
            m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    if (my != token) return;
                    prepared = true;
                    dur = p.getDuration();
                    if (wantPlay) startPlayback();
                    publish();
                }
            });
            m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) {
                    if (my != token) return;
                    if (repeat == 2) { try { p.seekTo(0); p.start(); } catch (Exception ignored) {} }
                    else next(true);
                }
            });
            m.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) {
                    if (my == token) main.post(new Runnable() { public void run() { next(true); } });
                    return true;
                }
            });
            m.prepareAsync();
        } catch (Exception e) {
            stopAll();
            return;
        }
        publish();
    }

    private void startPlayback() {
        if (mp == null || !prepared) return;
        if (am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            wantPlay = false; playing = false; return;
        }
        try { mp.start(); playing = true; } catch (Exception e) { playing = false; return; }
        if (!lock.isHeld()) lock.acquire(6 * 60 * 60 * 1000L);
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    private void releasePlayer() {
        main.removeCallbacks(ticker);
        if (mp != null) {
            try { mp.setOnPreparedListener(null); mp.setOnCompletionListener(null); mp.setOnErrorListener(null); mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        prepared = false;
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (mp != null && prepared && playing) {
                try { vPos = mp.getCurrentPosition(); vAt = android.os.SystemClock.elapsedRealtime(); } catch (Exception ignored) {}
                main.postDelayed(this, 250);
            }
        }
    };

    // ------------------------------------------------------------------ lock screen + notification
    /** Reads title / artist / cover of a song from its saved info. Returns true if something changed. */
    private boolean loadMeta(String id) {
        String t = "Music", a = "", key = "";
        Bitmap b = null;
        try {
            File f = new File(Core.dir, "meta/" + id + ".json");
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject o = new JSONObject(new String(bo.toByteArray(), "UTF-8"));
            t = o.optString("title", "Music");
            a = o.optString("artist", "");
            String cover = o.optString("cover", "");
            key = id + ":" + cover.length();
            int i = cover.indexOf("base64,");
            if (cover.startsWith("data:image") && i > 0) {
                byte[] raw = Base64.decode(cover.substring(i + 7), Base64.DEFAULT);
                b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            }
        } catch (Exception ignored) {}
        if (b == null) { b = defaultArt; if (key.isEmpty()) key = id + ":0"; }
        boolean changed = !t.equals(title) || !a.equals(artist) || !key.equals(coverKey);
        title = t; artist = a; coverKey = key; art = b;
        return changed;
    }

    private void updateState() {
        long pos = vPos;
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP);
        int st = curId.isEmpty() ? PlaybackState.STATE_STOPPED : (playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
        b.setState(st, pos, playing ? 1f : 0f, SystemClock.elapsedRealtime());
        session.setPlaybackState(b.build());
    }

    private void updateMetadata() {
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Loved Songs")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .putBitmap(MediaMetadata.METADATA_KEY_ART, art)
                .build());
    }

    private PendingIntent act(String action, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action action(int icon, String label, PendingIntent pi) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build();
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title)
                .setContentText(artist)
                .setLargeIcon(art)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(playing)
                .addAction(action(R.drawable.ic_prev, "Previous", act(A_PREV, 1)))
                .addAction(playing ? action(R.drawable.ic_pause, "Pause", act(A_PAUSE, 2)) : action(R.drawable.ic_play, "Play", act(A_PLAY, 3)))
                .addAction(action(R.drawable.ic_next, "Next", act(A_NEXT, 4)))
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private void publish() {
        vId = curId; vPlaying = playing; vShuffle = shuffle; vRepeat = repeat; vDur = (int) dur;
        if (curId.isEmpty()) { updateState(); return; }
        updateMetadata();
        updateState();
        Notification n = buildNotification();
        if (playing) {
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(NID, n);
            } catch (Exception e) {
                nm.notify(NID, n);
            }
        } else {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
            nm.notify(NID, n);
        }
    }
}
