package dev.fmradio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RadioService extends Service implements RootClient.Listener {
    static final String ACTION_POWER_ON = "dev.fmradio.POWER_ON";
    static final String ACTION_PREV = "dev.fmradio.PREV";
    static final String ACTION_NEXT = "dev.fmradio.NEXT";
    static final String ACTION_STOP = "dev.fmradio.STOP";
    static final String EXTRA_SCAN = "scan";

    static final int MIN_KHZ = 87500;
    static final int MAX_KHZ = 108000;

    private static final String CHANNEL = "fm";
    private static final int NOTIF_ID = 1;
    private static final int MAX_LOG = 300;

    interface Ui {
        void onChanged();

        void onLog(String line);
    }

    class LocalBinder extends Binder {
        RadioService get() {
            return RadioService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private SharedPreferences prefs;
    private AudioManager audio;
    private NotificationManager nm;
    private AudioFocusRequest focusRequest;

    private RootClient root;
    private boolean rootReady;
    private boolean opening;
    private boolean powered;
    private boolean scanning;
    private boolean pendingScan;
    private boolean foreground;
    private boolean speaker;
    private int freq;
    private String ps = "";
    private String rt = "";
    private String status = "Радио выключено";
    private List<Integer> found = new ArrayList<>();
    private List<Integer> favs = new ArrayList<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private Ui ui;

    // ---------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Stations.prefs(this);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        freq = prefs.getInt("last", 98000);
        speaker = prefs.getBoolean("speaker", false);
        found = Stations.read(prefs, "found");
        favs = Stations.read(prefs, "fav");

        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "FM радио", NotificationManager.IMPORTANCE_LOW));

        IntentFilter f = new IntentFilter("android.media.VOLUME_CHANGED_ACTION");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(volumeReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, f);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_POWER_ON.equals(a)) {
            ensureForeground();
            if (intent.getBooleanExtra(EXTRA_SCAN, false)) pendingScan = true;
            powerOn();
        } else if (ACTION_PREV.equals(a)) {
            seek(false);
        } else if (ACTION_NEXT.equals(a)) {
            seek(true);
        } else if (ACTION_STOP.equals(a)) {
            powerOff();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(volumeReceiver);
        } catch (Exception ignored) {
        }
        abandonFocus();
        if (root != null) {
            root.quit();
            root = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------- getters

    void setUi(Ui ui) {
        this.ui = ui;
    }

    boolean isPowered() {
        return powered;
    }

    boolean isBusy() {
        return opening;
    }

    boolean isScanning() {
        return scanning;
    }

    boolean isSpeaker() {
        return speaker;
    }

    int getFreq() {
        return freq;
    }

    String getPs() {
        return ps;
    }

    String getRt() {
        return rt;
    }

    String getStatus() {
        return status;
    }

    List<Integer> getFound() {
        return new ArrayList<>(found);
    }

    List<Integer> getFavs() {
        return new ArrayList<>(favs);
    }

    List<String> getLog() {
        return new ArrayList<>(logLines);
    }

    boolean isFavorite(int khz) {
        return favs.contains(khz);
    }

    // ------------------------------------------------------------- actions

    void powerOn() {
        if (powered || opening) {
            changed();
            return;
        }
        opening = true;
        status = "Запуск… (запрос root)";
        if (!requestFocus()) log("аудиофокус не получен — продолжаю");
        if (root == null || !root.isAlive()) {
            rootReady = false;
            root = new RootClient(this, this);
            if (!root.start()) {
                failOpen("Не удалось запустить su");
                return;
            }
        }
        if (rootReady) sendOpen();
        changed();
    }

    void powerOff() {
        pendingScan = false;
        if (root != null) {
            if (rootReady) root.send("CLOSE");
            root.quit();
            root = null;
            rootReady = false;
        }
        powered = false;
        opening = false;
        scanning = false;
        ps = "";
        rt = "";
        status = "Радио выключено";
        abandonFocus();
        stopForegroundCompat();
        changed();
    }

    void tune(int khz) {
        khz = Math.round(khz / 100f) * 100;
        if (khz > MAX_KHZ) khz = MIN_KHZ;
        if (khz < MIN_KHZ) khz = MAX_KHZ;
        freq = khz;
        ps = "";
        rt = "";
        prefs.edit().putInt("last", freq).apply();
        if (powered && root != null) root.send("TUNE " + freq);
        updateNotification();
        changed();
    }

    void step(int deltaKhz) {
        tune(freq + deltaKhz);
    }

    void seek(boolean up) {
        if (powered && root != null) {
            root.send("SEEK " + (up ? "up" : "down"));
            ps = "";
            rt = "";
            changed();
        }
    }

    void startScan() {
        if (!powered) {
            pendingScan = true;
            powerOn();
            return;
        }
        if (root != null) root.send("SCAN");
    }

    void stopScan() {
        if (root != null) root.send("STOPSCAN");
    }

    void setSpeaker(boolean on) {
        speaker = on;
        prefs.edit().putBoolean("speaker", on).apply();
        if (powered && root != null) root.send("SPEAKER " + (on ? 1 : 0));
    }

    /** Вызывается UI при уходе с экрана: если радио не играет — сервис больше не нужен. */
    void stopIfIdle() {
        if (!powered && !opening) stopSelf();
    }

    void toggleFavorite(int khz) {
        if (favs.contains(Integer.valueOf(khz))) {
            favs.remove(Integer.valueOf(khz));
        } else {
            favs.add(khz);
        }
        java.util.Collections.sort(favs);
        Stations.write(prefs, "fav", favs);
        changed();
    }

    // -------------------------------------------------- helper protocol

    @Override
    public void onLine(String line) {
        if (line.startsWith("LOG ")) {
            log(line.substring(4));
            return;
        }
        log("« " + line);
        String[] p = line.split(" ", 2);
        String arg = p.length > 1 ? p[1] : "";
        switch (p[0]) {
            case "READY":
                rootReady = true;
                status = "Root OK, включаю тюнер…";
                if (opening) sendOpen();
                break;
            case "OPENED":
                powered = true;
                opening = false;
                status = "Играет";
                pushVolume();
                updateNotification();
                if (pendingScan) {
                    pendingScan = false;
                    if (root != null) root.send("SCAN");
                }
                break;
            case "CLOSED":
                break;
            case "FREQ":
                try {
                    int f = Integer.parseInt(arg.trim());
                    if (f != freq) {
                        ps = "";
                        rt = "";
                    }
                    freq = f;
                    prefs.edit().putInt("last", freq).apply();
                    updateNotification();
                } catch (NumberFormatException ignored) {
                }
                break;
            case "PS":
                ps = arg;
                updateNotification();
                break;
            case "RT":
                rt = arg;
                updateNotification();
                break;
            case "SCAN_START":
                scanning = true;
                status = "Идёт поиск станций…";
                break;
            case "SCANDONE":
                scanning = false;
                status = "Играет";
                found = parseList(arg);
                Stations.write(prefs, "found", found);
                toast("Найдено станций: " + found.size());
                break;
            case "SCAN_STOPPED":
                scanning = false;
                status = "Играет";
                break;
            case "TUNE_FAIL":
            case "SEEK_FAIL":
                toast("Не удалось перестроиться (" + arg + ")");
                break;
            case "FATAL":
                failOpen("Ошибка helper: " + arg);
                return;
            case "ERR":
                if (opening) {
                    failOpen(arg);
                    return;
                }
                toast(arg);
                break;
            default:
                break;
        }
        changed();
    }

    @Override
    public void onExit(int code) {
        log("root-процесс завершился, код " + code);
        boolean wasActive = powered || opening;
        rootReady = false;
        root = null;
        if (wasActive) {
            if (opening && code != 0) {
                failOpen("Нет root-доступа или он отклонён (su, код " + code + ")");
                return;
            }
            powerOff();
        }
    }

    private void sendOpen() {
        if (root != null) root.send("OPEN " + freq);
    }

    private void failOpen(String msg) {
        log("ОШИБКА: " + msg);
        toast(msg);
        if (root != null) {
            root.quit();
            root = null;
        }
        rootReady = false;
        opening = false;
        powered = false;
        pendingScan = false;
        status = "Ошибка: " + msg;
        abandonFocus();
        stopForegroundCompat();
        changed();
    }

    // -------------------------------------------------------------- audio

    private void pushVolume() {
        if (!powered || root == null) return;
        int v = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        root.send("VOL " + Math.round(v * 15f / max));
        if (speaker) root.send("SPEAKER 1");
    }

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) == AudioManager.STREAM_MUSIC) {
                pushVolume();
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    log("потерян аудиофокус — выключаю");
                    powerOff();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    if (powered && root != null) root.send("MUTE 1");
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (powered && root != null) root.send("MUTE 0");
                    break;
                default:
                    break;
            }
        }
    };

    private boolean requestFocus() {
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(focusListener, main)
                    .build();
        }
        return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonFocus() {
        if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
    }

    // ------------------------------------------------------- notification

    private void ensureForeground() {
        if (foreground) return;
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        foreground = true;
    }

    private void stopForegroundCompat() {
        if (foreground) {
            stopForeground(true);
            foreground = false;
        }
        if (ui == null) stopSelf();
    }

    private void updateNotification() {
        if (foreground) nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        String title = "FM " + Stations.format(freq) + " МГц" + (ps.isEmpty() ? "" : " · " + ps);
        String text = !rt.isEmpty() ? rt : (powered ? "Играет" : status);

        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_fm)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "◀◀", actionIntent(ACTION_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(null, "Стоп", actionIntent(ACTION_STOP, 2)).build())
                .addAction(new Notification.Action.Builder(null, "▶▶", actionIntent(ACTION_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, RadioService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE);
    }

    // --------------------------------------------------------------- misc

    private void changed() {
        if (ui != null) ui.onChanged();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void log(String s) {
        String line = timeFmt.format(new Date()) + " " + s;
        logLines.addLast(line);
        while (logLines.size() > MAX_LOG) logLines.removeFirst();
        if (ui != null) ui.onLog(line);
    }

    private static List<Integer> parseList(String s) {
        List<Integer> res = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) return res;
        for (String p : s.split(",")) {
            try {
                res.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return res;
    }
}
