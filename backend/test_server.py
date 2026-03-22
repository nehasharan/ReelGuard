"""
Quick test script for the ReelGuard backend.
Run the server first: python server.py
Then run this: python test_server.py
"""

import httpx
import json
import sys

BASE_URL = "http://localhost:8000"


def test_health():
    print("─── Testing /health ───")
    resp = httpx.get(f"{BASE_URL}/health")
    print(f"Status: {resp.status_code}")
    print(f"Response: {resp.json()}")
    print()


def test_fact_check_legit():
    print("─── Testing fact-check with likely legit content ───")
    resp = httpx.post(
        f"{BASE_URL}/api/fact-check",
        json={
            "app_package": "com.instagram.android",
            "text": """
                The Earth orbits the Sun at an average distance of about 93 million miles.
                Water freezes at 0 degrees Celsius at standard atmospheric pressure.
                The Great Wall of China is visible from space with the naked eye.
            """,
        },
        timeout=120.0,
    )
    print(f"Status: {resp.status_code}")
    print(f"Response: {json.dumps(resp.json(), indent=2)}")
    print()


def test_fact_check_false():
    print("─── Testing fact-check with likely false content ───")
    resp = httpx.post(
        f"{BASE_URL}/api/fact-check",
        json={
            "app_package": "com.instagram.android",
            "text": """
                BREAKING: Scientists confirm that drinking bleach cures all diseases!
                A new study from Harvard proves that vaccines cause autism.
                The government is hiding proof that the earth is flat.
            """,
        },
        timeout=120.0,
    )
    print(f"Status: {resp.status_code}")
    print(f"Response: {json.dumps(resp.json(), indent=2)}")
    print()


def test_fact_check_scam():
    print("─── Testing fact-check with scam content ───")
    resp = httpx.post(
        f"{BASE_URL}/api/fact-check",
        json={
            "app_package": "com.instagram.android",
            "text": """
                🎉 CONGRATULATIONS! You've been selected for a FREE iPhone 16!
                Click the link in bio to claim your prize! Limited time offer!
                DM me to claim your reward! Act now before it's too late!
                Send me a DM with your details to receive your prize!
            """,
        },
        timeout=120.0,
    )
    print(f"Status: {resp.status_code}")
    print(f"Response: {json.dumps(resp.json(), indent=2)}")
    print()


def test_fact_check_no_claims():
    print("─── Testing fact-check with opinion content (no claims) ───")
    resp = httpx.post(
        f"{BASE_URL}/api/fact-check",
        json={
            "app_package": "com.instagram.android",
            "text": """
                I love this coffee shop so much! The vibes are immaculate.
                Spending my Sunday morning just chilling and reading.
                Life is good when you find your happy place.
            """,
        },
        timeout=120.0,
    )
    print(f"Status: {resp.status_code}")
    print(f"Response: {json.dumps(resp.json(), indent=2)}")
    print()


def test_short_text():
    print("─── Testing fact-check with too-short text ───")
    resp = httpx.post(
        f"{BASE_URL}/api/fact-check",
        json={
            "app_package": "com.instagram.android",
            "text": "Hello",
        },
        timeout=120.0,
    )
    print(f"Status: {resp.status_code}")
    print(f"Response: {resp.json()}")
    print()


if __name__ == "__main__":
    print("🛡️ ReelGuard Backend Tests\n")

    test_health()

    if "--quick" in sys.argv:
        test_short_text()
        print("✅ Quick tests passed!")
    else:
        test_short_text()
        test_fact_check_no_claims()
        test_fact_check_legit()
        test_fact_check_false()
        test_fact_check_scam()
        print("✅ All tests complete!")
