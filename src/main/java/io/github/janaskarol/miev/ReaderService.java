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

    private static final long SAMPLE_MOVING_MS = 2000;
    private static final long SAMPLE_IDLE_MS = 30000;
    private static final long RECONNECT_MS = 5000;
    /** Ogniwa i temperatury odczytujemy rzadko - kazdy odczyt blokuje nasluch na kilka sekund. */
    private static final long CELL_POLL_MS = 30000;

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
    /** Czy adapter jest w trybie nasluchu (surowe ramki). Po odczycie ogniw - nie. */
    private boolean monitorMode = false;
    private long lastNotifyAt = 0;

    // ------------------------------------------------------------------ cykl zycia

    @Override
    public void onCreate() {
        super.onCreate();
        sampleLog = new SampleLog(this);
        tripTracker = new TripTracker(sampleLog::addTrip);
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
        for (String cmd : INIT_SEQ) {
            if (elm.command(cmd, 1000).isEmpty()) return false;
        }
        sleep(200);
        return true;
    }

    private void innerLoop() {
        long lastCells = System.currentTimeMillis() - CELL_POLL_MS + 2000;   // pierwszy odczyt po 2 s
        long lastUi = 0;
        monitorMode = false;

        while (running && elm.isOpen()) {
            long now = System.currentTimeMillis();

            if (now - lastCells > CELL_POLL_MS) {
                pollCellsAndTemps();
                lastCells = now;
                monitorMode = false;
            } else {
                ensureMonitorMode();
                decoder.decodeBurst(elm.monitor(80));
            }

            maybeLogSample();

            tripTracker.update(decoder, now);

            if (now - lastUi > 1000) {
                lastUi = now;
                CarState.update(decoder);
                CarState.pushPower(CarState.powerKw);
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
    private void ensureMonitorMode() {
        if (monitorMode) return;
        boolean ok = !elm.command("ATCAF0", 800).isEmpty();
        elm.command("ATFCSM0", 800);
        elm.command("ATSH7DF", 800);
        if (!ok) {
            throw new IllegalStateException("adapter nie przyjął trybu nasłuchu");
        }
        monitorMode = true;
    }

    private void pollCellsAndTemps() {
        elm.command("ATCAF1", 900);
        elm.command("ATFCSM1", 900);
        elm.command("ATFCSH761", 900);
        elm.command("ATFCSD300000", 900);
        elm.command("ATSH761", 900);
        sleep(150);

        String cells = elm.command("2102", 2500);
        if (cells.isEmpty()) throw new IllegalStateException("brak odpowiedzi na 2102");
        decoder.decodeCells(cells);

        String temps = elm.command("2103", 2500);
        if (!temps.isEmpty()) decoder.decodeTemps(temps);
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
