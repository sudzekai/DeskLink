# Запуск DeskLink

## Windows Agent

Нужны Windows 10/11 и .NET 10 SDK для сборки. Из корня репозитория:

```powershell
dotnet build windows/DeskLink.Windows.csproj -c Release
dotnet run --project windows/DeskLink.Windows.csproj
```

В разделе «Подключение» выберите доверенное устройство и перенесите его ID и ключ в настройки Android. Ключ хранится локально в `%LOCALAPPDATA%\DeskLink\settings.json`. Удаление устройства из списка немедленно отзывает его ключ. Окно можно закрыть: Agent остаётся в системном трее. Автозапуск включается там же.

Для USB tethering разрешите Agent входящие локальные TCP-соединения на порту 47652 и UDP-обнаружение на порту 47653 в брандмауэре Windows. Включите USB-модем на телефоне. Android сначала пробует TCP, затем Bluetooth. Если автоматическое обнаружение не проходит, укажите IP-адрес Windows вручную в настройках Android.

## Android

Нужны JDK 17 и Android SDK 36. Из каталога `android`:

```powershell
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Для тестирования по USB-отладке без включения USB-модема выполните:

```powershell
adb reverse tcp:47652 tcp:47652
```

При первом запуске откройте настройки DeskLink и введите ID и ключ доверенного устройства. Для Bluetooth сначала выполните сопряжение телефона и ПК в системных настройках, затем нажмите «Выбрать Bluetooth-устройство» в DeskLink и разрешите доступ к сопряжённым устройствам. На каждом запросе TCP имеет приоритет над Bluetooth.

Логи Android доступны через `adb logcat`; журнал ошибок Windows Agent находится в `%LOCALAPPDATA%\DeskLink\errors.log`.

## Ограничения платформы

Минимальная физическая частота дисплея выбирается из режимов, которые сообщает Android. Если 5–10 Гц нет, приложение выбирает ближайший доступный режим. Автоматический запуск приложения из фона при включении ПК ограничен Android; `setTurnScreenOn` действует при открытии Activity. Передача через USB tethering и Bluetooth требует разрешений/сопряжения и доступности соответствующего транспорта. Возможности медиа зависят от поддержки Windows Media Session самим проигрывателем.
