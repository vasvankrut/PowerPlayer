# PowerPlayer — контекст для продолжения работы

Этот файл — полный снимок состояния проекта. Читать в начале следующего сеанса, чтобы сразу продолжить работу, не тратя время на разведку.

---

## 1. Суть проекта

Чёрно-белый музыкальный плеер для Android (Kotlin + Jetpack Compose, minSdk 26 / target 34).
- Выбор папки с музыкой через SAF (Storage Access Framework, `ACTION_OPEN_DOCUMENT_TREE`), доступ переоткрывается при старте.
- **Собственный аудио-конвейер**: `MediaExtractor → MediaCodec → AudioTrack`. PCM-сэмплы в руках плеера — НЕ требуется ни одного разрешения (`MODIFY_AUDIO_SETTINGS`/`RECORD_AUDIO` запрещены пользователем).
- Визуализация «бегущая лента» (сейсмограф/кардиограмма как в Poweramp): звук пишется ОДНОЙ точкой на линии прогресса и уплывает влево в белую историю; справа от ползунка — серая плоская тишина.

## 2. GitHub и релизы

- Репозиторий: `https://github.com/vasvankrut/PowerPlayer` (ветка `main`).
- **Пуш ТОЛЬКО через** `https://x-access-token:${PAT}@github.com/vasvankrut/PowerPlayer.git` (PAT хранится локально, в репозиторий не коммитить — push protection блокирует).
- Релизы собираются ТОЛЬКО GitHub Actions (push в `main` запускает `.github/workflows/build.yml` → `assembleDebug` → создаёт/обновляет release + заливает APK).
- Раннер зелёный, если в релизе `gh release view <tag>` — ассеты: `PowerPlayer-v0.1.x.apk` + `app-debug.apk`.
- Проверка статуса раннера (пример):
  `curl -s -H "Authorization: Bearer $PAT" "https://api.github.com/repos/vasvankrut/PowerPlayer/actions/runs?per_page=1"`
- Список вышедших релизов: v0.1.0-alpha … v0.1.11 (история фиксов — в разделе 7).

## 3. Текущее состояние (актуально на v0.1.11)

**Работает:**
- Выбор/запоминание папки, сканирование MP3/FLAC, список, prev/play/next, перемотка по тапу и драгу по волне.
- Обложка на всю высоту с пре-блюром фона (не ч/б — цветной), текст трека снизу слева поверх градиента.
- Свой декодер, громкость (RMS) пишется в историю сэмплов.
- Визуализатор «бегущая лента»: неподвижная сетка слотов, ползунок едет слева направо.

**Последний коммит:** `71c45e5` = v0.1.11 (релиз `https://github.com/vasvankrut/PowerPlayer/releases/tag/v0.1.11`).

## 4. Визуализатор — как устроено сейчас (v0.1.11)

Файлы:
- `app/src/main/java/com/powerplayer/player/PlayerController.kt`
- `app/src/main/java/com/powerplayer/viewmodel/PlayerViewModel.kt`
- `app/src/main/java/com/powerplayer/ui/components/WaveVisualizer.kt`
- `app/src/main/java/com/powerplayer/ui/screens/PlayerScreen.kt`

**Цепочка данных:**
1. `PlayerController.appendPcm(pcm, info.presentationTimeUs)` — вызывается на каждый буфер из декодера. Считает RMS громкости, нормализует по бегущему пику (`energyPeak`, распад `*0.995f`), сглаживает (`energySmooth += (rms-energySmooth)*0.55f`), пишет:
   - `_energy.value = sqrt(energySmooth)` — живое значение 0..1 (для пульса ползунка);
   - `_energyPosMs.value = presentationTimeUs/1000L` — **позиция сэмпла берётся из `info.presentationTimeUs` декодера** (реальное аудио-время). ВАЖНО: не `currentPosition()` — он отстаёт из-за буферизации AudioTrack, из-за этого в v0.1.10 история «сжималась слева».
2. `PlayerViewModel` коллектит `player.energy` → в `_samples` (список `EnergySample(positionMs, value)`; одинаковые позиции заменяются). При `onSeekCommit` сэмплы обрезаются `positionMs < target - 700L`. При смене трека — `_samples.value = emptyList()`.
3. `PlayerScreen` читает `state.positionMs/durationMs` (польер каждые 250мс), `samples`, `energy` и передаёт в `WaveVisualizer`.

**`WaveVisualizer.kt` — алгоритм отрисовки (требования пользователя, не нарушать):**
- Сетка слотов НЕПОДВИЖНА на всю ширину: `barWidth = 3.5.dp`, `gap = 2.dp`, `count = (width / (barWidth+gap)).toInt().coerceIn(8,512)`.
- `currentIndex = (count * progressFraction).toInt().coerceIn(0, count-1)` — позиция ползунка, едет слева направо.
- `history = samples.filter { it.positionMs <= currentMs }`, где `currentMs = durationMs * progressFraction` — НЕ сыгранные сэмплы исключены.
- В цикле `for (i in 0 until count)`, `x = i * (barWidth+gap)`:
  - `i < currentIndex` — белая (`Color.White`, история alpha 0.92) зафиксированная высота = `peakInWindow(lo,hi) * maxAmp` (бинарный поиск по `history`, окно `[i*tpb,(i+1)*tpb)`, tpb = durationMs/count);
  - `i == currentIndex` — полоска пульсирует от `liveEnergy` (параметр `liveEnergy`), цвет чистый белый + мягкое свечение (3x ширина, alpha 0.25);
  - `i > currentIndex` — **высота ВСЕГДА 0** (серая `Color(0xFF9E9E9E)` alpha 0.38, мин. 1.5px «точки») — плоская тишина до конца экрана;
- Тап = seek в точку, драг = превью/коммит (обработчики в этом же Canvas).

**Жёсткие требования пользователя (история всех итераций):**
- НЕ анимировать полоски вверх-вниз на фиксированных местах. FFT-спектр (v0.1.8) и статичная «волна с цветовым разделением» — отвергнуты.
- Справа от ползунка НИЧЕГО не прыгает — плоская серая линия/точки 1-2px.
- Слева — ярко-белая история, полоски 3-4px с зазорами 2px, не сливаться в «расчёску».
- Ползунок обязан плавно ехать вправо по мере воспроизведения.
- Кнопки ◀/Play/▶ поверх визуализатора, прозрачные, без подложек.
- Обложка снизу слева, папка запоминается, пре-блюр, цветной фон (не ч/б), размытие усилено — всё это уже одобрено.

**Известные остаточные риски:**
- `presentationTimeUs` от кодеков может быть не от 0 (обрезка: `_energyPosMs` пока не клампится в duration — проверить на реальных файлах).
- Позиция ползунка (`state.positionMs` из `playbackHeadPosition`) и позиция сэмплов (`presentationTimeUs`) — две независимые шкалы; на практике совпадают, но при буферизации может расходиться на ~0.5с.
- Сэмплы копятся весь трек (каждый буфер ≈ 20-25мс) — для 4-минутного трека ~10k сэмплов; `peakInWindow` (бинарный поиск) вызывается на каждый слот (~count раз за кадр) — потенциально дорого, при лагах добавить downsampling в ViewModel.

## 5. Архитектура и ключевые места кода

```
app/src/main/java/com/powerplayer/
  MainActivity.kt            — старт, SAF-выбор папки (ActivityResultContracts.OpenDocumentTree), передача Uri в VM
  player/PlayerController.kt — свой конвейер MediaExtractor→MediaCodec→AudioTrack
      play(uri, onPrepared)      — stopInternal, сброс состояния, decodeThread
      decodeLoop()               — ввод/вывод буферов, пауза-петля, maybeSeek, onCompletion
      maybeSeek()                — ex.seekTo + cd.flush + at.flush, сброс seekBase*/energyPeak
      appendPcm(pcm, ptUs)       — RMS → _energy + _energyPosMs (см. раздел 4)
      currentPosition()          — seekBaseMs + (playbackHeadPosition-seekBaseFrames)*1000/sampleRate
  viewmodel/PlayerViewModel.kt — PlayerUiState, EnergySample, _samples, poller 250мс, seek-логика
  ui/components/WaveVisualizer.kt — Canvas, бегущая лента (раздел 4)
  ui/screens/PlayerScreen.kt   — обложка+текст, WaveVisualizer, кнопки поверх, таймкоды
  ui/CoverFx.kt                — пре-блюр фона
  ui/theme/Color.kt            — Black/White/WhiteDim(0x80)/WhiteFaint(0x33)/Error
  data/TrackScanner.kt         — рекурсивный обход SAF, MP3/FLAC/WAV/OGG/AAC/M4A
  data/AlbumArt.kt             — обложка из файла (ищем *.jpg/*.png рядом) — var parentUri не используется (warning)
  data/Track.kt, data/FolderPrefs.kt — модель + сохранение папки (SharedPreferences)
```

- Версии: `app/build.gradle.kts` — compileSdk 34, kotlinCompilerExtensionVersion 1.5.6, compose-bom 2023.10.01, material-icons-extended.
- Локальный SDK: `/opt/android-sdk` (platforms/android-34, build-tools 34.0.0), `local.properties` уже есть (`sdk.dir=/opt/android-sdk`).
- Локальная сборка: `./gradlew assembleDebug` — долгая (первый прогон до ~16 мин; пользователь обычно прерывает — тогда полагаемся на GitHub Actions). Установка: `adb install app/build/outputs/apk/debug/app-debug.apk`.

## 6. API-ключи / секреты

- GitHub PAT (пользователя vasvankrut, scopes: repo) — см. раздел 2. Засвечен, пользователь предупреждён об отзыве; если перестанет работать — спросить новый.
- Других ключей нет (приложение не использует сетевые API).
- В workflow используется только `secrets.GITHUB_TOKEN` (автоматический, не хранить вручную).

## 7. История версий (что и когда менялось)

| Версия | Суть |
|---|---|
| v0.1.0-alpha | первый релиз, базовый ч/б плеер |
| v0.1.3 | добавлен `MODIFY_AUDIO_SETTINGS` |
| v0.1.4 | добавлен `RECORD_AUDIO` — **пользователь потребовал убрать** |
| v0.1.5 | своя статичная волна из MediaCodec |
| v0.1.6 | живая амплитуда на своём конвейере |
| v0.1.7 | починка отрицательной ширины баров + скруглённые плотные бары + серый несыгранный слой |
| v0.1.8 | FFT-спектр, 96 баров (позже отвергнут пользователем) |
| v0.1.9 | «бегущая лента»: громкость одной точкой у ползунка, история слева, тишина справа |
| v0.1.10 | фикс «забора»: будущее справа всегда 0; история фильтруется по `positionMs <= currentMs` |
| v0.1.11 | фикс «сжатия слева»: позиция сэмпла = `info.presentationTimeUs`; неподвижная сетка слотов; `currentIndex = count*progressFraction`; пульс от `liveEnergy`; APK в релизе = `PowerPlayer-v0.1.11.apk` |

**Что обычно ждёт пользователь от релиза:** закоммитить (`git add -A && git commit -m "vX.Y.Z: ..."`), запушить через URL с PAT, дождаться зелёного раннера, проверить ассеты релиза, сообщить ссылку.

## 8. Правила работы с пользователем

- Пользователь пишет по-русски, кратко, императивно («делай»).
- Он сам разворачивает APK на устройстве и смотрит результат — обратную связь даёт в следующем сообщении (часто «снова сломалось»).
- Чувствителен к деталям визуала: «полоски не прыгают», «не сливается в расчёску», «справа тишина», «ползунок едет вправо». НЕ отклоняться от этих требований без явного разрешения.
- Если что-то неясно — краткий вопрос/предложение вместо самовольных решений.

## 9. На что смотреть при «сломалось»

1. Соблюдены ли требования раздела 4 (прыжки/сжатие/пульс/серые будущие слоты).
2. Правильность позиции сэмплов: `_energyPosMs` берётся из `presentationTimeUs` (а не `currentPosition()`).
3. Не разорвана ли цепочка `energy → samples → WaveVisualizer` (имена параметров/полей в PlayerScreen).
4. Не сбиты ли `versionCode`/`versionName` и тэг в workflow (должны совпадать).
5. Зелёный ли раннер (раздел 2).
