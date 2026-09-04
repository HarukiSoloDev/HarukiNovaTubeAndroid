# NewPipe Extractor / Rhino rules
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Keep Haruki worker names stable for WorkManager.
-keep class com.harukisolodev.harukistream.download.** { *; }
