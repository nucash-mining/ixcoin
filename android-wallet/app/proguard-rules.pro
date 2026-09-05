# bitcoinj reflects over its protobuf wallet format and its network params.
-keep class org.bitcoinj.** { *; }
-keep class net.ixcoin.wallet.core.** { *; }
-dontwarn org.bitcoinj.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn com.google.common.**
-dontwarn org.bouncycastle.**

# logback.xml is an *asset*, so R8 cannot see that it names LogcatAppender by
# string and strips the class: the app then starts with no logging at all.
# Verified on device — release builds lost every app log line until these keeps
# were added, and a release build cannot be inspected with run-as, so logging is
# the only diagnostic channel there is.
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn ch.qos.logback.**
