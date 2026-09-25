# kotlinx.serialization keeps what it needs through its own rules; Room and
# WorkManager ship theirs. Nothing reflective of our own needs keeping.
-dontwarn org.slf4j.**
# Ktor's debug detector and OkHttp's optional TLS providers reference classes
# Android does not have; neither path is taken on a phone.
-dontwarn java.lang.management.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
