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

        // ISO-TP: dwie ramki, 8 bajtow danych (2102)
        {
            Decoder d = new Decoder();
            String raw = "762" + "10" + "08" + "61" + "02" + "0F" + "3A" + "0F" + "3B"
                    + "\r762" + "21" + "0F" + "3C" + "0F" + "3D" + "0F" + "3E" + "0F";
            int cells = d.decodeCells(raw);
            check(cells > 0 && d.cellCount == cells && d.cellMin >= 2000,
                    "ISO-TP sklada 2102 i dekoduje ogniwa");
        }

        // 2103: temperatury (bajt - 50), 0xFE pomijane; 8 bajtow w dwoch ramkach
        {
            Decoder d = new Decoder();
            String raw = "762" + "10" + "08" + "61" + "03" + "37" + "38" + "39" + "FE"
                    + "\r762" + "21" + "3A" + "FE";
            int n = d.decodeTemps(raw);
            check(n == 4 && d.tempMin == 5 && d.tempMax == 8, "2103 temperatury 5..8 C (czujniki 0xFE pominiete)");
        }

        System.out.println();
        System.out.println(failures == 0 ? "WYNIK: wszystkie testy OK" : "WYNIK: sa bledy (" + failures + ")");
        if (failures > 0) System.exit(1);
    }
}
