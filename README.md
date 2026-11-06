# VK TURN Proxy Android Client

[![VirusTotal](https://img.shields.io/badge/VirusTotal-0%2F70%20Detections-brightgreen)](https://www.virustotal.com/gui/file/2d47f99b682b264f6b9cdeed41952afc85065d745d323392c512089dcd234aba)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-blue)](https://android.com)

Официальный Android-клиент для туннелирования трафика через протокол **VK TURN** (маскировка сетевого трафика под WebRTC видеозвонки ВКонтакте). Разработан специально для устойчивой работы в условиях жестких блокировок операторов и «белых списков».

---

## 🔒 Безопасность и прозрачность

- **100% Открытый исходный код**: Никаких скрытых трекеров, рекламы, сборщиков аналитики или сторонних вредоносных модулей.
- **Минимальные системные разрешения**: Приложение запрашивает исключительно стандартное системное разрешение Android `VpnService` для создания защищённого сетевого интерфейса.
- **Независимая проверка VirusTotal**:
  - `vkturn-arm64-v8a.apk`: [Отчёт VirusTotal (0 детектов из 70+ антивирусов)](https://www.virustotal.com/gui/file/2d47f99b682b264f6b9cdeed41952afc85065d745d323392c512089dcd234aba)  
    `SHA-256: 2d47f99b682b264f6b9cdeed41952afc85065d745d323392c512089dcd234aba`
  - `vkturn-universal.apk`: [Отчёт VirusTotal (0 детектов из 70+ антивирусов)](https://www.virustotal.com/gui/file/b0471a8c6dd4acd2bf5ba5a1011bdfa9a7c61b05ff063bb04480e13f3df24b02)  
    `SHA-256: b0471a8c6dd4acd2bf5ba5a1011bdfa9a7c61b05ff063bb04480e13f3df24b02`

---

## 🛠 Технологический стек

1. **Пользовательский интерфейс**: Нативный **Jetpack Compose**, Material 3, архитектура MVI/MVVM.
2. **Сетевое ядро**: Нативное скомпилированное Go-ядро (`libwgturn.so` через Android NDK / JNI), реализующее обфусцированный транспорт WireGuard поверх WebRTC TURN / STUN протокола VKontakte (порт 56000, SRTP).
3. **Системная интеграция**:
   - `VkTurnService` на базе Android `VpnService`.
   - `VkTurnTileService` — Quick Settings тайл для мгновенного включения туннеля из системной шторки Android.
   - Изолированная активность `VKAuthActivity` с аппаратным ускорением для безопасной авторизации.

---

## 📦 Сборка из исходников

Для самостоятельной сборки приложения требуются:
- **JDK 17** или новее
- **Android SDK** (API 34, Build Tools 34.0.0)
- **Go 1.22+** (если требуется пересобрать ядро `core-go/`)

### Сборка Release APK:

```bash
# Клонирование репозитория
git clone https://github.com/maximyt288/vkturn-android.git
cd vkturn-android

# Сборка Release APK
./gradlew assembleRelease
```

Собранные APK-файлы будут доступны в каталоге:
`app/build/outputs/apk/release/`

---

## 📄 Лицензия

Проект распространяется под открытой лицензией MIT.
