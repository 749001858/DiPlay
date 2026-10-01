-dontoptimize
-dontobfuscate
-keep class local.airuize.receiver.** { *; }
-keepattributes InnerClasses,EnclosingMethod,Signature,Exceptions,SourceFile,LineNumberTable
# SDK18 EnumMap obtains enum constants by reflecting on values(). R8 full mode
# cannot infer that library-side access; removing it breaks JmDNS ServiceInfo.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# Unused standard-library/JCA adapters target newer Android versions; they must be removed by shrinking.
-dontwarn java.nio.file.**
-dontwarn java.util.function.**
-dontwarn java.util.stream.**
-dontwarn java.security.cert.PKIXRevocationChecker
-dontwarn java.lang.ClassValue
-dontwarn java.lang.invoke.**
-dontwarn javax.naming.**
-dontwarn org.jetbrains.annotations.**
