package io.github.janaskarol.miev;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Usluga czytajaca dane z adaptera w tle (z powiadomieniem).
 *
 * Dlaczego usluga, a nie watek w ekranie: Android zabija proces, gdy ekran jest
 * zgaszony i nie ma foreground service. Dzieki temu odczyt trwa w kieszeni,
 * a powiadomienie pokazuje na biezaco SoC i moc.
 */
public class ReaderService extends Service {

    static final String ACTION_START = "io.github.janaskarol.miev.START";
    static final String ACTION_STOP = "io.github.janaskarol.miev.STOP";
    static final String EXTRA_MAC = "mac";
    static final String EXTRA_NAME = "name";

    private static final String CHANNEL_ID = "imiev_reader";
    private static final int NOTIFICATION_ID = 1;

    private static final String[] INIT_SEQ = {
            "ATE0", "ATL0", "ATS0", "ATH1", "ATSP6",
            "ATCAF1", "ATFCSH761", "ATFCSD300000", "ATFCSM1", "ATSH761",
    };

    private static final long SAMPLE_MOVING_MS = 1000;   // probka co 1 s w jezdzie - dosc gesto na testy pod obciazeniem
    private static final long SAMPLE_IDLE_MS = 30000;
    /** Co ile przepisywac dane do ekranu. Igla i tak animuje sie miedzy odczytami. */
    private static final long UI_REFRESH_MS = 500;
    private static final long RECONNECT_MS = 5000;
    /** Po wylaczeniu zaplonu ramki 0x373 znikaja - tyle ciszy uznajemy za koniec jazdy. */
    private static final long CAR_OFF_SILENCE_MS = 60_000;
    /** Brak ramek dluzej niz to = dane nieaktualne (nie liczymy z nich ruchu ani energii). */
    private static final long FRAME_STALE_MS = 10_000;
    /**
     * Co ile pytac o ogniwa. Jeden odczyt to 26 ramek ISO-TP plus przelaczenie
     * trybu, wiec blokuje nasluch na ok. 0,7-1 s. Przy 5 s zostaje ~80% czasu
     * na moc - przy 3 s moc zaczynala rwac.
     */
    private static final long CELL_POLL_MS = 5000;
    /** Limit na odpowiedz 2102. Pelna odpowiedz przychodzi w ~0,5 s, wiec 1,2 s to zapas. */
    private static final long CELL_TIMEOUT_MS = 1200;
    /** Limit na odpowiedz 2103 (temperatury - krotsza odpowiedz). */
    private static final long TEMP_TIMEOUT_MS = 1000;
    /** Po tylu nieudanych cyklach restartujemy adapter (ATZ), zeby nie rwac polaczenia. */
    private static final int CELL_REINIT_AFTER = 6;

    private volatile boolean running = false;
    private Thread worker;
    private final ElmConnection elm = new ElmConnection();
    private Decoder decoder = new Decoder();
    private SampleLog sampleLog;
    private TripTracker tripTracker;
    private PowerManager.WakeLock wakeLock;

    private String targetMac;
    private String targetName = "adapter";
    private long lastSampleAt = 0;
    /** Ile razy z rzedu nie udalo sie odczytac ogniw (urwane odpowiedzi adaptera). */
    private int cellsFailures = 0;
    /** Licznik cykli odczytu ogniw - temperatury czytamy rzadziej. */
    private int cellCycles = 0;
    /** Czy adapter jest w trybie nasluchu (surowe ramki). Po odczycie ogniw - nie. */
    private boolean monitorMode = false;
    private long lastNotifyAt = 0;
    /** Pasek "ostatnie 2 minuty" ma 120 punktow - dokladamy je raz na sekunde. */
    private long lastPowerPushAt = 0;

    // ------------------------------------------------------------------ cykl zycia

    @Override
    public void onCreate() {
        super.onCreate();
        sampleLog = new SampleLog(this);
        // jazda przerwana przez zabicie aplikacji wraca do historii przy starcie
        sampleLog.finalizeOpenTrips();
        tripTracker = new TripTracker(new TripRecorder());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopReading("Zatrzymano");
            return START_NOT_STICKY;
        }

        if (intent != null) {
            targetMac = intent.getStringExtra(EXTRA_MAC);
            String name = intent.getStringExtra(EXTRA_NAME);
            if (name != null) targetName = name;
        }

        startInForeground(buildNotification("Łączę z adapterem…"));
        CarState.serviceRunning = true;

        if (targetMac == null) {
            CarState.status = "Brak adresu adaptera";
            stopReading("Brak adresu adaptera");
            return START_NOT_STICKY;
        }

        if (worker == null || !worker.isAlive()) {
            running = true;
            acquireWakeLock();
            worker = new Thread(this::readLoop, "reader-service");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopReading("Usługa zatrzymana");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ petla

    private void readLoop() {
        while (running) {
            try {
                BluetoothDevice device = resolveDevice();
                CarState.status = "Łączę z " + targetName + "…";
                elm.connect(device);
                CarState.connected = true;

                if (!initializeAdapter()) {
                    throw new IllegalStateException("adapter nie odpowiedział na ATZ");
                }

                decoder = new Decoder();
                CarState.status = "Czytam dane";
                innerLoop();
            } catch (Exception e) {
                CarState.connected = false;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                CarState.status = "Błąd: " + msg;
                elm.close();
            }

            if (!running) break;
            CarState.status = "Ponawiam połączenie za " + (RECONNECT_MS / 1000) + " s…";
            sleep(RECONNECT_MS);
        }
    }

    /**
     * Zapisywanie jazd. Odcinek jest w bazie od pierwszej sekundy (closed = 0) i
     * aktualizowany co 5 s, wiec nawet zabicie aplikacji go nie kasuje.
     */
    private final class TripRecorder implements TripTracker.Listener {
        @Override
        public int onTripStarted(TripTracker.Trip t) {
            return sampleLog.startTrip(t);
        }

        @Override
        public void onTripProgress(int id, TripTracker.Trip t) {
            sampleLog.updateTrip(id, t);
        }

        @Override
        public void onTripFinished(int id, TripTracker.Trip t) {
            sampleLog.closeTrip(id, t);
            CarState.tripsSaved++;
            CarState.lastTripSaved = String.format(java.util.Locale.US,
                    "zapisano jazdę: %.1f km, %.1f kWh", t.distanceKm, t.netKwh());
        }

        @Override
        public void onTripDiscarded(int id) {
            sampleLog.discardTrip(id);
        }
    }

    private BluetoothDevice resolveDevice() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new IllegalStateException("brak Bluetootha w telefonie");
        if (!adapter.isEnabled()) throw new IllegalStateException("Bluetooth wyłączony");
        return adapter.getRemoteDevice(targetMac);
    }

    private boolean initializeAdapter() {
        elm.command("ATZ", 2500);
        // po restarcie klon ELM potrzebuje chwili, zanim przyjmie komendy
        sleep(700);
        // cala sekwencja jednym zapisem - 10 rund po ~50 ms to za duzo przy restarcie
        int ok = elm.commandsBatch(INIT_SEQ, 3000);
        monitorMode = false;
        sleep(100);
        return ok >= INIT_SEQ.length - 1;    // jeden zgubiony prompt nie przekresla startu
    }

    private void innerLoop() {
        cellsFailures = 0;
        cellCycles = 0;
        long lastCells = System.currentTimeMillis() - CELL_POLL_MS + 2000;   // pierwszy odczyt po 2 s
        long lastUi = 0;
        monitorMode = false;

        while (running && elm.isOpen()) {
            long now = System.currentTimeMillis();

            if (now - lastCells > CELL_POLL_MS) {
                elm.stopMonitor();          // zwolnij magistrale na czas 2102/2103
                pollCellsAndTemps();
                lastCells = now;
                monitorMode = false;        // po odczycie trzeba znowu ustawic nasluch
            } else {
                ensureMonitorStream();
                String burst = elm.readMonitor(150);
                if (!burst.isEmpty()) {
                    decoder.decodeBurst(burst);
                    // "BUFFER FULL" = adapter przestal nadawac; wznawiamy strumien
                    if (burst.contains("BUFF")) monitorMode = false;
                }
            }

            maybeLogSample();

            if (now - lastPowerPushAt >= 1000) {      // pasek mocy: 120 punktow = 2 minuty
                lastPowerPushAt = now;
                CarState.pushPower(CarState.powerKw);
            }

            // cisza w ramkach = auto wylaczone; odcinek trzeba zamknac, bo warunek
            // "120 s stania" nigdy sie nie spelni (nie ma juz zadnych ramek)
            if (tripTracker.isActive() && decoder.lastFrameAt > 0
                    && now - decoder.lastFrameAt > CAR_OFF_SILENCE_MS) {
                tripTracker.end(decoder.lastFrameAt);
            }

            boolean fresh = now - decoder.lastFrameAt <= FRAME_STALE_MS;
            tripTracker.update(decoder, now, fresh);

            if (now - lastUi > UI_REFRESH_MS) {
                lastUi = now;
                CarState.update(decoder);
                CarState.updateTrip(tripTracker.snapshot(now), tripTracker.isActive());
                CarState.setPeak(tripTracker.peakKw());
                if (now - lastNotifyAt > 5000) {
                    lastNotifyAt = now;
                    notify(buildNotification(CarState.summary()));
                }
            }
        }
    }

    /**
     * Przelaczenie adaptera w tryb monitora. Bez CAF0 klon ELM gubi ramki 0x373/0x374
     * (tak samo jak w wersji Python) - dlatego wracamy do tego trybu po kazdym odczycie ogniw.
     */
    private void ensureMonitorStream() {
        if (monitorMode) return;
        int ok = elm.commandsBatch(new String[]{"ATCAF0", "ATFCSM0", "ATSH7DF"}, 900);
        if (ok < 3) {
            throw new IllegalStateException("adapter nie przyjął trybu nasłuchu");
        }
        if (!elm.startMonitor()) throw new IllegalStateException("adapter nie startuje nasłuchu");
        monitorMode = true;
    }

    /**
     * Odczyt ogniw i temperatur przez ISO-TP.
     *
     * Klon ELM czasem zwraca urwana odpowiedz (jedno ogniwo po 53 V, temperatury
     * po 170 C). Dekoder odrzuca takie smieci, a tutaj probujemy drugi raz, zamiast
     * kasowac ostatnie dobre dane. Po trzech nieudanych cyklach z rzedu uznajemy,
     * ze adapter nie odpowiada i wymuszamy ponowne polaczenie.
     */
    private void pollCellsAndTemps() {
        elm.command("ATCAF1", 900);
        elm.command("ATFCSM1", 900);
        elm.command("ATFCSH761", 900);
        elm.command("ATFCSD300000", 900);
        elm.command("ATSH761", 900);
        sleep(100);

        boolean cellsOk = false;
        for (int attempt = 0; attempt < 2 && !cellsOk; attempt++) {
            if (attempt > 0) sleep(250);
            String cells = elm.command("2102", 4000);
            if (cells.isEmpty()) continue;
            cellsOk = decoder.decodeCells(cells) > 0;
        }
        cellsFailures = cellsOk ? 0 : cellsFailures + 1;

        // temperatury zmieniaja sie wolno - pytamy co piaty cykl (15 s), zeby nie
        // blokowac nasluchu dluzej, niz trzeba
        if (++cellCycles % 5 == 0) {
            String temps = elm.command("2103", 4000);
            if (!temps.isEmpty()) decoder.decodeTemps(temps);
        }
        sleep(50);

        if (cellsFailures >= 10) {         // 10 cykli po 3 s = pol minuty bez ogniw
            throw new IllegalStateException("adapter nie podaje ogniw (" + decoder.cellsRejected + ")");
        }
    }

    private void maybeLogSample() {
        if (!decoder.havePack) return;
        long now = System.currentTimeMillis();
        long interval = decoder.isMoving() ? SAMPLE_MOVING_MS : SAMPLE_IDLE_MS;
        if (now - lastSampleAt < interval) return;
        lastSampleAt = now;
        sampleLog.add(decoder, now / 1000f);
        CarState.samples = sampleLog.count();
    }

    // ------------------------------------------------------------------ powiadomienie

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Odczyt z i-MiEV", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Stały odczyt danych z adaptera ELM327");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setContentTitle("i-MiEV — czytam dane")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_imiev)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        Intent stop = new Intent(this, ReaderService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, flags);
        // stara forma z ikona zasobu - ikona null bywa odrzucana przez nowsze systemy
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Zatrzymaj", stopIntent).build());

        return b.build();
    }

    /**
     * Start usługi pierwszoplanowej. Od targetSdk 34 system sprawdza typ usługi
     * (connectedDevice) i jego uprawnienia - podajemy typ jawnie i łapiemy błąd,
     * żeby zamiast zamknięcia aplikacji pokazać przyczynę w statusie.
     */
    private void startInForeground(Notification notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException e) {
            CarState.status = "Nie mogę uruchomić odczytu w tle: " + e.getClass().getSimpleName()
                    + " — " + e.getMessage();
            CrashLog.write(this, e);
        }
    }

    private void notify(Notification n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, n);
    }

    // ------------------------------------------------------------------ zatrzymanie

    private void stopReading(String reason) {
        // najpierw domykamy jazde - inaczej historia gubi odcinek
        if (tripTracker != null) tripTracker.end(System.currentTimeMillis());
        running = false;
        CarState.serviceRunning = false;
        CarState.connected = false;
        CarState.status = reason;

        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        elm.close();

        releaseWakeLock();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "imiev:reader");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
