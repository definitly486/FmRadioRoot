package android.hardware.radio;

import java.util.Map;

/** Compile-time stub. */
public abstract class RadioTuner {
    public static final int DIRECTION_UP = 0;
    public static final int DIRECTION_DOWN = 1;

    public abstract void close();

    public abstract int tune(int channel, int subChannel);

    public abstract int scan(int direction, boolean skipSubChannel);

    public abstract Map<String, String> setParameters(Map<String, String> parameters);

    public abstract ProgramList getDynamicProgramList(ProgramList.Filter filter);

    public abstract boolean hasControl();

    public abstract static class Callback {
        public Callback() { }

        public void onError(int status) { }

        public void onConfigurationChanged(RadioManager.BandConfig config) { }

        public void onProgramInfoChanged(RadioManager.ProgramInfo info) { }

        public void onMetadataChanged(RadioMetadata metadata) { }

        public void onControlChanged(boolean control) { }
    }
}
