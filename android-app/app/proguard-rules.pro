# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.namm.meteo.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.namm.meteo.**$$serializer { *; }
# Retrofit (interfaces suspend)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Widgets Glance : chaque widget doit garder SA classe. Sinon R8 fusionne les sous-classes
# (presque identiques) et Glance confond tous les widgets (tous affichent la même variante).
-keep class com.namm.meteo.widget.** extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class com.namm.meteo.widget.** extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
