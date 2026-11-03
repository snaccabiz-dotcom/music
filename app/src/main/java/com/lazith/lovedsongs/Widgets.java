package com.lazith.lovedsongs;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Equalizer bars that dance next to the song that is playing. */
final class EqView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ValueAnimator anim;
    private float t;

    EqView(Context c) {
        super(c);
        p.setColor(Ui.GREEN);
    }

    void setActive(boolean on) {
        if (on && anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1000);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    t = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        } else if (!on && anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (anim != null) { anim.cancel(); anim = null; }
    }

    @Override
    protected void onDraw(Canvas c) {
        float bw = Ui.U * 3f, gap = Ui.U * 2f, min = Ui.U * 3f, max = Ui.U * 14f;
        float h = getHeight();
        float[] phase = {0f, 0.37f, 0.71f};
        for (int i = 0; i < 3; i++) {
            float k = 0.5f * (1f - (float) Math.cos(2 * Math.PI * (t + phase[i])));
            float bh = min + (max - min) * k;
            float x = i * (bw + gap);
            c.drawRoundRect(x, h - bh, x + bw, h, bw / 2, bw / 2, p);
        }
    }
}

/** The thin Spotify-style progress bar: white, turns green and shows a handle while you drag. */
final class SeekView extends View {
    interface Listener {
        void onDrag(float fraction);
        void onCommit(float fraction);
    }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress, thumbT;
    private boolean dragging;
    private Listener listener;
    private ValueAnimator thumbAnim;

    SeekView(Context c) { super(c); }

    void setListener(Listener l) { listener = l; }
    boolean isDragging() { return dragging; }

    void setProgress(float f) {
        if (dragging) return;
        progress = Ui.clamp(f, 0f, 1f);
        invalidate();
    }

    private void animateThumb(float to) {
        if (thumbAnim != null) thumbAnim.cancel();
        thumbAnim = ValueAnimator.ofFloat(thumbT, to);
        thumbAnim.setDuration(140);
        thumbAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                thumbT = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        thumbAnim.start();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), Ui.u(28));
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = Ui.U * 6f, th = Ui.U * 4f, cy = getHeight() / 2f;
        float x0 = pad, x1 = getWidth() - pad;
        p.setColor(0xFF4D4D4D);
        c.drawRoundRect(x0, cy - th / 2, x1, cy + th / 2, th / 2, th / 2, p);
        float px = x0 + (x1 - x0) * progress;
        if (progress > 0f) {
            p.setColor(dragging ? Ui.GREEN : Ui.WHITE);
            c.drawRoundRect(x0, cy - th / 2, Math.max(px, x0 + th), cy + th / 2, th / 2, th / 2, p);
        }
        if (thumbT > 0f) {
            p.setColor(Ui.WHITE);
            c.drawCircle(px, cy, Ui.U * 6.5f * thumbT, p);
        }
    }

    private float fraction(MotionEvent e) {
        float pad = Ui.U * 6f;
        return Ui.clamp((e.getX() - pad) / Math.max(1f, getWidth() - 2 * pad), 0f, 1f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                progress = fraction(e);
                animateThumb(1f);
                if (listener != null) listener.onDrag(progress);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                progress = fraction(e);
                if (listener != null) listener.onDrag(progress);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                progress = fraction(e);
                dragging = false;
                animateThumb(0f);
                if (listener != null) listener.onCommit(progress);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                animateThumb(0f);
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}

/** On/off switch that slides and turns green. */
final class PillSwitch extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean checked;
    private float t;
    private ValueAnimator anim;
    private final ArgbEvaluator ev = new ArgbEvaluator();

    PillSwitch(Context c) {
        super(c);
        setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { set(!checked, true); }
        });
        Ui.pressable(this, 0.92f);
    }

    boolean isChecked() { return checked; }

    void set(boolean on, boolean animate) {
        checked = on;
        if (anim != null) anim.cancel();
        if (!animate) { t = on ? 1f : 0f; invalidate(); return; }
        anim = ValueAnimator.ofFloat(t, on ? 1f : 0f);
        anim.setDuration(180);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) { t = (Float) a.getAnimatedValue(); invalidate(); }
        });
        anim.start();
    }

    @Override
    protected void onMeasure(int w, int h) { setMeasuredDimension(Ui.u(34), Ui.u(20)); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        p.setColor((Integer) ev.evaluate(t, 0xFF6A6A6A, Ui.GREEN));
        c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
        float r = Ui.U * 8f;
        float cx = Ui.U * 10f + (w - Ui.U * 20f) * t;
        p.setColor(Ui.WHITE);
        c.drawCircle(cx, h / 2, r, p);
    }
}

/** Icon that can be switched on (green + a little dot under it), like shuffle and repeat. */
final class ToggleBtn extends FrameLayout {
    private final ImageView iv;
    private final View dot;
    private boolean on;

    ToggleBtn(Context c, int res, float boxUnits, float iconUnits) {
        super(c);
        iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setColorFilter(Ui.GRAY);
        LayoutParams lp = new LayoutParams(Ui.u(iconUnits), Ui.u(iconUnits));
        lp.gravity = Gravity.CENTER;
        addView(iv, lp);
        dot = new View(c);
        dot.setBackground(Ui.oval(Ui.GREEN));
        dot.setAlpha(0f);
        LayoutParams dl = new LayoutParams(Ui.u(4), Ui.u(4));
        dl.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        dl.bottomMargin = Ui.u(1);
        addView(dot, dl);
        setBackground(Ui.ripple(null, 0x33FFFFFF, true, 0));
        setClickable(true);
        Ui.pressable(this, 0.84f);
    }

    void setIcon(int res) { iv.setImageResource(res); }

    void set(boolean value) {
        if (value == on) return;
        Ui.tintTo(iv, on ? Ui.GREEN : Ui.GRAY, value ? Ui.GREEN : Ui.GRAY);
        dot.animate().alpha(value ? 1f : 0f).scaleX(value ? 1f : 0.3f).scaleY(value ? 1f : 0.3f).setDuration(180).start();
        on = value;
    }

    boolean isOn() { return on; }
}

/** A pop-up that scales/slides in and out smoothly while the screen behind it dims. */
final class AnimDialog extends Dialog {
    private final View content;
    private final boolean bottom;
    private boolean closing;
    private ValueAnimator dim;

    AnimDialog(Context c, View content, boolean bottom, int widthPx) {
        super(c);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.content = content;
        this.bottom = bottom;
        setContentView(content, new ViewGroup.LayoutParams(bottom ? ViewGroup.LayoutParams.MATCH_PARENT : widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0f);
            w.setLayout(bottom ? ViewGroup.LayoutParams.MATCH_PARENT : widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(bottom ? Gravity.BOTTOM : Gravity.CENTER);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        setCanceledOnTouchOutside(true);
        setOnShowListener(new OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) { animateIn(); }
        });
    }

    private void animateDim(float from, float to, int ms) {
        final Window w = getWindow();
        if (w == null) return;
        if (dim != null) dim.cancel();
        dim = ValueAnimator.ofFloat(from, to);
        dim.setDuration(ms);
        dim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) { w.setDimAmount((Float) a.getAnimatedValue()); }
        });
        dim.start();
    }

    private void animateIn() {
        animateDim(0f, 0.66f, 240);
        content.setAlpha(0f);
        if (bottom) {
            content.setTranslationY(Ui.u(300));
            content.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(new DecelerateInterpolator(2.2f)).start();
        } else {
            content.setTranslationY(Ui.u(26));
            content.setScaleX(0.94f);
            content.setScaleY(0.94f);
            content.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    private void reallyDismiss() {
        try { super.dismiss(); } catch (Exception ignored) { }
    }

    @Override
    public void dismiss() {
        if (closing || !isShowing()) { reallyDismiss(); return; }
        closing = true;
        animateDim(0.66f, 0f, 180);
        if (bottom) {
            content.animate().alpha(0f).translationY(Ui.u(300)).setDuration(210).setInterpolator(new AccelerateInterpolator(1.6f))
                    .withEndAction(new Runnable() { @Override public void run() { reallyDismiss(); } }).start();
        } else {
            content.animate().alpha(0f).translationY(Ui.u(14)).scaleX(0.97f).scaleY(0.97f).setDuration(170)
                    .setInterpolator(new AccelerateInterpolator(1.4f))
                    .withEndAction(new Runnable() { @Override public void run() { reallyDismiss(); } }).start();
        }
    }
}
