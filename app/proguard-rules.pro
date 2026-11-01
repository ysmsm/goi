# Pleos SDK는 AIDL·리플렉션으로 서비스와 통신하므로 난독화하지 않는다
-keep class ai.pleos.** { *; }
-dontwarn ai.pleos.**
-keep class android.car.** { *; }
-dontwarn android.car.**
