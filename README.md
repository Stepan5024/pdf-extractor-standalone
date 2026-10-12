# PDF Extractor Standalone

Самостоятельный Java-проект для извлечения PDF в `Markdown` или `TXT` с локальным OCR.

## Что внутри

- `PDFBox` для извлечения нативного текста, ссылок и части табличной структуры
- `Tess4J` для offline OCR
- встроенные OCR-ресурсы для `eng` и `rus`
- fallback на установленный `tesseract`, если нужен другой язык OCR

Проект не зависит от `adapstory-*` модулей и может быть перенесен отдельной папкой.

## Source Of Truth

- исходник для развития логики: `adapstory-cli`
- этот проект: самостоятельный переносимый дубль с тем же кодом PDF/OCR
- если алгоритм меняется, сначала правится `adapstory-cli`, затем изменения переносятся сюда

## Состав папки

- `src/main/java` — код standalone-приложения
- `src/main/resources/tessdata` — встроенные OCR-ресурсы
- `src/test/java` — тесты
- `scripts` — запускные скрипты под macOS/Linux и Windows
- `dist` — готовый fat-jar для запуска без Maven

## Важное про перенос только текстом

- OCR-ресурсы `eng` и `rus` хранятся как текстовые `.b64` файлы
- это сделано специально, чтобы папку можно было переносить в среду, где разрешен только текстовый перенос
- при запуске приложение декодирует их обратно во временный `tessdata` каталог

## Требования

- Java 17+ для запуска
- JDK 25 для сборки и проверок форматирования (байткод совместим с Java 17)
- Maven 3.9+ или `mvnw` / `mvnw.cmd`

## Linux: запуск релиза v3.0.3

Скачайте `pdf-extractor-standalone.jar` из GitHub Releases. Для запуска требуется Java 17+.
Из папки с JAR выполните:

```bash
java -Djava.awt.headless=true -jar pdf-extractor-standalone.jar combine "$HOME/Загрузки/PDFS" --output-file "$HOME/Загрузки/PDFS/combined.md"
```

Режим `combine` обходит вложенные папки и сохраняет один Markdown bundle.
Отсутствующее имя шрифта считается неизвестным обычным начертанием. Приложение
сохраняет исходное сопоставление символов и не подменяет шрифт в PDF.
Подстановку невстроенных шрифтов при отрисовке выполняет PDFBox; его резервный
Liberation Sans включён в JAR. При отсутствии Unicode-сопоставления режим `auto` распознаёт затронутую страницу
через OCR, даже если на ней уже есть много извлечённого текста. Если OCR отключён,
недоступен или вернул пустой текст, обработка завершается ошибкой вместо выдачи
неполного результата. Для OCR на Debian/Ubuntu установите локальный Tesseract:

```bash
sudo apt-get install tesseract-ocr tesseract-ocr-rus
```

Модели `eng` и `rus` включены в JAR; Linux требует нативную библиотеку Tesseract.
Точное восстановление на плохо читаемых страницах зависит от качества OCR.

## Режимы запуска

Приложение умеет три сценария:

1. Один PDF -> один `md` или `txt`
2. Папка с PDF -> один общий `md` bundle
3. Один bundle `md` -> набор составных `md` файлов

Можно запускать как старый single-file режим без subcommand:

```bash
java -jar dist/pdf-extractor-standalone.jar /path/to/file.pdf --file-format md
```

или через явные subcommand:

```bash
java -jar dist/pdf-extractor-standalone.jar extract /path/to/file.pdf --file-format md
java -jar dist/pdf-extractor-standalone.jar combine /path/to/pdf-folder --output-file /path/to/combined.md
java -jar dist/pdf-extractor-standalone.jar split /path/to/combined.md --output-dir /path/to/restored
```

Если запустить без аргументов, откроется простое меню выбора из 3 режимов.

## Схемы работы

## GitHub Release

В публичном репозитории `Stepan5024/pdf-extractor-standalone` основной сценарий такой:

- скачать `pdf-extractor-standalone.jar` со страницы Releases
- положить jar в `dist/pdf-extractor-standalone.jar`
- запускать через `scripts/extract-pdf.cmd` или `scripts/extract-pdf.ps1` на Windows

Минимальный набор для корпоративного ноутбука:

```text
pdf-extractor-standalone\
  dist\
    pdf-extractor-standalone.jar
  scripts\
    extract-pdf.cmd
    extract-pdf.ps1
```

### Схема 1. Готовый jar без Maven

Это основной путь для закрытой корпоративной среды.

Запуск:

```bash
java -jar dist/pdf-extractor-standalone.jar /path/to/file.pdf --file-format md
```

или:

```bash
./scripts/extract-pdf.sh /path/to/file.pdf --file-format md
```

```bat
scripts\extract-pdf.cmd C:\path\to\file.pdf --file-format md
```

PowerShell:

```powershell
.\scripts\extract-pdf.ps1 C:\path\to\file.pdf --file-format md
```

Плюсы:

- Maven не нужен
- Nexus не нужен
- `shade` plugin не нужен

Минусы:

- нужно заранее иметь готовый jar в `dist`

### Схема 2. Обычная сборка в корп-среде без fat-jar

Это основной build-путь, если `maven-shade-plugin` недоступен.

Сборка:

```bash
mvn package
```

или:

```bash
./mvnw package
```

На Windows:

```bat
mvn package
```

или:

```bat
mvnw.cmd package
```

Результат:

```text
target/pdf-extractor-standalone-3.0.3.jar
```

Это thin jar. Его надо запускать через скрипты:

```bash
./scripts/extract-pdf.sh /path/to/file.pdf --file-format md
```

```bat
scripts\extract-pdf.cmd C:\path\to\file.pdf --file-format md
```

Скрипты добирают зависимости из локального Maven cache:

- macOS/Linux: `~/.m2/repository`
- Windows: `%USERPROFILE%\.m2\repository`
- можно переопределить через `M2_REPO`

### Схема 3. Fat-jar вне корп-среды

Если `maven-shade-plugin` доступен, можно собрать fat-jar:

```bash
mvn -Pfat-jar package
```

Результат:

```text
target/pdf-extractor-standalone-3.0.3.jar
```

После этого можно запускать его напрямую:

```bash
java -jar target/pdf-extractor-standalone-3.0.3.jar /path/to/file.pdf --file-format md
```

## Опции запуска

- `--output-file <path>`
- `--file-format <md|txt>`
- `--ocr-mode <auto|always|off>`
- `--ocr-language <expr>` например `eng+rus`
- `--ocr-dpi <number>`
- `--help`

### Режим 1. Один PDF

Примеры:

```bash
java -jar dist/pdf-extractor-standalone.jar extract /path/to/file.pdf --file-format md
```

```powershell
.\scripts\extract-pdf.ps1 C:\path\to\file.pdf --file-format md
```

### Режим 2. Папка PDF -> один bundle markdown

Этот режим рекурсивно обходит обычную папку, не `git`-репозиторий, и собирает все найденные `*.pdf` в один markdown bundle.

Пример:

```bash
java -jar dist/pdf-extractor-standalone.jar combine /path/to/pdf-folder --output-file /path/to/combined.md
```

```powershell
.\scripts\extract-pdf.ps1 combine C:\path\to\pdf-folder --output-file C:\path\to\combined.md
```

Что внутри bundle:

- для каждого PDF создается отдельный markdown-блок
- относительный путь сохраняется как `PATH: subdir/file.md`
- формат совместим с логикой `FileCombiner.java` / `UnZip.java`

### Режим 3. Bundle markdown -> набор markdown файлов

Этот режим берет один общий bundle-файл и восстанавливает из него составные `md` файлы.

Пример:

```bash
java -jar dist/pdf-extractor-standalone.jar split /path/to/combined.md --output-dir /path/to/restored
```

```powershell
.\scripts\extract-pdf.ps1 split C:\path\to\combined.md --output-dir C:\path\to\restored
```

## Поведение OCR

- модели `eng` и `rus` включены в JAR; для OCR на Linux требуется нативная библиотека Tesseract
- облачные вызовы не используются
- во время выполнения программы зависимости не скачиваются
- для языков вне `eng`/`rus` нужен установленный `tesseract`

## Что переносить на рабочий компьютер

### Вариант A. Можно передать бинарный jar

Передавай:

- `dist/pdf-extractor-standalone.jar`
- `scripts/extract-pdf.sh`
- `scripts/extract-pdf.cmd`
- `scripts/extract-pdf.ps1`

### Вариант B. Можно передать только текст

Передавай всю папку проекта:

- `pom.xml`
- `mvnw`
- `mvnw.cmd`
- `.mvn`
- `src`
- `scripts`
- `README.md`

Тогда:

- обычная сборка идет через `mvn package`
- fat-jar не обязателен
- OCR-ресурсы уже лежат в текстовом виде

## Если `mvn package` падает в корп-среде

Пример с:

```text
maven-shade-plugin ... 401 Unauthorized
```

означает не проблему кода, а то, что корпоративный Nexus не дал скачать Maven plugin или его зависимости.

Что делать:

- не использовать `-Pfat-jar`
- использовать обычный `mvn package`
- запускать через `scripts/extract-pdf.sh` или `scripts\extract-pdf.cmd`
- либо вообще не собирать, а запускать готовый `dist/pdf-extractor-standalone.jar`

Что отдельно проверить в корпоративном репозитории:

- `org.apache.pdfbox:pdfbox:2.0.35`
- `net.sourceforge.tess4j:tess4j:5.19.0`
- `net.sourceforge.lept4j:lept4j:1.24.0`
- transitives для `tess4j`

## Ограничения качества

Лучше всего извлекаются:

- обычный текст
- заголовки
- ссылки
- простые таблицы

Хуже всего извлекаются:

- журнальная верстка с несколькими колонками

## Запуск из IntelliJ IDEA

Если на рабочем ноуте Maven умеет скачать зависимости, можно запускать проект прямо из IDE.

Что открыть:

- `pom.xml` этого проекта

Какой класс запускать:

- `io.github.stepanbokarev.pdfextractor.PdfExtractorApplication`

Пример `Program arguments`:

```text
C:\path\to\file.pdf --file-format md --output-file C:\path\to\file.md --ocr-mode auto
```

Если нужен `txt`:

```text
C:\path\to\file.pdf --file-format txt --output-file C:\path\to\file.txt --ocr-mode auto
```
- сложные инфографики
- визуальные блоки, где текст является частью дизайна страницы
