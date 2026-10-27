# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class fr.jbrunet.yama.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fr.jbrunet.yama.**$$serializer { *; }
# Retrofit (suspend interfaces)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Glance widgets: each widget must keep ITS OWN class. Otherwise R8 merges the (nearly identical)
# subclasses and Glance mixes up all widgets (they all show the same variant).
-keep class fr.jbrunet.yama.widget.** extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class fr.jbrunet.yama.widget.** extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
