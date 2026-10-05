# PowerPlayer — контекст для продолжения работы

Этот файл — полный снимок состояния проекта. Читать в начале следующего сеанса, чтобы сразу продолжить работу, не тратя время на разведку.

---

## 1. Суть проекта

Чёрно-белый музыкальный плеер для Android (Kotlin + Jetpack Compose, minSdk 26 / target 34).
- Выбор папки с музыкой через SAF (Storage Access Framework, `ACTION_OPEN_DOCUMENT_TREE`), доступ переоткрывается при старте.
- **Собственный аудио-конвейер**: `MediaExtractor → MediaCodec → AudioTrack`. PCM-сэмплы в руках плеера — НЕ требуется ни одного разрешения (`MODIFY_AUDIO_SETTINGS`/`RECORD_AUDIO` запрещены пользователем).
- Визуализация как в Poweramp: волна **всего трека** на всю ширину экрана (считается headless-анализом MediaCodec), симметричная относительно центра; сыгранное — белое, непроигранное — серое, тонкий белый playhead, тап/драг в любой точке = перемотка.

## 2. GitHub и релизы

- Репозиторий: `https://github.com/vasvankrut/PowerPlayer` (ветка `main`).
- **Пуш ТОЛЬКО через** `https://x-access-token:${PAT}@github.com/vasvankrut/PowerPlayer.git` (PAT хранится локально, в репозиторий не коммитить — push protection блокирует). Пушить разовым URL (`git push https://x-access-token:... main`) безопаснее, чем менять remote: токен не остаётся в `.git/config`.
- Релизы собираются ТОЛЬКО GitHub Actions (push в `main` запускает `.github/workflows/build.yml` → `assembleDebug` → создаёт/обновляет release + заливает APK).
- Раннер зелёный, если в релизе `gh release view <tag>` — ассеты: `PowerPlayer-v0.1.x.apk` + `app-debug.apk`.
- Проверка статуса раннера (пример):
  `curl -s -H "Authorization: Bearer $PAT" "https://api.github.com/repos/vasvankrut/PowerPlayer/actions/runs?per_page=1"`
- Список вышедших релизов: v0.1.0-alpha … v0.1.16 (история фиксов — в разделе 7).

## 3. Текущее состояние (актуально на v0.1.16)

**Работает:**
- Выбор/запоминание папки, сканирование MP3/FLAC, список, prev/play/next, перемотка по тапу и драгу по волне.
- Обложка на всю высоту верхней половины с пре-блюром фона (не ч/б — цветной), текст трека снизу слева поверх градиента.
- Свой декодер; живая громкость (RMS) идёт в свечение линии playhead.
- Визуализатор Poweramp-стиля: **движущаяся** волна скроллится влево под неподвижной линией.

**Последний коммит:** `v0.1.16` — v0.1.15 сделал «Static Waveseek» (вся песня зумом-аут), но пользователь требует дефолтный движущийся Waveseek: бары едут, линия стоит.

## 4. Визуализатор — как устроено сейчас (v0.1.16)

Файлы:
- `app/src/main/java/com/powerplayer/player/PlayerController.kt`
- `app/src/main/java/com/powerplayer/viewmodel/PlayerViewModel.kt`
- `app/src/main/java/com/powerplayer/ui/components/WaveVisualizer.kt`
- `app/src/main/java/com/powerplayer/ui/screens/PlayerScreen.kt`

**Цепочка данных:**
1. `PlayerController.analyzeTrack(context, uri, bucketMs = 80L)` — **headless-анализ всего трека** (MediaExtractor + MediaCodec БЕЗ AudioTrack) в момент `playTrack`. Считает в каждом бакете **максимум модуля сэмпла** (`maxAbs`), нормализует по глобальному пику и слегка поднимает тихие участки: `value = (peak/globalPeak).pow(0.85f)`. Возвращает `List<EnergySample>`, отсортированный по `positionMs` (шаг 80мс, ~3000 точек на 4-минутный трек). Считается в `Dispatchers.Default`, результат кладётся в `_samples`.
2. `PlayerController.appendPcm(pcm, presentationTimeUs)` — на каждом буфере основного конвейера считает RMS, нормализует по бегущему пику, сглаживает и публикует `_energy.value = sqrt(energySmooth)` (0..1). Это **только** живое свечение playhead, в `_samples` оно больше не пишется.
3. `PlayerViewModel` держит `_samples` (пусто при старте трека, затем результат анализа), `energy`, и `poller` каждые 250мс пишет `positionMs = player.currentPosition()`.
4. `PlayerScreen` читает `state.positionMs/durationMs`, `samples`, `energy` и передаёт в `WaveVisualizer`. Полоса волны — `fillMaxWidth().height(112.dp).padding(horizontal = 20.dp)`, под ней ряд кнопок ◀/Play/▶, под ним таймкоды.

**`WaveVisualizer.kt` — алгоритм отрисовки (текущий):**
- **Волна движется под неподвижной линией** (дефолтный Waveseek в Poweramp). Окно времени длиной `WINDOW_MS = 8с` едет вправо по мере воспроизведения → бары уезжают влево. Линия стоит на месте: `PLAYHEAD_FRAC = 0.75f`, то есть 6с яркой истории слева и 2с серого будущего справа.
- Окно считается так, что линия попадает ровно в текущую позицию: `windowStartMs(f) = duration*f - PLAYHEAD_FRAC*WINDOW_MS`.
- Сетка: `BAR_WIDTH_DP = 3f`, `BAR_GAP_DP = 1.5f`, `count = (width / (barWidth+gap)).toInt().coerceIn(1,512)`; шаг пересчитывается как `width / count`, чтобы сетка заканчивалась ровно у правого края (без «забора» из неполного бара).
- Бар `i`: окно времени `[msStart, msStart+msPerBar]` → пик по бинарному поиску в `samples` (headless-анализ всего трека). Высота `h = (peak*maxAmp).coerceIn(2.dp, maxAmp)`, рисуется зеркально: `top = centerY - h`, размер `h*2`.
- Цвет: `pastColor = White@0.95` (левее линии), `futureColor = White@0.30` (правее). Бар под линией разрезается на две части точно по `playheadX` — граница цвета пиксельная. За пределами трека — плоские точки `outsideColor = White@0.16` высотой 2dp.
- Линия: белая 1.5dp на всю высоту + свечение 7dp, альфа следует за `liveEnergy` (`0.05 + 0.16*energy`). Сама линия неподвижна.
- Плавность хода: `animateFloatAsState(progressFraction, tween(260ms, LinearEasing))` — 250мс-обновления поллера превращаются в непрерывное скольжение. Во время драга анимация отключается (`isDragging`), окно идёт ровно за пальцем.
- Тап = перемотка в точку под пальцем; драг = относительный сдвиг от точки захвата (`fractionFromDrag`), без накопления ошибки.
- Пока `samples` пуст (анализ ещё идёт) — тонкая серая линия 2dp + неподвижная линия playhead.

**Жёсткие требования пользователя (текущие, не нарушать):**
- **ВОЛНА НЕ ДОЛЖНА БЫТЬ СТАТИЧНОЙ** — это ключевое требование. Бары обязаны ехать влево под неподвижной линией (дефолтный Waveseek). «Static Waveseek» с зумом-аутом всей песни пользователь отверг (v0.1.15).
- Высоты баров НЕ анимируются и столбец под линией не «прыгает»: двигается только таймлайн. Анимируется лишь свечение линии.
- Никакого FFT-спектра (v0.1.8) и никакой высоты, зависящей от экранной позиции бара.
- Слева ярко-белое, справа серое, граница — ровно по линии.
- Полоса низкая и широкая (пропорции Poweramp).
- Обложка сверху с пре-блюром цветного фона, текст снизу слева — одобрено и не трогается.

**Справка по Poweramp (проверено по официальному KB и обсуждениям):**
- Официальная справка: «The thin bars behind the control buttons are called the Waveseek area which you can drag left or right to make adjustments to the playback position within the track».
- У Poweramp два режима Waveseek: **обычный** — бары движутся за статичной линией; **Static Waveseek** — вся волна зумом-аут, по ней едет линия прогресса. Пользователь хочет первый.
- Отдельная «Visualization» (анимированный визуализатор/пресеты .milk) — это другой элемент, полноэкранный фон; в проекте не используется.

**Известные остаточные риски:**
- `analyzeTrack` декодирует трек целиком при каждом переключении — на длинных треках заметная задержка до появления волны (показывается тонкая линия). Кэш по URI ещё не добавлен.
- `presentationTimeUs` от кодеков может быть не от 0 — если у части файлов время стартует не с нуля, бакеты сместятся (лечится вычитанием минимального `presentationTimeUs` в анализе).
- При паузе `currentPosition()` замирает, поэтому волна тоже замирает — это ожидаемо.

## 5. Архитектура и ключевые места кода

```
app/src/main/java/com/powerplayer/
  MainActivity.kt            — старт, SAF-выбор папки (ActivityResultContracts.OpenDocumentTree), передача Uri в VM
  player/PlayerController.kt — свой конвейер MediaExtractor→MediaCodec→AudioTrack
      play(uri, onPrepared)      — stopInternal, сброс состояния, decodeThread
      decodeLoop()               — ввод/вывод буферов, пауза-петля, maybeSeek, onCompletion
      maybeSeek()                — ex.seekTo + cd.flush + at.flush, сброс seekBase*/energyPeak
      appendPcm(pcm, ptUs)       — RMS → _energy (только свечение playhead)
      currentPosition()          — seekBaseMs + (playbackHeadPosition-seekBaseFrames)*1000/sampleRate
      analyzeTrack(ctx, uri)     — companion: headless-анализ всего трека по пикам (см. раздел 4)
  viewmodel/PlayerViewModel.kt — PlayerUiState, EnergySample, _samples, poller 250мс, seek-логика
  ui/components/WaveVisualizer.kt — Canvas, полнотрековая волна Poweramp (раздел 4)
  ui/screens/PlayerScreen.kt   — обложка+текст, WaveVisualizer, ряд кнопок под волной, таймкоды
  ui/CoverFx.kt                — пре-блюр фона
  ui/theme/Color.kt            — Black/White/WhiteDim(0x80)/WhiteFaint(0x33)/Error
  data/TrackScanner.kt         — рекурсивный обход SAF, MP3/FLAC/WAV/OGG/AAC/M4A
  data/AlbumArt.kt             — обложка из файла (ищем *.jpg/*.png рядом) — var parentUri не используется (warning)
  data/Track.kt, data/FolderPrefs.kt — модель + сохранение папки (SharedPreferences)
```

- Версии: `app/build.gradle.kts` — compileSdk 34, kotlinCompilerExtensionVersion 1.5.6, compose-bom 2023.10.01, material-icons-extended.
- **Локальной сборки на этой машине нет**: Android SDK не установлен, `local.properties` отсутствует (в репозитории его и не должно быть). Сборка и релиз — только через GitHub Actions.
- Установка на устройство: `adb install app/build/outputs/apk/debug/app-debug.apk` (APK качать из ассетов релиза).

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
| v0.1.11 | фикс «сжатия слева»: позиция сэмпла = `info.presentationTimeUs`; неподвижная сетка слотов; `currentIndex = count*progressFraction`; пульс от `liveEnergy` |
| v0.1.12 | фикс сжатия слева + серые точки вне трека (1.5dp) |
| v0.1.13 | headless-анализ всего трека → видна форма песни целиком; починен `StrokeCap`-импорт |
| v0.1.14 | сейсмограф-окно 6с назад + 0.5с вперёд, волна едет под неподвижной иголкой; починен `pointerInput size.width` (Int→Float) |
| **v0.1.15** | «Static Waveseek»: вся песня зумом-аут, тонкие бары 3dp+1.5dp, шаг `width/count`, разрез бара по playhead, анализ по пику амплитуды вместо RMS + кривая 0.85, полоса 112dp, кнопки под волной — **отвергнуто: волна статичная** |
| **v0.1.16** | **дефолтный движущийся Waveseek Poweramp**: окно 6с назад + 2с вперёд едет по треку, бары скроллятся влево под НЕПОДВИЖНОЙ линией на 25% ширины, реальная форма песни из headless-анализа, непрерывный ход через tween 260мс |

**Что обычно ждёт пользователь от релиза:** закоммитить (`git add -A && git commit -m "vX.Y.Z: ..."`), запушить через URL с PAT, дождаться зелёного раннера, проверить ассеты релиза, сообщить ссылку.

## 8. Правила работы с пользователем

- Пользователь пишет по-русски, кратко, императивно («делай»).
- Он сам разворачивает APK на устройстве и смотрит результат — обратную связь даёт в следующем сообщении (часто «снова сломалось»).
- Чувствителен к деталям визуала. НЕ отклоняться от требований раздела 4 без явного разрешения.
- Если референс приложен картинкой — картинка может не долететь; тогда стоит уточнить вид, а не угадывать (в сессии v0.1.15 так и вышло: «сделай весь визуал как у поверамп»).
- Если что-то неясно — краткий вопрос/предложение вместо самовольных решений.

## 9. На что смотреть при «сломалось»

1. Соблюдены ли требования раздела 4 (статичные высоты, симметрия, белое/серое по ползунку, плавный playhead).
2. Есть ли `samples` вообще: пустой список = упал `analyzeTrack` (проверить try/catch в `playTrack` и лог MediaCodec).
3. Порядок `samples` по `positionMs` — от него зависит бинарный поиск `peakIn` (должен быть строго возрастающий).
4. `maxAmp >= minBar`, иначе `coerceIn` бросит IllegalArgumentException на низком холсте.
5. Не сбиты ли `versionCode`/`versionName` и тэг в workflow (должны совпадать).
6. Зелёный ли раннер (раздел 2).