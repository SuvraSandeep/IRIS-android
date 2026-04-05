[app]
title         = IRIS
package.name  = iris
package.domain= dev.stg

source.dir    = .
source.include_exts = py,png,jpg,kv,atlas,txt,ttf

version = 2.1

requirements = python3,kivy==2.2.1,pillow

orientation = portrait

android.permissions = INTERNET
android.api         = 33
android.minapi      = 21
android.ndk_api     = 21
android.ndk         = 25b
android.archs       = arm64-v8a
android.enable_androidx = True
android.gradle_dependencies = androidx.core:core:1.9.0

[buildozer]
log_level = 2
warn_on_root = 0
