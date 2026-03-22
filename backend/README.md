# ReelGuard Backend

FastAPI server that powers the ReelGuard Android app's fact-checking pipeline.

## Quick Start

```bash
# 1. Install dependencies
pip install -r requirements.txt

# 2. Set your API key
cp .env.example .env
# Edit .env and add your ANTHROPIC_API_KEY

# 3. Run the server
python server.py
```

Server starts at `http://localhost:8000`. API docs at `http://localhost:8000/docs`.

## How the Pipeline Works

```
Incoming text from Android app
        │
        ▼
┌─ Scam Pattern Check (local, instant) ─┐
│  Matches known scam phrases?           │
│  If yes → return SCAM immediately      │
└────────────────────────────────────────┘
        │ no scam patterns
        ▼
┌─ Step 1: Claim Extraction (Claude) ────┐
│  "Extract verifiable factual claims"   │
│  Filters out opinions, UI text, etc.   │
│  Returns: ["claim1", "claim2", ...]    │
└────────────────────────────────────────┘
        │
        ▼ for each claim
┌─ Step 2: Web Search ───────────────────┐
│  Google Custom Search / SerpAPI /      │
│  Claude knowledge fallback             │
│  Returns: search results with snippets │
└────────────────────────────────────────┘
        │
        ▼
┌─ Step 3: Claim Assessment (Claude) ────┐
│  "Given this claim and these sources,  │
│   is the claim accurate?"              │
│  Returns: verdict + explanation        │
└────────────────────────────────────────┘
        │
        ▼
┌─ Step 4: Summary Generation (Claude) ──┐
│  "Write a brief mobile notification"   │
│  Returns: 1-2 sentence summary         │
└────────────────────────────────────────┘
        │
        ▼
    JSON response → Android app → Overlay bubble
```

## API

### `POST /api/fact-check`

**Request:**
```json
{
    "app_package": "com.instagram.android",
    "text": "Scientists discovered that coffee cures cancer...",
    "timestamp": 1234567890
}
```

**Response:**
```json
{
    "overall_verdict": "LIKELY_FALSE",
    "summary": "The claim about coffee curing cancer is not supported by scientific evidence.",
    "claims": [
        {
            "text": "Coffee cures cancer",
            "verdict": "LIKELY_FALSE",
            "explanation": "No peer-reviewed studies support this claim...",
            "sources": ["https://cancer.org/...", "https://pubmed.ncbi.nlm.nih.gov/..."]
        }
    ],
    "checked_at": "2026-03-21T14:30:00",
    "cached": false
}
```

### Verdicts

| Verdict | Meaning | Bubble Color |
|---------|---------|-------------|
| `LEGIT` | Supported by reliable sources | 🟢 Green |
| `UNVERIFIED` | Can't confirm or deny | 🟡 Yellow |
| `LIKELY_FALSE` | Contradicted by reliable sources | 🔴 Red |
| `SCAM` | Known scam patterns detected | 🔴 Dark Red |

## Search Backends

The server supports three search backends (in priority order):

1. **Google Custom Search API** (recommended) — Set `SEARCH_API_KEY` and `SEARCH_ENGINE_ID`
2. **SerpAPI** — Set `SERPAPI_KEY`
3. **Claude knowledge fallback** — No API key needed, uses Claude's training data (less reliable, good for dev)

## Testing

```bash
# Start the server
python server.py

# In another terminal, run tests
python test_server.py         # Full test suite
python test_server.py --quick # Just health + validation
```

## Deploy

```bash
# Docker
docker build -t reelguard-backend .
docker run -p 8000:8000 -e ANTHROPIC_API_KEY=your-key reelguard-backend

# Or deploy to Railway/Render/Fly.io — just set ANTHROPIC_API_KEY env var
```

## Connect to Android App

Update `FactCheckClient.kt`:
```kotlin
private const val BASE_URL = "https://your-deployed-url.com/api"
```
