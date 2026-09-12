# SMRITI AQUA proguard rules.

# JNI entry points: native bridge methods on DspEngine are called from C++ by name.
-keep class com.smriti.aqua.dsp.DspEngine { *; }

# Memory models stored/queried by name.
-keep class com.smriti.aqua.memory.** { *; }
