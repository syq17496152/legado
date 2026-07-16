@echo off
set JAVA_HOME=C:\Program Files\AdoptOpenJDK\jdk-17.0.0.20-hotspot
set ANDROID_HOME=F:\myself\github\WeAgentChat\temp\legado\temp\android-sdk
set GRADLE_USER_HOME=F:\gh
call gradlew.bat assembleAppDebug --no-daemon --no-build-cache -Dorg.gradle.vfs.watch=false
