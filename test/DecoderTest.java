package io.github.janaskarol.miev;

/**
 * Testy dekodowania bez Androida - Decoder nie zalezy od SDK, wiec wystarczy JDK.
 *
 * Uruchomienie (po skompilowaniu razem ze src):
 *   javac -d /tmp/atest src/main/java/pl/imiev/app/Decoder.java test/DecoderTest.java
 *   java -cp /tmp/atest io.github.janaskarol.miev.DecoderTest
 */
public final class DecoderTest {

    private static int failures = 0;

    private static void check(boolean ok, String what) {
        System.out.printf("%-56s %s%n", what, ok ? "OK" : "BLAD");
        if (!ok) failures++;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 0.01;
    }

    /** Payload 2102: nagłówek 61 02 i `n` ogniw po `first` mV (kolejne +1 mV). */
    private static byte[] cellsPayload(int n, int first) {
        byte[] p = new byte[2 + n * 2];
        p[0] = 0x61;
        p[1] = 0x02;
        for (int i = 0; i < n; i++) {
            int mv = first + i;
            p[2 + i * 2] = (byte) (mv >> 8);
            p[3 + i * 2] = (byte) mv;
        }
        return p;
    }

    /** Buduje tekst odpowiedzi ISO-TP (ATH1, ATS0): pierwsza ramka + kolejne, po 8 bajtów. */
    private static String isotp(int id, byte[] payload) {
        StringBuilder sb = new StringBuilder();
        int total = payload.length;
        sb.append(String.format("%03X", id)).append("10").append(String.format("%02X", total));
        for (int i = 0; i < 6 && i < total; i++) sb.append(String.format("%02X", payload[i]));
        int idx = Math.min(6, total), seq = 1;
        while (idx < total) {
            sb.append("\r").append(String.format("%03X", id)).append("2")
                    .append(String.format("%X", seq & 0x0F));
            int n = 0;
            while (n < 7 && idx < total) {
                sb.append(String.format("%02X", payload[idx++]));
                n++;
            }
            while (n < 7) {                       // dopełnienie do 8 bajtów jak w CAN
                sb.append("00");
                n++;
            }
            seq++;
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println("=== dekoder i-MiEV (Java) ===");

        // 0x373: prad -8.00 A (0x7CE0 - 32768), napiecie 340.2 V (0x0D4A)
        {
            Decoder d = new Decoder();
            d.decodeBurst("373" + "00" + "00" + "7C" + "E0" + "0D" + "4A" + "00" + "00");
            check(near(d.amps, -8.0) && near(d.volts, 340.2), "0x373 pobor -8 A przy 340.2 V");
            check(near(d.powerKw(), -2.72), "0x373 moc -2.72 kW");
        }

        // 0x373: rekuperacja +3.52 A (0x8160)
        {
            Decoder d = new Decoder();
            d.decodeBurst("37300008160" + "0D4A0000");
            check(near(d.amps, 3.52), "0x373 rekuperacja +3.52 A");
        }

        // Smieci (napiecie 0) nie moga nadpisac stanu
        {
            Decoder d = new Decoder();
            d.decodeBurst("37300008000" + "00000000");
            check(!d.havePack, "0x373 smieci odrzucone");
        }

        // 0x374: SoC 57% -> (0x7C - 10) * 0.5
        {
            Decoder d = new Decoder();
            d.decodeBurst("374" + "00" + "7C");
            check(near(d.soc, 57.0), "0x374 SoC 57%");
        }

        // 0x215: 80 km/h -> 0x2800 * 0.0078125
        {
            Decoder d = new Decoder();
            d.decodeBurst("215" + "28" + "00");
            check(near(d.speed, 80.0), "0x215 predkosc 80 km/h");
        }

        // 0x346: zasieg 42 km (0x2A) w bajcie 7
        {
            Decoder d = new Decoder();
            d.decodeBurst("346" + "000000000000002A");
            check(d.rangeKm == 42, "0x346 zasieg 42 km");
            d.decodeBurst("346" + "00000000000000FF");
            check(d.rangeKm == 42, "0x346 0xFF nie nadpisuje zasiegu");
        }

        // 0x412: licznik 51252 km (0x00C834)
        {
            Decoder d = new Decoder();
            d.decodeBurst("412" + "00" + "00" + "00" + "C8" + "34" + "00" + "00" + "00");
            check(d.odoKm == 51252, "0x412 licznik 51252 km");
        }

        // 0x384: prawdziwa ramka - grzalka 8.3 A, plynu 99.8 -> 51.2 C
        {
            Decoder d = new Decoder();
            d.volts = 340.0f;
            d.decodeBurst("384" + "00" + "00" + "00" + "01" + "53" + "98" + "E9" + "00");
            check(near(d.heaterA, 8.3) && near(d.acA, 0.0)
                    && near(d.flowC, 99.8) && near(d.retC, 51.2),
                    "0x384 grzalka 8.3 A, plynu 99.8 -> 51.2 C");
            check(near(d.heaterKw(), 2.822), "0x384 moc grzalki 2.82 kW");
        }

        // 0x384: jazda #13 z bazy - grzalka 15.5 A, plynu 42.2 -> 37.4 C
        {
            Decoder d = new Decoder();
            d.decodeBurst("384" + "00" + "00" + "00" + "02" + "9B" + "81" + "89" + "00");
            check(near(d.heaterA, 15.5) && near(d.flowC, 42.2) && near(d.retC, 37.4),
                    "0x384 grzalka 15.5 A, plynu 42.2 -> 37.4 C");
        }

        // 2102: pelna odpowiedz - 88 ogniw 3700..3787 mV, ISO-TP w 26 ramkach
        {
            Decoder d = new Decoder();
            int cells = d.decodeCells(isotp(0x762, cellsPayload(88, 3700)));
            check(cells == 88 && d.cellCount == 88, "2102 pelna odpowiedz: 88 ogniw");
            check(d.cellMin == 3700 && d.cellMax == 3787, "2102 min 3700, max 3787 mV");
            check(d.cellsRejected.isEmpty(), "2102 poprawna odpowiedz bez noty o odrzuceniu");
            check(d.cellMaxIndex / 4 + 1 == 22, "2102 indeks najslabszego/najmocniejszego ogniwa");
        }

        // 2102: urwana odpowiedz (jedno ogniwo 0xCF64 = 53092 mV) - to bylo widac na zrzucie
        {
            Decoder d = new Decoder();
            String raw = "762" + "04" + "61" + "02" + "CF" + "64" + "00" + "00" + "00" + "00";
            check(d.decodeCells(raw) == 0 && d.cellCount == 0, "2102 urwana ramka (53092 mV) odrzucona");
            check(!d.cellsRejected.isEmpty(), "2102 odrzucenie ma podany powod");
        }

        // 2102: odpowiedz z trzema ogniwami (za malo) - tez smieci
        {
            Decoder d = new Decoder();
            check(d.decodeCells(isotp(0x762, cellsPayload(3, 3700))) == 0,
                    "2102 odpowiedz z 3 ogniwami odrzucona");
        }

        // 2103: temperatury (bajt - 50), 0xFE pomijane
        {
            Decoder d = new Decoder();
            byte[] p = new byte[10];
            p[0] = 0x61;
            p[1] = 0x03;
            p[2] = 0x37;   // 5 C
            p[3] = 0x38;   // 6 C
            p[4] = 0x39;   // 7 C
            p[5] = (byte) 0xFE;
            p[6] = 0x3A;   // 8 C
            p[7] = 0x3B;   // 9 C
            int n = d.decodeTemps(isotp(0x762, p));
            check(n == 5 && d.tempMin == 5 && d.tempMax == 9, "2103 temperatury 5..9 C (czujniki 0xFE pominiete)");
        }

        // 2103: smieci (0x64 -> 50 C, 0xDC -> 170 C) - to bylo widac na zrzucie
        {
            Decoder d = new Decoder();
            byte[] p = new byte[8];
            p[0] = 0x61;
            p[1] = 0x03;
            p[2] = 0x64;
            p[3] = (byte) 0xDC;
            check(d.decodeTemps(isotp(0x762, p)) == 0, "2103 temperatury 50/170 C odrzucone");
        }

        System.out.println();
        System.out.println(failures == 0 ? "WYNIK: wszystkie testy OK" : "WYNIK: sa bledy (" + failures + ")");
        if (failures > 0) System.exit(1);
    }
}
