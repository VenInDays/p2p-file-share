# Add project specific ProGuard rules here.
-keep class com.p2pfileshare.app.** { *; }
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**
-dontwarn javax.servlet.**
