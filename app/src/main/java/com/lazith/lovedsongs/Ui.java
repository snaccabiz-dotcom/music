package com.lazith.lovedsongs;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Sizes, colours and small helpers shared by every screen. */
final class Ui {
    static final int GREEN = 0xFF1ED760;
    static final int BG = 0xFF121212;
    static final int GRAY = 0xFFB3B3B3;
    static final int WHITE = 0xFFFFFFFF;
    static final int BLACK = 0xFF000000;
    static final int SURFACE = 0xFF282828;
    static final int FIELD = 0xFF3E3E3E;
    static final int LINE = 0x1AFFFFFF;

    /** Pixels in one design unit. The design is laid out on a 680-unit-wide screen (same as the website). */
    static float U = 1f;

    private Ui() {}

    static void init(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float widthDp = dm.widthPixels / dm.density;
        float scale = Math.min(1.5f, Math.max(1f, widthDp / 680f));
        U = scale * dm.density;
    }

    static int u(float v) { return Math.round(v * U); }

    static GradientDrawable rect(int color, float radiusUnits) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusUnits * U);
        return g;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Touch ripple on top of an optional background. */
    static RippleDrawable ripple(android.graphics.drawable.Drawable content, int color, boolean circle, float radiusUnits) {
        android.graphics.drawable.Drawable mask = circle ? oval(0xFFFFFFFF) : rect(0xFFFFFFFF, radiusUnits);
        return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
    }

    /** Springy press: shrinks under the finger and bounces back. */
    static void pressable(View v, final float scale) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(scale).scaleY(scale).setDuration(90)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new OvershootInterpolator(2.4f)).start();
                }
                return false;
            }
        });
    }

    static TextView text(Context c, String s, float sizeUnits, int weight, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeUnits * U);
        t.setTypeface(Fonts.w(c, weight));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A plain icon (tinted). */
    static ImageView icon(Context c, int res, float sizeUnits, int tint) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setLayoutParams(new FrameLayout.LayoutParams(u(sizeUnits), u(sizeUnits)));
        return iv;
    }

    static void roundClip(View v, final float radiusUnits) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusUnits * U);
            }
        });
        v.setClipToOutline(true);
    }

    static void circleClip(View v) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        v.setClipToOutline(true);
    }

    /** Smoothly changes an icon's colour. */
    static void tintTo(final ImageView iv, int from, int to) {
        ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        a.setDuration(180);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator va) {
                iv.setColorFilter((Integer) va.getAnimatedValue());
            }
        });
        a.start();
    }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    static String clock(double ms) {
        long s = Math.max(0, (long) (ms / 1000));
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
