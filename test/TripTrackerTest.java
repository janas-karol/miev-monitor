package io.github.janaskarol.miev;

import java.util.ArrayList;
import java.util.List;

/**
 * Testy liczenia i zapisywania odcinka jazdy bez Androida.
 *
 * Uruchomienie:
 *   javac -encoding UTF-8 -d /tmp/tt src/main/java/io/github/janaskarol/miev/Decoder.java \
 *         src/main/java/io/github/janaskarol/miev/TripTracker.java test/TripTrackerTest.java
 *   java -cp /tmp/tt io.github.janaskarol.miev.TripTrackerTest
 */
public final class TripTrackerTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        System.out.printf("%-58s %s%n", what, ok ? "OK" : "BLAD");
        if (!ok) failures++;
    }

    /** Zapisuje zdarzenia z trackerа, zeby sprawdzic kolejnosc i parametry. */
    private static final class Recorder implements TripTracker.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public int onTripStarted(TripTracker.Trip t) {
            events.add("start");
            return 7;
        }

        @Override
        public void onTripProgress(int id, TripTracker.Trip t) {
            events.add("progress:" + id);
        }

        @Override
        public void onTripFinished(int id, TripTracker.Trip t) {
            events.add(String.format(java.util.Locale.US, "finish:%d:%.2f", id, t.distanceKm));
        }

        @Override
        public void onTripDiscarded(int id) {
            events.add("discard:" + id);
        }

        boolean has(String prefix) {
            for (String e : events) if (e.startsWith(prefix)) return true;
            return false;
        }

        String last() {
            return events.isEmpty() ? "" : events.get(events.size() - 1);
        }
    }

    /** Dekoder z ustawionymi recznie sygnalami - symuluje ramki z auta. */
    private static Decoder car(float kph, int odo, float amps) {
        Decoder d = new Decoder();
        d.speed = kph;
        d.odoKm = odo;
        d.volts = 330f;
        d.amps = amps;
        d.soc = 60f;
        return d;
    }

    public static void main(String[] args) {
        System.out.println("=== odcinek jazdy ===");

        // jazda przerwana wylaczeniem auta: end() zamyka odcinek bez czekania 120 s
        {
            Recorder r = new Recorder();
            TripTracker t = new TripTracker(r);
            t.update(car(50f, 1000, -10f), 0);
            t.update(car(50f, 1001, -10f), 5000);
            t.update(car(0f, 1002, -0.1f), 10000);
            check(t.isActive(), "jazda aktywna w trakcie");
            t.end(12000);
            check(!t.isActive(), "end() zamyka odcinek od razu (bez 120 s stania)");
            check(r.last().startsWith("finish:7:2.00"), "odcinek zapisany z dystansem 2 km");
        }

        // postep trafia do historii co ~5 s (zabicie aplikacji nie kasuje jazdy)
        {
            Recorder r = new Recorder();
            TripTracker t = new TripTracker(r);
            for (int s = 0; s <= 20; s++) {
                t.update(car(40f, 2000 + s, -8f), s * 1000L);
            }
            int progress = 0;
            for (String e : r.events) if (e.startsWith("progress")) progress++;
            check(r.has("start"), "start odcinka zapisany od razu");
            check(progress >= 3, "postep zapisywany co ~5 s (" + progress + " razy)");
        }

        // krotki odcinek (przeparkowanie) nie trafia do historii
        {
            Recorder r = new Recorder();
            TripTracker t = new TripTracker(r);
            t.update(car(5f, 3000, -2f), 0);
            t.update(car(0f, 3000, 0f), 2000);
            t.end(3000);
            check(r.last().equals("discard:7"), "odcinek 0 km usuniety z historii");
        }

        // stary warunek nadal dziala: 120 s stania przy zywych ramkach
        {
            Recorder r = new Recorder();
            TripTracker t = new TripTracker(r);
            t.update(car(30f, 4000, -5f), 0);
            t.update(car(0f, 4001, 0f), 1000);
            t.update(car(0f, 4001, 0f), 119_000);
            check(t.isActive(), "po 118 s stania odcinek jeszcze trwa");
            t.update(car(0f, 4001, 0f), 122_000);
            check(!t.isActive(), "po 120 s stania odcinek zamkniety");
            check(r.last().startsWith("finish:7:1.00"), "dystans z licznika 1 km");
        }

        // end() na nieaktywnym trackerze nic nie robi (brak pustych wpisow)
        {
            Recorder r = new Recorder();
            TripTracker t = new TripTracker(r);
            t.end(1000);
            check(r.events.isEmpty(), "end() bez jazdy nie tworzy wpisu");
        }

        System.out.println();
        System.out.println(failures == 0 ? "WYNIK: wszystkie testy OK" : "WYNIK: sa bledy (" + failures + ")");
        if (failures > 0) System.exit(1);
    }
}
