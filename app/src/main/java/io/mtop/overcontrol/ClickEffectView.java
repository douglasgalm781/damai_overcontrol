package io.mtop.overcontrol;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * The confirmation flourish drawn around the pill when a booking press lands: three
 * staggered rings expanding outward through a soft glow, with a check badge that pops in
 * the centre and fades.
 *
 * <p>It lives in its own overlay window rather than inside the pill's. The burst is much
 * wider than the pill, and growing the pill's window to fit would leave a rectangle of
 * invisible padding around it swallowing taps meant for Damai underneath. This window is
 * {@code FLAG_NOT_TOUCHABLE}, so it is purely visual — every touch passes straight
 * through — and it removes itself when the animation ends.
 */
final class ClickEffectView extends View {

    private static final int SIZE_DP = 260;
    private static final long DURATION_MS = 1100L;

    private static final int MINT = 0xFF3DD6C0;
    private static final int DEEP = 0xFF0B6B6B;

    private static final int RINGS = 3;
    private static final float RING_STAGGER = 0.14f; // head start between rings, 0..1
    private static final float RING_LIFE = 0.62f;    // how much of the timeline each ring gets

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path check = new Path();
    private final float density;
    private float progress = 0f;

    private ClickEffectView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    /**
     * Plays the burst centred on the given screen point. Safe to call when the overlay
     * permission has just been revoked — the window simply fails to attach and nothing
     * else happens.
     */
    static void burst(Context context, WindowManager wm, int centerX, int centerY) {
        ClickEffectView view = new ClickEffectView(context);
        int size = view.dp(SIZE_DP);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = centerX - size / 2;
        lp.y = centerY - size / 2;

        try {
            wm.addView(view, lp);
        } catch (Exception ignored) {
            return;
        }
        view.run(wm);
    }

    private void run(WindowManager wm) {
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.4f));
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                try {
                    wm.removeView(ClickEffectView.this);
                } catch (Exception ignored) {}
            }
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxRadius = Math.min(cx, cy) - dp(4);
        float minRadius = dp(26);

        // Soft glow breathing out underneath everything else.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(MINT, (int) (70 * (1f - progress))));
        canvas.drawCircle(cx, cy, minRadius + (maxRadius - minRadius) * 0.55f * progress, paint);

        // Staggered rings.
        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < RINGS; i++) {
            float p = (progress - i * RING_STAGGER) / RING_LIFE;
            if (p <= 0f || p >= 1f) continue;
            paint.setStrokeWidth(dp(2) * (1f - 0.55f * p));
            paint.setColor(withAlpha(i == 0 ? DEEP : MINT, (int) (200 * (1f - p))));
            canvas.drawCircle(cx, cy, minRadius + (maxRadius - minRadius) * p, paint);
        }

        drawCheckBadge(canvas, cx, cy);
    }

    /** Pops in with a slight overshoot, holds, then fades with the rest. */
    private void drawCheckBadge(Canvas canvas, float cx, float cy) {
        float p = (progress - 0.08f) / 0.30f;
        if (p <= 0f) return;
        float scale = p >= 1f ? 1f : 1f - (1f - p) * (1f - p) * (1f - 2.2f * (1f - p));
        float fade = progress < 0.72f ? 1f : 1f - (progress - 0.72f) / 0.28f;
        if (fade <= 0f) return;

        float radius = dp(24) * Math.min(scale, 1.12f);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(DEEP, (int) (235 * fade)));
        canvas.drawCircle(cx, cy, radius, paint);

        float arm = radius * 0.46f;
        check.reset();
        check.moveTo(cx - arm, cy + arm * 0.08f);
        check.lineTo(cx - arm * 0.18f, cy + arm * 0.66f);
        check.lineTo(cx + arm, cy - arm * 0.58f);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(withAlpha(Color.WHITE, (int) (255 * fade)));
        canvas.drawPath(check, paint);
    }

    private static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00FFFFFF);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
