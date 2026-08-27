# Нативные методы регистрируются по имени класса из JNI_OnLoad —
# переименование этих классов сломает загрузку библиотеки.
-keep class com.tupicgames.takt.engine.analysis.NativeAnalyzer { *; }
-keep class com.tupicgames.takt.engine.audio.NativeClickEngine { *; }
-keepclasseswithmembernames class * { native <methods>; }
