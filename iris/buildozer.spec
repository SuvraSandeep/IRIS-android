[app]
title         = IRIS
package.name  = iris
package.domain= dev.stg

source.dir    = .
source.include_exts = py,png,jpg,kv,atlas,txt,gz,fst,int,conf,mdl

version = 0.2

requirements = python3,kivy==2.3.0,vosk,sounddevice,plyer

# Orientation
orientation = portrait

# Android
android.permissions = RECORD_AUDIO, INTERNET, READ_PHONE_STATE, CALL_PHONE, RECEIVE_BOOT_COMPLETED, BIND_NOTIFICATION_LISTENER_SERVICE, BATTERY_STATS, FOREGROUND_SERVICE
android.api         = 33
android.minapi      = 21
android.ndk         = 25b
android.archs       = arm64-v8a

# Keep model files
android.add_assets  = models/en-small:models/en-small

# Foreground service (keeps mic alive in background)
android.meta_data   = android.app.FOREGROUND_SERVICE_TYPE=microphone

android.logcat_filters = *:S python:D

[buildozer]
log_level = 2
warn_on_root = 1
