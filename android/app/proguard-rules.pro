# Settings and the cached forecast are stored as JSON through
# kotlinx.serialization. The library ships its own rules; these are the
# belt-and-braces ones from its README for the app's own classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class nl.laadmoment.core.**$$serializer { *; }
-keepclassmembers class nl.laadmoment.core.** {
    *** Companion;
}
-keepclasseswithmembers class nl.laadmoment.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
