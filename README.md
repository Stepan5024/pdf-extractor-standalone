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

- Java 17+ для сборки и запуска
- Maven 3.9+ или `mvnw` / `mvnw.cmd`

## Схемы работы

## GitHub Release

В этом репозитории jar можно брать двумя путями:

- скачать `dist/pdf-extractor-standalone.jar`, если он уже лежит в репозитории
- скачать релизный asset `pdf-extractor-standalone.jar` со страницы Releases

Для корпоративного ноутбука основной путь такой:

```text
pdf-extractor-standalone\
  dist\
    pdf-extractor-standalone.jar
  scripts\
    extract-pdf.cmd
```

И запуск:

```bat
scripts\extract-pdf.cmd C:\path\to\file.pdf --file-format md
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
target/pdf-extractor-standalone-1.0.0.jar
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
target/pdf-extractor-standalone-1.0.0.jar
```

После этого можно запускать его напрямую:

```bash
java -jar target/pdf-extractor-standalone-1.0.0.jar /path/to/file.pdf --file-format md
```

## Опции запуска

- `--output-file <path>`
- `--file-format <md|txt>`
- `--ocr-mode <auto|always|off>`
- `--ocr-language <expr>` например `eng+rus`
- `--ocr-dpi <number>`
- `--help`

## Поведение OCR

- `eng` и `rus` работают без системного `tesseract`
- облачные вызовы не используются
- во время выполнения программы зависимости не скачиваются
- для языков вне `eng`/`rus` нужен установленный `tesseract`

## Что переносить на рабочий компьютер

### Вариант A. Можно передать бинарный jar

Передавай:

- `dist/pdf-extractor-standalone.jar`
- `scripts/extract-pdf.sh`
- `scripts/extract-pdf.cmd`

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
- сложные инфографики
- визуальные блоки, где текст является частью дизайна страницы
