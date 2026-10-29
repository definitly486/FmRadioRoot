package dev.fmradio.root;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.hardware.radio.ProgramList;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioMetadata;
import android.hardware.radio.RadioTuner;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Процесс, запускаемый от root через
 *   CLASSPATH=base.apk app_process /system/bin dev.fmradio.root.FmHelper
 *
 * Делает то же, что штатный com.android.fmradio (DreamFMRadio):
 *   1. RadioManager.openTuner(...)  -> HAL broadcastradio (sprd FM)
 *   2. tuner.tune(kHz, 0) / tuner.scan(dir, true) / getDynamicProgramList(null)
 *   3. AudioSystem "FM_Volume=N", "FM_mute=0|1"
 *   4. UnisocAudioManager.setDeviceConnectionStateForFM(0x100000, 1|0, "", "")
 *
 * Протокол: команды приходят строками в stdin, ответы уходят строками в stdout.
 */
public final class FmHelper {
    private static final int DEVICE_OUT_FM = 0x100000;      // 1048576 из байткода штатного приложения
    private static final int STATUS_DEAD_OBJECT = -38;
    private static final int MIN_KHZ = 87500;
    private static final int MAX_KHZ = 108000;
    private static final long SCAN_TIMEOUT_MS = 45_000;
    private static final long SEEK_SCAN_TIMEOUT_MS = 100_000;

    private final PrintStream out;
    private final Handler handler;

    private Context ctx;
    private boolean realContext;
    private RadioManager radioManager;
    private RadioTuner tuner;
    private Object unisoc;                 // android.media.UnisocAudioManager

    private boolean fmRouted;
    private boolean muted = true;
    private boolean speaker;
    private int volume = 8;                // 0..15
    private int currentKhz = 98000;

    // автопоиск
    private boolean scanActive;
    private boolean seekScanActive;
    private boolean seekWaiting;
    private int seekScanSteps;
    private final List<Integer> found = new ArrayList<>();
    private ProgramList programList;
    private ProgramList.OnCompleteListener scanListener;
    private boolean seekPending;           // ждём результат ручного seek, чтобы снять mute

    private final Runnable scanTimeout = new Runnable() {
        @Override
        public void run() {
            log("scan timeout");
            if (seekScanActive) {
                finishScan(sortedCopy(found));
            } else {
                finishListScan();
            }
        }
    };

    private final Runnable seekStep = new Runnable() {
        @Override
        public void run() {
            seekScanStep();
        }
    };

    private final Runnable unmuteLater = new Runnable() {
        @Override
        public void run() {
            if (seekPending) {
                seekPending = false;
                setMute(false);
            }
        }
    };

    private final RadioTuner.Callback callback = new RadioTuner.Callback() {
        @Override
        public void onError(int status) {
            log("tuner.onError " + status);
            emit("TUNER_ERROR " + status);
        }

        @Override
        public void onConfigurationChanged(RadioManager.BandConfig config) {
            log("tuner.onConfigurationChanged");
        }

        @Override
        public void onControlChanged(boolean control) {
            log("tuner.onControlChanged " + control);
        }

        @Override
        public void onProgramInfoChanged(RadioManager.ProgramInfo info) {
            try {
                onProgramInfo(info.getChannel());
            } catch (Throwable t) {
                log("onProgramInfoChanged: " + t);
            }
        }

        @Override
        public void onMetadataChanged(RadioMetadata md) {
            try {
                String ps = md.getString("android.hardware.radio.metadata.RDS_PS");
                String rt = md.getString("android.hardware.radio.metadata.RDS_RT");
                if (ps != null) emit("PS " + ps.trim());
                if (rt != null) emit("RT " + rt.trim());
            } catch (Throwable t) {
                log("onMetadataChanged: " + t);
            }
        }
    };

    private FmHelper() throws Exception {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        handler = new Handler(Looper.getMainLooper());
    }

    public static void main(String[] args) {
        try {
            Looper.prepareMainLooper();
            final FmHelper h = new FmHelper();
            h.init();
            h.startReader();
            Looper.loop();
        } catch (Throwable t) {
            System.out.println("FATAL " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- init

    private void init() {
        try {
            initContext();
            emit("READY model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
                    + " uid=" + Process.myUid() + " realCtx=" + realContext
                    + " unisoc=" + (unisoc != null));
        } catch (Throwable t) {
            emit("FATAL init: " + describe(t));
            return;
        }
        diagnose();
    }

    private void initContext() throws Exception {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            ctx = (Context) at.getMethod("getSystemContext").invoke(thread);
            realContext = ctx != null;
        } catch (Throwable t) {
            log("systemMain failed, using fake context: " + describe(t));
        }
        if (ctx == null) {
            ctx = new FakeContext();
        }

        Object rm = null;
        if (realContext) {
            try {
                rm = ctx.getSystemService("broadcastradio");
            } catch (Throwable t) {
                log("getSystemService(broadcastradio): " + describe(t));
            }
        }
        if (rm == null) {
            rm = RadioManager.class.getConstructor(Context.class).newInstance(ctx);
        }
        radioManager = (RadioManager) rm;

        try {
            Class<?> u = Class.forName("android.media.UnisocAudioManager");
            unisoc = u.getMethod("getInstance", Context.class).invoke(null, ctx);
        } catch (Throwable t) {
            log("UnisocAudioManager unavailable: " + describe(t));
        }
    }

    /** Проверка прав и наличия модуля — результат сразу виден в журнале приложения. */
    private void diagnose() {
        try {
            List<RadioManager.ModuleProperties> modules = new ArrayList<>();
            int st = radioManager.listModules(modules);
            StringBuilder sb = new StringBuilder("listModules status=" + st + " count=" + modules.size());
            for (RadioManager.ModuleProperties m : modules) {
                sb.append(" id=").append(m.getId());
            }
            log(sb.toString());
        } catch (Throwable t) {
            emit("ERR diag listModules: " + describe(t));
        }
    }

    private void startReader() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(new FileInputStream(FileDescriptor.in), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) {
                        final String l = line;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                handle(l);
                            }
                        });
                    }
                } catch (Throwable ignored) {
                    // EOF/ошибка = приложение умерло
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        shutdown();
                    }
                });
            }
        }, "stdin-reader");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------ commands

    private void handle(String line) {
        line = line.trim();
        if (line.isEmpty()) return;
        String[] p = line.split("\\s+", 2);
        String cmd = p[0].toUpperCase(Locale.ROOT);
        String arg = p.length > 1 ? p[1].trim() : "";
        try {
            switch (cmd) {
                case "OPEN":
                    open(parseInt(arg, currentKhz));
                    break;
                case "TUNE":
                    tuneCmd(parseInt(arg, currentKhz));
                    break;
                case "SEEK":
                    seek(!"down".equalsIgnoreCase(arg));
                    break;
                case "SCAN":
                    startScan();
                    break;
                case "STOPSCAN":
                    stopScan();
                    break;
                case "VOL":
                    setVolume(parseInt(arg, volume));
                    break;
                case "MUTE":
                    setMute("1".equals(arg));
                    break;
                case "SPEAKER":
                    setSpeaker("1".equals(arg));
                    break;
                case "CLOSE":
                    close();
                    break;
                case "PING":
                    emit("PONG");
                    break;
                case "QUIT":
                    shutdown();
                    break;
                default:
                    log("unknown command: " + line);
            }
        } catch (Throwable t) {
            emit("ERR " + cmd + " " + describe(t));
        }
    }

    private void open(int khz) throws Exception {
        ensureTuner();
        setRds(true);
        if (!tune(khz)) {
            throw new IllegalStateException("tune failed");
        }
        routeFm(true);
        setSpeaker(speaker);
        setMute(false);
        emit("OPENED " + currentKhz);
    }

    private void tuneCmd(int khz) throws Exception {
        if (scanActive) stopScan();
        setMute(true);
        if (tune(khz)) {
            setMute(false);
        }
    }

    private void seek(boolean up) throws Exception {
        ensureTuner();
        setMute(true);
        seekPending = true;
        handler.removeCallbacks(unmuteLater);
        handler.postDelayed(unmuteLater, 6000);
        int r = tuner.scan(up ? RadioTuner.DIRECTION_UP : RadioTuner.DIRECTION_DOWN, true);
        if (r == STATUS_DEAD_OBJECT) {
            dropTuner();
            ensureTuner();
            r = tuner.scan(up ? RadioTuner.DIRECTION_UP : RadioTuner.DIRECTION_DOWN, true);
        }
        log("seek " + (up ? "up" : "down") + " -> " + r);
        if (r != 0) {
            seekPending = false;
            setMute(false);
            emit("SEEK_FAIL " + r);
        }
    }

    private void close() {
        stopScanQuiet();
        handler.removeCallbacks(unmuteLater);
        try {
            setMute(true);
        } catch (Throwable ignored) {
        }
        audioParams("AudioFmPreStop=1");
        routeFm(false);
        dropTuner();
        emit("CLOSED");
    }

    private void shutdown() {
        try {
            close();
        } catch (Throwable ignored) {
        }
        System.exit(0);
    }

    // --------------------------------------------------------------- tuner

    private void ensureTuner() throws Exception {
        if (tuner != null) return;
        List<RadioManager.ModuleProperties> modules = new ArrayList<>();
        int st = radioManager.listModules(modules);
        log("listModules status=" + st + " count=" + modules.size());
        if (st != 0 || modules.isEmpty()) {
            throw new IllegalStateException("нет радио-модуля (status " + st + ")");
        }
        int id = modules.get(0).getId();
        tuner = radioManager.openTuner(id, null, true, callback, handler);
        if (tuner == null) {
            throw new IllegalStateException("openTuner вернул null");
        }
        log("openTuner ok, module id=" + id);
        if (sysPropBool("persist.fmradio.50k_step")) {
            Map<String, String> m = new HashMap<>();
            m.put("spacing", "50k");
            tuner.setParameters(m);
        }
    }

    private void dropTuner() {
        if (tuner != null) {
            try {
                tuner.close();
            } catch (Throwable t) {
                log("tuner.close: " + describe(t));
            }
            tuner = null;
        }
    }

    private boolean tune(int khz) throws Exception {
        ensureTuner();
        khz = Math.max(MIN_KHZ, Math.min(MAX_KHZ, khz));
        int r = tuner.tune(khz, 0);
        if (r == STATUS_DEAD_OBJECT) {
            dropTuner();
            ensureTuner();
            r = tuner.tune(khz, 0);
        }
        log("tune " + khz + " -> " + r);
        if (r == 0) {
            currentKhz = khz;
            emit("FREQ " + khz);
            return true;
        }
        emit("TUNE_FAIL " + khz + " " + r);
        return false;
    }

    private void setRds(boolean on) {
        try {
            Map<String, String> m = new HashMap<>();
            m.put("sprdsetrds", on ? "sprdrdson" : "sprdrdsoff");
            tuner.setParameters(m);
        } catch (Throwable t) {
            log("setRds: " + describe(t));
        }
    }

    private void onProgramInfo(int khz) {
        if (khz < MIN_KHZ || khz > MAX_KHZ) return;
        currentKhz = khz;
        emit("FREQ " + khz);

        if (seekPending) {
            seekPending = false;
            handler.removeCallbacks(unmuteLater);
            setMute(false);
        }
        if (seekScanActive && seekWaiting) {
            seekWaiting = false;
            boolean wrapped = !found.isEmpty() && khz <= found.get(found.size() - 1);
            if (wrapped) {
                finishScan(sortedCopy(found));
            } else {
                found.add(khz);
                handler.postDelayed(seekStep, 350);
            }
        }
    }

    // ---------------------------------------------------------------- scan

    private void startScan() throws Exception {
        if (scanActive) return;
        ensureTuner();
        scanActive = true;
        setMute(true);
        emit("SCAN_START");

        ProgramList list = null;
        try {
            list = tuner.getDynamicProgramList(null);
        } catch (Throwable t) {
            log("getDynamicProgramList: " + describe(t));
        }
        if (list != null) {
            programList = list;
            scanListener = new ProgramList.OnCompleteListener() {
                @Override
                public void onComplete() {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            finishListScan();
                        }
                    });
                }
            };
            list.addOnCompleteListener(scanListener);
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } else {
            startSeekScan();
        }
    }

    private void finishListScan() {
        if (!scanActive || seekScanActive) return;
        handler.removeCallbacks(scanTimeout);
        List<Integer> res = new ArrayList<>();
        try {
            if (programList != null) {
                for (RadioManager.ProgramInfo pi : programList.toList()) {
                    int f = pi.getChannel();
                    if (f >= MIN_KHZ && f <= MAX_KHZ && !res.contains(f)) res.add(f);
                }
            }
        } catch (Throwable t) {
            log("programList.toList: " + describe(t));
        }
        releaseProgramList();
        Collections.sort(res);
        log("dynamic list scan: " + res.size() + " stations");
        if (res.isEmpty()) {
            log("список пуст, пробую поиск через seek");
            try {
                startSeekScan();
            } catch (Throwable t) {
                emit("ERR SCAN " + describe(t));
                finishScan(res);
            }
            return;
        }
        finishScan(res);
    }

    private void startSeekScan() throws Exception {
        seekScanActive = true;
        seekWaiting = false;
        seekScanSteps = 0;
        found.clear();
        log("seek-scan: старт с " + MIN_KHZ);
        tune(MIN_KHZ);
        handler.removeCallbacks(scanTimeout);
        handler.postDelayed(scanTimeout, SEEK_SCAN_TIMEOUT_MS);
        handler.postDelayed(seekStep, 700);
    }

    private void seekScanStep() {
        if (!seekScanActive) return;
        if (++seekScanSteps > 95) {
            finishScan(sortedCopy(found));
            return;
        }
        try {
            seekWaiting = true;
            int r = tuner.scan(RadioTuner.DIRECTION_UP, true);
            if (r != 0) {
                log("seek-scan step -> " + r);
                finishScan(sortedCopy(found));
            }
        } catch (Throwable t) {
            log("seek-scan: " + describe(t));
            finishScan(sortedCopy(found));
        }
    }

    private void finishScan(List<Integer> res) {
        handler.removeCallbacks(scanTimeout);
        handler.removeCallbacks(seekStep);
        scanActive = false;
        seekScanActive = false;
        seekWaiting = false;
        releaseProgramList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < res.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(res.get(i));
        }
        emit("SCANDONE " + sb);
        try {
            if (tuner != null) {
                int back = res.isEmpty() ? currentKhz : (res.contains(currentKhz) ? currentKhz : res.get(0));
                tune(back);
            }
        } catch (Throwable t) {
            log("retune after scan: " + describe(t));
        }
        setMute(false);
    }

    private void stopScan() {
        if (!scanActive) return;
        List<Integer> partial = seekScanActive ? sortedCopy(found) : new ArrayList<Integer>();
        stopScanQuiet();
        emit("SCAN_STOPPED");
        if (!partial.isEmpty()) {
            emit("SCANDONE " + join(partial));
        }
        setMute(false);
    }

    private void stopScanQuiet() {
        handler.removeCallbacks(scanTimeout);
        handler.removeCallbacks(seekStep);
        scanActive = false;
        seekScanActive = false;
        seekWaiting = false;
        releaseProgramList();
    }

    private void releaseProgramList() {
        if (programList != null) {
            try {
                if (scanListener != null) programList.removeOnCompleteListener(scanListener);
            } catch (Throwable ignored) {
            }
            try {
                programList.close();
            } catch (Throwable ignored) {
            }
            programList = null;
            scanListener = null;
        }
    }

    // --------------------------------------------------------------- audio

    private void setMute(boolean m) {
        muted = m;
        audioParams("FM_mute=" + (m ? 1 : 0));
        audioParams("FM_Volume=" + (m ? 0 : volume));
    }

    private void setVolume(int v) {
        volume = Math.max(0, Math.min(15, v));
        if (!muted && fmRouted) {
            audioParams("FM_Volume=" + volume);
        }
    }

    private void routeFm(boolean on) {
        fmRouted = on;
        try {
            if (unisoc == null) {
                log("routeFm: UnisocAudioManager недоступен — звук может не идти");
                return;
            }
            Method m = unisoc.getClass().getMethod("setDeviceConnectionStateForFM",
                    int.class, int.class, String.class, String.class);
            m.invoke(unisoc, DEVICE_OUT_FM, on ? 1 : 0, "", "");
            log("setDeviceConnectionStateForFM(FM, " + (on ? 1 : 0) + ")");
        } catch (Throwable t) {
            log("routeFm: " + describe(t));
        }
    }

    private void setSpeaker(boolean on) {
        speaker = on;
        if (!fmRouted) return;
        try {
            removePreferredMediaDevice();
        } catch (Throwable t) {
            log("removePreferredDevice: " + describe(t));
        }
        try {
            if (unisoc != null) {
                unisoc.getClass().getMethod("setMediaSpeakerOn", boolean.class).invoke(unisoc, on);
                log("setMediaSpeakerOn(" + on + ")");
            }
        } catch (Throwable t) {
            log("setMediaSpeakerOn: " + describe(t));
        }
    }

    private void removePreferredMediaDevice() throws Exception {
        if (!realContext) return;
        Object am = ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
        Class<?> strat = Class.forName("android.media.audiopolicy.AudioProductStrategy");
        List<?> all = (List<?>) strat.getMethod("getAudioProductStrategies").invoke(null);
        Method supports = strat.getMethod("supportsAudioAttributes", AudioAttributes.class);
        Method remove = am.getClass().getMethod("removePreferredDeviceForStrategy", strat);
        for (Object s : all) {
            if ((Boolean) supports.invoke(s, attrs)) {
                remove.invoke(am, s);
            }
        }
    }

    private void audioParams(String kv) {
        try {
            Class<?> as = Class.forName("android.media.AudioSystem");
            Object r = as.getMethod("setParameters", String.class).invoke(null, kv);
            log("AudioSystem.setParameters(" + kv + ") -> " + r);
        } catch (Throwable t) {
            log("AudioSystem.setParameters(" + kv + "): " + describe(t));
        }
    }

    // ------------------------------------------------------------- helpers

    private static boolean sysPropBool(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (Boolean) sp.getMethod("getBoolean", String.class, boolean.class).invoke(null, key, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static List<Integer> sortedCopy(List<Integer> l) {
        List<Integer> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    private static String join(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getClass().getSimpleName() + ": " + c.getMessage();
        return m.replace('\n', ' ').replace('\r', ' ');
    }

    private void log(String s) {
        emit("LOG " + s);
    }

    private synchronized void emit(String s) {
        out.println(s.replace('\n', ' ').replace('\r', ' '));
    }

    /** Запасной Context, если ActivityThread.systemMain() не взлетит. */
    private static final class FakeContext extends ContextWrapper {
        FakeContext() {
            super(null);
        }

        @Override
        public String getPackageName() {
            return "android";
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            ApplicationInfo ai = new ApplicationInfo();
            ai.packageName = "android";
            ai.targetSdkVersion = Build.VERSION.SDK_INT;
            return ai;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Object getSystemService(String name) {
            return null;
        }
    }
}
