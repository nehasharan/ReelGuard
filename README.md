# 🛡️ ReelGuard — Real-Time Misinformation Detection Agent

An Android Accessibility Service that monitors your social media feeds in real time,
extracts on-screen claims, and fact-checks them via an LLM-powered backend.

---

## Architecture Overview

```
┌─────────────────────────────────────┐
│         User's Phone (Android)       │
│                                      │
│  ┌──────────────────────────────┐   │
│  │  Instagram / YouTube / etc.  │   │
│  └──────────┬───────────────────┘   │
│             │ screen content         │
│  ┌──────────▼───────────────────┐   │
│  │  AccessibilityService        │   │
│  │  - Reads text from views     │   │
│  │  - Filters by app whitelist  │   │
│  │  - Deduplicates content      │   │
│  └──────────┬───────────────────┘   │
│             │ extracted text         │
│  ┌──────────▼───────────────────┐   │
│  │  FactCheckClient             │   │
│  │  - Sends text to backend     │   │
│  │  - Parses verdict response   │   │
│  └──────────┬───────────────────┘   │
│             │ verdict + summary      │
│  ┌──────────▼───────────────────┐   │
│  │  OverlayService (Bubble)     │   │
│  │  - 🟢🟡🔴 color indicator    │   │
│  │  - Tap to expand details     │   │
│  │  - Draggable position        │   │
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
                 │
                 │ HTTPS POST (text only, no screenshots)
                 ▼
┌─────────────────────────────────────┐
│         Your Backend Server          │
│                                      │
│  1. Receive extracted text           │
│  2. LLM extracts specific claims     │
│  3. Web search to verify each claim  │
│  4. LLM assesses claim vs. sources   │
│  5. Return structured verdict JSON   │
│                                      │
│  Stack: Python/FastAPI + Claude API  │
└─────────────────────────────────────┘
```

---

## Project Structure

```
ReelGuard/
├── app/src/main/
│   ├── AndroidManifest.xml              # Permissions + service declarations
│   ├── res/
│   │   ├── xml/
│   │   │   └── accessibility_service_config.xml  # A11y service configuration
│   │   ├── drawable/
│   │   │   └── ic_shield.xml            # App icon (placeholder)
│   │   └── values/
│   │       └── strings.xml              # App strings + a11y description
│   └── java/com/reelguard/app/
│       ├── ReelGuardApp.kt              # Application class
│       ├── model/
│       │   └── Models.kt               # Data classes (Verdict, Claim, Config)
│       ├── service/
│       │   ├── ReelGuardAccessibilityService.kt  # ⭐ Core agent
│       │   └── OverlayService.kt                 # Floating bubble UI
│       ├── network/
│       │   └── FactCheckClient.kt       # Backend API client
│       ├── ui/
│       │   └── MainActivity.kt          # Setup screen (permissions + toggles)
│       └── util/
│           └── Utils.kt                 # Deduplicator + PrefsManager
└── app/build.gradle                     # Dependencies
```

---

## How Each Component Works

### 1. AccessibilityService (the "eyes")

When the user scrolls in Instagram, Android fires accessibility events. Our service
catches these, walks the view tree with `rootInActiveWindow`, and collects all visible
text. This is NOT a screenshot — it reads the actual text content from UI elements.

Key privacy features:
- Only activates for apps the user explicitly enables
- 2-second debounce prevents flooding during fast scrolling
- LRU dedup cache avoids re-checking the same content
- Content must pass a "substantive content" check (>50 chars, >10 words)

### 2. OverlayService (the "face")

A floating bubble (like Facebook Messenger chat heads) that sits on top of other apps.
Uses `TYPE_APPLICATION_OVERLAY` window type. The bubble is:
- Color-coded by verdict (green/yellow/red/grey)
- Draggable to any screen position
- Tappable to expand a summary card
- Backed by a foreground service notification

### 3. FactCheckClient (the "voice")

Sends extracted text to your backend via HTTPS POST. The client:
- Never sends screenshots or images
- Never calls the LLM API directly (your API key stays on the server)
- Has a 15-second timeout
- Returns structured data (verdict + claims + sources)

### 4. MainActivity (the "settings")

Simple setup screen that:
- Guides users through enabling Accessibility Service + Overlay permissions
- Shows toggle switches for each supported app
- Displays the agent's current status

---

## Setup Instructions

### 1. Clone and Open in Android Studio

```bash
# Open the ReelGuard/ directory as an Android Studio project
# Sync Gradle and let dependencies download
```

### 2. Build Your Backend

You need a server that accepts POST requests and returns fact-check verdicts.
Here's a minimal Python/FastAPI example:

```python
# server.py — minimal fact-checking backend
from fastapi import FastAPI
from anthropic import Anthropic

app = FastAPI()
client = Anthropic()  # Uses ANTHROPIC_API_KEY env var

@app.post("/api/fact-check")
async def fact_check(request: dict):
    text = request["text"]

    # Step 1: Extract claims
    claims_response = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=1024,
        system="Extract specific factual claims from this social media content. Return JSON array of claims.",
        messages=[{"role": "user", "content": text}]
    )

    # Step 2: For each claim, search + verify (use web search tool or your own search)
    # Step 3: Return structured verdict

    return {
        "overall_verdict": "LIKELY_FALSE",  # or LEGIT, UNVERIFIED, SCAM
        "summary": "The claim about X is not supported by evidence...",
        "claims": [
            {
                "text": "Extracted claim text",
                "verdict": "LIKELY_FALSE",
                "explanation": "Why this is false...",
                "sources": ["https://reliable-source.com/article"]
            }
        ]
    }
```

### 3. Update the Backend URL

In `FactCheckClient.kt`, replace:
```kotlin
private const val BASE_URL = "https://your-backend.example.com/api"
```

### 4. Run on Device

- Connect an Android device (API 26+)
- Build and install the app
- Open ReelGuard → enable Accessibility Service → grant Overlay permission
- Toggle on the apps you want monitored
- Open Instagram and scroll — the bubble should appear

---

## Next Steps to Build

| Priority | Feature | Description |
|----------|---------|-------------|
| 🔴 High | Backend server | Build the full fact-checking pipeline (claim extraction → web search → verdict) |
| 🔴 High | Error handling | Retry logic, offline mode, graceful degradation |
| 🟡 Med  | OCR fallback | Use ML Kit for text in images/video frames that aren't in the view tree |
| 🟡 Med  | Audio analysis | Use Whisper for speech-to-text on reel audio |
| 🟡 Med  | Caching layer | Cache verdicts for URLs/content hashes to reduce API calls |
| 🟡 Med  | History screen | Show past fact-checks so users can review later |
| 🟢 Low  | Widget | Home screen widget showing recent verdicts |
| 🟢 Low  | Share integration | "Share to ReelGuard" for manual fact-checking |
| 🟢 Low  | Local patterns | On-device scam URL database + urgency language detector |

---

## Privacy Principles

1. **User controls which apps are monitored** — nothing is accessed without explicit opt-in
2. **No screenshots** — we read text from the view hierarchy, not pixel data
3. **No raw storage** — extracted text is sent for analysis but never persisted
4. **Server-side AI** — the LLM runs on your server, not a third-party app
5. **Minimal data** — only the text needed for fact-checking is transmitted
