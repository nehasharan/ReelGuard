# ReelGuard ProGuard Rules
# Keep accessibility service
-keep class com.reelguard.app.service.ReelGuardAccessibilityService { *; }
-keep class com.reelguard.app.service.OverlayService { *; }

# Keep model classes (used with JSON parsing)
-keep class com.reelguard.app.model.** { *; }
