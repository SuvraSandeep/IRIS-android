[app]
title         = IRIS
package.name  = iris
package.domain= dev.stg

source.dir    = .
source.include_exts = py,png,jpg,kv,atlas,txt,ttf

version = 2.0

requirements = python3,kivy==2.3.0,plyer

orientation = portrait

android.permissions = INTERNET
android.api         = 31
android.minapi      = 21
android.ndk_api     = 21
android.ndk         = 25b
android.archs       = arm64-v8a, armeabi-v7a
android.enable_androidx = True

[buildozer]
log_level = 2
warn_on_root = 0
