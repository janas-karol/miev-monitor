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
     * Nasluch magistrali: ATMA na podana liczbe milisekund, potem przerwanie.
     * Zwraca surowy tekst z ramkami (ATH1, ATS0).
     */
    String monitor(int ms) {
        if (in == null || out == null) return "";
        try {
            drain();
            out.write("ATMA\r".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + ms;
            byte[] buf = new byte[1024];
            while (System.currentTimeMillis() < deadline) {
                int n = in.available();
                if (n > 0) {
                    int read = in.read(buf, 0, Math.min(n, buf.length));
                    if (read > 0) sb.append(new String(buf, 0, read, StandardCharsets.US_ASCII));
                } else {
                    Thread.sleep(4);
                }
            }

            // przerwij nasluch i oproznij bufor
            out.write('\r');
            out.flush();
            Thread.sleep(40);
            drain();
            return sb.toString();
        } catch (IOException | InterruptedException e) {
            return "";
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
