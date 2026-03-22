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

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from dotenv import load_dotenv
load_dotenv()
import anthropic
import httpx

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

    # Check cache first
    cache_key = hashlib.md5(text.encode()).hexdigest()
    cached = verdict_cache.get(cache_key)
    if cached and (time.time() - cached["timestamp"]) < CACHE_TTL_SECONDS:
        result = cached["result"]
        result["cached"] = True
        return result

    # Step 0: Quick local scam pattern check (instant, no API call)
    scam_verdict = check_scam_patterns(text)
    if scam_verdict:
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
    claims = await extract_claims(text)

    if not claims:
        return FactCheckResponse(
            overall_verdict="LEGIT",
            summary="No specific factual claims detected in this content.",
            claims=[],
            checked_at=datetime.utcnow().isoformat(),
        )

    # Step 2: Verify each claim
    verified_claims = []
    for claim_text in claims:
        verdict = await verify_claim(claim_text, text)
        verified_claims.append(verdict)

    # Step 3: Determine overall verdict
    overall = determine_overall_verdict(verified_claims)

    # Step 4: Generate summary
    summary = await generate_summary(text, verified_claims, overall)

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
    """
    # Search for evidence
    search_results = await web_search(claim)

    if not search_results:
        return ClaimVerdict(
            text=claim,
            verdict="UNVERIFIED",
            explanation="Could not find reliable sources to verify or refute this claim.",
            sources=[],
        )

    # Format search results for Claude
    evidence_text = format_search_results(search_results)

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
    "source_indices": [0, 1]  // indices of the most relevant search results
}

Verdict guidelines:
- LEGIT: Claim is supported by multiple reliable sources
- LIKELY_FALSE: Claim is contradicted by reliable sources
- UNVERIFIED: Not enough evidence either way
- SCAM: Content shows scam patterns (fake giveaways, too-good-to-be-true offers, urgency tactics, phishing)

Be conservative — only mark LIKELY_FALSE if there is clear contradicting evidence.""",
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
            if 0 <= idx < len(search_results):
                source_urls.append(search_results[idx].get("link", ""))

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


# ─── Scam Pattern Detection (runs locally, no API call needed) ───

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
]


def check_scam_patterns(text: str) -> Optional[ClaimVerdict]:
    """
    Quick local check for common scam patterns.
    Runs before the LLM pipeline to catch obvious scams fast.
    """
    text_lower = text.lower()
    matched = [p for p in SCAM_PATTERNS if p in text_lower]

    if len(matched) >= 2:  # Two or more scam patterns = likely scam
        return ClaimVerdict(
            text="Multiple scam indicators detected",
            verdict="SCAM",
            explanation=f"This content contains known scam patterns: {', '.join(matched[:3])}",
            sources=[],
        )
    return None


# ─── Run Server ───

if __name__ == "__main__":
    import uvicorn

    port = int(os.getenv("PORT", "8000"))
    uvicorn.run("server:app", host="0.0.0.0", port=port, reload=True)
