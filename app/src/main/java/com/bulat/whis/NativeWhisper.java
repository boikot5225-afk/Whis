package com.bulat.whis;

public final class NativeWhisper {
    static {
        System.loadLibrary("whis");
    }

    private NativeWhisper() {}

    public interface ProgressCallback {
        void onProgress(int progress);
    }

    public static native long initContext(String modelPath);
    public static native void freeContext(long contextPtr);
    public static native int fullTranscribe(
            long contextPtr,
            int numThreads,
            float[] audioData,
            String language,
            boolean translate,
            ProgressCallback progressCallback
    );
    public static native int getSegmentCount(long contextPtr);
    public static native String getSegmentText(long contextPtr, int index);
    public static native long getSegmentT0(long contextPtr, int index);
    public static native long getSegmentT1(long contextPtr, int index);
    public static native String getSystemInfo();
    public static native String getTimings(long contextPtr);
    public static native String getModelInfo(long contextPtr);
}
