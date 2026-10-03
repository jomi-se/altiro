-keep class org.altiro.inference.NativeWhisper { *; }
-keep interface org.altiro.inference.NativeProgress { *; }
-keepclassmembers class * implements org.altiro.inference.NativeProgress {
    public void onProgress(int);
}
