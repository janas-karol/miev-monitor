package io.github.janaskarol.miev;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Wskaznik mocy jak obrotomierz, ale z rekuperacja.
 *
 * Skala: rekuperacja do -25 kW, pobor do +50 kW. Zero lezy w 1/3 tarczy
 * (25 kW z 75 kW zakresu), bo rekuperacja w i-MiEV realnie dochodzi do ~21 kW -
 * w danych z 13 jazd maksimum to 21,2 kW, a pobor 40,5 kW.
 *
 * Lewa strona dostaje 90 stopni, prawa 180 - dzieki temu igla przy rekuperacji
 * wychyla sie wyraznie, a przy pelnej mocy tez ma zapas.
 */
public class PowerGaugeView extends View {

    private static final float REGEN_MIN_KW = -25f;
    private static final float DRAW_MAX_KW = 50f;

    /**
     * Uklady tarczy. Kazdy opisuje, gdzie stoi zero i ile stopni dostaje kazda strona.
     *
     * THIRD  - zero na 12:00, rekuperacja 90, pobor 180 (skala liniowa 3,6 st./kW)
     * HALF   - zero na 12:00, po 135 na strone (klasyczny obrotomierz)
     * LEFT   - zero na 9:00, rekuperacja 90 w dol do 6:00, pobor 180 przez 12:00 do 3:00
     *          (skala liniowa, bez kolizji koncow)
     * FULL   - zero na 9:00, rekuperacja 90 w dol do 6:00, pobor 270 przez 12:00 do 6:00
     *          (pelne kolo; -25 kW i +50 kW wypadaja w tym samym punkcie)
     */
    public enum Layout {
        THIRD(270f, 90f, 180f, 0f),
        HALF(270f, 135f, 135f, 0f),
        LEFT(180f, 90f, 180f, 0f),
        /** Pelne kolo z 12-stopniowa przerwa na 6:00 - konce sie nie stykaja. */
        FULL(180f, 90f, 270f, 12f);

        final float zeroAngle;
        final float regenSweep;
        final float drawSweep;
        final float gap;

        Layout(float zeroAngle, float regenSweep, float drawSweep, float gap) {
            this.zeroAngle = zeroAngle;
            this.regenSweep = regenSweep;
            this.drawSweep = drawSweep;
            this.gap = gap;
        }

        float regenSpan() {
            return regenSweep - gap / 2f;
        }

        float drawSpan() {
            return drawSweep - gap / 2f;
        }

        float totalSweep() {
            return regenSpan() + drawSpan();
        }

        float startAngle() {
            return zeroAngle - regenSpan();
        }
    }

    private Layout layout = Layout.FULL;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bigTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcRect = new RectF();

    /** Ile czasu (w sekundach) igla potrzebuje na pokonanie ~63% roznicy wartosci. */
    private static final float NEEDLE_TAU_S = 0.09f;
    /** Gorny limit predkosci igly (kW/s) - zeby skok z rekuperacji na pelna moc nie byl teleportacja. */
    private static final float NEEDLE_MAX_RATE = 400f;
    /** Ponizej tej roznicy uznajemy, ze igla dotarla na miejsce. */
    private static final float NEEDLE_SNAP = 0.02f;

    /** Wartosc docelowa z auta. */
    private float kw = Float.NaN;
    /** Wartosc rysowana - dogania `kw` plynnie, jak wskazowka obrotomierza. */
    private float shownKw = Float.NaN;
    private boolean animating = false;
    private long lastFrameMs = 0;
    private float peakKw = 0f;

    public PowerGaugeView(Context context) {
        this(context, null);
    }

    public PowerGaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            width = (int) (320 * getResources().getDisplayMetrics().density);
        }
        // kwadrat: tarcza jest ograniczona szerokoscia, wiec wysokosc musi byc taka sama
        setMeasuredDimension(width, width);
    }

    private void init() {
        // Cienie pod lukiem wartosci wymagaja warstwy programowej; ustawiamy raz,
        // bo setLayerType() w onDraw() powoduje ponowne rysowanie w kolko.
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(Color.parseColor("#232C38"));

        zonePaint.setStyle(Paint.Style.STROKE);
        zonePaint.setStrokeCap(Paint.Cap.BUTT);

        valuePaint.setStyle(Paint.Style.STROKE);
        valuePaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setColor(Color.parseColor("#4A5766"));

        labelPaint.setColor(Color.parseColor("#A7B2BE"));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        needlePaint.setColor(Color.parseColor("#EAEEF2"));

        bigTextPaint.setColor(Color.parseColor("#EAEEF2"));
        bigTextPaint.setTextAlign(Paint.Align.CENTER);
        bigTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        unitPaint.setColor(Color.parseColor("#8B98A5"));
        unitPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setPower(float kw) {
        if (Float.isNaN(kw)) {
            // brak danych - igla od razu na zero, bez animacji dokreślania
            this.kw = Float.NaN;
            this.shownKw = Float.NaN;
            animating = false;
            invalidate();
            return;
        }

        this.kw = kw;
        if (kw > peakKw) peakKw = kw;

        if (Float.isNaN(shownKw)) {
            shownKw = kw;          // pierwsza wartosc po polaczeniu - bez skoku z zera
            animating = false;
            invalidate();
            return;
        }

        if (!animating) {
            animating = true;
            lastFrameMs = 0;
            postInvalidateOnAnimation();
        }
    }

    /**
     * Przesuwa igle o jedna klatke w strone wartosci docelowej.
     *
     * Ruch jest wykladniczy (szybki start, miekkie dojscie), a nie skokowy: przy
     * skoku 2 -> 20 kW igla przechodzi przez wszystkie wartosci posrednie w ok. 0,25 s.
     * Dzieki temu liczba i luk wartosci pokazuja to samo, co igla.
     */
    private void stepNeedle() {
        long now = android.os.SystemClock.uptimeMillis();
        if (!animating) {
            lastFrameMs = now;
            return;
        }

        float dt = lastFrameMs == 0 ? 0.016f : (now - lastFrameMs) / 1000f;
        lastFrameMs = now;
        if (dt <= 0f) dt = 0.016f;
        if (dt > 0.05f) dt = 0.05f;      // klatka zgubiona (np. ekran w tle) - nie skaczemy

        if (Float.isNaN(shownKw)) {
            shownKw = kw;
            animating = false;
            return;
        }

        float delta = kw - shownKw;
        if (Math.abs(delta) <= NEEDLE_SNAP) {
            shownKw = kw;
            animating = false;
            return;
        }

        float step = delta * (1f - (float) Math.exp(-dt / NEEDLE_TAU_S));
        float maxStep = NEEDLE_MAX_RATE * dt;
        if (Math.abs(step) > maxStep) step = Math.signum(delta) * maxStep;

        shownKw += step;
        // nie przestrzel w druga strone
        if ((delta > 0f && shownKw > kw) || (delta < 0f && shownKw < kw)) {
            shownKw = kw;
            animating = false;
        }
    }

    public void setPeak(float peakKw) {
        this.peakKw = peakKw;
        invalidate();
    }

    public void resetPeak() {
        peakKw = 0f;
    }

    public void setLayout(Layout layout) {
        this.layout = layout;
        invalidate();
    }

    private float zeroAngle() {
        return layout.zeroAngle;
    }

    /**
     * Kat dla wartosci. 0 kW = 270 (12:00), rekuperacja w lewo (90 stopni),
     * pobor w prawo (180 stopni) - zero wypada w 1/3 tarczy.
     */
    private float angleOf(float value) {
        if (value < 0f) {
            float t = Math.min(1f, -value / -REGEN_MIN_KW);
            return zeroAngle() - layout.regenSpan() * t;
        }
        float t = Math.min(1f, value / DRAW_MAX_KW);
        return zeroAngle() + layout.drawSpan() * t;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // igla dogania wartosc docelowa - rysujemy stan posredni, nie skok
        stepNeedle();

        int w = getWidth();
        int h = getHeight();
        float size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f + size * 0.02f;
        float radius = size / 2f - size * 0.07f;

        float stroke = Math.max(10f, size * 0.045f);
        trackPaint.setStrokeWidth(stroke);
        valuePaint.setStrokeWidth(stroke);
        zonePaint.setStrokeWidth(stroke * 0.5f);

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        canvas.drawArc(arcRect, layout.startAngle(), layout.totalSweep(), false, trackPaint);

        // strefy
        drawZone(canvas, REGEN_MIN_KW, -8f, "#1B3446");     // rekuperacja
        drawZone(canvas, 0f, 18f, "#1F3A2C");               // spokojnie
        drawZone(canvas, 18f, 32f, "#3A3020");              // mocniej
        drawZone(canvas, 32f, DRAW_MAX_KW, "#3A2222");      // maks

        // podzialka: kreska co 5 kW (dluzsza co 10), etykieta tylko przy wielokrotnosci 10
        tickPaint.setStrokeWidth(Math.max(2f, size * 0.006f));
        labelPaint.setTextSize(Math.max(15f, size * 0.050f));
        float outer = radius - stroke * 0.75f;
        float inner = outer - size * 0.05f;

        for (float v = REGEN_MIN_KW; v <= DRAW_MAX_KW + 0.01f; v += 5f) {
            // skrajne kreski (-25 i +50) pomijamy - schodza sie na 6:00 i tylko zageszczaja
            // podzialke przy przerwie; skala konczy sie na -20 i +40
            if (v <= REGEN_MIN_KW + 0.01f || v >= DRAW_MAX_KW - 0.01f) continue;

            float angle = (float) Math.toRadians(angleOf(v));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            // co 10 kW kreska dluzsza i etykieta; -5 i -15 maja tylko krotka kreske
            boolean major = Math.abs(Math.round(v) % 10) == 0;
            float tickInner = major ? inner : inner - size * 0.014f;
            float tickOuter = major ? outer : outer - size * 0.014f;
            canvas.drawLine(cx + tickInner * cos, cy + tickInner * sin,
                    cx + tickOuter * cos, cy + tickOuter * sin, tickPaint);

            // skrajne wartosci (-25 i 50) schodza sie na 6:00 - nie opisujemy ich,
            // zeby etykiety sie nie zlewaly. Kreski zostaja.
            boolean labeled = major
                    && v > REGEN_MIN_KW + 0.01f
                    && v < DRAW_MAX_KW - 0.01f;

            if (labeled) {
                float labelR = outer - size * 0.082f;
                canvas.drawText(String.valueOf((int) v),
                        cx + labelR * cos,
                        cy + labelR * sin + labelPaint.getTextSize() * 0.35f,
                        labelPaint);
            }
        }

        // luk wartosci
        if (!Float.isNaN(shownKw)) {
            int color = colorFor(shownKw);
            valuePaint.setColor(color);
            valuePaint.setShadowLayer(size * 0.025f, 0f, 0f, color);

            float valueAngle = angleOf(shownKw);
            canvas.drawArc(arcRect, Math.min(zeroAngle(), valueAngle),
                    Math.abs(valueAngle - zeroAngle()), false, valuePaint);
            valuePaint.clearShadowLayer();
        }

        // igla - krotka, zeby nie wchodzila w tekst w srodku
        float needleAngle = (float) Math.toRadians(angleOf(Float.isNaN(shownKw) ? 0f : shownKw));
        needlePaint.setStrokeWidth(Math.max(3f, size * 0.011f));
        canvas.drawLine(cx + radius * 0.45f * (float) Math.cos(needleAngle),
                cy + radius * 0.45f * (float) Math.sin(needleAngle),
                cx + radius * 0.90f * (float) Math.cos(needleAngle),
                cy + radius * 0.90f * (float) Math.sin(needleAngle), needlePaint);

        // wartosc liczbowa: czesc calkowita duza, ulamkowa mniejsza (czytelniej niz "2,5")
        float baseline = cy + size * 0.14f;
        String integers;
        String fraction;
        if (Float.isNaN(shownKw)) {
            integers = "–";
            fraction = "";
        } else {
            String formatted = String.format(java.util.Locale.US, "%.1f", shownKw);   // np. 2.5
            int dot = formatted.indexOf('.');
            integers = formatted.substring(0, dot);
            fraction = "," + formatted.substring(dot + 1);
        }

        float bigSize = size * 0.185f;
        float smallSize = bigSize * 0.62f;

        bigTextPaint.setTextAlign(Paint.Align.LEFT);
        bigTextPaint.setTextSize(bigSize);
        float widthInt = bigTextPaint.measureText(integers);
        bigTextPaint.setTextSize(smallSize);
        float widthFraction = fraction.isEmpty() ? 0f : bigTextPaint.measureText(fraction);

        float startX = cx - (widthInt + widthFraction) / 2f;
        bigTextPaint.setTextSize(bigSize);
        canvas.drawText(integers, startX, baseline, bigTextPaint);
        if (!fraction.isEmpty()) {
            bigTextPaint.setTextSize(smallSize);
            canvas.drawText(fraction, startX + widthInt, baseline, bigTextPaint);
        }
        bigTextPaint.setTextAlign(Paint.Align.CENTER);

        unitPaint.setTextSize(size * 0.058f);
        canvas.drawText("kW", cx, cy + size * 0.205f, unitPaint);

        // igla jeszcze nie dotarla - kolejna klatka animacji (ok. 60 na sekunde)
        if (animating) postInvalidateOnAnimation();
    }

    private void drawZone(Canvas canvas, float from, float to, String color) {
        zonePaint.setColor(Color.parseColor(color));
        float a1 = angleOf(from);
        float a2 = angleOf(to);
        canvas.drawArc(arcRect, Math.min(a1, a2), Math.abs(a2 - a1), false, zonePaint);
    }

    private static int colorFor(float kw) {
        if (kw < -0.3f) return Color.parseColor("#5BC8FF");   // rekuperacja
        if (kw < 18f) return Color.parseColor("#57D7A5");     // spokojna jazda
        if (kw < 32f) return Color.parseColor("#FFB057");     // mocno
        return Color.parseColor("#FF6B6B");                   // maks
    }
}
