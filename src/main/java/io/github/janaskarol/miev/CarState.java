package io.github.janaskarol.miev;

/**
 * Wspolny stan miedzy usluga czytajaca (watek w tle) a ekranem.
 *
 * Usluga pisze, ekran czyta - dlatego wszystkie pola sa volatile i tylko
 * prostych typow (zadnych obiektow wspoldzielonych miedzy watkami).
 */
final class CarState {

    private CarState() {
    }

    static volatile boolean serviceRunning = false;
    static volatile boolean connected = false;
    static volatile String status = "Nie połączono z adapterem";
    static volatile String adapterName = "";

    static volatile long samples = 0;

    static volatile float soc = Float.NaN;
    static volatile float volts = Float.NaN;
    static volatile float amps = Float.NaN;
    static volatile float powerKw = Float.NaN;
    static volatile float speed = Float.NaN;
    static volatile float heaterA = Float.NaN;
    static volatile float heaterKw = Float.NaN;
    static volatile float acA = Float.NaN;
    static volatile float acKw = Float.NaN;
    static volatile float flowC = Float.NaN;
    static volatile float retC = Float.NaN;

    static volatile int rangeKm = -1;
    static volatile int odoKm = -1;
    static volatile int cellMin = 0;
    static volatile int cellMax = 0;
    static volatile int cellCount = 0;
    static volatile int tempMin = 0;
    static volatile int tempMax = 0;
    static volatile int tempCount = 0;
    static volatile int[] cells = new int[0];
    static volatile int cellMinIndex = -1;
    static volatile int cellMaxIndex = -1;

    // --- biezacy odcinek jazdy ---
    static volatile boolean tripActive = false;
    static volatile float tripDistanceKm = 0f;
    static volatile float tripDischargeKwh = 0f;
    static volatile float tripRegenKwh = 0f;
    static volatile float tripHeaterKwh = 0f;
    static volatile float tripNetKwh = 0f;
    static volatile float tripPer100 = Float.NaN;
    static volatile long tripDurationMs = 0;
    static volatile float tripStartSoc = Float.NaN;
    static volatile float tripEndSoc = Float.NaN;
    static volatile float tripMaxSpeed = 0f;
    static volatile float peakKw = 0f;

    // --- ostatnie 2 minuty mocy (1 probka na sekunde) ---
    static final int POWER_HISTORY = 120;
    private static final float[] powerHistory = new float[POWER_HISTORY];
    private static int powerHistoryFilled = 0;

    static synchronized void pushPower(float kw) {
        System.arraycopy(powerHistory, 1, powerHistory, 0, POWER_HISTORY - 1);
        powerHistory[POWER_HISTORY - 1] = Float.isNaN(kw) ? 0f : kw;
        if (powerHistoryFilled < POWER_HISTORY) powerHistoryFilled++;
    }

    /** Kopia historii mocy (najstarsze -> najnowsze); dlugosc = ile zebrano. */
    static synchronized float[] powerSnapshot() {
        float[] out = new float[Math.max(powerHistoryFilled, 0)];
        System.arraycopy(powerHistory, POWER_HISTORY - out.length, out, 0, out.length);
        return out;
    }

    static void updateTrip(TripTracker.Trip t, boolean active) {
        tripActive = active;
        tripDistanceKm = t.distanceKm;
        tripDischargeKwh = t.dischargeKwh;
        tripRegenKwh = t.regenKwh;
        tripHeaterKwh = t.heaterKwh;
        tripNetKwh = t.netKwh();
        tripPer100 = t.per100Km();
        tripDurationMs = t.durationMs();
        tripStartSoc = t.startSoc;
        tripEndSoc = t.endSoc;
        tripMaxSpeed = t.maxSpeed;
    }

    static void setPeak(float peak) {
        peakKw = peak;
    }

    /** Przepisuje wartosci z dekodera - wolane tylko z watku uslugi. */
    static void update(Decoder d) {
        soc = d.soc;
        volts = d.volts;
        amps = d.amps;
        // wskazanie: dodatnia = pobor z baterii (prawa strona tarczy), ujemna = rekuperacja
        powerKw = -d.powerKw();
        speed = d.speed;
        heaterA = d.heaterA;
        heaterKw = d.heaterKw();
        acA = d.acA;
        acKw = d.acKw();
        flowC = d.flowC;
        retC = d.retC;
        rangeKm = d.rangeKm;
        odoKm = d.odoKm;
        cellMin = d.cellMin;
        cellMax = d.cellMax;
        cellCount = d.cellCount;
        tempMin = d.tempMin;
        tempMax = d.tempMax;
        tempCount = d.tempCount;
        cells = d.cells;
        cellMinIndex = d.cellMinIndex;
        cellMaxIndex = d.cellMaxIndex;
    }

    /** Czysci dane po rozlaczeniu (zostawia status i licznik probek). */
    static void reset() {
        soc = Float.NaN;
        volts = Float.NaN;
        amps = Float.NaN;
        powerKw = Float.NaN;
        speed = Float.NaN;
        heaterA = Float.NaN;
        heaterKw = Float.NaN;
        acA = Float.NaN;
        acKw = Float.NaN;
        flowC = Float.NaN;
        retC = Float.NaN;
        rangeKm = -1;
        odoKm = -1;
        cellMin = 0;
        cellMax = 0;
        cellCount = 0;
        tempMin = 0;
        tempMax = 0;
        tempCount = 0;
        cells = new int[0];
        cellMinIndex = -1;
        cellMaxIndex = -1;
    }

    static String summary() {
        StringBuilder sb = new StringBuilder();
        if (!Float.isNaN(soc)) sb.append(String.format(java.util.Locale.US, "%.0f%%", soc));
        if (!Float.isNaN(powerKw)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(java.util.Locale.US, "%.1f kW", powerKw));
        }
        if (!Float.isNaN(speed) && speed > 1) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(java.util.Locale.US, "%.0f km/h", speed));
        }
        if (sb.length() == 0) sb.append("brak danych");
        return sb.toString();
    }
}
