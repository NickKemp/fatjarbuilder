<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# JARBUILDER Changelog

## Unreleased

## 1.0.7

- Fixed inconsistent success logging between Build menu and artifact/before-run builds
- Output path now shown in tool window for all build methods
-

## 1.0.6

- Added Gradle project support — output path detection for Maven and Gradle
- Fixed main class scanner false positives — interface detection via bytecode
- Extracted FatJarCore — shared build logic between all build methods
- Fixed JAR size display — shows bytes/KB/MB accurately
- Fixed Windows file lock on rebuild — uses temp file with NIO atomic move
- Added build success notification balloon with Open output folder button
- Removed right-click project panel action — Build menu is the entry point
- Fixed compiler.task order attribute

## 1.0.5

- Added Build menu integration (Build → FatJar Builder → Build Fat JAR)
- Added Before launch task (Run → Edit Configurations → Before launch → + → Build Fat JAR)
- Added Artifact system integration (File → Project Structure → Artifacts → + → Fat JAR)
- Main class browser in artifact settings
- Fixed deprecated API usage throughout
-

## 1.0.4

- Fixed deprecated PopupChooserBuilder API
- Fixed tool window factory deprecated methods

## 1.0.3

- Remove warnings

## 1.0.2

- Remove warnings

## 1.0.1

- Raised minimum IDE version to 2024.1 for full compatibility

## 1.0.0

- Initial release
