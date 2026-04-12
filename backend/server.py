"""
ReelGuard Backend Server

FastAPI server that receives extracted text from the Android app,
uses Claude to identify factual claims, verifies them via web search,
and returns a structured verdict.

Pipeline:
1. Receive extracted screen text from the app
2. Claude extracts specific factual claims
3. For each claim, search the web for verification
4. Claude assesses each claim against found sources
5. Return overall verdict + per-claim breakdown
"""

import os
import time
import hashlib
import json
from typing import Optional
from datetime import datetime

# Load .env for local development (ignored on Railway where env vars are set directly)
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import anthropic
import httpx

import logging

# ─── Logging ───
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s │ %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("reelguard")

# ─── Configuration ───

ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY")
SEARCH_API_KEY = os.getenv("SEARCH_API_KEY")  # Google Custom Search or SerpAPI
SEARCH_ENGINE_ID = os.getenv("SEARCH_ENGINE_ID")  # For Google Custom Search

if not ANTHROPIC_API_KEY:
    raise RuntimeError("ANTHROPIC_API_KEY environment variable is required")

# ─── App Setup ───

app = FastAPI(
    title="ReelGuard API",
    description="Real-time misinformation detection backend",
    version="0.1.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

client = anthropic.Anthropic(api_key=ANTHROPIC_API_KEY)

# Simple in-memory cache (use Redis in production)
verdict_cache: dict[str, dict] = {}
CACHE_TTL_SECONDS = 3600  # 1 hour


# ─── Models ───

class FactCheckRequest(BaseModel):
    app_package: str
    text: str
    timestamp: Optional[int] = None


class ClaimVerdict(BaseModel):
    text: str
    verdict: str  # LEGIT, UNVERIFIED, LIKELY_FALSE, SCAM
    explanation: str
    sources: list[str]


class FactCheckResponse(BaseModel):
    overall_verdict: str
    summary: str
    claims: list[ClaimVerdict]
    checked_at: str
    cached: bool = False


# ─── Endpoints ───

@app.get("/health")
async def health():
    return {"status": "ok", "timestamp": datetime.utcnow().isoformat()}


@app.post("/api/fact-check", response_model=FactCheckResponse)
async def fact_check(request: FactCheckRequest):
    """
    Main fact-checking endpoint.
    Receives text extracted from screen, returns a verdict.
    """
    text = request.text.strip()

    if not text or len(text) < 20:
        raise HTTPException(status_code=400, detail="Text too short to fact-check")

    log.info("═" * 60)
    log.info("NEW FACT-CHECK REQUEST")
    log.info("═" * 60)
    log.info(f"App: {request.app_package}")
    log.info(f"Text length: {len(text)} chars")
    log.info(f"Preview: {text[:200]}...")

    # Check cache first
    cache_key = hashlib.md5(text.encode()).hexdigest()
    cached = verdict_cache.get(cache_key)
    if cached and (time.time() - cached["timestamp"]) < CACHE_TTL_SECONDS:
        log.info("⚡ Cache hit — returning cached result")
        result = cached["result"]
        result["cached"] = True
        return result

    # Step 0: Quick local scam pattern check (instant, no API call)
    log.info("─── Step 0: Scam pattern check ───")
    scam_verdict = check_scam_patterns(text)
    if scam_verdict:
        log.info(f"🚨 SCAM detected by patterns: {scam_verdict.explanation}")
        result = FactCheckResponse(
            overall_verdict="SCAM",
            summary=scam_verdict.explanation,
            claims=[scam_verdict],
            checked_at=datetime.utcnow().isoformat(),
        )
        verdict_cache[cache_key] = {
            "result": result.model_dump(),
            "timestamp": time.time(),
        }
        return result

    # Step 1: Extract claims from the text
    log.info("─── Step 1: Extracting claims ───")
    claims = await extract_claims(text)

    if not claims:
        log.info("No factual claims found — checking for scams")
        # No factual claims found — but could still be a scam
        # Run full scam check (search + AI) before declaring it clean
        scam_result = await full_scam_check(text)
        if scam_result:
            log.info(f"🚨 SCAM detected: {scam_result.explanation}")
            result = FactCheckResponse(
                overall_verdict="SCAM",
                summary=scam_result.explanation,
                claims=[scam_result],
                checked_at=datetime.utcnow().isoformat(),
            )
            verdict_cache[cache_key] = {
                "result": result.model_dump(),
                "timestamp": time.time(),
            }
            return result

        log.info("✅ No claims, no scams — content looks clean")
        return FactCheckResponse(
            overall_verdict="LEGIT",
            summary="No specific factual claims or scam indicators detected.",
            claims=[],
            checked_at=datetime.utcnow().isoformat(),
        )

    # Step 2: Verify each claim
    log.info(f"─── Step 2: Verifying {len(claims)} claims ───")
    for i, c in enumerate(claims):
        log.info(f"  Claim {i+1}: {c}")

    verified_claims = []
    for claim_text in claims:
        verdict = await verify_claim(claim_text, text)
        verified_claims.append(verdict)
        log.info(f"  → [{verdict.verdict}] {verdict.text}")
        if verdict.sources:
            for src in verdict.sources:
                log.info(f"    📎 {src}")

    # Step 3: Full scam check — search for known scam reports + AI analysis
    log.info("─── Step 3: Scam check (search + AI) ───")
    if not any(c.verdict == "SCAM" for c in verified_claims):
        scam_result = await full_scam_check(text)
        if scam_result:
            log.info(f"🚨 SCAM detected: {scam_result.explanation}")
            verified_claims.append(scam_result)
        else:
            log.info("No scam indicators found")
    else:
        log.info("Skipped — claim already flagged as SCAM")

    # Step 4: Determine overall verdict
    overall = determine_overall_verdict(verified_claims)
    log.info(f"─── Step 4: Overall verdict: {overall} ───")

    # Step 5: Generate summary
    log.info("─── Step 5: Generating summary ───")
    summary = await generate_summary(text, verified_claims, overall)
    log.info(f"Summary: {summary}")

    result = FactCheckResponse(
        overall_verdict=overall,
        summary=summary,
        claims=verified_claims,
        checked_at=datetime.utcnow().isoformat(),
    )

    # Cache the result
    verdict_cache[cache_key] = {
        "result": result.model_dump(),
        "timestamp": time.time(),
    }

    log.info("═" * 60)
    log.info(f"DONE — {overall} — {len(verified_claims)} claims checked")
    log.info("═" * 60)

    return result


# ─── Pipeline Steps ───

async def extract_claims(text: str) -> list[str]:
    """
    Step 1: Use Claude to identify specific factual claims in the text.
    Filters out opinions, jokes, and non-verifiable statements.
    """
    response = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=1024,
        system="""You are a claim extraction engine. Your job is to identify specific,
verifiable factual claims from social media content.

Rules:
- Extract ONLY claims that can be fact-checked (statistics, events, scientific claims, quotes attributed to people)
- IGNORE opinions, humor, sarcasm, personal stories, and subjective statements
- IGNORE UI text like "Like", "Share", "Follow", timestamps, usernames
- Each claim should be a single, self-contained statement
- Maximum 5 claims per piece of content
- If there are NO verifiable claims, return an empty array

Return ONLY a JSON array of claim strings. No other text.
Example: ["Claim 1 here", "Claim 2 here"]
If no claims: []""",
        messages=[
            {
                "role": "user",
                "content": f"Extract factual claims from this social media content:\n\n{text[:2000]}",
            }
        ],
    )

    try:
        response_text = response.content[0].text.strip()
        # Handle potential markdown code blocks
        if response_text.startswith("```"):
            response_text = response_text.split("```")[1]
            if response_text.startswith("json"):
                response_text = response_text[4:]
            response_text = response_text.strip()
        claims = json.loads(response_text)
        if isinstance(claims, list):
            return claims[:5]  # Cap at 5 claims
    except (json.JSONDecodeError, IndexError, KeyError):
        pass

    return []


async def verify_claim(claim: str, original_context: str) -> ClaimVerdict:
    """
    Step 2: Search the web for evidence about a claim, then have Claude assess it.
    Uses multiple search strategies to maximize the chance of finding relevant results.
    """
    # Generate smart search queries from the claim
    search_queries = await generate_search_queries(claim, original_context)
    log.info(f"    Search queries for '{claim[:50]}...':")
    for q in search_queries:
        log.info(f"      🔍 {q}")

    # Run all searches and combine results
    all_results = []
    seen_urls = set()
    for query in search_queries:
        results = await web_search(query)
        log.info(f"      → {len(results)} results for '{query}'")
        for r in results:
            url = r.get("link", "")
            if url not in seen_urls:
                seen_urls.add(url)
                all_results.append(r)

    log.info(f"    Total unique results: {len(all_results)}")
    for i, r in enumerate(all_results[:8]):
        log.info(f"      [{i}] {r.get('title', 'No title')[:60]}")
        log.info(f"          {r.get('link', 'No URL')}")

    if not all_results:
        return ClaimVerdict(
            text=claim,
            verdict="UNVERIFIED",
            explanation="Could not find reliable sources to verify or refute this claim.",
            sources=[],
        )

    # Format search results for Claude
    evidence_text = format_search_results(all_results[:8])

    # Have Claude assess the claim against the evidence
    response = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=512,
        system="""You are a fact-checking assessor. Given a claim and search results,
determine if the claim is accurate.

Return ONLY a JSON object with these fields:
{
    "verdict": "LEGIT" | "LIKELY_FALSE" | "UNVERIFIED" | "SCAM",
    "explanation": "Brief 1-2 sentence explanation of why",
    "source_indices": [0, 1]
}

Verdict guidelines:
- LEGIT: Claim is supported by at least one reliable source. News reports, social media trends,
  viral events, and cultural phenomena count as evidence when reported by credible outlets.
- LIKELY_FALSE: Claim is clearly contradicted by reliable sources
- UNVERIFIED: Genuinely cannot find any evidence either way after thorough search
- SCAM: Content shows scam patterns

IMPORTANT:
- Trending events, viral moments, and social media phenomena ARE verifiable. If news
  outlets or credible sources report on the event, it should be marked LEGIT.
- Don't default to UNVERIFIED just because a claim is unusual or surprising.
  Check if the search results actually discuss the topic.
- If search results mention the topic at all, lean toward LEGIT or LIKELY_FALSE
  based on what they say, not UNVERIFIED.""",
        messages=[
            {
                "role": "user",
                "content": f"""Claim to verify: "{claim}"

Original context: "{original_context[:500]}"

Search results:
{evidence_text}""",
            }
        ],
    )

    try:
        response_text = response.content[0].text.strip()
        if response_text.startswith("```"):
            response_text = response_text.split("```")[1]
            if response_text.startswith("json"):
                response_text = response_text[4:]
            response_text = response_text.strip()

        assessment = json.loads(response_text)

        # Map source indices to URLs
        source_urls = []
        for idx in assessment.get("source_indices", []):
            if 0 <= idx < len(all_results):
                source_urls.append(all_results[idx].get("link", ""))

        return ClaimVerdict(
            text=claim,
            verdict=assessment.get("verdict", "UNVERIFIED"),
            explanation=assessment.get("explanation", "Assessment unavailable"),
            sources=source_urls,
        )
    except (json.JSONDecodeError, IndexError, KeyError):
        return ClaimVerdict(
            text=claim,
            verdict="UNVERIFIED",
            explanation="Could not complete assessment for this claim.",
            sources=[],
        )


async def generate_search_queries(claim: str, context: str) -> list[str]:
    """
    Generate multiple smart search queries for a claim.
    Instead of just searching the raw claim text, this creates
    targeted queries that are more likely to find relevant results.
    """
    try:
        response = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=300,
            system="""Generate 2-3 effective Google search queries to verify a claim from social media.

Rules:
- First query: the core factual claim in simple search terms
- Second query: add context like year, location, or trending keywords
- Third query (optional): alternative phrasing or the specific names/events mentioned
- Keep queries short (3-8 words each) — Google works best with concise queries
- Include the current year (2026) if the claim is about a recent event
- For trending/viral content, include words like "trend", "viral", "video"

Return ONLY a JSON array of query strings. No other text.
Example: ["moonwalk crosswalk trend 2026", "people moonwalking crosswalks viral", "Michael Jackson dance crosswalk"]""",
            messages=[
                {
                    "role": "user",
                    "content": f"Claim: {claim}\nContext: {context[:300]}",
                }
            ],
        )

        response_text = response.content[0].text.strip()
        if response_text.startswith("```"):
            response_text = response_text.split("```")[1]
            if response_text.startswith("json"):
                response_text = response_text[4:]
            response_text = response_text.strip()

        queries = json.loads(response_text)
        if isinstance(queries, list) and queries:
            return queries[:3]
    except (json.JSONDecodeError, IndexError, KeyError, Exception):
        pass

    # Fallback: use the raw claim as the search query
    return [claim]


async def generate_summary(
    original_text: str, claims: list[ClaimVerdict], overall_verdict: str
) -> str:
    """
    Step 4: Generate a brief, user-friendly summary of the fact-check results.
    """
    claims_summary = "\n".join(
        [f"- [{c.verdict}] {c.text}: {c.explanation}" for c in claims]
    )

    response = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=200,
        system="""Write a brief 1-2 sentence summary of fact-check results for a mobile notification.
Be direct and clear. No hedging. Start with the most important finding.
Do not use markdown. Keep it under 150 characters if possible.""",
        messages=[
            {
                "role": "user",
                "content": f"""Overall verdict: {overall_verdict}

Claims checked:
{claims_summary}

Write a brief mobile-friendly summary:""",
            }
        ],
    )

    return response.content[0].text.strip()


# ─── Web Search ───

async def web_search(query: str) -> list[dict]:
    """
    Search the web for information about a claim.

    Supports multiple search backends:
    1. Google Custom Search API (recommended for production)
    2. SerpAPI (alternative)
    3. Fallback mock results for development
    """
    # Try Google Custom Search first
    if SEARCH_API_KEY and SEARCH_ENGINE_ID:
        return await google_custom_search(query)

    # Try SerpAPI as fallback
    serpapi_key = os.getenv("SERPAPI_KEY")
    if serpapi_key:
        return await serpapi_search(query, serpapi_key)

    # Development fallback: use Claude's knowledge (less reliable but works without API keys)
    return await claude_knowledge_search(query)


async def google_custom_search(query: str) -> list[dict]:
    """Search using Google Custom Search JSON API."""
    url = "https://www.googleapis.com/customsearch/v1"
    params = {
        "key": SEARCH_API_KEY,
        "cx": SEARCH_ENGINE_ID,
        "q": query,
        "num": 5,
    }

    try:
        async with httpx.AsyncClient(timeout=10.0) as http:
            resp = await http.get(url, params=params)
            resp.raise_for_status()
            data = resp.json()

        results = []
        for item in data.get("items", [])[:5]:
            results.append(
                {
                    "title": item.get("title", ""),
                    "snippet": item.get("snippet", ""),
                    "link": item.get("link", ""),
                }
            )
        return results
    except Exception:
        return []


async def serpapi_search(query: str, api_key: str) -> list[dict]:
    """Search using SerpAPI."""
    url = "https://serpapi.com/search"
    params = {
        "api_key": api_key,
        "q": query,
        "engine": "google",
        "num": 5,
    }

    try:
        async with httpx.AsyncClient(timeout=10.0) as http:
            resp = await http.get(url, params=params)
            resp.raise_for_status()
            data = resp.json()

        results = []
        for item in data.get("organic_results", [])[:5]:
            results.append(
                {
                    "title": item.get("title", ""),
                    "snippet": item.get("snippet", ""),
                    "link": item.get("link", ""),
                }
            )
        return results
    except Exception:
        return []


async def claude_knowledge_search(query: str) -> list[dict]:
    """
    Fallback: Use Claude's training knowledge as a pseudo-search.
    Less reliable than real search but works without API keys for development.
    """
    response = client.messages.create(
        model="claude-sonnet-4-20250514",
        max_tokens=512,
        system="""You are a search results simulator for development purposes.
Given a query, return what you know about the topic as if they were search results.

Return ONLY a JSON array of objects, each with "title", "snippet", and "link" fields.
Return 3-5 results. For the link, use the actual source URL if you know it,
otherwise use a plausible URL.

Be factually accurate — this is used for fact-checking.""",
        messages=[
            {"role": "user", "content": f"Search query: {query}"}
        ],
    )

    try:
        response_text = response.content[0].text.strip()
        if response_text.startswith("```"):
            response_text = response_text.split("```")[1]
            if response_text.startswith("json"):
                response_text = response_text[4:]
            response_text = response_text.strip()
        results = json.loads(response_text)
        if isinstance(results, list):
            return results[:5]
    except (json.JSONDecodeError, IndexError):
        pass

    return []


def format_search_results(results: list[dict]) -> str:
    """Format search results into a readable string for Claude."""
    formatted = []
    for i, r in enumerate(results):
        formatted.append(
            f"[{i}] {r.get('title', 'No title')}\n"
            f"    {r.get('snippet', 'No snippet')}\n"
            f"    Source: {r.get('link', 'No URL')}"
        )
    return "\n\n".join(formatted)


# ─── Verdict Logic ───

def determine_overall_verdict(claims: list[ClaimVerdict]) -> str:
    """
    Determine the overall verdict from individual claim verdicts.

    Logic:
    - If ANY claim is SCAM → overall SCAM
    - If ANY claim is LIKELY_FALSE → overall LIKELY_FALSE
    - If ALL claims are LEGIT → overall LEGIT
    - Otherwise → UNVERIFIED
    """
    verdicts = [c.verdict for c in claims]

    if "SCAM" in verdicts:
        return "SCAM"
    if "LIKELY_FALSE" in verdicts:
        return "LIKELY_FALSE"
    if all(v == "LEGIT" for v in verdicts):
        return "LEGIT"
    return "UNVERIFIED"


# ─── Scam Pattern Detection ───

# Tier 1: Instant hardcoded patterns (zero cost, catches obvious scams)
SCAM_PATTERNS = [
    "send me a dm",
    "link in bio",
    "limited time offer",
    "act now",
    "guaranteed profit",
    "double your money",
    "free iphone",
    "free giveaway",
    "congratulations you won",
    "click the link",
    "dm me to claim",
    "investment opportunity",
    "100% guaranteed",
    "no risk",
    "wire transfer",
    "cash app me",
    "venmo me",
    "crypto giveaway",
    "send crypto",
    "whatsapp me",
    "earn money from home",
    "make money fast",
    "get rich quick",
    "binary options",
    "forex signals",
    "pyramid scheme",
    "multi-level marketing",
    "drop shipping course",
    "passive income secret",
    "financial freedom hack",
    "lottery winner",
    "you have been selected",
    "claim your prize",
    "verify your account",
    "urgent action required",
    "account suspended",
    "click here to verify",
    "confirm your identity",
]


def check_scam_patterns(text: str) -> Optional[ClaimVerdict]:
    """
    Tier 1: Quick local check for common scam patterns.
    Catches obvious scams instantly with zero API cost.
    """
    text_lower = text.lower()
    matched = [p for p in SCAM_PATTERNS if p in text_lower]

    if len(matched) >= 2:
        return ClaimVerdict(
            text="Multiple scam indicators detected",
            verdict="SCAM",
            explanation=f"This content contains known scam patterns: {', '.join(matched[:3])}",
            sources=[],
        )
    return None


async def search_scam_check(text: str) -> Optional[ClaimVerdict]:
    """
    Tier 2: Search for known scam reports matching this content.
    Checks if the content, product, person, or scheme has been reported as a scam.
    """
    try:
        # Extract key identifiers from the content (names, products, URLs, etc.)
        identifiers = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=200,
            system="""Extract the key identifiers from this social media content that could be
searched for scam reports. Look for: product names, person names, company names,
website URLs, app names, course names, investment scheme names.

Return ONLY a JSON object:
{
    "search_queries": ["query1", "query2"],
    "identifiers": ["name or product found"]
}

Return max 3 search queries. Each query should be the identifier + "scam" or "fraud".
If no identifiable names/products found, return empty arrays.""",
            messages=[
                {"role": "user", "content": text[:1500]}
            ],
        )

        response_text = identifiers.content[0].text.strip()
        if response_text.startswith("```"):
            response_text = response_text.split("```")[1]
            if response_text.startswith("json"):
                response_text = response_text[4:]
            response_text = response_text.strip()

        parsed = json.loads(response_text)
        queries = parsed.get("search_queries", [])

        if not queries:
            return None

        # Search for scam reports
        all_results = []
        for query in queries[:3]:
            results = await web_search(query)
            all_results.extend(results)

        if not all_results:
            return None

        # Check if search results indicate a known scam
        evidence = format_search_results(all_results[:6])

        assessment = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=300,
            system="""Based on the search results, determine if the content is related to a known scam.

Return ONLY a JSON object:
{
    "is_known_scam": true/false,
    "confidence": 0.0 to 1.0,
    "scam_type": "type of scam or null",
    "explanation": "brief explanation citing the search evidence",
    "source_indices": [0, 1]
}

Only mark is_known_scam=true if the search results clearly show this is a reported scam.
Consumer complaints, fraud alerts, or multiple scam reports count as clear evidence.""",
            messages=[
                {
                    "role": "user",
                    "content": f"Original content:\n{text[:500]}\n\nSearch results about potential scam:\n{evidence}",
                }
            ],
        )

        assess_text = assessment.content[0].text.strip()
        if assess_text.startswith("```"):
            assess_text = assess_text.split("```")[1]
            if assess_text.startswith("json"):
                assess_text = assess_text[4:]
            assess_text = assess_text.strip()

        result = json.loads(assess_text)

        if result.get("is_known_scam") and result.get("confidence", 0) >= 0.6:
            source_urls = []
            for idx in result.get("source_indices", []):
                if 0 <= idx < len(all_results):
                    source_urls.append(all_results[idx].get("link", ""))

            return ClaimVerdict(
                text=f"Known scam: {result.get('scam_type', 'reported scam')}",
                verdict="SCAM",
                explanation=result.get("explanation", "This matches known scam reports found online"),
                sources=source_urls,
            )

        return None

    except (json.JSONDecodeError, IndexError, KeyError, Exception):
        return None


async def ai_scam_check(text: str) -> Optional[ClaimVerdict]:
    """
    Tier 3: AI-powered scam detection for subtler scams that bypass
    both hardcoded patterns and search-based detection.
    This is the last resort — catches novel scams with no online reports yet.
    """
    try:
        response = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=512,
            system="""You are a scam detection specialist. Analyze the following social media content
for scam indicators that go beyond simple keyword matching.

Note: This content has already passed keyword-based scam detection and a web search
for known scam reports — neither found anything. Your job is to catch SUBTLE scams
that are too new or too clever for those methods.

Look for:
- Fake testimonials or fabricated success stories
- Emotional manipulation (fear, urgency, FOMO, guilt)
- Too-good-to-be-true promises (unrealistic returns, miracle cures)
- Social engineering tactics (impersonating authorities, fake deadlines)
- Phishing attempts (requesting personal info, login credentials)
- Pump-and-dump schemes (hyping stocks, crypto, NFTs)
- Fake charity or disaster relief scams
- Romance scam patterns
- Job scam patterns (easy money, no experience needed)
- Counterfeit product promotions
- Misleading health/wellness claims with product sales

Return ONLY a JSON object:
{
    "is_scam": true/false,
    "confidence": 0.0 to 1.0,
    "scam_type": "type of scam or null",
    "explanation": "brief explanation"
}

Only mark is_scam=true if confidence >= 0.7. Be careful not to flag legitimate
promotions, honest product reviews, or genuine personal stories.""",
            messages=[
                {
                    "role": "user",
                    "content": f"Analyze this social media content for scam indicators:\n\n{text[:2000]}",
                }
            ],
        )

        response_text = response.content[0].text.strip()
        if response_text.startswith("```"):
            response_text = response_text.split("```")[1]
            if response_text.startswith("json"):
                response_text = response_text[4:]
            response_text = response_text.strip()

        result = json.loads(response_text)

        if result.get("is_scam") and result.get("confidence", 0) >= 0.7:
            return ClaimVerdict(
                text=f"Scam detected: {result.get('scam_type', 'suspicious content')}",
                verdict="SCAM",
                explanation=result.get("explanation", "This content shows scam characteristics"),
                sources=[],
            )

        return None

    except (json.JSONDecodeError, IndexError, KeyError, Exception):
        return None


async def full_scam_check(text: str) -> Optional[ClaimVerdict]:
    """
    Run all three tiers of scam detection in order.
    Stops at the first tier that finds a scam.

    Tier 1: Hardcoded patterns (instant, free)
    Tier 2: Web search for known scam reports (fast, uses search API)
    Tier 3: AI analysis for novel/subtle scams (slower, uses Claude API)
    """
    # Tier 1: Instant pattern match
    pattern_result = check_scam_patterns(text)
    if pattern_result:
        return pattern_result

    # Tier 2: Search for known scam reports
    search_result = await search_scam_check(text)
    if search_result:
        return search_result

    # Tier 3: AI judgment for novel scams
    ai_result = await ai_scam_check(text)
    if ai_result:
        return ai_result

    return None


# ─── Run Server ───

if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("PORT", "8000"))
    uvicorn.run("server:app", host="0.0.0.0", port=port, reload=True)