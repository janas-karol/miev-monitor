package io.github.janaskarol.miev;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** Pasek ostatnich ~2 minut mocy: wykres wypelniony, z linia zera. */
public class SparklineView extends View {

    private static final float MIN_KW = -25f;
    private static final float MAX_KW = 50f;

    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path areaPath = new Path();
    private final Path linePath = new Path();

    private float[] values = new float[0];

    public SparklineView(Context context) {
        this(context, null);
    }

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(3f);
        linePaint.setColor(Color.parseColor("#57D7A5"));
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        zeroPaint.setStyle(Paint.Style.STROKE);
        zeroPaint.setStrokeWidth(2f);
        zeroPaint.setColor(Color.parseColor("#2A333F"));

        textPaint.setColor(Color.parseColor("#6C7A89"));
        textPaint.setTextSize(24f);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        textPaint.setTextAlign(Paint.Align.RIGHT);
    }

    public void setValues(float[] snapshot) {
        this.values = snapshot;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float padTop = h * 0.12f;
        float padBottom = h * 0.12f;
        float usable = h - padTop - padBottom;

        float yZero = padTop + usable * (MAX_KW / (MAX_KW - MIN_KW));

        canvas.drawLine(0, yZero, w, yZero, zeroPaint);

        if (values.length < 2) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("zbieram dane…", w / 2f, h / 2f, textPaint);
            return;
        }

        areaPath.reset();
        linePath.reset();

        int n = values.length;
        float stepX = (float) w / (n - 1);

        for (int i = 0; i < n; i++) {
            float v = values[i];
            float clamped = Math.max(MIN_KW, Math.min(MAX_KW, v));
            float y = padTop + usable * (1f - (clamped - MIN_KW) / (MAX_KW - MIN_KW));
            float x = i * stepX;
            if (i == 0) {
                linePath.moveTo(x, y);
                areaPath.moveTo(x, yZero);
                areaPath.lineTo(x, y);
            } else {
                linePath.lineTo(x, y);
                areaPath.lineTo(x, y);
            }
        }
        areaPath.lineTo(w, yZero);
        areaPath.close();

        areaPaint.setShader(new LinearGradient(0, padTop, 0, h - padBottom,
                Color.parseColor("#5557D7A5"), Color.parseColor("#0057D7A5"),
                Shader.TileMode.CLAMP));
        areaPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(areaPath, areaPaint);

        // kolor linii zaleznie od znaku sredniej
        float sum = 0;
        for (float v : values) sum += v;
        float avg = sum / n;
        linePaint.setColor(avg < -0.3f ? Color.parseColor("#5BC8FF") : Color.parseColor("#57D7A5"));
        canvas.drawPath(linePath, linePaint);
    }
}
