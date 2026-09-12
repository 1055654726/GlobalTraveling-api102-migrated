# GlobalTraveling - API 102 migration notes

This version targets libxposed Modern API 102 and removes the legacy Xposed API dependency.

## Migration changes

- `compileOnly 'io.github.libxposed:api:102.0.0'`
- Java entry point moved to `META-INF/xposed/java_init.list`
- Added `META-INF/xposed/module.prop`
- Removed legacy Xposed manifest metadata and `assets/xposed_init`
- `HookMain` now extends `io.github.libxposed.api.XposedModule`
- Hook callbacks use the API 102 interceptor chain
- `Chain.getArgs()` is treated as immutable; `proceed(Object...)` is used when changing arguments
- `LocationListener` hooks are de-duplicated per `Method`
- Listener method lookup also searches implemented interfaces
- `MainActivity` keeps the saved instance state for `MapView` and removes a redundant `setContentView()` call
- Java 17 compatibility is explicitly declared for the Android module

## Important runtime note

The project can be compiled with a normal Android Studio/Gradle environment that has access to Maven Central and the Android SDK. The current execution environment does not have the Gradle 9.1 distribution and Android/Maven dependencies cached, so a full APK build could not be executed here.
