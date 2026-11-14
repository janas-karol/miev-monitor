package io.github.janaskarol.miev;

/**
 * Liczy parametry biezacego odcinka jazdy.
 *
 * Ta sama logika co w wersji Python (dashboard): odcinek zaczyna sie, gdy auto
 * ruszy, a konczy po 120 s stania. Dystans bierzemy z licznika (0x412), a gdy
 * go brak - z calkowania predkosci. Energia: pobor, rekuperacja, grzalka.
 */
final class TripTracker {

    private static final long END_AFTER_STATIONARY_MS = 120_000;

    static final class Trip {
        long startMs;
        long endMs;
        float startSoc = Float.NaN;
        float endSoc = Float.NaN;
        float distanceKm;
        float dischargeKwh;
        float regenKwh;
        float heaterKwh;
        float maxSpeed;
        int odoStart = -1;
        int odoEnd = -1;

        float netKwh() {
            return dischargeKwh - regenKwh;
        }

        float per100Km() {
            return distanceKm > 0.05f ? netKwh() / distanceKm * 100f : Float.NaN;
        }

        long durationMs() {
            return Math.max(0, endMs - startMs);
        }
    }

    interface Listener {
        void onTripFinished(Trip trip);
    }

    private final Listener listener;

    private boolean active;
    private long startMs;
    private long lastMs;
    private long lastMovingMs;
    private float startSoc = Float.NaN;
    private float endSoc = Float.NaN;
    private float distance;
    private float discharge;
    private float regen;
    private float heater;
    private float maxSpeed;
    private int odoStart = -1;
    private int odoEnd = -1;
    private float integratedDistance;
    private float peakKw;

    TripTracker(Listener listener) {
        this.listener = listener;
    }

    boolean isActive() {
        return active;
    }

    float peakKw() {
        return peakKw;
    }

    /** Wola to usluga raz na sekunde po zdekodowaniu probki. */
    void update(Decoder d, long nowMs) {
        if (!active) {
            if (d.isMoving()) begin(d, nowMs);
            return;
        }

        float dt = lastMs == 0 ? 0f : (nowMs - lastMs) / 1000f;
        if (dt > 5f) dt = 5f;              // przerwa w danych - nie doliczamy "na zapas"
        if (dt < 0f) dt = 0f;
        lastMs = nowMs;

        boolean moving = d.isMoving();
        if (moving) lastMovingMs = nowMs;

        if (!Float.isNaN(d.soc)) endSoc = d.soc;
        if (!Float.isNaN(d.speed)) {
            maxSpeed = Math.max(maxSpeed, d.speed);
            if (d.speed > 0.5f) integratedDistance += d.speed * dt / 3600f;   // km
        }
        if (d.odoKm > 0) {
            odoEnd = d.odoKm;
            if (odoStart < 0) odoStart = d.odoKm;
        }

        float kw = d.powerKw();
        if (!Float.isNaN(kw)) {
            peakKw = Math.max(peakKw, kw);
            if (kw < 0) discharge += -kw * dt / 3600f;
            else regen += kw * dt / 3600f;
        }
        float hk = d.heaterKw();
        if (!Float.isNaN(hk) && hk > 0.1f) heater += hk * dt / 3600f;

        // dystans: licznik ma priorytet, calka predkosci jako zapas
        if (odoStart > 0 && odoEnd >= odoStart) distance = odoEnd - odoStart;
        else distance = integratedDistance;

        if (nowMs - lastMovingMs > END_AFTER_STATIONARY_MS) finish(nowMs);
    }

    private void begin(Decoder d, long nowMs) {
        active = true;
        startMs = nowMs;
        lastMs = nowMs;
        lastMovingMs = nowMs;
        startSoc = d.soc;
        endSoc = d.soc;
        distance = 0;
        integratedDistance = 0;
        discharge = 0;
        regen = 0;
        heater = 0;
        maxSpeed = 0;
        peakKw = 0;
        odoStart = d.odoKm > 0 ? d.odoKm : -1;
        odoEnd = odoStart;
    }

    private void finish(long nowMs) {
        Trip trip = new Trip();
        trip.startMs = startMs;
        // koniec = ostatni ruch, nie moment wykrycia
        trip.endMs = lastMovingMs;
        trip.startSoc = startSoc;
        trip.endSoc = endSoc;
        trip.distanceKm = distance;
        trip.dischargeKwh = discharge;
        trip.regenKwh = regen;
        trip.heaterKwh = heater;
        trip.maxSpeed = maxSpeed;
        trip.odoStart = odoStart;
        trip.odoEnd = odoEnd;

        active = false;
        if (listener != null) listener.onTripFinished(trip);
    }

    /** Podglad biezacego odcinka (do wyswietlenia). */
    Trip snapshot(long nowMs) {
        Trip trip = new Trip();
        trip.startMs = startMs;
        trip.endMs = nowMs;
        trip.startSoc = startSoc;
        trip.endSoc = endSoc;
        trip.distanceKm = distance;
        trip.dischargeKwh = discharge;
        trip.regenKwh = regen;
        trip.heaterKwh = heater;
        trip.maxSpeed = maxSpeed;
        trip.odoStart = odoStart;
        trip.odoEnd = odoEnd;
        return trip;
    }
}
