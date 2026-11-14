package io.github.janaskarol.miev;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ekran glowny: wskaznik mocy jak obrotomierz + karty z napieciami, temperaturami,
 * parametrami odcinka i historia jazd.
 *
 * Caly odczyt odbywa sie w {@link ReaderService}; ten ekran tylko podglada
 * {@link CarState} raz na sekunde.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "imiev";
    private static final String PREF_MAC = "adapter_mac";
    private static final int REQ_PERMISSIONS = 1;
    private static final long HISTORY_REFRESH_MS = 15000;
    private static final int TAB_START = 0;
    private static final int TAB_CELLS = 1;
    private static final int TAB_TRIP = 2;
    private static final int TAB_OTHER = 3;
    private static final String PREF_TAB = "tab";

    private final List<BluetoothDevice> devices = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("d MMM", Locale.US);

    private Spinner deviceSpinner;
    private Button connectButton;
    private TextView statusText;
    private PowerGaugeView powerGauge;
    private SparklineView powerSpark;

    private TextView socValue, speedValue, heaterValue, peakText;
    private TextView cellMinValue, cellMaxValue, cellDeltaValue, packText;
    private TextView tempMinValue, tempMaxValue, tempDeltaValue, tempsText;
    private TextView tripDistance, tripEnergy, tripPer100, tripTime, tripAvg, tripRegen, tripFoot;
    private LinearLayout historyList;
    private TextView historyEmpty;
    private TextView rangeValue, odoValue, coolantValue, acValue, logValue, cellsText, csvPathText;
    private TextView aboutText;

    // zakladki
    private View panelStart, panelCells, panelTrip, panelOther;
    private TextView tabStart, tabCells, tabTrip, tabOther;
    private LinearLayout cellGrid;
    private TextView cellTabMin, cellTabMax, cellTabDelta, cellSummary;
    private int currentTab = TAB_START;
    private int lastCellsHash = 0;


    private long lastHistoryAt = 0;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshDashboard();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ cykl zycia

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        CrashLog.install(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceSpinner = findViewById(R.id.deviceSpinner);
        connectButton = findViewById(R.id.connectButton);
        statusText = findViewById(R.id.statusText);
        powerGauge = findViewById(R.id.powerGauge);
        powerSpark = findViewById(R.id.powerSpark);

        socValue = findViewById(R.id.socValue);
        speedValue = findViewById(R.id.speedValue);
        heaterValue = findViewById(R.id.heaterValue);
        peakText = findViewById(R.id.peakText);

        cellMinValue = findViewById(R.id.cellMinValue);
        cellMaxValue = findViewById(R.id.cellMaxValue);
        cellDeltaValue = findViewById(R.id.cellDeltaValue);
        packText = findViewById(R.id.packText);

        tempMinValue = findViewById(R.id.tempMinValue);
        tempMaxValue = findViewById(R.id.tempMaxValue);
        tempDeltaValue = findViewById(R.id.tempDeltaValue);
        tempsText = findViewById(R.id.tempsText);

        tripDistance = findViewById(R.id.tripDistance);
        tripEnergy = findViewById(R.id.tripEnergy);
        tripPer100 = findViewById(R.id.tripPer100);
        tripTime = findViewById(R.id.tripTime);
        tripAvg = findViewById(R.id.tripAvg);
        tripRegen = findViewById(R.id.tripRegen);
        tripFoot = findViewById(R.id.tripFoot);

        historyList = findViewById(R.id.historyList);
        historyEmpty = findViewById(R.id.historyEmpty);

        rangeValue = findViewById(R.id.rangeValue);
        odoValue = findViewById(R.id.odoValue);
        coolantValue = findViewById(R.id.coolantValue);
        acValue = findViewById(R.id.acValue);
        logValue = findViewById(R.id.logValue);
        cellsText = findViewById(R.id.cellsText);
        csvPathText = findViewById(R.id.csvPathText);
        aboutText = findViewById(R.id.aboutText);

        panelStart = findViewById(R.id.panelStart);
        panelCells = findViewById(R.id.panelCells);
        panelTrip = findViewById(R.id.panelTrip);
        panelOther = findViewById(R.id.panelOther);

        tabStart = findViewById(R.id.tabStart);
        tabCells = findViewById(R.id.tabCells);
        tabTrip = findViewById(R.id.tabTrip);
        tabOther = findViewById(R.id.tabOther);

        cellGrid = findViewById(R.id.cellGrid);
        cellTabMin = findViewById(R.id.cellTabMin);
        cellTabMax = findViewById(R.id.cellTabMax);
        cellTabDelta = findViewById(R.id.cellTabDelta);
        cellSummary = findViewById(R.id.cellSummary);

        tabStart.setOnClickListener(v -> showTab(TAB_START));
        tabCells.setOnClickListener(v -> showTab(TAB_CELLS));
        tabTrip.setOnClickListener(v -> showTab(TAB_TRIP));
        tabOther.setOnClickListener(v -> showTab(TAB_OTHER));
        currentTab = getPreferences().getInt(PREF_TAB, TAB_START);

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);

        connectButton.setOnClickListener(v -> {
            if (CarState.serviceRunning) stopServiceNow();
            else requestPermissionsAndStart();
        });

        findViewById(R.id.exportButton).setOnClickListener(v -> exportCsv());
        findViewById(R.id.clearButton).setOnClickListener(v -> {
            new SampleLog(this).clear();
            CarState.samples = 0;
            Toast.makeText(this, "Bufor wyczyszczony", Toast.LENGTH_SHORT).show();
        });

        ToggleButton screen = findViewById(R.id.screenToggle);
        screen.setOnCheckedChangeListener((b, checked) -> {
            if (checked) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });

        applySystemBarInsets();
        loadDevices();
        showTab(currentTab);
        showFirstRunNotice();
        aboutText.setText("i-MiEV Monitor · wersja " + versionName()
                + "\nNieoficjalna aplikacja, niepowiązana z Mitsubishi Motors."
                + "\nBrak dostępu do internetu — dane nie opuszczają telefonu."
                + "\nCertyfikat SHA-256:\n" + certificateFingerprint()
                + crashSection());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(ticker);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(ticker);
        super.onPause();
    }

    // ------------------------------------------------------------------ Bluetooth

    private void loadDevices() {
        devices.clear();
        deviceAdapter.clear();

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            statusText.setText("Ten telefon nie ma Bluetootha");
            return;
        }
        if (!adapter.isEnabled()) {
            statusText.setText("Włącz Bluetooth, żeby połączyć się z adapterem");
            return;
        }

        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice d : bonded) {
                devices.add(d);
                String name = d.getName() != null ? d.getName() : "?";
                deviceAdapter.add(name + "  [" + d.getAddress() + "]");
            }
        } catch (SecurityException e) {
            statusText.setText("Brak zgody na Bluetooth — Android zapyta przy połączeniu");
            return;
        }

        if (devices.isEmpty()) {
            statusText.setText("Brak sparowanych urządzeń — sparuj adapter w ustawieniach BT (PIN 1234)");
            return;
        }

        String savedMac = getPreferences().getString(PREF_MAC, null);
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).getAddress().equals(savedMac)) {
                deviceSpinner.setSelection(i);
                break;
            }
        }
        if (!CarState.serviceRunning) {
            statusText.setText("Wybierz adapter i naciśnij Połącz");
        }
    }

    private void requestPermissionsAndStart() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                   != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
            return;
        }
        startServiceNow();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_PERMISSIONS) return;

        boolean btGranted = true;
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.BLUETOOTH_CONNECT.equals(permissions[i])
                    && results[i] != PackageManager.PERMISSION_GRANTED) {
                btGranted = false;
            }
        }
        if (btGranted) startServiceNow();
        else statusText.setText("Bez zgody na Bluetooth nie da się czytać danych");
    }

    private void startServiceNow() {
        if (devices.isEmpty()) {
            loadDevices();
            if (devices.isEmpty()) return;
        }
        int index = deviceSpinner.getSelectedItemPosition();
        if (index < 0 || index >= devices.size()) return;

        BluetoothDevice device = devices.get(index);
        getPreferences().edit().putString(PREF_MAC, device.getAddress()).apply();

        String name;
        try {
            name = device.getName() != null ? device.getName() : device.getAddress();
        } catch (SecurityException e) {
            name = device.getAddress();
        }

        Intent intent = new Intent(this, ReaderService.class)
                .setAction(ReaderService.ACTION_START)
                .putExtra(ReaderService.EXTRA_MAC, device.getAddress())
                .putExtra(ReaderService.EXTRA_NAME, name);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);

        powerGauge.resetPeak();
        statusText.setText("Uruchamiam odczyt w tle…");
    }

    private void stopServiceNow() {
        Intent intent = new Intent(this, ReaderService.class)
                .setAction(ReaderService.ACTION_STOP);
        startService(intent);
        CarState.serviceRunning = false;
        CarState.connected = false;
        CarState.reset();
        statusText.setText("Zatrzymano odczyt");
    }

    // ------------------------------------------------------------------ zakladki

    private void showTab(int tab) {
        currentTab = tab;
        panelStart.setVisibility(tab == TAB_START ? View.VISIBLE : View.GONE);
        panelCells.setVisibility(tab == TAB_CELLS ? View.VISIBLE : View.GONE);
        panelTrip.setVisibility(tab == TAB_TRIP ? View.VISIBLE : View.GONE);
        panelOther.setVisibility(tab == TAB_OTHER ? View.VISIBLE : View.GONE);

        styleTab(tabStart, tab == TAB_START);
        styleTab(tabCells, tab == TAB_CELLS);
        styleTab(tabTrip, tab == TAB_TRIP);
        styleTab(tabOther, tab == TAB_OTHER);

        getPreferences().edit().putInt(PREF_TAB, tab).apply();

        if (tab == TAB_CELLS) rebuildCellGrid();
    }

    private void styleTab(TextView tab, boolean active) {
        tab.setSelected(active);
        tab.setTextColor(getColorCompat(active ? R.color.accent : R.color.dim));
        tab.setTypeface(Typeface.create(Typeface.DEFAULT,
                active ? Typeface.BOLD : Typeface.NORMAL));
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        rebuildCellGrid();      // w poziomie wiecej kolumn
    }

    /** Siatka wszystkich ogniw - 4 kolumny w pionie, 8 w poziomie. */
    private void rebuildCellGrid() {
        int[] cells = CarState.cells;
        cellGrid.removeAllViews();
        if (cells.length == 0) return;

        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int columns = landscape ? 8 : 4;
        int rows = (int) Math.ceil(cells.length / (double) columns);
        int min = CarState.cellMin;
        int max = CarState.cellMax;

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            for (int c = 0; c < columns; c++) {
                int index = r * columns + c;
                if (index >= cells.length) {
                    View filler = new View(this);
                    filler.setLayoutParams(new LinearLayout.LayoutParams(0, dp(1), 1f));
                    row.addView(filler);
                    continue;
                }
                row.addView(buildCellView(index, cells[index], min, max));
            }
            cellGrid.addView(row);
        }
    }

    private View buildCellView(int index, int mv, int min, int max) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        cell.setLayoutParams(params);

        boolean isMin = index == CarState.cellMinIndex;
        boolean isMax = index == CarState.cellMaxIndex;

        TextView number = new TextView(this);
        number.setText("m" + Integer.toString(index / 4 + 1));   // moduł jak na laptopie
        number.setTextSize(10f);
        number.setGravity(Gravity.CENTER);
        number.setTextColor(getColorCompat(R.color.muted));

        TextView value = new TextView(this);
        value.setText(String.valueOf(mv));
        value.setTextSize(12f);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(getColorCompat(
                isMin ? R.color.warn : isMax ? R.color.accent : R.color.txt));

        // pasek: skala bezwzgledna 3800..4200 mV - paski sa porownywalne miedzy odczytami
        float fraction = (mv - 3800f) / 400f;
        fraction = Math.max(0.02f, Math.min(1f, fraction));
        View bar = new View(this);
        bar.setBackgroundColor(getColorCompat(isMin ? R.color.warn : R.color.accent));
        View barBg = new View(this);
        barBg.setBackgroundColor(getColorCompat(R.color.cardStroke));

        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        barParams.setMargins(0, dp(3), 0, 0);
        barRow.setLayoutParams(barParams);

        float filled = fraction;
        barRow.addView(bar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, filled));
        barRow.addView(barBg, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - filled));

        cell.addView(number);
        cell.addView(value);
        cell.addView(barRow);
        return cell;
    }

    /** Naglowek zakladki OGNIWA + przebudowa siatki, gdy zmienily sie dane. */
    private void refreshCellsTab() {
        int[] cells = CarState.cells;
        if (cells.length == 0) return;

        cellTabMin.setText(CarState.cellMin + " mV");
        cellTabMax.setText(CarState.cellMax + " mV");
        int delta = CarState.cellMax - CarState.cellMin;
        cellTabDelta.setText(delta + " mV");
        cellTabDelta.setTextColor(deltaColor(delta));


        cellSummary.setText(String.format(Locale.US,
                "Ogniw %d · najsłabsze: m%d (%d mV) · najmocniejsze: m%d (%d mV) · Δ %d mV"
                        + "\npasek: 3800–4200 mV",
                cells.length,
                CarState.cellMinIndex / 4 + 1, CarState.cellMin,
                CarState.cellMaxIndex / 4 + 1, CarState.cellMax, delta));

        // siatke przebudowujemy tylko, gdy przyszly nowe dane z ogniw (co ~30 s),
        // a nie co sekunde - 88 widokow na watku UI to glowna przyczyna lagow
        int hash = java.util.Arrays.hashCode(cells);
        if (hash != lastCellsHash) {
            lastCellsHash = hash;
            if (currentTab == TAB_CELLS) rebuildCellGrid();
        }
    }

    // ------------------------------------------------------------------ odswiezanie

    private void refreshDashboard() {
        boolean running = CarState.serviceRunning;
        connectButton.setText(running ? "Rozłącz" : "Połącz");
        statusText.setText(CarState.status);

        powerGauge.setPower(CarState.powerKw);
        powerGauge.setPeak(CarState.peakKw);
        powerSpark.setValues(CarState.powerSnapshot());

        socValue.setText(fmt(CarState.soc, 1, "%"));
        speedValue.setText(fmt(CarState.speed, 0, " km/h"));
        heaterValue.setText(fmt(CarState.heaterKw, 2, " kW"));
        peakText.setText(CarState.peakKw > 0.1f
                ? String.format(Locale.US, "szczyt %.0f kW  ·  rekuperacja −25 / pobór 50 kW", CarState.peakKw)
                : "rekuperacja −25 kW  ·  pobór 50 kW");

        if (CarState.cellCount > 0) {
            cellMinValue.setText(CarState.cellMin + " mV");
            cellMaxValue.setText(CarState.cellMax + " mV");
            int delta = CarState.cellMax - CarState.cellMin;
            cellDeltaValue.setText(delta + " mV");
            cellDeltaValue.setTextColor(deltaColor(delta));
            packText.setText("Pakiet " + fmt(CarState.volts, 1, " V") + " / "
                    + fmt(CarState.amps, 1, " A") + " · ogniw " + CarState.cellCount);
        } else {
            cellMinValue.setText("–");
            cellMaxValue.setText("–");
            cellDeltaValue.setText("–");
            cellDeltaValue.setTextColor(getColorCompat(R.color.muted));
            packText.setText("Pakiet " + fmt(CarState.volts, 1, " V") + " / "
                    + fmt(CarState.amps, 1, " A"));
        }

        if (CarState.tempCount > 0) {
            tempMinValue.setText(CarState.tempMin + "°");
            tempMaxValue.setText(CarState.tempMax + "°");
            int delta = CarState.tempMax - CarState.tempMin;
            tempDeltaValue.setText(delta + " K");
            tempDeltaValue.setTextColor(deltaColor(delta * 3));   // 1 K ≈ 30 mV wg czułości ogniw
            tempsText.setText("Czujników " + CarState.tempCount + " · temperatury baterii");
        } else {
            tempMinValue.setText("–");
            tempMaxValue.setText("–");
            tempDeltaValue.setText("–");
            tempDeltaValue.setTextColor(getColorCompat(R.color.muted));
            tempsText.setText("Czujniki –");
        }

        refreshTrip();
        refreshOthers();
        refreshCellsTab();

        long now = System.currentTimeMillis();
        if (now - lastHistoryAt > HISTORY_REFRESH_MS) {
            lastHistoryAt = now;
            refreshHistory();
        }
    }

    private void refreshTrip() {
        boolean active = CarState.tripActive;
        tripDistance.setText(active ? String.format(Locale.US, "%.1f km", CarState.tripDistanceKm) : "–");
        tripEnergy.setText(active ? String.format(Locale.US, "%.2f kWh", CarState.tripNetKwh) : "–");
        tripPer100.setText(active && !Float.isNaN(CarState.tripPer100)
                ? String.format(Locale.US, "%.1f", CarState.tripPer100) : "–");

        if (active) {
            long seconds = CarState.tripDurationMs / 1000;
            tripTime.setText(String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60));
            float hours = CarState.tripDurationMs / 3600000f;
            float avg = hours > 0.01f ? CarState.tripDistanceKm / hours : Float.NaN;
            tripAvg.setText(Float.isNaN(avg) ? "–" : String.format(Locale.US, "%.0f", avg));
            tripRegen.setText(String.format(Locale.US, "%.2f kWh", CarState.tripRegenKwh));
            tripFoot.setText(String.format(Locale.US,
                    "Szczyt %.0f kW · grzanie %.2f kWh · SoC %.0f→%.0f%%",
                    CarState.peakKw, CarState.tripHeaterKwh,
                    Float.isNaN(CarState.tripStartSoc) ? 0 : CarState.tripStartSoc,
                    Float.isNaN(CarState.tripEndSoc) ? 0 : CarState.tripEndSoc));
        } else {
            tripTime.setText("–");
            tripAvg.setText("–");
            tripRegen.setText("–");
            tripFoot.setText("Odcinek startuje automatycznie, gdy auto ruszy");
        }
    }

    private void refreshOthers() {
        rangeValue.setText(CarState.rangeKm >= 0 ? CarState.rangeKm + " km" : "–");
        odoValue.setText(CarState.odoKm >= 0 ? CarState.odoKm + " km" : "–");
        coolantValue.setText(Float.isNaN(CarState.flowC) ? "–"
                : String.format(Locale.US, "%.0f → %.0f °C (Δ %.1f K)",
                        CarState.flowC, CarState.retC, CarState.flowC - CarState.retC));
        acValue.setText(Float.isNaN(CarState.acKw) ? "–"
                : String.format(Locale.US, "%.2f kW", CarState.acKw));
        logValue.setText(String.valueOf(CarState.samples));
        cellsText.setText(CarState.cellCount > 0
                ? CarState.cellMin + "–" + CarState.cellMax + " mV, " + CarState.cellCount + " ogniw"
                : "–");
    }

    /** Historia jazd z bazy - przebudowywana co 5 s. */
    private void refreshHistory() {
        List<SampleLog.TripRow> trips = new SampleLog(this).recentTrips(6);
        historyList.removeAllViews();

        if (trips.isEmpty()) {
            historyEmpty.setVisibility(View.VISIBLE);
            return;
        }
        historyEmpty.setVisibility(View.GONE);

        long now = System.currentTimeMillis();
        for (SampleLog.TripRow trip : trips) {
            historyList.addView(buildHistoryRow(trip, now));
        }
    }

    private View buildHistoryRow(SampleLog.TripRow trip, long now) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.setLayoutParams(leftParams);

        TextView when = new TextView(this);
        when.setText(dayLabel(trip.startedAt * 1000, now) + "  "
                + timeFormat.format(new Date(trip.startedAt * 1000))
                + "  ·  " + String.format(Locale.US, "%.1f km", trip.distanceKm));
        when.setTextColor(getColorCompat(R.color.txt));
        when.setTextSize(13f);
        when.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(when);

        TextView details = new TextView(this);
        String socPart = Float.isNaN(trip.startSoc) || Float.isNaN(trip.endSoc) ? ""
                : String.format(Locale.US, " · SoC %.0f→%.0f%%", trip.startSoc, trip.endSoc);
        details.setText(String.format(Locale.US, "%.2f kWh netto · rekuperacja %.2f · %d min",
                trip.netKwh(), trip.regenKwh, trip.durationMs() / 60000) + socPart);
        details.setTextColor(getColorCompat(R.color.muted));
        details.setTextSize(11f);
        details.setPadding(0, dp(2), 0, 0);
        left.addView(details);

        TextView per100 = new TextView(this);
        float per100Value = trip.per100Km();
        per100.setText(Float.isNaN(per100Value) ? "–"
                : String.format(Locale.US, "%.1f", per100Value));
        per100.setTextColor(getColorCompat(R.color.accent));
        per100.setTextSize(17f);
        per100.setTypeface(Typeface.DEFAULT_BOLD);
        per100.setGravity(Gravity.END);

        TextView unit = new TextView(this);
        unit.setText("kWh/100");
        unit.setTextColor(getColorCompat(R.color.dim));
        unit.setTextSize(9f);
        unit.setGravity(Gravity.END);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.addView(per100);
        right.addView(unit);

        row.addView(left);
        row.addView(right);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(getColorCompat(R.color.cardStroke));
        LinearLayout.LayoutParams dividerParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        wrapper.addView(divider, dividerParams);

        return wrapper;
    }

    private String dayLabel(long whenMs, long nowMs) {
        SimpleDateFormat dayOfYear = new SimpleDateFormat("yyyyDDD", Locale.US);
        String when = dayOfYear.format(new Date(whenMs));
        String today = dayOfYear.format(new Date(nowMs));
        String yesterday = dayOfYear.format(new Date(nowMs - 86400000L));
        if (when.equals(today)) return "dziś";
        if (when.equals(yesterday)) return "wczoraj";
        return dayFormat.format(new Date(whenMs));
    }

    // ------------------------------------------------------------------ pomocnicze

    private int deltaColor(int deltaMilliVolts) {
        if (deltaMilliVolts <= 30) return getColorCompat(R.color.accent);
        if (deltaMilliVolts <= 60) return getColorCompat(R.color.warn);
        return getColorCompat(R.color.danger);
    }

    @SuppressWarnings("deprecation")
    private int getColorCompat(int id) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? getResources().getColor(id, getTheme())
                : getResources().getColor(id);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String fmt(float value, int digits, String unit) {
        if (Float.isNaN(value)) return "–";
        String text = String.format(Locale.US, "%." + digits + "f", value);
        return unit.isEmpty() ? text : text + unit;
    }

    private void exportCsv() {
        try {
            File file = new SampleLog(this).exportCsv(this);
            csvPathText.setText("CSV: " + file.getAbsolutePath());
            Toast.makeText(this, "Zapisano " + file.getName(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Błąd eksportu: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Od Androida 15 aplikacje docelowo rysuja pod paskami systemowymi (edge-to-edge).
     * Dodajemy wciecia jako padding, zeby tresc nie wchodzila pod zegar ani pod pasek nawigacji.
     */
    private void applySystemBarInsets() {
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
    }

    /** Ostatni zapisany blad (jesli byl) - do przepisania lub zrzutu ekranu. */
    private String crashSection() {
        String last = CrashLog.last(this);
        if (last == null) return "";
        return "\n\n— OSTATNI BŁĄD (zrób zrzut i wyślij) —\n" + last;
    }

    /** Jednorazowa informacja przy pierwszym uruchomieniu. */
    private void showFirstRunNotice() {
        if (getPreferences().getBoolean("notice_shown", false)) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Zanim zaczniesz")
                .setMessage("i-MiEV Monitor czyta dane z adaptera ELM327 po Bluetooth.\n\n"
                        + "• Aplikacja nie ma dostępu do internetu — nic nie wysyła ani nie zbiera.\n"
                        + "• Bluetooth jest potrzebny wyłącznie do połączenia z adapterem.\n"
                        + "• To nieoficjalna aplikacja, niepowiązana z Mitsubishi Motors.\n"
                        + "• Odczyty są informacyjne — nie zastępują wskaźników auta.")
                .setPositiveButton("Rozumiem", (dialog, which) ->
                        getPreferences().edit().putBoolean("notice_shown", true).apply())
                .setCancelable(false)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    /** Odcisk SHA-256 certyfikatu podpisujacego - do porownania z opublikowanym. */
    private String certificateFingerprint() {
        try {
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = info.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo info = getPackageManager().getPackageInfo(
                        getPackageName(), PackageManager.GET_SIGNATURES);
                signatures = info.signatures;
            }
            if (signatures == null || signatures.length == 0) return "brak";
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException
                 | NullPointerException e) {
            return "nie udało się odczytać";
        }
    }

    private SharedPreferences getPreferences() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
