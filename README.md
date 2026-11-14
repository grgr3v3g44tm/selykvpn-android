# Селюк VPN (Selyk VPN) & VK TURN Android Client 2.0

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-blue)](https://android.com)
[![Android TV](https://img.shields.io/badge/Android%20TV-Supported-emerald)](https://android.com/tv/)
[![VirusTotal](https://img.shields.io/badge/VirusTotal-0%2F70%20Clean-brightgreen)](https://www.virustotal.com/gui/file/539198bab87b2a66195e519a3ecf3c36a44a1aefcdd9a502223878a9c10a1127)

Официальный Android-клиент сетевого комплекса **Селюк VPN**, объединяющий два режима защиты в одном приложении:
1. **Обычный VPN**: Высокоскоростное ядро **Xray-core** (VLESS + Reality XHTTP / Vision) для максимальной скорости (до 1 Гбит/с), стриминга YouTube в 4K и свободного доступа к мировым сервисам.
2. **Свободный доступ (VK TURN / SRTP)**: Инновационный транспорт поверх официальных TURN-серверов ВКонтакте (UDP порт 56000), маскирующий трафик под WebRTC видеозвонки ВК для работы в условиях «белых списков» и глухих блокировок мобильных операторов.

---

## 🔒 Безопасность и открытость

- **100% Открытый исходный код**: Никаких скрытых трекеров, рекламы или сторонних сборщиков данных.
- **Стандартные системные разрешения**: Используется только стандартный Android `VpnService` для создания туннеля.
- **Встроенные базы GeoIP & GeoSite**: Полноценные актуальные базы маршрутизации `geoip.dat` и `geosite.dat` поставляются в комплекте с автоматическим фоновым обновлением.
- **Верификация VirusTotal (Релиз 2.0.9)**:
  - **arm64-v8a**: [VirusTotal Report (Clean)](https://www.virustotal.com/gui/file/539198bab87b2a66195e519a3ecf3c36a44a1aefcdd9a502223878a9c10a1127)  
    `SHA-256: 539198bab87b2a66195e519a3ecf3c36a44a1aefcdd9a502223878a9c10a1127`
  - **Universal**: [VirusTotal Report (Clean)](https://www.virustotal.com/gui/file/2d0004e607168b02c6077c525e8f2ca4fa3fcaf4b3dabb8cda1d74629e28b2a9)  
    `SHA-256: 2d0004e607168b02c6077c525e8f2ca4fa3fcaf4b3dabb8cda1d74629e28b2a9`

---

## 📺 Поддержка Android TV и Google TV

- Приложение полностью оптимизировано для умных телевизоров и ТВ-приставок (Xiaomi Mi Box, Яндекс ТВ, Сбер Салют, Sony BRAVIA, realme TV, Chromecast with Google TV).
- **Leanback Launcher**: Фирменный баннер на домашнем экране ТВ.
- **D-Pad навигация**: Управление всеми функциями с помощью обычного пульта.
- **Быстрый перенос по QR-коду**: Не нужно вводить длинную ссылку подписки с пульта — на экране ТВ отображается QR-код, который сканируется телефоном в 1 клик.

---

## 🛠 Технологический стек

- **UI**: Нативный Jetpack Compose, Material 3, навигация и системная тёмная тема.
- **Xray Core**: Скомпилированная библиотека `libxray.aar` (v25.10+) с поддержкой VLESS, Reality XHTTP, TCP/Vision.
- **VK TURN Core**: Скомпилированное Go-ядро `libwgturn.so` через Android JNI, реализующее маскированный WireGuard поверх WebRTC SRTP.
- **Системные службы**:
  - `SelykVpnService` — центральная служба туннелирования;
  - `VpnTileService` — шторка быстрых настроек (Quick Settings Tile);
  - `VKAuthActivity` — изолированная активность с аппаратным ускорением для стелс-звонков ВК.

---

## 📦 Сборка из исходников

Требования:
- JDK 17 или новее
- Android SDK (compileSdk 37, targetSdk 36, minSdk 24)

```bash
# Клонирование репозитория
git clone https://github.com/grgr3v3g44tm/selykvpn-android.git
cd selykvpn-android

# Сборка Release APK
./gradlew assembleDirectRelease

# Сборка Debug APK
./gradlew assembleDirectDebug
```

Собранные APK-файлы будут доступны в каталоге:
`app/build/outputs/apk/direct/release/`

---

## 📄 Лицензия

Проект распространяется под свободной лицензией MIT. Подробнее см. [LICENSE](LICENSE).
