# MiEV Monitor — dokumentacja dla programistów

Ten plik opisuje budowanie, strukturę kodu i testy. Dla użytkowników aplikacji
instrukcja jest w [README.md](README.md).

## Wymagania do budowania

- JDK 17 (np. Temurin) — ścieżka domyślna `~/tools/jdk-17`, nadpisywalna zmienną `JDK`,
- Android SDK: platforma `android-36`, build-tools `36.0.0`
  (`minSdk 21`, `targetSdk 36`; starszy `targetSdk` powoduje ostrzeżenie Play Protect
  „aplikacja na starszą wersję Androida”)
  (domyślnie `~/android-sdk`, nadpisywalne `ANDROID_SDK_ROOT`).

Projekt **nie używa Gradle** ani AndroidX — wystarczają `aapt2`, `javac`, `d8`,
`zipalign` i `apksigner` z SDK.

## Budowanie

```bash
./build.sh                                   # APK w apk/imiev-<wersja>.apk
VERSION_NAME=1.16 VERSION_CODE=17 ./build.sh # z numerem wersji
./build.sh install                           # + adb install -r
```

Każdy build tworzy też `apk/SHA256SUMS-<wersja>.txt` i `apk/CERT-<wersja>.txt`.

## Podpisywanie

`build.sh` czyta `~/.secrets/imiev-release.env` i podpisuje kluczem release
(`~/.secrets/imiev-release.keystore`, PKCS12, RSA 4096). Bez tego pliku
podpisuje kluczem debug z ostrzeżeniem.

| element | wartość |
|---|---|
| alias | `imiev` |
| DN | `CN=i-MiEV Dashboard, OU=homelab, O=i-MiEV, L=home, C=PL` |
| SHA-256 certyfikatu | `09ad787b939be7db7ab43e8dad753bdd6c389fdbefae53503b9bee0b229d5f78` |

**Zasady:**

1. Zgubienie klucza = brak aktualizacji dla wszystkich użytkowników.
2. Kopia zapasowa keystore **i** env w menedżerze haseł.
3. Klucz nigdy nie trafia do repozytorium.

## Struktura kodu

| plik | rola |
|---|---|
| `src/main/java/io/github/janaskarol/miev/MainActivity.java` | ekran: zakładki, adapter, odświeżanie, eksport |
| `.../ReaderService.java` | usługa w tle: połączenie, pętla odczytu, powiadomienie, reconnect |
| `.../CarState.java` | stan współdzielony między usługą a ekranem |
| `.../ElmConnection.java` | SPP: gniazdo (3 warianty), komendy AT, nasłuch `ATMA` |
| `.../Decoder.java` | parser ramek, ISO-TP, dekodowanie sygnałów |
| `.../PowerGaugeView.java` | zegar mocy (Canvas), układy `THIRD/HALF/LEFT/FULL`, animowana igła (wygładzanie wykładnicze) |
| `.../SparklineView.java` | pasek ostatnich 2 minut |
| `.../TripTracker.java` | liczenie odcinka jazdy |
| `.../SampleLog.java` | SQLite (próbki, jazdy) + eksport CSV |
| `res/layout/activity_main.xml` | cały wygląd |
| `mockup/render.py` | generator makiet PNG (HTML/SVG, bez telefonu) |
| `test/DecoderTest.java` | testy dekodowania |

## Dekodowane ramki

| ramka | znaczenie |
|---|---|
| `0x373` | napięcie pakietu `×0,1` (b4:b5), prąd `(b2:b3 − 32768) × 0,01` |
| `0x374` | SoC: `(b1 − 10) × 0,5` |
| `0x215` | prędkość: `(b0:b1) × 0,0078125` |
| `0x346` | zasięg wskazywany przez auto: bajt 7 (255 = brak) |
| `0x412` | licznik: `(b2 << 16) \| (b3 << 8) \| b4` |
| `0x384` | grzałka PTC `b4 × 0,1 A`, płyn `b5/b6 × 0,6 − 40 °C`, klima `(b0:b1) × 0,001 A` |
| `2102` | napięcia 88 ogniw (ISO-TP, adres 0x7E4 → odp. 0x7EC) |
| `2103` | temperatury (bajt − 50, 0xFE = brak czujnika) |

Inicjalizacja klonu ELM327:
`ATZ → ATE0 → ATL0 → ATS0 → ATH1 → ATSP6 → ATCAF1 → ATFCSH761 → ATFCSD300000 → ATFCSM1 → ATSH761`

## Testy

```bash
javac -encoding UTF-8 -d /tmp/atest src/main/java/io/github/janaskarol/miev/Decoder.java test/DecoderTest.java
java -cp /tmp/atest io.github.janaskarol.miev.DecoderTest
```

Testy działają na prawdziwych ramkach z odczytów i nie wymagają telefonu.

## Makiety

```bash
cd mockup && python3 render.py          # wszystkie zakładki, porównania
cd mockup && python3 render.py cells    # jedna zakładka
```

Wymaga `weasyprint`, `pdftoppm` (poppler) i Pillow. Makiety rysują ten sam układ
co aplikacja, ale są podglądem — nie zrzutem z telefonu.

## Wydanie

1. Podbij `VERSION_NAME` i `VERSION_CODE`, zbuduj `./build.sh`.
2. Sprawdź `apk/SHA256SUMS-*.txt` i `apk/CERT-*.txt`.
3. Utwórz wydanie:
   ```bash
   gh release create vX.Y apk/imiev-X.Y.apk apk/SHA256SUMS-X.Y.txt apk/CERT-X.Y.txt \
       --title "MiEV Monitor X.Y" --notes-file notatki.md
   ```
4. Odcisk certyfikatu w `CERT-*.txt` musi być taki sam jak w poprzednich wydaniach.

## Ograniczenia techniczne

- Usługa może być zabijana przez agresywną optymalizację baterii (Xiaomi, Samsung, Huawei).
  Typ usługi `connectedDevice` ogranicza to, ale nie wyklucza.
- Adapter obsługuje jedno połączenie naraz.
- Tanie klony ELM327 pamiętają jedno urządzenie partnerskie.
- Brak uprawnienia INTERNET — świadoma decyzja (prywatność), nie do zmiany bez zgody użytkownika.
