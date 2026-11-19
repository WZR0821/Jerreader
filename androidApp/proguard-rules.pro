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

# ML Kit (PDF text recognition, on-device translation) finds its parts the
# Firebase way: registrar class names come from manifest metadata and each is
# created through its no-argument constructor. firebase-components 16.1.0
# keeps those classes but not the constructors, and R8 full mode then drops
# them, so 1.7.0–1.7.1 Release builds could not create any ML Kit client
# (every PDF showed an error instead of its pages).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Kotlin metadata and generic signatures are read by Readium's serializers.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
