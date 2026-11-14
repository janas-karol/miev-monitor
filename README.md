# MiEV Monitor — aplikacja na Androida

Telefon łączy się **bezpośrednio z adapterem ELM327 po Bluetooth Classic (SPP)**,
czyta ramki producenta, pokazuje dane na żywo i zapisuje próbki lokalnie
(SQLite → CSV).

**Obsługiwane auta:** Mitsubishi i-MiEV, Peugeot iOn, Citroën C-Zero — cała
platforma (te same ramki CAN i te same identyfikatory ECU).

Zero dodatkowego sprzętu w aucie: żadnego ESP32, żadnego laptopa.

Aplikacja jest **nieoficjalna** i niepowiązana z Mitsubishi Motors ani PSA.
Nazwy aut użyto wyłącznie opisowo.

## Co potrafi (wersja 1.12)

- **wskaźnik mocy jak obrotomierz** — rysowany od zera (`PowerGaugeView`):
  rekuperacja do **−25 kW** po lewej, pobór do **+50 kW** po prawej, zero w 1/3
  tarczy (na 12:00). Skala dobrana z danych: największa rekuperacja w bazie
  to 21,2 kW, największy pobór 40,5 kW,
- pod zegarem pasek **ostatnich 2 minut** mocy (`SparklineView`),
- **cztery zakładki** (pasek na dole, wybór pamiętany między uruchomieniami):
  1. **START** — duża tarcza mocy (kwadratowa, wypełnia szerokość), napięcia
     ogniw, temperatury,
  2. **OGNIWA** — wszystkie 88 ogniw w siatce (4 kolumny w pionie, **8 w poziomie**),
     z modułem, napięciem i paskiem w skali 3800–4200 mV,
  3. **ODCINEK** — bieżący odcinek i historia jazd,
  4. **INNE** — kafle SoC / prędkość / grzanie PTC, zasięg, licznik, płyn, klima,
     bufor, eksport CSV, ekran.
- karty z parametrami (dystans, energia, kWh/100 km, czas, średnia,
  rekuperacja, szczyt mocy, udziały grzania), **historia jazd** z bazy,
- **odczyt w tle** — foreground service z powiadomieniem: działa przy zgaszonym
  ekranie i zablokowanym telefonie, w powiadomieniu widać SoC i moc,
- **auto-reconnect** — po zerwaniu połączenia usługa ponawia co 5 s,
- wybór spośród **sparowanych** urządzeń BT (jak V-LINK; PIN 1234 paramy w systemie),
- odczyt: SoC, napięcie/prąd/moc pakietu, prędkość, zasięg, licznik, 88 ogniw,
  temperatury baterii, grzałka PTC + klima + temperatury płynu (`0x384`),
- zapis próbek do SQLite (0,5 Hz w ruchu, co 30 s na postoju) i **eksport CSV**,
- „Ekran zawsze włączony" — gdy chcesz widzieć kafelki w uchwycie.

## Wygląd

Tarcza mocy ma dwa warianty (stała `PowerGaugeView.Layout`):

| wariant | układ | skala |
|---|---|---|
| `FULL` (domyślny) | zero na **9:00**; rekuperacja 90° w dół, pobór 270° przez 12:00, **12° przerwy na dole** | 3,6°/kW rekuperacja, 5,4°/kW pobór |
| `THIRD` | zero na 12:00, rekuperacja 90°, pobór 180° | liniowa 3,6°/kW |
| `HALF` | zero na 12:00, po 135° na stronę | 5,4 i 2,7°/kW |
| `LEFT` | zero na 9:00, pobór 180° przez 12:00 do 3:00 | liniowa 3,6°/kW |

W wariancie `FULL` końce skali prawie się stykają na dole, dlatego **skrajne
wartości (−25 i +50) nie mają etykiet** — zostają same kreski, a zakres opisuje
podpis pod tarczą. Etykiety biegną od −20 do 40, a między końcem rekuperacji i
poboru zostaje 12° przerwy, żeby nie zlewały się w jeden punkt.

Igła jest „pływająca" (od 45% do 90% promienia) — dzięki temu nie przecina dużej
cyfry w środku. Nie rysujemy kropki osi: przy takiej igle była zbędna i czytała
się jak kropka dziesiętna przy wartości.

Wariant `THIRD` wynika z danych: rekuperacja w i-MiEV realnie sięga ~21 kW,
pobór ~40 kW, więc zakres −25…+50 daje zero dokładnie w 1/3 tarczy i — co ważne —
**jednakową rozdzielczość na obu stronach**. `HALF` wygląda symetrycznie, ale
rozciąga rekuperację kosztem czytelności poboru.

Zmiana w kodzie:

```java
powerGauge.setLayout(PowerGaugeView.Layout.HALF);
```

Podglądy projektu (makiety, nie zrzuty z telefonu) generuje `mockup/render.py`:

```bash
cd mockup
python3 render.py            # cztery zakładki + porównanie tarcz + widok poziomy
python3 render.py cells      # tylko zakładkę OGNIWA
```

Pliki:

| plik | co pokazuje |
|---|---|
| `screen-start.png` | zakładka START (tarcza + kafle + napięcia + temperatury) |
| `screen-cells.png` | zakładka OGNIWA, portret (4 kolumny) |
| `screen-cells-landscape.png` | zakładka OGNIWA, poziom (8 kolumn) |
| `screen-trip.png` | zakładka ODCINEK (odcinek + historia) |
| `screen-other.png` | zakładka INNE |
| `screen-zakladki.png` | wszystkie cztery obok siebie |
| `screen-porownanie.png` | dwa warianty tarczy (1/3 vs połowa) |

Skrypt rysuje ten sam układ co `PowerGaugeView` i ta sama siatkę ogniw co
`MainActivity.rebuildCellGrid()`, więc makieta i aplikacja się nie rozjeżdżają.

## Budowanie

Bez Gradle i bez AndroidX — aplikacja nie ma żadnych zależności, więc wystarczą
`aapt2`, `javac`, `d8`, `zipalign`, `apksigner`.

```bash
cd ~/projects/imiev/android
./build.sh                # APK w apk/imiev-1.0.apk
./build.sh install        # dodatkowo: adb install -r
```

Ścieżki można nadpisać zmiennymi: `JDK=... ANDROID_SDK_ROOT=... ./build.sh`.
Domyślnie: `~/tools/jdk-17` i `~/android-sdk`.

Sprawdzone: JDK 17 (Temurin), Android SDK platform `android-33`,
build-tools `34.0.0`. Wynik: **45 KB APK**, `minSdk 21`, `targetSdk 33`.

Wersję ustawia się zmiennymi środowiskowymi:

```bash
VERSION_NAME=1.2 VERSION_CODE=3 ./build.sh
```

### Podpisywanie (klucz release)

APK jest podpisywany **własnym kluczem release**, nie debug:

| element | gdzie |
|---|---|
| keystore (PKCS12, RSA 4096, ważny do 2054) | `~/.secrets/imiev-release.keystore` |
| alias i hasła | `~/.secrets/imiev-release.env` (chmod 600) |
| DN certyfikatu | `CN=i-MiEV Dashboard, OU=homelab, O=i-MiEV, L=home, C=PL` |

`build.sh` sam czyta ten plik. Gdy go brak — podpisuje kluczem debug i wypisuje
ostrzeżenie. Aby wymusić debug: `IMIEV_SECRETS_ENV=/brak ./build.sh`.

**Zasady, które warto znać:**

1. **Zgubienie klucza = brak aktualizacji.** Nowy klucz to inny podpis, więc
   telefon odmówi instalacji na starym (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
   Trzeba wtedy odinstalować aplikację — razem z bazą jazd.
2. **Kopia zapasowa:** skopiuj `imiev-release.keystore` i `imiev-release.env`
   w bezpieczne miejsce (np. do menedżera haseł / na NAS). Bez `.env` keystore
   jest bezużyteczny, bez keystore — hasło.
3. **Nie wrzucaj ich do repo ani na Syncthing.** `~/.secrets/` jest poza
   katalogami synchronizowanymi.
4. Keystore jest wspólny dla przyszłych wersji aplikacji — nie generuj nowego
   przy każdej zmianie wersji.

### Instalacja na telefonie

Najprościej: skopiuj `apk/imiev-1.0.apk` na telefon (kabel, Syncthing, e-mail)
i otwórz — trzeba zezwolić na instalację z nieznanych źródeł.
Albo kablem z włączonym debugowaniem USB:

```bash
~/android-sdk/platform-tools/adb install -r apk/imiev-1.0.apk
```

APK jest podpisany **kluczem debug** (`~/.android/debug.keystore`), więc system
może ostrzec, że aplikacja nie jest z Google Play — to normalne przy sideloadingu.

## Testy dekodowania (bez telefonu)

`Decoder` nie zależy od Androida, więc testuje się zwykłą Javą:

```bash
~/tools/jdk-17/bin/javac -encoding UTF-8 -d /tmp/atest \
    src/main/java/pl/imiev/app/Decoder.java test/DecoderTest.java
~/tools/jdk-17/bin/java -cp /tmp/atest pl.imiev.app.DecoderTest
```

14 sprawdzeń na prawdziwych ramkach (`0x373`, `0x374`, `0x215`, `0x346`, `0x412`,
`0x384`, ISO-TP `2102`/`2103`) — wszystkie przechodzą.

## Struktura

| plik | rola |
|---|---|
| `src/main/java/pl/imiev/app/MainActivity.java` | ekran: wybór adaptera, kafelki, odcinek, historia |
| `src/main/java/pl/imiev/app/PowerGaugeView.java` | zegar mocy (Canvas, zero w 1/3 tarczy) |
| `src/main/java/pl/imiev/app/SparklineView.java` | pasek ostatnich 2 minut |
| `src/main/java/pl/imiev/app/TripTracker.java` | liczenie odcinka jazdy |
| `src/main/java/pl/imiev/app/ReaderService.java` | **usługa w tle**: połączenie, pętla odczytu, powiadomienie, auto-reconnect |
| `src/main/java/pl/imiev/app/CarState.java` | wspólny stan między usługą a ekranem |
| `src/main/java/pl/imiev/app/ElmConnection.java` | SPP: gniazdo (3 warianty), komendy AT, nasłuch `ATMA` |
| `src/main/java/pl/imiev/app/Decoder.java` | parser ramek, ISO-TP, dekodowanie sygnałów |
| `src/main/java/pl/imiev/app/SampleLog.java` | SQLite + eksport CSV |
| `res/layout/activity_main.xml` | wygląd (kafelki, szczegóły, przyciski) |
| `build.sh` | build APK bez Gradle |
| `test/DecoderTest.java` | testy dekodowania |

## Dystrybucja i weryfikacja (GitHub Releases)

Kod jest opublikowany, żeby każdy mógł sprawdzić, co robi aplikacja. **Nie ma
licencji na redystrybucję** — źródło służy weryfikacji i nauce, a gotowe buildy
publikowane są wyłącznie w wydaniach tego repozytorium. To celowe: nie chcemy,
żeby krążyły zmodyfikowane kopie pod tą samą nazwą.

Aplikacja jest udostępniana jako plik APK — bez sklepu, więc zaufanie musi być
sprawdzalne. Każdy build produkuje trzy pliki:

| plik | do czego |
|---|---|
| `apk/imiev-<wersja>.apk` | sama aplikacja (podpisana kluczem release) |
| `apk/SHA256SUMS-<wersja>.txt` | suma kontrolna pliku — dowód, że plik nie został podmieniony |
| `apk/CERT-<wersja>.txt` | odcisk certyfikatu — dowód, kto podpisał aplikację |

**Odcisk certyfikatu (stały dla wszystkich wersji):**

```
CN=i-MiEV Dashboard, OU=homelab, O=i-MiEV, L=home, C=PL
SHA-256: 09ad787b939be7db7ab43e8dad753bdd6c389fdbefae53503b9bee0b229d5f78
```

Klucz się nie zmienia, więc ten odcisk jest ważny także dla przyszłych wersji —
łącznie ze zmianą nazwy pakietu.

### Jak to sprawdzić (Windows)

```powershell
Get-FileHash .\imiev-1.14.apk -Algorithm SHA256
# porównaj z SHA256SUMS-1.14.txt z wydania
```

### Jak to sprawdzić (Linux / macOS)

```bash
sha256sum imiev-1.14.apk
```

### Jak sprawdzić podpis (opcjonalnie, wymaga Android SDK)

```bash
apksigner verify --print-certs imiev-1.14.apk
```

### Co aplikacja robi, a czego nie robi

- **nie ma uprawnienia do internetu** — nie może nigdzie wysłać danych; widać to
  w ustawieniach telefonu (Aplikacje → i-MiEV → Uprawnienia) i w manifeście APK,
- uprawnienia: Bluetooth, Bluetooth Connect, Wake Lock, Foreground Service
  (connectedDevice), Powiadomienia — wszystkie potrzebne do odczytu z adaptera,
- brak analityki, reklam i bibliotek zewnętrznych (57 KB, tylko kod własny),
- nieoficjalna aplikacja, niepowiązana z Mitsubishi Motors; nazwy i-MiEV użyto
  wyłącznie opisowo.

## Ograniczenia wersji 1.1 — przeczytaj przed jazdą

1. **Android może wciąż ubić usługę** przy agresywnej optymalizacji baterii
   (Xiaomi, Samsung, Huawei mają własne zabijaczki). Wyłącz optymalizację baterii
   dla tej aplikacji w ustawieniach telefonu. Sama usługa ma typ
   `connectedDevice`, więc system traktuje ją poważniej niż zwykły proces.
2. **Jedno urządzenie naraz.** Telefon zajmuje adapter, gdy jest połączony —
   laptop i ESP32 muszą wtedy milczeć.
3. **Brak wysyłki do bazy.** CSV ląduje w
   `Android/data/io.github.janaskarol.miev/files/csv/`; ścieżkę pokazuje aplikacja.
   Synchronizacja z serwerem dashboardu to kolejny krok.
4. **Nietestowane na telefonie.** APK się kompiluje i przechodzi weryfikację
   podpisu, ale nie był uruchamiany na urządzeniu —trzeba to zrobić na Twoim
   telefonie i ewentualne błędy poprawić z logów (`adb logcat`).
5. Tanie klony pamiętają jedno urządzenie partnerskie — po parowaniu ESP32
   może być konieczne ponowne sparowanie telefonu (PIN 1234).

## Kolejne kroki

- ~~usługa w tle + powiadomienie~~ — zrobione w 1.1,
- wysyłka CSV/JSON do serwera (`/api/ingest`) — wspólna baza z dashboardem,
- wykres zużycia w aplikacji, historia jazd,
- ewentualnie MQTT do Home Assistant.
