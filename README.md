# FatJar Builder

An IntelliJ IDEA plugin that builds a single deployable fat JAR from any module with one click.

The **Eclipse FatJar plugin** equivalent for IntelliJ IDEA.

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains%20Marketplace-FatJar%20Builder-blue)](https://plugins.jetbrains.com/plugin/com.github.nickkemp.fatjarbuilder)
[![Version](https://img.shields.io/badge/version-1.0.0-green)]()
[![License](https://img.shields.io/badge/license-Apache%202.0-orange)]()

---

## The Problem

Teams migrating from Eclipse to IntelliJ miss the FatJar plugin. The alternatives are:

- **Maven shade plugin** — works but breaks the IntelliJ debugger when build delegation is enabled
- **IntelliJ artifact builder** — nests JARs inside JARs rather than extracting them, causing `NoClassDefFoundError` at runtime
- **Manual post-processing** — `zip -d` to strip signatures, extract and repack — tedious and error-prone

FatJar Builder solves all of this with one click, without touching your Maven or Gradle build configuration.

---

## Features

- **Right-click any module → FatJar Builder → Build Fat JAR**
- Browses compiled classes to find and select your main class
- Recursively includes all dependent module compiled classes
- Extracts all dependency JARs into a single flat JAR
- Merges `META-INF/services` files correctly (SLF4J, JAXB, etc.)
- Strips JAR signatures automatically — no more `SecurityException`
- Excludes unwanted packages with Ant-style glob patterns
- Shows detailed timestamped build output in a dedicated tool window
- Remembers settings per module between sessions

---

## Installation

### From JetBrains Marketplace (recommended)

1. Open IntelliJ IDEA
2. **File → Settings → Plugins → Marketplace**
3. Search for **FatJar Builder**
4. Click **Install**
5. Restart IntelliJ

### From disk

1. Download the latest ZIP from [Releases](https://github.com/nickkemp/fatjarbuilder/releases)
2. **File → Settings → Plugins → ⚙ → Install Plugin from Disk...**
3. Select the ZIP file
4. Restart IntelliJ

---

## Usage

### Building a Fat JAR

1. **Right-click on a module** in the Project panel
2. Select **FatJar Builder → Build Fat JAR**
3. The configuration dialog opens:

   | Field | Description |
   |---|---|
   | **Main class** | Click Browse to select from compiled classes |
   | **Output JAR name** | Name of the output file e.g. `myapp.jar` |
   | **Output directory** | Where to write the JAR — click Browse to choose |
   | **Exclude packages** | Ant-style patterns e.g. `com/example/legacy/**` |

4. Click **OK** — the build runs in the background
5. Output appears in the **FatJar Builder** tool window at the bottom of the IDE

### Exclude patterns

Use Ant-style glob patterns to exclude packages from the output JAR:

```
com/example/legacy/**       excludes everything under com.example.legacy
com/example/Test*.class     excludes classes starting with Test
**/unused/**                excludes any directory named unused
```

### Running the output JAR

```bash
java -jar myapp.jar
```

Or with system properties:

```bash
java -Dmy.property=value -jar myapp.jar
```

---

## How it works

1. Collects compiled `.class` files from the selected module and all dependent modules
2. Collects all dependency JARs from the module classpath
3. Extracts all JAR contents into a single flat structure
4. Merges `META-INF/services` provider files (required for SLF4J, JAXB etc.)
5. Strips signature files (`*.SF`, `*.DSA`, `*.RSA`) from signed JARs
6. Applies package excludes
7. Writes a new JAR with a generated `MANIFEST.MF` containing the specified main class

---

## Requirements

- IntelliJ IDEA 2022.2 or later (Community or Ultimate)
- Java module (not Gradle/Maven specific — works with any IntelliJ Java module)

---

## Known Limitations

- **Gradle projects** — output path detection may not work for all Gradle configurations. Ensure the module has been built before running the plugin.
- **Main class scanner** — uses bytecode pattern matching to find classes with a `main()` method. May occasionally include false positives for classes that reference main method signatures in string constants.
- **Signed JARs** — signature files are stripped. If your application requires JAR signing for security purposes, sign the output JAR separately after building.

---

## Building from Source

```bash
git clone https://github.com/nickkemp/fatjarbuilder.git
cd fatjarbuilder
./gradlew buildPlugin
```

The plugin ZIP is produced at `build/distributions/JARBUILDER-1.0.0.zip`.

To run in a sandbox IntelliJ instance:

```bash
./gradlew runIde
```

---

## Contributing

Issues and pull requests are welcome. Please open an issue before submitting a PR for significant changes.

---

## Changelog

### 1.0.0
- Initial release
- Right-click module → Build Fat JAR
- Main class browser from compiled output
- Recursive module dependency collection
- `META-INF/services` merging
- Signature file stripping
- Package excludes with glob patterns
- Build output tool window with timestamps
- Per-module persistent settings

---

## Licence

Licensed under the [Apache License 2.0](LICENSE).

---

## Acknowledgements

Inspired by the [Eclipse FatJar Plugin](http://fjep.sourceforge.net/) which many Java developers relied on before migrating to IntelliJ IDEA.
