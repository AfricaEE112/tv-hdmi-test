# Перенесення Android-застосунків на Android 7 (API 24)

Інструменти, якими UAPlay (minSdk 26) перероблено під телевізор з Android 7.0.
Самі APK сюди не кладуться.

1. `apktool d` розбирає APK; `scan.py` + `apidiff.py` знаходять посилання на API новіші за 24
   (база рівнів API: реліз `apk-tools`, файл `api-versions.xml.gz`).
2. `backport.py` генерує Java-шими (`ua.compat.C26`: на Android 8+ справжній виклик, на 7 запасна
   поведінка) і переписує smali, щоб виклики, поля й класи Android 8 ішли через шими.
   `special.py` містить точкові правки для конкретної збірки (імена класів після R8).
3. Шими компілюються `javac` + `d8 --min-api 24` (android.jar і d8.jar: реліз `apk-tools`),
   додаються як `classes2.dex`; minSdk 24, `mipmap-anydpi` -> `mipmap-anydpi-v26`,
   кореневі сертифікати (Let's Encrypt, GTS тощо) через network security config.
4. `finish.py` підписує v1 (Android 7 приймає) і вирівнює архів.
