package android.hardware.radio;

import android.os.Handler;

import java.util.List;

/** Compile-time stub. Сигнатуры взяты из байткода штатного com.android.fmradio. */
public class RadioManager {
    public static class ModuleProperties {
        public int getId() { throw new RuntimeException("stub"); }
    }

    public static class BandConfig {
    }

    public static class ProgramInfo {
        public int getChannel() { throw new RuntimeException("stub"); }
    }

    public int listModules(List<ModuleProperties> modules) { throw new RuntimeException("stub"); }

    public RadioTuner openTuner(int moduleId, BandConfig config, boolean withAudio,
                                RadioTuner.Callback callback, Handler handler) {
        throw new RuntimeException("stub");
    }
}
