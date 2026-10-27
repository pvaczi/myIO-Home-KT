# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.vaczi.myiohome.data.net.** {
    *** Companion;
    <init>(...);   # AGP 9 strict keep rules: a class keep no longer keeps constructors
}
-keepclasseswithmembers class org.vaczi.myiohome.data.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
