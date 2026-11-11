package app.spektralab.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.DashPathEffect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * A1 "暗房安全灯" visual kit: palette, type, and the handful of custom views the
 * five screens are built from. Everything is programmatic -- the APK is built
 * without a res/ directory (aapt2 link only sees the manifest), so no XML.
 */
final class Ui {
    static final int BG      = 0xFF14110D;
    static final int DEEP    = 0xFF100D09;
    static final int ROW     = 0xFF1C1812;
    static final int NAVBAR  = 0xFF171310;
    static final int LINE    = 0xFF2A241A;
    static final int LINE2   = 0xFF1C1913;
    static final int PAPER   = 0xFFE8E2D8;
    static final int CREAM   = 0xFFC9C4B8;
    static final int DIM     = 0xFF6E675A;
    static final int AMBER   = 0xFFE8A13C;
    static final int INK     = 0xFF1A1409;

    static final Typeface MONO = Typeface.MONOSPACE;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTypeface(MONO);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** label + tracking, the film-edge print voice used for every section header. */
    static TextView tracked(Context c, CharSequence s, float sp, int color, float em) {
        TextView t = text(c, s, sp, color);
        t.setLetterSpacing(em);
        return t;
    }

    static View hair(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f))));
        return v;
    }

    static View dotted(Context c) {
        DottedLine v = new DottedLine(c);
        return v;
    }

    static GradientDrawable box(int fill, float radiusDp, Context c, int stroke, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) d.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return d;
    }

    /** The one button in the whole app: amber block, ink text, wide tracking. */
    static TextView button(Context c, String label) {
        TextView t = text(c, label, 13f, INK);
        t.setTypeface(MONO, Typeface.BOLD);
        t.setLetterSpacing(0.32f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(c, 15), 0, dp(c, 15));
        t.setBackground(box(AMBER, 2f, c, 0, 0));
        return t;
    }

    static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(255 * Math.min(1f, Math.max(0f, a))) << 24);
    }

    // ------------------------------------------------------------------
    /** Dotted rule: the ruled-paper line the prototype puts under every row. */
    static final class DottedLine extends View {
        private final Paint p = new Paint();
        private final float d;
        DottedLine(Context c) {
            super(c);
            d = dp(c, 2f);
            p.setColor(Ui.LINE);
            p.setStrokeWidth(Math.max(1f, dp(c, 0.7f)));
            p.setStyle(Paint.Style.STROKE);
            p.setPathEffect(new DashPathEffect(new float[]{ d * 0.55f, d * 1.35f }, 0));
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Math.max(1, dp(getContext(), 1f)));
        }
        @Override protected void onDraw(Canvas cv) {
            cv.drawLine(0, getHeight() * 0.5f, getWidth(), getHeight() * 0.5f, p);
        }
    }

    /** Screen header: SPEKTRALAB ▷ FILM / STAGE, amber, with the hairline under it. */
    static final class EdgeBar extends LinearLayout {
        final TextView left, right;
        EdgeBar(Context c, String l, String r) {
            super(c);
            setOrientation(VERTICAL);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setPadding(0, dp(c, 4), 0, dp(c, 10));
            left = tracked(c, l, 9.5f, AMBER, 0.20f);
            right = tracked(c, r, 9.5f, AMBER, 0.20f);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(hair(c, LINE), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f))));
        }
        void set(String l, String r) { left.setText(l); right.setText(r); }
    }

    /** The canvas: photo, cover-fit, with the safelight's glow bleeding in from the edges. */
    static final class Photo extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.graphics.Bitmap bmp;
        Photo(Context c) { super(c); setBackgroundColor(0xFF000000); }
        void set(android.graphics.Bitmap b) { bmp = b; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (bmp != null && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                float s = Math.max(w / (float) bmp.getWidth(), h / (float) bmp.getHeight());
                float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
                RectF dst = new RectF((w - dw) * 0.5f, (h - dh) * 0.5f, (w + dw) * 0.5f, (h + dh) * 0.5f);
                cv.drawBitmap(bmp, null, dst, paint);
            }
            float r = Math.max(w, h) * 0.72f;
            glow.setShader(new RadialGradient(w * 0.5f, h * 0.5f, r,
                    new int[]{ 0x00E8A13C, 0x00E8A13C, 0x33E8A13C, 0x59E8A13C },
                    new float[]{ 0f, 0.55f, 0.86f, 1f }, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, glow);
        }
    }

    /** A film's characteristic curve, drawn small -- the film list's only graphic. */
    static final class Spark extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path a = new Path(), b = new Path();
        private int color = AMBER;
        private boolean selected;
        Spark(Context c) { super(c); p.setStyle(Paint.Style.STROKE); }
        void set(int c, boolean sel) { color = c; selected = sel; build(); invalidate(); }
        private void build() {
            float w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
            float sx = w / 46f, sy = h / 18f;
            a.reset(); a.moveTo(1 * sx, 14.5f * sy);
            a.cubicTo(10 * sx, 14.5f * sy, 14 * sx, 4 * sy, 23 * sx, 5 * sy);
            a.cubicTo(32 * sx, 6 * sy, 40 * sx, 13.5f * sy, 43 * sx, 14.5f * sy);
            b.reset(); b.moveTo(1 * sx, 16 * sy);
            b.cubicTo(12 * sx, 16 * sy, 16 * sx, 8.5f * sy, 23 * sx, 9 * sy);
            b.cubicTo(33 * sx, 9.6f * sy, 40 * sx, 15 * sy, 43 * sx, 16 * sy);
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) { build(); }
        @Override protected void onDraw(Canvas cv) {
            p.setColor(color);
            p.setStrokeWidth(Math.max(1f, dp(getContext(), selected ? 1.5f : 1f)));
            cv.drawPath(a, p);
            p.setAlpha(selected ? 120 : 80);
            cv.drawPath(b, p);
        }
    }

    /** Develop progress: a 4dp track, amber fill, a whisper of glow. */
    static final class Bar extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float pct = 100f;
        Bar(Context c) { super(c); }
        void set(float v) { pct = v; invalidate(); }
        @Override protected void onDraw(Canvas cv) {
            float h = getHeight(), rad = h * 0.5f;
            p.setColor(Ui.LINE);
            r.set(0, 0, getWidth(), h);
            cv.drawRoundRect(r, rad, rad, p);
            float w = getWidth() * Math.min(1f, pct / 100f);
            if (w > 0.5f) {
                p.setColor(Ui.alpha(Ui.AMBER, 0.18f));
                r.set(-h, -h, w + h, getHeight() + h);
                cv.drawRoundRect(r, rad * 2, rad * 2, p);
                p.setColor(Ui.AMBER);
                r.set(0, 0, w, h);
                cv.drawRoundRect(r, rad, rad, p);
            }
        }
    }

    /** Label-left / value-right row with the dotted rule under it. */
    static final class Row extends LinearLayout {
        final TextView label, value;
        Row(Context c, String l, String v, boolean amberValue) {
            super(c);
            setOrientation(HORIZONTAL);
            setPadding(0, dp(c, 9), 0, dp(c, 9));
            label = tracked(c, l, 10f, DIM, 0.14f);
            value = text(c, v, 11f, amberValue ? AMBER : PAPER);
            addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setMinimumHeight(dp(c, 34));
            setGravity(Gravity.CENTER_VERTICAL);
        }
        @Override protected void dispatchDraw(Canvas cv) {
            super.dispatchDraw(cv);
            Paint p = new Paint();
            p.setColor(Ui.LINE);
            p.setStrokeWidth(Math.max(1f, Ui.dp(getContext(), 0.7f)));
            p.setStyle(Paint.Style.STROKE);
            p.setPathEffect(new DashPathEffect(new float[]{ Ui.dp(getContext(), 1.1f), Ui.dp(getContext(), 2.7f) }, 0));
            cv.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1, p);
        }
    }

    /** Slider block: label + amber value on one line, SeekBar under it. */
    static final class Slider extends LinearLayout {
        final SeekBar bar;
        final TextView value;
        Slider(Context c, String label, int max, int step) {
            super(c);
            setOrientation(VERTICAL);
            setPadding(0, dp(c, 11), 0, dp(c, 11));
            LinearLayout head = new LinearLayout(c);
            head.setOrientation(HORIZONTAL);
            TextView l = tracked(c, label, 10f, DIM, 0.14f);
            value = text(c, "", 10.5f, AMBER);
            head.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            head.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(head);
            bar = new SeekBar(c);
            bar.setMax(max);
            bar.setProgressTintList(ColorStateList.valueOf(AMBER));
            bar.setThumbTintList(ColorStateList.valueOf(AMBER));
            bar.setPadding(dp(c, 2), dp(c, 5), dp(c, 2), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 22));
            addView(bar, lp);
            addView(hair(c, Ui.LINE), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f))));
        }
    }

    /** Switch row: label left, amber toggle right, hairline under it. */
    static final class Toggle extends LinearLayout {
        final android.widget.Switch sw;
        Toggle(Context c, String label) {
            super(c);
            setOrientation(VERTICAL);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(c, 4), 0, dp(c, 4));
            TextView l = tracked(c, label, 10f, DIM, 0.14f);
            row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            sw = new android.widget.Switch(c);
            sw.setThumbTintList(ColorStateList.valueOf(AMBER));
            sw.setTrackTintList(ColorStateList.valueOf(0xFF3A332A));
            row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(hair(c, Ui.LINE), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.7f))));
        }
    }

    /** SeekBar listener: only onProgress matters here. */
    abstract static class SliderListener implements SeekBar.OnSeekBarChangeListener {
        public abstract void onProgress(int p);
        public void onStartTrackingTouch(SeekBar s) {}
        public void onStopTrackingTouch(SeekBar s) {}
        public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
            if (fromUser) onProgress(progress);
        }
    }

    /** One nav cell: glyph over a tiny label, amber when active. */
    static final class NavCell extends LinearLayout {
        final TextView glyph, label;
        NavCell(Context c, String g, String l) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setPadding(0, dp(c, 7), 0, dp(c, 7));
            glyph = text(c, g, 15f, DIM);
            glyph.setGravity(Gravity.CENTER);
            label = tracked(c, l, 9f, DIM, 0.10f);
            label.setGravity(Gravity.CENTER);
            addView(glyph);
            addView(label);
        }
        void active(boolean on) {
            glyph.setTextColor(on ? AMBER : DIM);
            label.setTextColor(on ? AMBER : DIM);
        }
    }
}