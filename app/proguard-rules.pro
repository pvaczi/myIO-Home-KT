# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.vaczi.myiohome.data.net.** {
    *** Companion;
}
-keepclasseswithmembers class org.vaczi.myiohome.data.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
