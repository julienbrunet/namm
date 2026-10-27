# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class fr.jbrunet.yama.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class fr.jbrunet.yama.**$$serializer { *; }
# Retrofit (interfaces suspend)
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Widgets Glance : chaque widget doit garder SA classe. Sinon R8 fusionne les sous-classes
# (presque identiques) et Glance confond tous les widgets (tous affichent la même variante).
-keep class fr.jbrunet.yama.widget.** extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class fr.jbrunet.yama.widget.** extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
