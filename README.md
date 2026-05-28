# Doc Scanner & Vectorizer

Native Android document scanner that captures paper pages, applies cleanup filters, saves scan history, and exports SVG vectors.

## App Details

- Package: `com.michael.docscannervectorizer`
- Version: `1.0.2` (`versionCode 3`)
- Minimum SDK: `24`
- Target SDK: `35`
- UI: Jetpack Compose
- Camera: CameraX
- Signing: release AAB uses the archived upload key in `~/Desktop/playstore-keys/Doc-Scanner-Vectorizer/`

## Features

- Live document boundary tracking with CameraX
- Manual capture fallback
- Perspective-corrected document scans
- Paper cleanup filters: Original, Grayscale, Monochrome, Shadow Removed, and Magic Color
- Saved scan history with notes
- PNG sharing
- SVG vector export
- Gallery import for existing images

## Project Structure

```text
app/                         Android app module
app/src/main/java/           Kotlin source
app/src/test/java/           Unit and Play Store screenshot tests
play-store/                  Play Console graphics and listing copy
gradle/libs.versions.toml    Dependency and plugin versions
```

## Build

Use JDK 17.

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest --no-configuration-cache
```

Build a signed release bundle:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home \
  bash ~/.codex/skills/generate-signed-aab/scripts/generate-signed-aab.sh \
  --project-name "Doc-Scanner-Vectorizer" \
  --reuse-existing .
```

Output:

```text
app/build/outputs/bundle/release/app-release.aab
```

## Play Store Assets

Generated assets are in `play-store/`:

- `app-icon-512.png`
- `feature-graphic.png`
- `phone/*.png`
- `tablet/*.png`
- `listing-descriptions.md`

Regenerate assets:

```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew generatePlayStoreAssets --no-configuration-cache
bash ~/.codex/skills/generate-app-assets/scripts/verify-play-store-assets.sh .
```

## Release Notes

The current release targets API 35 and uses CameraX `1.4.2` with native libraries rebuilt for 16 KB memory page size support. The release bundle is built with Android Gradle Plugin `8.6.1`.

## Secrets

Do not commit signing files or local machine config:

- `key.properties`
- `*.jks`
- `*.keystore`
- `local.properties`

Release signing material is stored separately in the private `playstore-keys` archive.
