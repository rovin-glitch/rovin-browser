package com.igalia.wolvic;

public final class RovinProduct {
    private RovinProduct() {}

    public static boolean isRuntime() {
        return BuildConfig.ROVIN_RUNTIME;
    }

    public static boolean shouldFetchRemoteContent() {
        return !isRuntime();
    }

    public static boolean shouldInitializeSpeechRecognizer() {
        return !isRuntime();
    }

    public static boolean shouldInitializeGeolocation() {
        return !isRuntime();
    }

    public static boolean shouldShowWhatsNew() {
        return !isRuntime();
    }

    public static boolean shouldShowHelpAndFeedback() {
        return !isRuntime();
    }

    public static boolean shouldShowEnvironmentSettings() {
        return !isRuntime();
    }

    public static boolean shouldShowVoiceSearchSettings() {
        return !isRuntime();
    }
}
