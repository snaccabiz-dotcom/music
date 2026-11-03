package com.lazith.lovedsongs;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_ADD = 1, REQ_COVER = 2, REQ_PFP = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private Bitmap defThumb;

    private List<Song> songs = new ArrayList<Song>();
    private final HashSet<String> entered = new HashSet<String>();
    private long loadedVersion = -1;
    private String lastIds = "";
    private boolean resumed;

    private FrameLayout root;
    private ListView list;
    private SongAdapter adapter;
    private LinearLayout bar;
    private FrameLayout topBar;
    private TextView topTitle, subtitle, emptyView;
    private FrameLayout heroBox;
    private boolean heroHeld, scrolling, scrollDown = true;
    private int lastEnd;
    private android.animation.ValueAnimator beat;
    private View heroCover;
    private ImageView heroHeart, avatar, heroPlay, barPlay;
    private ToggleBtn heroShuffle, barShuffle, barRepeat;
    private SeekView seek;
    private TextView tLeft, tRight;
    private View snack;
    private TextView snackText;
    private int heroHeight = 1;

    private String shownId = null;
    private boolean shownPlaying = false;
    private int shownRepeat = -1;
    private String shownLeft = "", shownRight = "";

    // ================================================================== lifecycle
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        File cf = new File(getFilesDir(), "crash.txt");
        if (cf.exists()) {
            String txt = "";
            try {
                InputStream in = new java.io.FileInputStream(cf);
                byte[] b = new byte[(int) Math.min(cf.length(), 5000)];
                int n = in.read(b);
                in.close();
                txt = new String(b, 0, Math.max(0, n), "UTF-8");
            } catch (Exception ignored) { }
            cf.delete();
            showPlain("Last time the app closed with this error:", txt);
            return;
        }
        try {
            start();
        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            showPlain("Startup error:", sw.toString());
        }
    }

    /** Plain error screen that uses no custom code, so it always shows. */
    private void showPlain(String head, String body) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundColor(Color.BLACK);
        l.setPadding(40, 80, 40, 40);
        TextView h = new TextView(this);
        h.setText(head + "\n(please send a screenshot of this)");
        h.setTextColor(Color.WHITE);
        h.setTextSize(16);
        l.addView(h);
        TextView t = new TextView(this);
        t.setText(body);
        t.setTextColor(0xFFB3B3B3);
        t.setTextSize(11);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        l.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        android.widget.Button bt = new android.widget.Button(this);
        bt.setText("Continue");
        bt.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { recreate(); } });
        l.addView(bt);
        setContentView(l);
    }

    private void start() throws Exception {
        Ui.init(this);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        Core.init(this);
        startService(new Intent(this, PlaybackService.class));
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 11);
        }
        build();
        reload();
        if (!getSharedPreferences("loved", MODE_PRIVATE).getBoolean("welcomed", false)) {
            ui.postDelayed(new Runnable() { @Override public void run() { if (!isFinishing()) welcome(); } }, 350);
        }
    }

    @Override protected void onResume() { super.onResume(); resumed = true; ui.removeCallbacks(tick); ui.post(tick); }
    @Override protected void onPause() { resumed = false; super.onPause(); }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (beat != null) beat.cancel();
        io.shutdownNow();
        worker.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() { moveTaskToBack(true); }

    // ================================================================== layout
    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setHeaderDividersEnabled(false);
        list.setFooterDividersEnabled(false);
        list.setClipToPadding(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setScrollingCacheEnabled(false);
        list.setAnimationCacheEnabled(false);
        list.setPadding(0, 0, 0, Ui.u(14));
        list.addHeaderView(buildHeader(), null, false);
        FrameLayout foot = new FrameLayout(this);
        emptyView = Ui.text(this, "Tap  +  to add your songs", 15, 600, Ui.GRAY);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(0, Ui.u(48), 0, Ui.u(48));
        foot.addView(emptyView, new FrameLayout.LayoutParams(-1, -2));
        list.addFooterView(foot, null, false);
        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                int i = pos - list.getHeaderViewsCount();
                if (i >= 0 && i < songs.size()) playSong(songs.get(i).id);
            }
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int st) { scrolling = st != AbsListView.OnScrollListener.SCROLL_STATE_IDLE; }
            @Override public void onScroll(AbsListView v, int first, int vis, int total) {
                int end = first + vis;
                if (end > lastEnd) scrollDown = true; else if (end < lastEnd) scrollDown = false;
                lastEnd = end;
                onScrolled(first);
            }
        });
        root.addView(list, new FrameLayout.LayoutParams(-1, -1));

        topBar = new FrameLayout(this);
        topBar.setBackgroundColor(0xFF33285F);
        topBar.setAlpha(0f);
        topTitle = Ui.text(this, "Loved Songs", 18, 800, Ui.WHITE);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL);
        tl.leftMargin = Ui.u(16);
        topBar.addView(topTitle, tl);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, Ui.u(52), Gravity.TOP));

        bar = buildBar();
        bar.setVisibility(View.INVISIBLE);
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        snackText = Ui.text(this, "", 14, 600, Ui.BLACK);
        snackText.setPadding(Ui.u(20), Ui.u(12), Ui.u(20), Ui.u(12));
        snackText.setBackground(Ui.rect(Ui.WHITE, 40));
        snackText.setElevation(Ui.u(6));
        snack = snackText;
        snack.setAlpha(0f);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = Ui.u(24);
        root.addView(snack, sl);
    }

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);

        // hero
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.BOTTOM);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF5A44A8, 0xFF33285F, Ui.BG});
        hero.setBackground(g);
        hero.setPadding(Ui.u(16), Ui.u(60), Ui.u(16), Ui.u(20));

        heroCover = new View(this);
        heroCover.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF450AF5, 0xFFC4EFD9}));
        Ui.roundClip(heroCover, 4);
        heroCover.setElevation(Ui.u(8));
        heroHeart = new ImageView(this);
        heroHeart.setImageResource(R.drawable.ic_heart);
        heroHeart.setColorFilter(Ui.WHITE);
        heroBox = new FrameLayout(this);
        heroBox.setClipChildren(false);
        heroBox.addView(heroCover, new FrameLayout.LayoutParams(-1, -1));
        heroBox.addView(heroHeart, new FrameLayout.LayoutParams(Ui.u(88), Ui.u(88), Gravity.CENTER));
        heroHeart.setElevation(Ui.u(9));
        heroBox.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) {
                    heroHeld = true;
                    heroHeart.animate().cancel();
                    heroHeart.animate().scaleX(1.3f).scaleY(1.3f).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
                    return true;
                } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                    heroHeld = false;
                    heroHeart.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new OvershootInterpolator(3f)).start();
                    return true;
                }
                return true;
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(Ui.u(128), Ui.u(128));
        cl.rightMargin = Ui.u(16);
        hero.addView(heroBox, cl);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(Ui.text(this, "Playlist", 13, 700, Ui.WHITE));
        TextView title = Ui.text(this, "Loved Songs", 40, 900, Ui.WHITE);
        title.setLetterSpacing(-0.035f);
        title.setSingleLine(true);
        title.setPadding(0, Ui.u(4), 0, Ui.u(8));
        info.addView(title);

        LinearLayout owner = new LinearLayout(this);
        owner.setGravity(Gravity.CENTER_VERTICAL);
        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(Ui.oval(0xFF535353));
        Ui.circleClip(avatar);
        avatar.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickImage(REQ_PFP); } });
        Ui.pressable(avatar, 0.9f);
        loadAvatar();
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(Ui.u(24), Ui.u(24));
        al.rightMargin = Ui.u(6);
        owner.addView(avatar, al);
        TextView name = Ui.text(this, "Lazith", 14, 700, Ui.WHITE);
        owner.addView(name);
        subtitle = Ui.text(this, "", 14, 500, 0xFFD0D0D0);
        owner.addView(subtitle);
        info.addView(owner);
        hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        h.addView(hero);
        heroHeight = Ui.u(60 + 128 + 20);

        // controls
        LinearLayout ctl = new LinearLayout(this);
        ctl.setGravity(Gravity.CENTER_VERTICAL);
        ctl.setPadding(Ui.u(16), Ui.u(4), Ui.u(16), Ui.u(12));

        FrameLayout play = new FrameLayout(this);
        play.setBackground(Ui.ripple(Ui.oval(Ui.GREEN), 0x44000000, true, 0));
        heroPlay = new ImageView(this);
        heroPlay.setImageResource(R.drawable.ic_play);
        heroPlay.setColorFilter(Ui.BLACK);
        play.addView(heroPlay, new FrameLayout.LayoutParams(Ui.u(28), Ui.u(28), Gravity.CENTER));
        play.setClickable(true);
        Ui.pressable(play, 0.9f);
        play.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { bigPlay(); } });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.u(56), Ui.u(56));
        pl.rightMargin = Ui.u(18);
        ctl.addView(play, pl);

        heroShuffle = new ToggleBtn(this, R.drawable.ic_shuffle, 36, 24);
        heroShuffle.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleShuffle(); } });
        ctl.addView(heroShuffle, box(36, 12));
        ctl.addView(iconBtn(R.drawable.ic_add, 36, 26, new View.OnClickListener() {
            @Override public void onClick(View v) { pickSongs(); } }), box(36, 12));
        ctl.addView(iconBtn(R.drawable.ic_sync, 36, 24, new View.OnClickListener() {
            @Override public void onClick(View v) { syncInfo(); } }), box(36, 0));
        View sp = new View(this);
        ctl.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView lbl = Ui.text(this, "List", 14, 600, Ui.GRAY);
        lbl.setPadding(0, 0, Ui.u(8), 0);
        ctl.addView(lbl);
        ImageView li = Ui.icon(this, R.drawable.ic_list, 18, Ui.GRAY);
        ctl.addView(li, new LinearLayout.LayoutParams(Ui.u(18), Ui.u(18)));
        h.addView(ctl);

        // table header
        LinearLayout th = new LinearLayout(this);
        th.setGravity(Gravity.CENTER_VERTICAL);
        th.setPadding(Ui.u(16), 0, Ui.u(16), 0);
        TextView n = Ui.text(this, "#", 14, 500, Ui.GRAY);
        n.setGravity(Gravity.CENTER);
        th.addView(n, new LinearLayout.LayoutParams(Ui.u(36), -2));
        TextView tt = Ui.text(this, "Title", 13, 500, Ui.GRAY);
        th.addView(tt, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView ck = Ui.icon(this, R.drawable.ic_clock, 16, Ui.GRAY);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(Ui.u(16), Ui.u(16));
        kl.rightMargin = Ui.u(44);
        th.addView(ck, kl);
        FrameLayout thw = new FrameLayout(this);
        thw.addView(th, new FrameLayout.LayoutParams(-1, Ui.u(36)));
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        thw.addView(line, new FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM));
        LinearLayout.LayoutParams twl = new LinearLayout.LayoutParams(-1, Ui.u(36));
        twl.leftMargin = Ui.u(8); twl.rightMargin = Ui.u(8); twl.bottomMargin = Ui.u(6);
        h.addView(thw, twl);
        return h;
    }

    private LinearLayout.LayoutParams box(int sizeUnits, int rightMarginUnits) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.u(sizeUnits), Ui.u(sizeUnits));
        l.rightMargin = Ui.u(rightMarginUnits);
        return l;
    }

    private View iconBtn(int res, int boxUnits, int iconUnits, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ui.ripple(null, 0x33FFFFFF, true, 0));
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(Ui.GRAY);
        f.addView(iv, new FrameLayout.LayoutParams(Ui.u(iconUnits), Ui.u(iconUnits), Gravity.CENTER));
        f.setClickable(true);
        f.setOnClickListener(click);
        Ui.pressable(f, 0.84f);
        return f;
    }

    private LinearLayout buildBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackgroundColor(Ui.BLACK);
        b.setPadding(Ui.u(16), Ui.u(8), Ui.u(16), Ui.u(10));
        b.setElevation(Ui.u(8));

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        barShuffle = new ToggleBtn(this, R.drawable.ic_shuffle, 36, 22);
        barShuffle.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleShuffle(); } });
        controls.addView(barShuffle, box(36, 14));
        controls.addView(iconBtn(R.drawable.ic_prev, 40, 24, new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.prev(); } }); } }), box(40, 14));
        FrameLayout play = new FrameLayout(this);
        play.setBackground(Ui.ripple(Ui.oval(Ui.WHITE), 0x44000000, true, 0));
        barPlay = new ImageView(this);
        barPlay.setImageResource(R.drawable.ic_play);
        barPlay.setColorFilter(Ui.BLACK);
        play.addView(barPlay, new FrameLayout.LayoutParams(Ui.u(22), Ui.u(22), Gravity.CENTER));
        play.setClickable(true);
        Ui.pressable(play, 0.9f);
        play.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.toggle(); } }); } });
        controls.addView(play, box(40, 14));
        controls.addView(iconBtn(R.drawable.ic_next, 40, 24, new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.next(false); } }); } }), box(40, 14));
        barRepeat = new ToggleBtn(this, R.drawable.ic_repeat, 36, 22);
        barRepeat.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            final int n = (PlaybackService.vRepeat + 1) % 3;
            PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.setRepeat(n); } });
            applyRepeat(n);
        } });
        controls.addView(barRepeat, box(36, 0));
        b.addView(controls);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        tLeft = Ui.text(this, "0:00", 12, 500, Ui.GRAY);
        tLeft.setGravity(Gravity.RIGHT);
        row.addView(tLeft, new LinearLayout.LayoutParams(Ui.u(40), -2));
        seek = new SeekView(this);
        seek.setListener(new SeekView.Listener() {
            @Override public void onDrag(float f) { tLeft.setText(Ui.clock(f * durMs())); }
            @Override public void onCommit(float f) {
                final int ms = (int) (f * durMs());
                PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.seek(ms); } });
            }
        });
        row.addView(seek, new LinearLayout.LayoutParams(0, -2, 1f));
        tRight = Ui.text(this, "0:00", 12, 500, Ui.GRAY);
        row.addView(tRight, new LinearLayout.LayoutParams(Ui.u(40), -2));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = Ui.u(2);
        b.addView(row, rl);
        return b;
    }

    // ================================================================== list
    private final class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return songs.size(); }
        @Override public Object getItem(int i) { return songs.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int i, View v, ViewGroup p) {
            Row r = v instanceof Row ? (Row) v : new Row();
            r.bind(songs.get(i), i);
            return r;
        }
    }

    private final class Row extends LinearLayout {
        final TextView num, title, artist, dur;
        final EqView eq;
        final ImageView thumb, more;
        final TextView badge;
        Song song;
        long animUntil;

        Row() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.u(16), 0, Ui.u(8), 0);
            setLayoutParams(new AbsListView.LayoutParams(-1, Ui.u(58)));
            setBackground(Ui.ripple(null, 0x22FFFFFF, false, 0));

            FrameLayout nb = new FrameLayout(getContext());
            num = Ui.text(getContext(), "", 16, 500, Ui.GRAY);
            num.setGravity(Gravity.CENTER);
            nb.addView(num, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            eq = new EqView(getContext());
            nb.addView(eq, new FrameLayout.LayoutParams(Ui.u(13), Ui.u(14), Gravity.CENTER));
            addView(nb, new LayoutParams(Ui.u(36), -2));

            thumb = new ImageView(getContext());
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Ui.roundClip(thumb, 4);
            LayoutParams tl = new LayoutParams(Ui.u(42), Ui.u(42));
            tl.rightMargin = Ui.u(12);
            addView(thumb, tl);

            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(VERTICAL);
            title = Ui.text(getContext(), "", 16, 600, Ui.WHITE);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(title);
            LinearLayout sub = new LinearLayout(getContext());
            sub.setGravity(Gravity.CENTER_VERTICAL);
            badge = Ui.text(getContext(), "E", 10, 800, Ui.BLACK);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(Ui.rect(0xFFB3B3B3, 2));
            LayoutParams bl = new LayoutParams(Ui.u(15), Ui.u(15));
            bl.rightMargin = Ui.u(6);
            sub.addView(badge, bl);
            artist = Ui.text(getContext(), "", 14, 500, Ui.GRAY);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            sub.addView(artist);
            LayoutParams sl = new LayoutParams(-2, -2);
            sl.topMargin = Ui.u(3);
            col.addView(sub, sl);
            addView(col, new LayoutParams(0, -2, 1f));

            dur = Ui.text(getContext(), "", 14, 500, Ui.GRAY);
            dur.setGravity(Gravity.RIGHT);
            LayoutParams dl = new LayoutParams(Ui.u(48), -2);
            addView(dur, dl);

            FrameLayout mb = new FrameLayout(getContext());
            mb.setBackground(Ui.ripple(null, 0x33FFFFFF, true, 0));
            more = new ImageView(getContext());
            more.setImageResource(R.drawable.ic_more);
            more.setColorFilter(Ui.GRAY);
            mb.addView(more, new FrameLayout.LayoutParams(Ui.u(20), Ui.u(20), Gravity.CENTER));
            mb.setClickable(true);
            Ui.pressable(mb, 0.8f);
            mb.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { if (song != null) openSheet(song); } });
            addView(mb, new LayoutParams(Ui.u(40), Ui.u(40)));
        }

        void bind(Song s, int pos) {
            song = s;
            boolean cur = s.id.equals(PlaybackService.vId);
            boolean pl = cur && PlaybackService.vPlaying;
            title.setText(s.title);
            title.setTextColor(cur ? Ui.GREEN : Ui.WHITE);
            artist.setText(s.artist);
            badge.setVisibility(s.explicit ? View.VISIBLE : View.GONE);
            dur.setText(s.duration > 0 ? Ui.clock(s.duration * 1000) : "");
            if (cur) { num.setText(""); eq.setVisibility(View.VISIBLE); eq.setActive(pl); }
            else { num.setText(String.valueOf(pos + 1)); eq.setActive(false); eq.setVisibility(View.GONE); }
            loadThumb(thumb, s, Ui.u(42));
            if (!entered.contains(s.id)) {
                entered.add(s.id);
                if (pos < 14) {
                    animUntil = android.os.SystemClock.uptimeMillis() + pos * 38L + 450;
                    setAlpha(0f);
                    setTranslationY(Ui.u(22));
                    animate().alpha(1f).translationY(0f).setStartDelay(pos * 38L).setDuration(380)
                            .setInterpolator(new DecelerateInterpolator(2f)).start();
                    return;
                }
            }
            if (android.os.SystemClock.uptimeMillis() < animUntil && getAlpha() < 1f) return;
            animate().cancel();
            if (scrolling && scrollDown) {
                animUntil = android.os.SystemClock.uptimeMillis() + 360;
                setAlpha(0f);
                setTranslationY(Ui.u(46));
                animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(340)
                        .setInterpolator(new DecelerateInterpolator(2.2f)).start();
                return;
            }
            animate().setStartDelay(0);
            setAlpha(1f);
            setTranslationY(0f);
        }
    }

    private void loadThumb(final ImageView iv, final Song s, final int px) {
        final String key = s.id + ":" + s.updatedAt;
        iv.setTag(key);
        Bitmap b = cache.get(key);
        if (b != null) { iv.setImageBitmap(b); return; }
        if (s.cover == null) { iv.setImageBitmap(defThumb()); return; }
        iv.setImageDrawable(new ColorDrawable(Ui.FIELD));
        try {
            io.execute(new Runnable() {
                @Override public void run() {
                    Bitmap t = Library.thumb(s, px);
                    if (t == null) t = defThumb();
                    cache.put(key, t);
                    final Bitmap fin = t;
                    ui.post(new Runnable() { @Override public void run() {
                        if (key.equals(iv.getTag())) { iv.setImageBitmap(fin); iv.setAlpha(0f); iv.animate().alpha(1f).setDuration(160).start(); }
                    } });
                }
            });
        } catch (Exception ignored) { }
    }

    private Bitmap defThumb() {
        if (defThumb == null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            defThumb = BitmapFactory.decodeResource(getResources(), R.drawable.default_art, o);
        }
        return defThumb;
    }

    private void onScrolled(int first) {
        if (topBar == null || topTitle == null || heroBox == null || list == null) return;
        float top = 0f;
        if (first == 0 && list.getChildCount() > 0) top = list.getChildAt(0).getTop();
        else if (first > 0) top = -heroHeight * 2f;
        float scrolled = -top;
        float a = Ui.clamp((scrolled - heroHeight * 0.45f) / (heroHeight * 0.4f), 0f, 1f);
        topBar.setAlpha(a);
        topTitle.setAlpha(a);
        float f = Ui.clamp(1f - scrolled / (heroHeight * 0.75f), 0f, 1f);
        heroBox.setAlpha(f);
        heroBox.setScaleX(0.8f + 0.2f * f);
        heroBox.setScaleY(0.8f + 0.2f * f);
        heroBox.setTranslationY(scrolled * 0.35f);
    }

    // ================================================================== state ticker
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed || isFinishing()) return;
            try { step(); } catch (Exception ignored) { }
            ui.postDelayed(this, PlaybackService.vPlaying ? 40 : 200);
        }
    };

    private double durMs() {
        double d = PlaybackService.vDur;
        if (d <= 0) {
            Song s = find(PlaybackService.vId);
            if (s != null) d = s.duration * 1000;
        }
        return Math.max(1, d);
    }

    private void step() {
        if (Core.server != null && Core.server.version != loadedVersion) reload();
        String id = PlaybackService.vId;
        boolean pl = PlaybackService.vPlaying;
        if (shownId == null || !id.equals(shownId) || pl != shownPlaying) {
            boolean hadBar = shownId != null && !shownId.isEmpty();
            shownId = id;
            shownPlaying = pl;
            adapter.notifyDataSetChanged();
            setBeat(pl);
            swapIcon(heroPlay, pl ? R.drawable.ic_pause : R.drawable.ic_play);
            swapIcon(barPlay, pl ? R.drawable.ic_pause : R.drawable.ic_play);
            showBar(!id.isEmpty() && find(id) != null, hadBar);
        }
        boolean sh = PlaybackService.vShuffle;
        heroShuffle.set(sh);
        barShuffle.set(sh);
        if (PlaybackService.vRepeat != shownRepeat) applyRepeat(PlaybackService.vRepeat);
        if (!id.isEmpty()) {
            double d = durMs();
            double pos = PlaybackService.vPos;
            if (pl) pos += android.os.SystemClock.elapsedRealtime() - PlaybackService.vAt;
            pos = Math.min(pos, d);
            if (!seek.isDragging()) {
                seek.setProgress((float) (pos / d));
                String l = Ui.clock(pos);
                if (!l.equals(shownLeft)) { shownLeft = l; tLeft.setText(l); }
            }
            String r = Ui.clock(d);
            if (!r.equals(shownRight)) { shownRight = r; tRight.setText(r); }
        }
    }

    private static float bump(float t, float start, float len) {
        if (t < start || t > start + len) return 0f;
        double x = Math.sin(Math.PI * (t - start) / len);
        return (float) (x * x);
    }

    /** Makes the big heart beat (lub-dub) while music plays. */
    private void setBeat(boolean on) {
        if (on && beat == null) {
            beat = android.animation.ValueAnimator.ofFloat(0f, 1f);
            beat.setDuration(860);
            beat.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            beat.setInterpolator(new android.view.animation.LinearInterpolator());
            beat.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (heroHeld || heroHeart == null) return;
                    float t = (Float) a.getAnimatedValue();
                    float k = 1f + 0.07f * bump(t, 0f, 0.16f) + 0.045f * bump(t, 0.22f, 0.14f);
                    heroHeart.setScaleX(k);
                    heroHeart.setScaleY(k);
                }
            });
            beat.start();
        } else if (!on && beat != null) {
            beat.cancel();
            beat = null;
            if (!heroHeld) heroHeart.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
        }
    }

    private void applyRepeat(int n) {
        shownRepeat = n;
        barRepeat.setIcon(n == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        barRepeat.set(n > 0);
    }

    private final java.util.HashMap<ImageView, Integer> iconRes = new java.util.HashMap<ImageView, Integer>();

    private void swapIcon(ImageView iv, int res) {
        Integer cur = iconRes.get(iv);
        if (cur != null && cur == res) return;
        iconRes.put(iv, res);
        iv.setImageResource(res);
        if (cur != null) {
            iv.setScaleX(0.6f); iv.setScaleY(0.6f);
            iv.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start();
        }
    }

    private boolean barShown;

    private void showBar(boolean show, boolean hadBar) {
        if (show == barShown) return;
        barShown = show;
        if (show) {
            bar.setVisibility(View.VISIBLE);
            bar.post(new Runnable() { @Override public void run() {
                list.setPadding(0, 0, 0, bar.getHeight() + Ui.u(14));
                bar.setTranslationY(bar.getHeight());
                bar.animate().translationY(0f).setDuration(340).setInterpolator(new DecelerateInterpolator(2.4f)).start();
                FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) snack.getLayoutParams();
                sl.bottomMargin = bar.getHeight() + Ui.u(16);
                snack.setLayoutParams(sl);
            } });
        } else {
            list.setPadding(0, 0, 0, Ui.u(14));
            bar.animate().translationY(bar.getHeight()).setDuration(240).withEndAction(new Runnable() {
                @Override public void run() { if (!barShown) bar.setVisibility(View.INVISIBLE); } }).start();
            FrameLayout.LayoutParams sl = (FrameLayout.LayoutParams) snack.getLayoutParams();
            sl.bottomMargin = Ui.u(24);
            snack.setLayoutParams(sl);
        }
    }

    // ================================================================== data
    private Song find(String id) {
        if (id == null) return null;
        for (Song s : songs) if (s.id.equals(id)) return s;
        return null;
    }

    private void reload() {
        if (Core.server == null) return;
        loadedVersion = Core.server.version;
        worker.execute(new Runnable() {
            @Override public void run() {
                List<Song> l0;
                try { l0 = Library.load(); } catch (Throwable t) { return; }
                final List<Song> l = l0;
                ui.post(new Runnable() { @Override public void run() { applySongs(l); } });
            }
        });
    }

    private void applySongs(List<Song> l) {
        songs = l;
        StringBuilder sb = new StringBuilder();
        double sec = 0;
        for (Song s : l) { if (sb.length() > 0) sb.append(','); sb.append(s.id); sec += s.duration; }
        int mins = (int) Math.round(sec / 60.0);
        String t = mins >= 60 ? (mins / 60) + " hr " + (mins % 60) + " min" : mins + " min";
        subtitle.setText("  •  " + l.size() + (l.size() == 1 ? " song" : " songs") + (l.isEmpty() ? "" : ", about " + t));
        emptyView.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setText(l.isEmpty() ? "Tap  +  to add your songs" : "");
        adapter.notifyDataSetChanged();
        final String csv = sb.toString();
        if (!csv.equals(lastIds)) {
            lastIds = csv;
            PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.setQueue(csv); } });
        }
        shownId = null;   // refresh bar / rows
        final List<Song> pre = l;
        worker.execute(new Runnable() {
            @Override public void run() {
                for (Song sg : pre) {
                    if (sg.cover == null) continue;
                    String key = sg.id + ":" + sg.updatedAt;
                    if (cache.get(key) != null) continue;
                    Bitmap t = Library.thumb(sg, Ui.u(76));
                    if (t != null) cache.put(key, t);
                }
            }
        });
    }

    private void playSong(final String id) {
        final String csv = lastIds;
        PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.setQueue(csv); s.cmdPlay(id); } });
    }

    private void bigPlay() {
        if (songs.isEmpty()) { pickSongs(); return; }
        if (PlaybackService.vId.isEmpty()) {
            final String csv = lastIds;
            final String id = PlaybackService.vShuffle ? songs.get((int) (Math.random() * songs.size())).id : songs.get(0).id;
            PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.setQueue(csv); s.cmdPlay(id); } });
        } else {
            PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.toggle(); } });
        }
    }

    private void toggleShuffle() {
        final boolean v = !PlaybackService.vShuffle;
        heroShuffle.set(v);
        barShuffle.set(v);
        PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService s) { s.setShuffle(v); } });
    }

    // ================================================================== picking files
    private void pickSongs() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_ADD); } catch (Exception e) { toast("No file picker found"); }
    }

    private void pickImage(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try { startActivityForResult(i, req); } catch (Exception e) { toast("No file picker found"); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_ADD) {
            final List<Uri> uris = new ArrayList<Uri>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) uris.add(data.getData());
            importAll(uris);
        } else if (req == REQ_COVER && data.getData() != null) {
            Bitmap b = Library.squareFromUri(this, data.getData(), 400);
            if (b != null && editThumb != null) {
                editCover = b;
                editThumb.setImageBitmap(b);
                editThumb.setAlpha(0f);
                editThumb.animate().alpha(1f).setDuration(200).start();
            }
        } else if (req == REQ_PFP && data.getData() != null) {
            Bitmap b = Library.squareFromUri(this, data.getData(), 256);
            if (b != null) {
                try {
                    FileOutputStream o = new FileOutputStream(new File(getFilesDir(), "pfp.jpg"));
                    b.compress(Bitmap.CompressFormat.JPEG, 90, o);
                    o.close();
                } catch (Exception ignored) { }
                loadAvatar();
                if (welcomePic != null) { welcomePic.setImageBitmap(b); welcomePic.setColorFilter(null); welcomePic.setPadding(0, 0, 0, 0); }
            }
        }
    }

    private void importAll(final List<Uri> uris) {
        if (uris.isEmpty()) return;
        final int total = uris.size();
        toast("Adding " + total + (total == 1 ? " song…" : " songs…"));
        worker.execute(new Runnable() {
            @Override public void run() {
                int ok = 0;
                long base = System.currentTimeMillis();
                for (int i = 0; i < total; i++) {
                    try { Library.importUri(MainActivity.this, uris.get(i), base + i); ok++; } catch (Exception ignored) { }
                    final int n = i + 1;
                    if (total > 1) ui.post(new Runnable() { @Override public void run() { toast("Adding " + n + " of " + total + "…"); } });
                }
                final int done = ok;
                ui.post(new Runnable() { @Override public void run() {
                    toast(done == 0 ? "Couldn't add those files" : (done == 1 ? "Added 1 song" : "Added " + done + " songs"));
                    reload();
                    if (done > 0) list.smoothScrollToPosition(0);
                } });
            }
        });
    }

    private void loadAvatar() {
        File f = new File(getFilesDir(), "pfp.jpg");
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) { avatar.setImageBitmap(b); avatar.setPadding(0, 0, 0, 0); avatar.setColorFilter(null); return; }
        }
        avatar.setImageResource(R.drawable.ic_person);
        avatar.setColorFilter(0xFFB3B3B3);
        avatar.setPadding(Ui.u(4), Ui.u(4), Ui.u(4), Ui.u(4));
    }

    // ================================================================== snackbar
    private void toast(String msg) {
        snackText.setText(msg);
        snack.animate().cancel();
        snack.setTranslationY(Ui.u(16));
        snack.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(new DecelerateInterpolator(2f)).start();
        ui.removeCallbacks(hideSnack);
        ui.postDelayed(hideSnack, 2400);
    }

    private final Runnable hideSnack = new Runnable() {
        @Override public void run() { snack.animate().alpha(0f).translationY(Ui.u(10)).setDuration(220).start(); }
    };

    // ================================================================== dialogs
    private int dialogWidth() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        return Math.min(Ui.u(360), (int) (sw * 0.9f));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.rect(Ui.SURFACE, 14));
        c.setPadding(Ui.u(20), Ui.u(20), Ui.u(20), Ui.u(16));
        c.setElevation(Ui.u(12));
        return c;
    }

    private View buttons(String cancel, String ok, final Runnable onOk, final Runnable onCancel) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.u(16), 0, 0);
        if (cancel != null) {
            TextView c = Ui.text(this, cancel, 14, 700, Ui.WHITE);
            c.setPadding(Ui.u(16), Ui.u(11), Ui.u(16), Ui.u(11));
            c.setBackground(Ui.ripple(null, 0x33FFFFFF, false, 20));
            c.setClickable(true);
            Ui.pressable(c, 0.94f);
            c.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (onCancel != null) onCancel.run(); } });
            r.addView(c);
        }
        TextView o = Ui.text(this, ok, 14, 800, Ui.BLACK);
        o.setPadding(Ui.u(24), Ui.u(11), Ui.u(24), Ui.u(11));
        o.setBackground(Ui.ripple(Ui.rect(Ui.GREEN, 40), 0x44000000, false, 40));
        o.setClickable(true);
        Ui.pressable(o, 0.94f);
        o.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onOk.run(); } });
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-2, -2);
        ol.leftMargin = Ui.u(8);
        r.addView(o, ol);
        return r;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(0xFF8A8A8A);
        e.setTextColor(Ui.WHITE);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.u(15));
        e.setTypeface(Fonts.w(this, 500));
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setHighlightColor(0x661ED760);
        e.setBackground(Ui.rect(Ui.FIELD, 8));
        e.setPadding(Ui.u(12), Ui.u(11), Ui.u(12), Ui.u(11));
        return e;
    }

    // ---- song options sheet
    private void openSheet(final Song s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.SURFACE);
        float r = Ui.u(16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        c.setBackground(bg);
        c.setPadding(Ui.u(16), Ui.u(18), Ui.u(16), Ui.u(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView th = new ImageView(this);
        th.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.roundClip(th, 4);
        loadThumb(th, s, Ui.u(52));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(Ui.u(52), Ui.u(52));
        tl.rightMargin = Ui.u(14);
        head.addView(th, tl);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Ui.text(this, s.title, 17, 700, Ui.WHITE);
        t1.setSingleLine(true);
        t1.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t1);
        TextView t2 = Ui.text(this, s.artist, 14, 500, Ui.GRAY);
        t2.setSingleLine(true);
        t2.setEllipsize(TextUtils.TruncateAt.END);
        t2.setPadding(0, Ui.u(3), 0, 0);
        col.addView(t2);
        head.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(head);
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 1);
        ll.topMargin = Ui.u(14); ll.bottomMargin = Ui.u(6);
        c.addView(line, ll);

        final AnimDialog[] d = new AnimDialog[1];
        c.addView(sheetRow(R.drawable.ic_edit, "Edit details", Ui.WHITE, new Runnable() { public void run() {
            d[0].dismiss();
            ui.postDelayed(new Runnable() { public void run() { openEdit(s); } }, 200);
        } }));
        c.addView(sheetRow(R.drawable.ic_delete, "Delete from Loved Songs", 0xFFFF7A7A, new Runnable() { public void run() {
            d[0].dismiss();
            ui.postDelayed(new Runnable() { public void run() { confirmDelete(s); } }, 200);
        } }));
        d[0] = new AnimDialog(this, c, true, 0);
        d[0].show();
    }

    private View sheetRow(int icon, String label, int color, final Runnable go) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.u(8), 0, Ui.u(8), 0);
        r.setBackground(Ui.ripple(null, 0x22FFFFFF, false, 8));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(color);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.u(22), Ui.u(22));
        il.rightMargin = Ui.u(18);
        r.addView(iv, il);
        r.addView(Ui.text(this, label, 16, 600, color));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { go.run(); } });
        r.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.u(54)));
        return r;
    }

    private void confirmDelete(final Song s) {
        LinearLayout c = card();
        c.addView(Ui.text(this, "Delete this song?", 19, 800, Ui.WHITE));
        TextView m = Ui.text(this, "\"" + s.title + "\" will be removed from this device.", 14, 500, Ui.GRAY);
        m.setPadding(0, Ui.u(8), 0, 0);
        c.addView(m);
        final AnimDialog[] d = new AnimDialog[1];
        c.addView(buttons("Cancel", "Delete", new Runnable() { public void run() {
            d[0].dismiss();
            Library.delete(s.id);
            toast("Song deleted");
            reload();
        } }, new Runnable() { public void run() { d[0].dismiss(); } }));
        d[0] = new AnimDialog(this, c, false, dialogWidth());
        d[0].show();
    }

    // ---- edit
    private Bitmap editCover;
    private ImageView editThumb;

    private void openEdit(final Song s) {
        editCover = null;
        LinearLayout c = card();
        c.addView(Ui.text(this, "Edit details", 19, 800, Ui.WHITE));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, Ui.u(14), 0, Ui.u(6));
        FrameLayout tw = new FrameLayout(this);
        editThumb = new ImageView(this);
        editThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Ui.roundClip(editThumb, 6);
        loadThumb(editThumb, s, Ui.u(80));
        tw.addView(editThumb, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackground(Ui.rect(0x66000000, 6));
        tw.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        tw.addView(Ui.icon(this, R.drawable.ic_camera, 22, Ui.WHITE), new FrameLayout.LayoutParams(Ui.u(22), Ui.u(22), Gravity.CENTER));
        tw.setClickable(true);
        Ui.pressable(tw, 0.94f);
        tw.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickImage(REQ_COVER); } });
        LinearLayout.LayoutParams twl = new LinearLayout.LayoutParams(Ui.u(76), Ui.u(76));
        twl.rightMargin = Ui.u(14);
        top.addView(tw, twl);
        TextView hint = Ui.text(this, "Tap the picture to choose a square (1:1) cover", 13, 500, Ui.GRAY);
        top.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(top);

        c.addView(label("Title"));
        final EditText et = field("Title", s.title);
        c.addView(et);
        c.addView(label("Artist"));
        final EditText ea = field("Artist", s.artist);
        c.addView(ea);

        LinearLayout ex = new LinearLayout(this);
        ex.setGravity(Gravity.CENTER_VERTICAL);
        ex.setPadding(0, Ui.u(14), 0, 0);
        TextView exl = Ui.text(this, "Show explicit  E  badge", 14, 600, Ui.WHITE);
        ex.addView(exl, new LinearLayout.LayoutParams(0, -2, 1f));
        final PillSwitch sw = new PillSwitch(this);
        sw.set(s.explicit, false);
        ex.addView(sw);
        c.addView(ex);

        final AnimDialog[] d = new AnimDialog[1];
        c.addView(buttons("Cancel", "Save", new Runnable() { public void run() {
            String nt = et.getText().toString().trim();
            s.title = nt.isEmpty() ? s.title : nt;
            s.artist = ea.getText().toString().trim();
            s.explicit = sw.isChecked();
            if (editCover != null) s.cover = Library.toDataUrl(editCover);
            try { Library.save(s); } catch (Exception e) { toast("Couldn't save"); }
            final String csv = lastIds;
            PlaybackService.run(new PlaybackService.Cmd() { public void go(PlaybackService sv) { sv.setQueue(csv); } });
            d[0].dismiss();
            toast("Saved");
            reload();
        } }, new Runnable() { public void run() { d[0].dismiss(); } }));
        d[0] = new AnimDialog(this, c, false, dialogWidth());
        d[0].setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface x) { /* keep editThumb until the picker returns */ }
        });
        d[0].show();
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s, 12, 700, Ui.GRAY);
        t.setPadding(0, Ui.u(12), 0, Ui.u(6));
        return t;
    }

    // ---- welcome / profile picture
    private ImageView welcomePic;

    private void welcome() {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(Ui.text(this, "Welcome, Lazith", 22, 900, Ui.WHITE));
        TextView sub = Ui.text(this, "Choose a profile picture", 14, 500, Ui.GRAY);
        sub.setPadding(0, Ui.u(6), 0, Ui.u(16));
        c.addView(sub);
        welcomePic = new ImageView(this);
        welcomePic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        welcomePic.setBackground(Ui.oval(0xFF535353));
        welcomePic.setImageResource(R.drawable.ic_camera);
        welcomePic.setColorFilter(0xFFE0E0E0);
        welcomePic.setPadding(Ui.u(32), Ui.u(32), Ui.u(32), Ui.u(32));
        Ui.circleClip(welcomePic);
        welcomePic.setClickable(true);
        Ui.pressable(welcomePic, 0.94f);
        welcomePic.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickImage(REQ_PFP); } });
        c.addView(welcomePic, new LinearLayout.LayoutParams(Ui.u(104), Ui.u(104)));
        final AnimDialog[] d = new AnimDialog[1];
        c.addView(buttons(null, "Continue", new Runnable() { public void run() {
            getSharedPreferences("loved", MODE_PRIVATE).edit().putBoolean("welcomed", true).apply();
            welcomePic = null;
            d[0].dismiss();
        } }, null));
        d[0] = new AnimDialog(this, c, false, dialogWidth());
        d[0].setCancelable(false);
        d[0].setCanceledOnTouchOutside(false);
        d[0].show();
    }

    // ---- sync info
    private void syncInfo() {
        List<String> ips = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        LinearLayout c = card();
        c.addView(Ui.text(this, "Sync with your PC", 19, 800, Ui.WHITE));
        TextView how = Ui.text(this, ips.isEmpty()
                ? "Connect this tablet to Wi-Fi first, then open this again."
                : "Keep both on the same Wi-Fi. Open the website on your PC, press Sync, and enter:", 14, 500, Ui.GRAY);
        how.setPadding(0, Ui.u(8), 0, Ui.u(14));
        c.addView(how);
        if (!ips.isEmpty()) {
            c.addView(infoLine("Address", ips.get(0) + ":" + Core.port));
            c.addView(infoLine("Code", Core.code));
        }
        final AnimDialog[] d = new AnimDialog[1];
        c.addView(buttons(null, "Done", new Runnable() { public void run() { d[0].dismiss(); } }, null));
        d[0] = new AnimDialog(this, c, false, dialogWidth());
        d[0].show();
    }

    private View infoLine(String k, String v) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(Ui.rect(Ui.FIELD, 8));
        r.setPadding(Ui.u(14), Ui.u(11), Ui.u(14), Ui.u(11));
        r.addView(Ui.text(this, k, 13, 600, Ui.GRAY), new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(Ui.text(this, v, 17, 800, Ui.WHITE));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.bottomMargin = Ui.u(8);
        r.setLayoutParams(l);
        return r;
    }
}
