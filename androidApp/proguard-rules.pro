# Release builds use R8 to drop unused code and resources, not to hide names.
# Readable class names keep crash logs useful and avoid surprises with code
# that looks types up by name.
-dontobfuscate

# Readium (3.4.0) ships no consumer rules. Its navigator talks to its own
# JavaScript through WebView bridges and restores fragments by class name,
# so keep the toolkit whole instead of guessing which parts are reached.
-keep class org.readium.** { *; }
-keep interface org.readium.** { *; }

# The PDF renderer's native code finds these classes and fields over JNI.
-keep class com.shockwave.pdfium.** { *; }
-keep class com.github.barteksc.pdfviewer.** { *; }

# Kotlin metadata and generic signatures are read by Readium's serializers.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
