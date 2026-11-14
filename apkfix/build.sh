#!/bin/bash
# Повна збірка UAPlay під Android 7 з оригінального розібраного smali
set -e
cd /home/claude/apkcheck
rm -rf full/smali && cp -r full_orig_backup/smali full/smali
python3 -I tools/special.py full/smali
python3 -I tools/backport.py full/smali missing26.json shim/src/ua/compat/C26.java
rm -rf shim/cls shim/out && mkdir -p shim/cls shim/out
javac -nowarn -source 8 -target 8 -bootclasspath tools/android.jar -encoding UTF-8 -d shim/cls shim/src/ua/compat/C26.java 2>&1 | grep -E "error" || true
java -cp tools/d8.jar com.android.tools.r8.D8 --release --min-api 24 --lib tools/android.jar --output shim/out shim/cls/ua/compat/*.class 2>&1 | grep -v JAVA_TOOL || true
java -jar tools/apktool.jar b full -o out/unsigned.apk --api-level 24 2>&1 | grep -E "Built|rror" 
python3 -I tools/finish.py out/unsigned.apk shim/out/classes.dex out/uaplay-android7.apk /home/claude/hdmi-test/signing/tv.keystore
jarsigner -verify out/uaplay-android7.apk 2>&1 | grep verified
