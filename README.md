# Домівка: лаунчер для телевізора Bravis

Легкий головний екран для Bravis LED-32G5000 Smart+T2
(плата CVTE, MediaTek, Android 7, ~800 МБ пам'яті).

## Завантажити

- **Домівка (лаунчер):** https://github.com/AfricaEE112/tv-hdmi-test/releases/download/latest/domivka.apk
- HDMI тест (діагностика): https://github.com/AfricaEE112/tv-hdmi-test/releases/download/latest/hdmi-test.apk
- Інструменти для ПК (вимкнути мотлох виробника через ADB): https://github.com/AfricaEE112/tv-hdmi-test/releases/download/latest/tv-tools.zip
  (інструкція всередині, `README.txt`)

APK збирає GitHub Actions при кожному push у `main`. Усі збірки підписані одним
ключем (`signing/`), тому нові версії ставляться поверх старих.

## Що вміє Домівка

- плитка **«Консоль»**: одним натисканням відкриває HDMI з консоллю на весь екран;
- **автоперемикання**: коли консоль вмикається, телевізор сам переходить на неї;
- **Головне**: закріплені застосунки (SmartTube/YouTube, браузер тощо);
- **Джерела**: HDMI 1–3 і телебачення, зі станом «підключено»;
- **Застосунки**: усі застосунки, частіше вживані першими;
- **Menu** або довге натискання: закріпити, посунути, сховати, видалити;
- годинник, дата і привітання українською.

## Як HDMI працює на цій прошивці

Стандартний TvView Android на цій прошивці відео не показує. Тому Домівка, як і штатний
лаунчер, перемикає джерело через сервіс прошивки `com.cvte.tv.api.impl` і відкриває
рідний повноекранний ТВ-екран `com.cvte.tv.androidsetting/.TvSettingActivity`.
Спосіб узято з MIT-проєкту
[cvte-android-tv-launcher](https://github.com/Koqpe/cvte-android-tv-launcher).

## Модулі

- `launcher/`: Домівка;
- `app/`: HDMI тест (список входів, коди кнопок пульта, діагностика).
