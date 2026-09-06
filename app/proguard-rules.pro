# EntryMobile alpha: keep WebView JavaScript bridge names.
-keepclassmembers class org.doubleduo.entrymobile.EntryBridge {
    @android.webkit.JavascriptInterface <methods>;
}
