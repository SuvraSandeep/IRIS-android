[app]
title         = IRIS
package.name  = iris
package.domain= dev.stg

source.dir    = .
source.include_exts = py,png,jpg,kv,atlas,txt,ttf

version = 1.0

requirements = python3,kivy==2.3.0,plyer

orientation = portrait

android.permissions = INTERNET, WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE
android.api         = 33
android.minapi      = 21
android.ndk         = 25b
android.archs       = arm64-v8a
android.enable_androidx = True

[buildozer]
log_level = 2
warn_on_root = 1
