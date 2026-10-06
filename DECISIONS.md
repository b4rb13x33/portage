# Portage — Decisions

Living record of architectural and product decisions. Rationale matters more than
the decision itself; when reversing one, update the row rather than deleting it.

## Product

| # | Decision | Rationale |
|---|----------|-----------|
| P1 | **Name: Portage** | Carrying things between waters — the portability thesis in one word. Check Play listing uniqueness before submission. |
| P2 | **Free + one-time unlock, $3.99–4.99** | Never gate chat. Free tier = 1 project, 1 provider. Unlock = unlimited projects, cross-model transfer, cost tracking. No subscription: recurring fees are the #1 complaint in this category, and a subscription would put us head-to-head with funded incumbents. |
| P3 | **Cut M5 (Supabase sync) from v1** | Local-first. Sync adds a backend, an account system, and a privacy story we'd have to defend — none of which differentiate. Ship portability first; sync is a post-launch paid feature candidate. |
| P4 | **Portability must be a demoable moment** | Defensible claim = a project survives `Claude → Ollama` mid-conversation with files + agents intact. "You can pick providers" is table stakes vs Chatbox / Cherry Studio / Open WebUI. |
| P5 | **Android only** | No iOS maintainer, no Mac. One platform done well beats two done badly. |

## Technical

| # | Decision | Rationale |
|---|----------|-----------|
| T1 | **`compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`** | Deviation from the handoff doc, which asked for compileSdk 36. Compose BOM `2026.09.00` pulls `androidx.compose 1.12.1`, whose AAR metadata hard-requires compileSdk ≥ 37 (`checkDebugAarMetadata` failure). **Play enforces the *target* API level, not the compile SDK** — `targetSdk 36` still satisfies the Aug 31 2026 requirement. Re-verify Play's minimum target API level at M6 before submission. |
| T2 | **Kotlin + Jetpack Compose, single module** | One module until proven otherwise. Package/module boundaries are free to add later; untangling them is not. |
| T3 | **Manual DI** | Hilt adds kapt/ksp + codegen cost and a class of build failures for a codebase this size. Hand-rolled `AppContainer` is ~40 lines. |
| T4 | **Keystore AES-GCM + DataStore** | `EncryptedSharedPreferences` is deprecated. Keys never leave the Keystore; ciphertext + IV in DataStore. |
| T5 | **OkHttp + kotlinx.serialization for SSE** | No Retrofit. SSE parsing against OpenAI `chat.completions` / Anthropic `messages` / Gemini `streamGenerateContent` is bespoke enough that an annotation layer obscures rather than helps. |
| T6 | **`ProviderAdapter` interface** | New providers are additive. The interface is the contract that makes T5's parsing per-provider instead of per-call-site. |
| T7 | **Direct device → provider calls** | Hard constraint. Our servers never see user keys or conversation content. This is also the privacy selling point. |
| T8 | **LiteRT-LM or ML Kit GenAI for on-device; MediaPipe LLM Inference is maintenance-only** | Do not build the M4 architecture on a maintenance-mode dependency. |

## Build / tooling

| # | Decision | Rationale |
|---|----------|-----------|
| B1 | **No sudo anywhere; everything under `$HOME`** | Toolchain installed to `~/tools` (JDK 21, Android SDK, Gradle 9.8.0), activated by `source ~/tools/env.sh`. |
| B2 | **Portable Temurin JDK 21** | System JDK is 25 — too new for AGP. |
| B3 | **`-Xmx2g`, `daemon=false`, `parallel=false`** | From the handoff doc: this laptop has 15 GB RAM shared with local models and OpenClaw. |
| B4 | **Release AAB via GitHub Actions on a public repo** | The laptop is for dev builds only. |
| B5 | **Toolchain versions** | Gradle 9.8.0 · AGP 9.4.1 · Kotlin 2.4.20 · Compose BOM 2026.09.00 · OkHttp 5.5.0 · kotlinx-serialization 1.11.0 · Room 2.8.5 · DataStore 1.2.1 — all pinned, all verified to resolve from Maven. |

## Open questions

- Play listing **package id uniqueness** for `com.portage.app`.
- Play **minimum target API level** as of submission date (re-check at M6).
- Final price point.
- Whether sync (M5) returns as a paid add-on post-launch.
