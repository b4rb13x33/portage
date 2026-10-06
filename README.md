# Portage

**Bring your own keys. Bring your project. Change your model.**

An Android AI workspace where your API keys and your conversations stay on your
device. No account, no middleman server, no lock-in — you talk to OpenAI,
Anthropic, Google, or an open-source model behind your own Ollama endpoint
directly.

## Why

Every AI client makes the same trade: convenience for custody. They hold your
key, they hold your history, and moving between models means starting over.

Portage inverts it. The app is a translator sitting between your projects and
any provider you plug in — your keys never leave the device's Keystore, and a
project survives switching from Claude to a local Ollama model mid-conversation
with its files and context intact.

## Status

Early development. Not yet published.

- [x] M0 — toolchain, build, CI, release signing
- [ ] M1 — encrypted key vault, provider registry, streaming chat
- [ ] M2 — projects + cross-model transfer
- [ ] M3 — files, agents, cost tracking
- [ ] M4 — on-device models
- [ ] M6 — Play Store polish

See [DECISIONS.md](DECISIONS.md) for the architectural and product rationale.

## Privacy model

- API keys are encrypted with AES-GCM using a key held in the Android Keystore.
  The master key is non-exportable hardware-backed material where available.
- Requests go **device → provider**, straight from your phone to the API you
  configured. There is no Portage server in the path.
- Conversations, projects, and files are stored locally in Room/DataStore.

## Building

Requires a JDK 21 and an Android SDK with platform 37.

```sh
source ~/tools/env.sh          # local toolchain (JDK, SDK, Gradle)
./gradlew assembleDebug
./gradlew bundleRelease        # unsigned unless PORTAGE_KEYSTORE_PATH is set
```

Release AABs are produced by GitHub Actions (`.github/workflows/release.yml`)
on version tags. Configure `PORTAGE_KEYSTORE_BASE64`,
`PORTAGE_KEYSTORE_PASSWORD`, `PORTAGE_KEY_ALIAS`, and `PORTAGE_KEY_PASSWORD` as
repository secrets.

## Toolchain

Gradle 9.8.0 · AGP 9.4.1 · Kotlin 2.4.20 · Compose BOM 2026.09.00 ·
OkHttp 5.5.0 · kotlinx-serialization 1.11.0 · DataStore 1.2.1

`compileSdk 37`, `targetSdk 36`, `minSdk 26`.
