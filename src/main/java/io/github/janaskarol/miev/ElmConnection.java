package io.github.janaskarol.miev;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Transport do adaptera ELM327 po Bluetooth Classic (SPP) + warstwa komend AT.
 *
 * Tanie klony czesto nie przyjmuja bezpiecznego gniazda (secure RFCOMM), dlatego
 * po niepowodzeniu probujemy gniazda niebezpiecznego, a na koncu sztuczki
 * z kanalem 1 (ta sama metoda, ktora dziala w aplikacjach typu Torque).
 */
final class ElmConnection {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;

    boolean isOpen() {
        return socket != null && socket.isConnected();
    }

    /** Laczy sie i od razu robi ATZ (restart adaptera). Wywolywac z watku w tle. */
    void connect(BluetoothDevice device) throws IOException {
        close();
        socket = openSocket(device);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    private BluetoothSocket openSocket(BluetoothDevice device) throws IOException {
        IOException last = null;

        try {
            BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP_UUID);
            s.connect();
            return s;
        } catch (IOException e) {
            last = e;
        }

        try {
            BluetoothSocket s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            s.connect();
            return s;
        } catch (IOException e) {
            last = e;
        }

        // Ostatnia proba: gniazdo na kanale 1 (wariant dla klonow).
        try {
            Method m = device.getClass().getMethod("createRfcommSocket", int.class);
            BluetoothSocket s = (BluetoothSocket) m.invoke(device, 1);
            s.connect();
            return s;
        } catch (Exception e) {
            last = new IOException("nie udalo sie otworzyc SPP: " + e.getMessage());
        }
        throw last != null ? last : new IOException("brak polaczenia");
    }

    void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    /** Wysyla komende AT i zbiera odpowiedz do znaku zachety '>'. */
    String command(String cmd, long timeoutMs) {
        if (in == null || out == null) return "";
        try {
            drain();
            out.write((cmd + "\r").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline) {
                int n = in.available();
                if (n > 0) {
                    int read = in.read(buf, 0, Math.min(n, buf.length));
                    if (read > 0) {
                        String chunk = new String(buf, 0, read, StandardCharsets.US_ASCII);
                        sb.append(chunk);
                        if (sb.indexOf(">") >= 0) break;
                    }
                } else {
                    Thread.sleep(5);
                }
            }
            String text = sb.toString();
            int prompt = text.indexOf('>');
            return prompt >= 0 ? text.substring(0, prompt) : text;
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /**
     * Wysyla kilka komend AT jednym zapisem i czeka na tyle znakow zachety,
     * ile komend wyslano. Klon ELM przetwarza je po kolei, wiec nie ma sensu
     * czekac osobno po kazdej - przełączanie trybu skraca sie z ~5 rund do jednej.
     *
     * @return liczba odebranych znakow zachety (rownanie komend = wszystko przyjete)
     */
    int commandsBatch(String[] cmds, long timeoutMs) {
        if (in == null || out == null) return 0;
        try {
            drain();
            StringBuilder all = new StringBuilder();
            for (String c : cmds) all.append(c).append('\r');
            out.write(all.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            int prompts = 0;
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[512];
            while (prompts < cmds.length && System.currentTimeMillis() < deadline) {
                int n = in.available();
                if (n > 0) {
                    int read = in.read(buf, 0, Math.min(n, buf.length));
                    for (int i = 0; i < read; i++) if (buf[i] == '>') prompts++;
                } else {
                    Thread.sleep(5);
                }
            }
            drain();
            return prompts;
        } catch (IOException | InterruptedException e) {
            return 0;
        }
    }

    /**
     * Rozpoczyna ciagly nasluch magistrali (ATMA). Strumien czytamy przez
     * readMonitor(), a zatrzymujemy stopMonitor() przed odczytem ogniw.
     *
     * Wczesniej nasluch byl seria 80 ms z 40 ms przerwy na zatrzymanie - jedna
     * trzecia czasu szla w bloto, a moc odswiezala sie skokowo.
     */
    boolean startMonitor() {
        if (in == null || out == null) return false;
        try {
            drain();
            out.write("ATMA\r".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Czyta to, co przyszlo. Czeka najwyzej maxWaitMs na pierwsze bajty. */
    String readMonitor(int maxWaitMs) {
        if (in == null || out == null) return "";
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + maxWaitMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                int n = in.available();
                if (n > 0) {
                    byte[] buf = new byte[Math.min(n, 2048)];
                    int read = in.read(buf, 0, buf.length);
                    if (read > 0) {
                        sb.append(new String(buf, 0, read, StandardCharsets.US_ASCII));
                        break;                      // jest co przetwarzac - nie czekamy dluzej
                    }
                } else {
                    Thread.sleep(4);
                }
            }
        } catch (IOException | InterruptedException ignored) {
        }
        return sb.toString();
    }

    /** Przerywa nasluch i dochodzi do znaku zachety, zeby adapter byl gotowy na komendy. */
    void stopMonitor() {
        if (in == null || out == null) return;
        try {
            out.write('\r');
            out.flush();
            long deadline = System.currentTimeMillis() + 500;
            byte[] buf = new byte[512];
            while (System.currentTimeMillis() < deadline) {
                int n = in.available();
                if (n > 0) {
                    int read = in.read(buf, 0, Math.min(n, buf.length));
                    boolean prompt = false;
                    for (int i = 0; i < read; i++) if (buf[i] == '>') prompt = true;
                    if (prompt) break;
                } else {
                    Thread.sleep(5);
                }
            }
            drain();
        } catch (IOException | InterruptedException ignored) {
        }
    }

    private void drain() throws IOException {
        int n = in.available();
        if (n > 0) in.skip(n);
    }

    static boolean isBluetoothOn() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled();
    }
}
