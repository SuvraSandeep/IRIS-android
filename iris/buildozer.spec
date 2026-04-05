[app]
title         = IRIS
package.name  = iris
package.domain= dev.stg

source.dir    = .
source.include_exts = py,png,jpg,kv,atlas,txt,ttf

version = 0.7

requirements = python3,kivy==2.3.0,plyer

orientation = portrait

android.permissions = RECORD_AUDIO,INTERNET,READ_PHONE_STATE,CALL_PHONE,RECEIVE_BOOT_COMPLETED,BATTERY_STATS,FOREGROUND_SERVICE
android.api         = 33
android.minapi      = 21
android.ndk         = 25b
android.archs       = arm64-v8a
android.enable_androidx = True

[buildozer]
log_level = 2
warn_on_root = 1
