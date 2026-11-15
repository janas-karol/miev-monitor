package io.github.janaskarol.miev;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Lokalny zapis probek (SQLite) + eksport CSV. */
final class SampleLog extends SQLiteOpenHelper {

    private static final String DB_NAME = "imiev.db";
    private static final int DB_VERSION = 4;

    /** Wiersz historii jazd. */
    static final class TripRow {
        long startedAt;
        long endedAt;
        float startSoc = Float.NaN;
        float endSoc = Float.NaN;
        float distanceKm;
        float dischargeKwh;
        float regenKwh;
        float heaterKwh;
        float maxSpeed;

        float netKwh() {
            return dischargeKwh - regenKwh;
        }

        float per100Km() {
            return distanceKm > 0.05f ? netKwh() / distanceKm * 100f : Float.NaN;
        }

        long durationMs() {
            return Math.max(0, endedAt - startedAt);
        }
    }

    SampleLog(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sample ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER NOT NULL,"        // epoch [s] z zegara telefonu
                + "soc REAL, v REAL, a REAL, kw REAL, kph REAL,"
                + "heater_a REAL, ac_a REAL, flow_c REAL, ret_c REAL,"
                + "moving INTEGER,"
                // ogniwa - potrzebne do testow pod obciazeniem (delta rosnie przy duzym pradzie)
                + "cell_min INTEGER, cell_max INTEGER, cell_delta INTEGER,"
                + "cell_count INTEGER, temp_max INTEGER)");
        db.execSQL("CREATE INDEX idx_sample_ts ON sample(ts)");
        createTripTable(db);
    }

    private static void createTripTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS trip ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "started_at INTEGER NOT NULL,"
                + "ended_at INTEGER NOT NULL,"
                + "start_soc REAL, end_soc REAL,"
                + "distance_km REAL, discharge_kwh REAL, regen_kwh REAL,"
                + "heater_kwh REAL, max_speed REAL,"
                + "odo_start INTEGER, odo_end INTEGER,"
                // 0 = jazda w trakcie (wpis powstal przy starcie odcinka)
                + "closed INTEGER DEFAULT 1)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trip_start ON trip(started_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createTripTable(db);
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE trip ADD COLUMN closed INTEGER DEFAULT 1");
        }
        if (oldVersion < 3) {
            // stare probki zostaja; nowe kolumny sa puste (NULL) dla historii
            db.execSQL("ALTER TABLE sample ADD COLUMN cell_min INTEGER");
            db.execSQL("ALTER TABLE sample ADD COLUMN cell_max INTEGER");
            db.execSQL("ALTER TABLE sample ADD COLUMN cell_delta INTEGER");
            db.execSQL("ALTER TABLE sample ADD COLUMN cell_count INTEGER");
            db.execSQL("ALTER TABLE sample ADD COLUMN temp_max INTEGER");
        }
    }

    /**
     * Odcinek trafia do historii juz przy starcie (closed = 0) i jest aktualizowany
     * co kilka sekund. Dzieki temu zabicie aplikacji albo zerwane polaczenie nie
     * kasuje jazdy - wpis zostaje i domyka sie przy nastepnym uruchomieniu.
     */
    int startTrip(TripTracker.Trip t) {
        ContentValues v = tripValues(t, 0);
        long id = getWritableDatabase().insert("trip", null, v);
        return (int) id;
    }

    void updateTrip(int id, TripTracker.Trip t) {
        if (id < 0) return;
        getWritableDatabase().update("trip", tripValues(t, 0), "id = ?",
                new String[]{String.valueOf(id)});
    }

    void closeTrip(int id, TripTracker.Trip t) {
        if (id < 0) return;
        getWritableDatabase().update("trip", tripValues(t, 1), "id = ?",
                new String[]{String.valueOf(id)});
    }

    void discardTrip(int id) {
        if (id < 0) return;
        getWritableDatabase().delete("trip", "id = ?", new String[]{String.valueOf(id)});
    }

    private static ContentValues tripValues(TripTracker.Trip t, int closed) {
        ContentValues v = new ContentValues();
        v.put("started_at", t.startMs / 1000);
        v.put("ended_at", t.endMs / 1000);
        putNullable(v, "start_soc", t.startSoc);
        putNullable(v, "end_soc", t.endSoc);
        v.put("distance_km", t.distanceKm);
        v.put("discharge_kwh", t.dischargeKwh);
        v.put("regen_kwh", t.regenKwh);
        v.put("heater_kwh", t.heaterKwh);
        v.put("max_speed", t.maxSpeed);
        v.put("odo_start", t.odoStart);
        v.put("odo_end", t.odoEnd);
        v.put("closed", closed);
        return v;
    }

    /**
     * Domyka odcinki zostawione jako "w trakcie" - np. gdy system zabil aplikacje
     * albo uzytkownik wyszedl z auta bez rozlaczania. Dane sa takie, jakie zdazyl
     * zapisac ostatni postep (co 5 s), wiec jazda nie przepada.
     */
    int finalizeOpenTrips() {
        return getWritableDatabase().update("trip", closedValues(),
                "closed IS NULL OR closed = 0", null);
    }

    private static ContentValues closedValues() {
        ContentValues v = new ContentValues();
        v.put("closed", 1);
        return v;
    }

    /** Ostatnie jazdy, najnowsze pierwsze. */
    List<TripRow> recentTrips(int limit) {
        List<TripRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT started_at, ended_at, start_soc, end_soc, distance_km,"
                        + " discharge_kwh, regen_kwh, heater_kwh, max_speed"
                        + " FROM trip WHERE closed = 1 ORDER BY started_at DESC LIMIT "
                        + Math.max(1, limit), null);
        try {
            while (c.moveToNext()) {
                TripRow r = new TripRow();
                r.startedAt = c.getLong(0);
                r.endedAt = c.getLong(1);
                r.startSoc = c.isNull(2) ? Float.NaN : c.getFloat(2);
                r.endSoc = c.isNull(3) ? Float.NaN : c.getFloat(3);
                r.distanceKm = c.getFloat(4);
                r.dischargeKwh = c.getFloat(5);
                r.regenKwh = c.getFloat(6);
                r.heaterKwh = c.getFloat(7);
                r.maxSpeed = c.getFloat(8);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    void add(Decoder d, float ts) {
        ContentValues v = new ContentValues();
        v.put("ts", (long) ts);
        putNullable(v, "soc", d.soc);
        putNullable(v, "v", d.volts);
        putNullable(v, "a", d.amps);
        putNullable(v, "kw", d.powerKw());
        putNullable(v, "kph", d.speed);
        putNullable(v, "heater_a", d.heaterA);
        putNullable(v, "ac_a", d.acA);
        putNullable(v, "flow_c", d.flowC);
        putNullable(v, "ret_c", d.retC);
        v.put("moving", d.isMoving() ? 1 : 0);
        if (d.cellCount > 0) {
            v.put("cell_min", d.cellMin);
            v.put("cell_max", d.cellMax);
            v.put("cell_delta", d.cellMax - d.cellMin);
            v.put("cell_count", d.cellCount);
        }
        if (d.tempCount > 0) v.put("temp_max", d.tempMax);
        getWritableDatabase().insert("sample", null, v);
    }

    private static void putNullable(ContentValues v, String key, float value) {
        if (Float.isNaN(value)) v.putNull(key);
        else v.put(key, value);
    }

    long count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sample", null);
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    void clear() {
        getWritableDatabase().delete("sample", null, null);
    }

    /** Zapisuje CSV do katalogu aplikacji i zwraca plik. */
    File exportCsv(Context context) throws IOException {
        File dir = new File(context.getExternalFilesDir(null), "csv");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("nie moge utworzyc " + dir);

        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        File out = new File(dir, "imiev-" + stamp + ".csv");

        Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, soc, v, a, kw, kph, heater_a, ac_a, flow_c, ret_c, moving,"
                        + " cell_min, cell_max, cell_delta, cell_count, temp_max "
                        + "FROM sample ORDER BY ts", null);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8))) {
            w.write("ts,iso_time,soc,v,a,kw,kph,heater_a,ac_a,flow_c,ret_c,moving,"
                    + "cell_min,cell_max,cell_delta,cell_count,temp_max\n");
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            while (c.moveToNext()) {
                StringBuilder line = new StringBuilder(96);
                long ts = c.getLong(0);
                line.append(ts).append(',').append(iso.format(new Date(ts * 1000)));
                for (int col = 1; col <= 15; col++) {
                    line.append(',');
                    if (!c.isNull(col)) line.append(c.getDouble(col));
                }
                line.append('\n');
                w.write(line.toString());
            }
        } finally {
            c.close();
        }
        return out;
    }
}
