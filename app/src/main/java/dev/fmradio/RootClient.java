package dev.fmradio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Запускает через su процесс dev.fmradio.root.FmHelper (app_process, uid 0)
 * и общается с ним строками через stdin/stdout.
 */
final class RootClient {
    interface Listener {
        void onLine(String line);

        void onExit(int code);
    }

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Process proc;
    private OutputStream stdin;
    private volatile boolean closed;

    RootClient(Context app, Listener listener) {
        this.app = app.getApplicationContext();
        this.listener = listener;
    }

    boolean start() {
        try {
            proc = new ProcessBuilder("su").start();
        } catch (IOException e) {
            post("ERR su: " + e.getMessage());
            return false;
        }
        stdin = proc.getOutputStream();
        pump(proc.getInputStream(), false);
        pump(proc.getErrorStream(), true);

        final Process p = proc;
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ignored) {
                }
                final int c = code;
                closed = true;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onExit(c);
                    }
                });
            }
        }, "su-waiter");
        waiter.setDaemon(true);
        waiter.start();

        String apk = app.getPackageCodePath();
        String cmd = "CLASSPATH='" + apk + "' exec /system/bin/app_process /system/bin"
                + " --nice-name=fmradio_root dev.fmradio.root.FmHelper\n";
        return writeRaw(cmd);
    }

    boolean send(String line) {
        return writeRaw(line + "\n");
    }

    /** Корректно завершить helper (он сам закроет тюнер и выключит FM-маршрут). */
    void quit() {
        send("QUIT");
        final Process p = proc;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (p != null) p.destroy();
            }
        }, 1500);
    }

    boolean isAlive() {
        return !closed && proc != null;
    }

    private synchronized boolean writeRaw(String s) {
        if (closed || stdin == null) return false;
        try {
            stdin.write(s.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void pump(final InputStream in, final boolean isErr) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        post(isErr ? "LOG [stderr] " + line : line);
                    }
                } catch (IOException ignored) {
                }
            }
        }, isErr ? "su-err" : "su-out");
        t.setDaemon(true);
        t.start();
    }

    private void post(final String line) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onLine(line);
            }
        });
    }
}
