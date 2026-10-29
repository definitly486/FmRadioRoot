# FM Радио (root) для Redmi A5

Приложение для **аппаратного FM-радио** (Unisoc UMS9230, чип через драйвер `sprd_fm`).
Интернет-радио нет. Нужен root (Magisk / KernelSU / APatch) и проводные наушники (антенна).

## Как это работает

Родное `com.android.fmradio` (DreamFMRadio) ходит в системный сервис `broadcastradio`
через скрытый `RadioManager` — это разрешение `ACCESS_BROADCAST_RADIO`, доступное только
привилегированным приложениям. Поэтому здесь:

1. Приложение запускает через `su` процесс `dev.fmradio.root.FmHelper`
   (`app_process`, uid 0, код лежит в этом же APK).
2. Helper делает то же, что штатное приложение (всё сверено по его байткоду):
   - `RadioManager.openTuner(id, null, true, callback, handler)`
   - `tuner.tune(kHz, 0)`, `tuner.scan(dir, true)`, `tuner.getDynamicProgramList(null)` (автопоиск)
   - RDS: `setParameters({"sprdsetrds": "sprdrdson"})`, PS/RT из `onMetadataChanged`
   - звук: `AudioSystem.setParameters("FM_Volume=N")`, `"FM_mute=0|1"`
   - маршрут: `UnisocAudioManager.setDeviceConnectionStateForFM(0x100000, 1|0, "", "")`
   - динамик: `UnisocAudioManager.setMediaSpeakerOn(bool)`
3. Общение приложение ⇄ helper — текстовые строки через stdin/stdout.
   Если приложение умирает, helper видит EOF, сам выключает FM и выходит.

Диапазон 87.5–108.0 МГц, шаг 100 кГц (частоты в кГц, как в штатном приложении).

## Сборка

Нужен только GitHub: залейте проект в репозиторий — Actions (`.github/workflows/build.yml`)
соберёт `app-debug.apk`, он лежит в артефактах запуска.
Локально: `gradle :app:assembleDebug` (JDK 17, Android SDK 34).

Модуль `radio-stubs` — заглушки скрытых классов только для компиляции, в APK не попадает.

## Использование

1. Вставьте наушники, откройте приложение, нажмите **ВКЛ**, выдайте root в Magisk.
2. **Поиск станций** — автопоиск (список сохраняется). Нажатие на станцию — настроиться,
   долгое нажатие — в избранное.
3. `◀◀ / ▶▶` — переход к соседней станции, `−0.1 / +0.1` — шаг, ползунок — ручная настройка.
4. Громкость — это громкость медиа (`FM_Volume` берётся из неё).
5. Внизу — **журнал**: нажмите на него, чтобы скопировать. Если что-то не заработает,
   пришлите журнал — по нему видно, на каком шаге остановилось
   (`listModules`, `openTuner`, `AudioSystem.setParameters`, `setDeviceConnectionStateForFM` …).

## Если нет звука / не стартует

- В журнале `listModules status=... count=0` или `SecurityException` — helper не получил доступ к
  `broadcastradio` (проверьте, что `su` реально выдаёт uid 0 и SELinux не режет вызов).
- `UnisocAudioManager unavailable` — класс не найден в этой прошивке, маршрут FM не включится.
- HyperOS может убивать фоновые сервисы: выставьте приложению «Без ограничений» по батарее.
