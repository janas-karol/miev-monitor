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
    /** Co ile zapisujemy postep odcinka (zeby przetrwal zabicie aplikacji). */
    private static final long PROGRESS_SAVE_MS = 5_000;
    /** Krotszego odcinka nie ma sensu pokazywac w historii. */
    private static final float MIN_TRIP_KM = 0.05f;

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

    /**
     * Zgloszenia do warstwy zapisu. Dzieki nim odcinek jest w historii od pierwszej
     * sekundy (jako "w trakcie"), wiec zabicie aplikacji nie kasuje jazdy.
     */
    interface Listener {
        /** Odcinek sie zaczal - zwraca id wpisu w historii (-1 = nie zapisujemy). */
        int onTripStarted(Trip trip);

        /** Postep co kilka sekund - aktualizuje wpis "w trakcie". */
        void onTripProgress(int id, Trip trip);

        /** Odcinek zamkniety - wpis dostaje finalne dane. */
        void onTripFinished(int id, Trip trip);

        /** Odcinek byl za krotki (np. przeparkowanie) - wpis do usuniecia. */
        void onTripDiscarded(int id);
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
    private int tripId = -1;
    private long lastProgressMs = 0;

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
        update(d, nowMs, true);
    }

    /**
     * @param fresh czy dane sa swieze (ramki wlasnie przyszly). Po wylaczeniu auta
     *              dekoder trzyma ostatnie wartosci - bez tego flaga ruchu zostawala
     *              wlaczona, energia naliczala sie z zamrozonych liczb, a odcinek
     *              nigdy sie nie konczyl.
     */
    void update(Decoder d, long nowMs, boolean fresh) {
        if (!active) {
            if (fresh && d.isMoving()) begin(d, nowMs);
            return;
        }

        float dt = lastMs == 0 ? 0f : (nowMs - lastMs) / 1000f;
        if (dt > 5f) dt = 5f;              // przerwa w danych - nie doliczamy "na zapas"
        if (dt < 0f) dt = 0f;
        lastMs = nowMs;
        if (!fresh) dt = 0f;               // cisza w ramkach - nie ma czego calkowac

        boolean moving = fresh && d.isMoving();
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
            peakKw = Math.max(peakKw, -kw);       // szczyt poboru (kw < 0 = pobor)
            if (kw < 0) discharge += -kw * dt / 3600f;
            else regen += kw * dt / 3600f;
        }
        float hk = d.heaterKw();
        if (!Float.isNaN(hk) && hk > 0.1f) heater += hk * dt / 3600f;

        // dystans: licznik ma priorytet, calka predkosci jako zapas
        if (odoStart > 0 && odoEnd >= odoStart) distance = odoEnd - odoStart;
        else distance = integratedDistance;

        if (nowMs - lastProgressMs >= PROGRESS_SAVE_MS) {
            lastProgressMs = nowMs;
            if (tripId >= 0 && listener != null) listener.onTripProgress(tripId, snapshot(nowMs));
        }

        if (nowMs - lastMovingMs > END_AFTER_STATIONARY_MS) finish(nowMs);
    }

    /**
     * Konczy odcinek natychmiast - przy rozlaczeniu, zatrzymaniu uslugi albo gdy
     * auto zamilklo (wylaczenie zaplonu). Bez tego jazda przepadala: warunek
     * "120 s stania" nigdy sie nie spelnial, bo po wylaczeniu auta nie ma ramek.
     */
    void end(long nowMs) {
        if (active) finish(nowMs);
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
        lastProgressMs = nowMs;
        tripId = listener != null ? listener.onTripStarted(snapshot(nowMs)) : -1;
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
        int id = tripId;
        tripId = -1;
        if (listener == null) return;
        if (trip.distanceKm < MIN_TRIP_KM) {
            if (id >= 0) listener.onTripDiscarded(id);
        } else if (id >= 0) {
            listener.onTripFinished(id, trip);
        }
    }

    /** Podglad biezacego odcinka (do wyswietlenia). */
    Trip snapshot(long nowMs) {
        Trip trip = new Trip();
        trip.startMs = startMs;
        // koniec odcinka to ostatni ruch, nie moment zapisu
        trip.endMs = lastMovingMs > startMs ? lastMovingMs : nowMs;
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
