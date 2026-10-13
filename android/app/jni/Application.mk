# Both ABIs go into the same APK (Android picks the right one per device).
# Keep this in sync with abiFilters in app/build.gradle.
APP_ABI := arm64-v8a armeabi-v7a
APP_PLATFORM := android-21
APP_CFLAGS := -Wno-error

# Temporary debug build: run gameplay on the real byte-accurate CPU-emulated
# interpreter instead of the decompiled C logic, to test whether a bug seen
# only on-device lives in the decompile. Revert after diagnosis.
APP_CFLAGS += -DFORCE_RM_THEIRS_DEBUG
