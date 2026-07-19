# DJI MSDK V5 uses reflection internally for parts of its aircraft/product model
# hierarchy; keep the whole SDK package to avoid runtime ClassNotFoundExceptions
# in release builds that are painful to root-cause against a real drone.
-keep class dji.** { *; }
-keep class dji.v5.** { *; }
-dontwarn dji.**

-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
