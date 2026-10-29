package android.hardware.radio;

import java.util.List;

/** Compile-time stub. */
public final class ProgramList implements AutoCloseable {
    public List<RadioManager.ProgramInfo> toList() { throw new RuntimeException("stub"); }

    public void addOnCompleteListener(OnCompleteListener listener) { throw new RuntimeException("stub"); }

    public void removeOnCompleteListener(OnCompleteListener listener) { throw new RuntimeException("stub"); }

    @Override
    public void close() { throw new RuntimeException("stub"); }

    public interface OnCompleteListener {
        void onComplete();
    }

    public static final class Filter {
    }
}
