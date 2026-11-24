package com.amresalehin.emregallery.data.ai;

/** JNI boundary for the app-private llama.cpp + mtmd runtime. */
public final class NativeVisionEngine {
    static {
        System.loadLibrary("eye-llama");
    }

    private NativeVisionEngine() {}

    public static native void loadModel(
            String modelPath,
            String projectorPath,
            int contextSize,
            int threads
    );

    public static native String generate(
            byte[][] imageFrames,
            String prompt,
            int maxTokens
    );

    public static native void cancel();

    public static native void close();
}
