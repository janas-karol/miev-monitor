package io.github.janaskarol.miev;

import java.util.ArrayList;
import java.util.List;

/**
 * Dekodowanie ramek i-MiEV — te same wzory co w wersji Python (dashboard)
 * i w mostku ESP32, zeby liczby byly porownywalne.
 */
final class Decoder {

    static final class Frame {
        final int id;
        final byte[] data;

        Frame(int id, byte[] data) {
            this.id = id;
            this.data = data;
        }

        int u8(int i) {
            return i < data.length ? (data[i] & 0xFF) : 0;
        }
    }

    // --- stan ---
    float soc = Float.NaN;
    float volts = Float.NaN;
    float amps = Float.NaN;
    float speed = Float.NaN;
    float heaterA = Float.NaN;
    float acA = Float.NaN;
    float flowC = Float.NaN;
    float retC = Float.NaN;
    int rangeKm = -1;
    int odoKm = -1;
    boolean havePack = false;

    int cellMin = 0, cellMax = 0, cellCount = 0;
    int cellMinIndex = -1, cellMaxIndex = -1;
    int[] cells = new int[0];          // wszystkie ogniwa [mV], kolejnosc jak w ramce
    int tempMin = 0, tempMax = 0, tempCount = 0;

    float powerKw() {
        if (Float.isNaN(volts) || Float.isNaN(amps)) return Float.NaN;
        return volts * amps / 1000f;
    }

    float heaterKw() {
        if (Float.isNaN(heaterA) || Float.isNaN(volts)) return Float.NaN;
        return heaterA * volts / 1000f;
    }

    float acKw() {
        if (Float.isNaN(acA) || Float.isNaN(volts)) return Float.NaN;
        return acA * volts / 1000f;
    }

    boolean isMoving() {
        float kw = powerKw();
        return (!Float.isNaN(speed) && speed > 1f) || (!Float.isNaN(kw) && Math.abs(kw) > 1f);
    }

    /** Zamienia surowy tekst (ATH1, ATS0) na liste ramek. */
    static List<Frame> parse(String raw) {
        List<Frame> frames = new ArrayList<>();
        for (String line : raw.split("[\\r\\n]+")) {
            String s = line.trim().replace(" ", "");
            if (s.length() < 5) continue;
            // linie kontrolne adaptera pomijamy (np. "SEARCHING...", "BUS INIT")
            if (!isHex(s)) continue;
            if (s.length() < 3 + 2) continue;
            try {
                int id = Integer.parseInt(s.substring(0, 3), 16);
                int bytes = (s.length() - 3) / 2;
                byte[] data = new byte[bytes];
                for (int i = 0; i < bytes; i++) {
                    data[i] = (byte) Integer.parseInt(s.substring(3 + i * 2, 5 + i * 2), 16);
                }
                frames.add(new Frame(id, data));
            } catch (NumberFormatException ignored) {
            }
        }
        return frames;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    /** Dekoduje pojedyncza porcje nasluchu (broadcast). */
    void decodeBurst(String raw) {
        for (Frame f : parse(raw)) {
            switch (f.id) {
                case 0x373: {
                    float a = (((f.u8(2) << 8) | f.u8(3)) - 32768) * 0.01f;
                    float v = ((f.u8(4) << 8) | f.u8(5)) * 0.1f;
                    if (v >= 200f && v <= 460f && a >= -400f && a <= 400f) {
                        volts = v;
                        amps = a;
                        havePack = true;
                    }
                    break;
                }
                case 0x374: {
                    int b = f.u8(1);
                    if (b > 10) soc = (b - 10) * 0.5f;
                    break;
                }
                case 0x215: {
                    float s = ((f.u8(0) << 8) | f.u8(1)) * 0.0078125f;
                    if (s >= 0 && s <= 200) speed = s;
                    break;
                }
                case 0x346: {
                    int r = f.u8(7);
                    if (r < 255) rangeKm = r;
                    break;
                }
                case 0x412: {
                    int o = (f.u8(2) << 16) | (f.u8(3) << 8) | f.u8(4);
                    if (o > 0) odoKm = o;
                    break;
                }
                case 0x384: {
                    if (f.data.length < 7) break;
                    float h = f.u8(4) * 0.1f;
                    float ac = ((f.u8(0) << 8) | f.u8(1)) * 0.001f;
                    float ret = f.u8(5) * 0.6f - 40f;
                    float flow = f.u8(6) * 0.6f - 40f;
                    if (h >= 0 && h <= 30 && ac >= 0 && ac <= 30
                            && flow > -45 && flow < 130 && ret > -45 && ret < 130) {
                        heaterA = h;
                        acA = ac;
                        flowC = flow;
                        retC = ret;
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    /** Odpowiedz wielo-ramkowa ISO-TP z ECU 0x762 (odpowiedz przychodzi z 0x762). */
    static byte[] assembleIsotp(List<Frame> frames) {
        int total = -1;
        byte[] out = new byte[512];
        int filled = 0;
        int expectedSeq = 1;

        for (Frame f : frames) {
            if (f.data.length == 0) continue;
            int type = (f.u8(0) & 0xF0) >> 4;
            if (type == 0x1) {                       // pierwsza ramka
                total = ((f.u8(0) & 0x0F) << 8) | f.u8(1);
                int n = Math.min(f.data.length - 2, total);
                System.arraycopy(f.data, 2, out, 0, n);
                filled = n;
                expectedSeq = 1;
            } else if (type == 0x2) {                // kolejna ramka
                int seq = f.u8(0) & 0x0F;
                if (seq != (expectedSeq & 0x0F)) continue;
                int n = Math.min(f.data.length - 1, Math.max(0, total - filled));
                if (n <= 0) continue;
                System.arraycopy(f.data, 1, out, filled, n);
                filled += n;
                expectedSeq++;
            } else if (type == 0x0) {                // pojedyncza ramka
                int len = f.u8(0) & 0x0F;
                int n = Math.min(len, f.data.length - 1);
                byte[] single = new byte[n];
                System.arraycopy(f.data, 1, single, 0, n);
                return single;
            }
            if (total > 0 && filled >= total) break;
        }
        if (total <= 0 || filled < total) return null;
        byte[] result = new byte[total];
        System.arraycopy(out, 0, result, 0, total);
        return result;
    }

    /** 2102 -> napiecia ogniw [mV]; zwraca liczbe ogniw. */
    int decodeCells(String raw) {
        List<Frame> frames = new ArrayList<>();
        for (Frame f : parse(raw)) {
            if (f.id == 0x762 || f.id == 0x7E8) frames.add(f);
        }
        if (frames.isEmpty()) frames = parse(raw);
        byte[] payload = assembleIsotp(frames);
        if (payload == null || payload.length < 4) return 0;

        int min = Integer.MAX_VALUE, max = 0, count = 0;
        int minIdx = -1, maxIdx = -1;
        int[] buffer = new int[256];
        for (int i = 2; i + 1 < payload.length; i += 2) {
            int mv = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
            if (mv < 2000 || mv == 0xFFFF || mv == 0xFFFE) continue;
            if (count < buffer.length) buffer[count] = mv;
            if (mv < min) { min = mv; minIdx = count; }
            if (mv > max) { max = mv; maxIdx = count; }
            count++;
        }
        if (count == 0) return 0;
        cellMin = min;
        cellMax = max;
        cellCount = Math.min(count, buffer.length);
        cellMinIndex = minIdx;
        cellMaxIndex = maxIdx;
        int[] copy = new int[cellCount];
        System.arraycopy(buffer, 0, copy, 0, cellCount);
        cells = copy;
        return count;
    }

    /** 2103 -> temperatury [C] (bajt - 50, 0xFE = brak czujnika). */
    int decodeTemps(String raw) {
        List<Frame> frames = new ArrayList<>();
        for (Frame f : parse(raw)) {
            if (f.id == 0x762 || f.id == 0x7E8) frames.add(f);
        }
        if (frames.isEmpty()) frames = parse(raw);
        byte[] payload = assembleIsotp(frames);
        if (payload == null || payload.length < 4) return 0;

        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, count = 0;
        for (int i = 2; i < payload.length; i++) {
            int b = payload[i] & 0xFF;
            if (b >= 0xFE) continue;
            int t = b - 50;
            min = Math.min(min, t);
            max = Math.max(max, t);
            count++;
        }
        if (count == 0) return 0;
        tempMin = min;
        tempMax = max;
        tempCount = count;
        return count;
    }
}
