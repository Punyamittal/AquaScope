"""Tests for privacy/data_filter.py — sensitive data redaction."""

from screenmind.privacy.data_filter import filter_sensitive_text, parse_enabled_types


def test_redact_credit_card():
    text = "My card is 4111-1111-1111-1111 thanks"
    result = filter_sensitive_text(text, ["credit_card"])
    assert "[REDACTED:card]" in result["clean_text"]
    assert "4111" not in result["clean_text"]
    assert result["redacted_count"] == 1
    assert "credit_card" in result["types_found"]


def test_credit_card_luhn_rejects_random_16_digits():
    """16-digit numbers that fail Luhn checksum are NOT redacted."""
    text = "tracking number: 1234-5678-9012-3456"
    result = filter_sensitive_text(text, ["credit_card"])
    assert result["redacted_count"] == 0
    assert "1234-5678-9012-3456" in result["clean_text"]


def test_credit_card_luhn_accepts_valid_mastercard():
    """Valid Mastercard test number passes Luhn."""
    text = "pay with 5500-0000-0000-0004"
    result = filter_sensitive_text(text, ["credit_card"])
    assert "[REDACTED:card]" in result["clean_text"]


def test_redact_ssn():
    text = "SSN: 123-45-6789"
    result = filter_sensitive_text(text, ["ssn"])
    assert "[REDACTED:ssn]" in result["clean_text"]
    assert "123-45-6789" not in result["clean_text"]


def test_redact_api_key_openai():
    text = "key: sk-abcdefghijklmnopqrstuvwxyz1234567890"
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_api_key_github():
    text = "token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghij"
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_password():
    text = "password: mysecretpass123"
    result = filter_sensitive_text(text, ["password"])
    assert "[REDACTED:password]" in result["clean_text"]
    assert "mysecretpass123" not in result["clean_text"]


def test_no_redaction_clean_text():
    text = "Just a normal sentence about coding in Python."
    result = filter_sensitive_text(text, ["credit_card", "ssn", "api_key", "password"])
    assert result["clean_text"] == text
    assert result["redacted_count"] == 0
    assert result["types_found"] == []


def test_empty_text():
    result = filter_sensitive_text("", ["credit_card"])
    assert result["clean_text"] == ""
    assert result["redacted_count"] == 0


def test_none_text():
    result = filter_sensitive_text(None, ["credit_card"])
    assert result["clean_text"] == ""


def test_multiple_redactions():
    text = "Card: 4111 1111 1111 1111, SSN: 999-88-7777, key: sk-AAAABBBBCCCCDDDDEEEEFFFFGGGG"
    result = filter_sensitive_text(text, ["credit_card", "ssn", "api_key"])
    assert result["redacted_count"] == 3
    assert len(result["types_found"]) == 3


def test_parse_enabled_types():
    assert parse_enabled_types("credit_card,ssn") == ["credit_card", "ssn"]
    assert parse_enabled_types("") == ["credit_card", "ssn", "api_key", "jwt", "password"]
    assert parse_enabled_types("invalid,credit_card") == ["credit_card"]


# ── New pattern coverage ──────────────────────────────────────────────


def test_redact_anthropic_key():
    text = "key: sk-ant-api03-AAAABBBBCCCCDDDDEEEEFFFFGGGG-1234567890"
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]
    assert "sk-ant-" not in result["clean_text"]


def test_redact_stripe_live_key():
    text = "STRIPE_KEY=" + "sk_live_" + "A" * 24  # build key dynamically to avoid GitHub push protection
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]
    assert "sk_live_" not in result["clean_text"]


def test_redact_stripe_test_key():
    text = "key: " + "sk_test_" + "A" * 24  # dynamic to avoid GitHub push protection
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_stripe_publishable_key():
    text = "pk_live_" + "A" * 24 + " in the frontend"
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_github_fine_grained_pat():
    text = "token: github_pat_AAAA_BBBBCCCCDDDDEEEEFFFFGGGG"
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]
    assert "github_pat_" not in result["clean_text"]


def test_redact_github_oauth_token():
    text = "gho_" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + "abcdefghij1234"
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_notion_key():
    text = "NOTION_KEY=" + "ntn_" + "A" * 24
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_webhook_secret():
    text = "webhook: " + "whsec_" + "A" * 24  # build dynamically to avoid GitHub push protection
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_jwt_token():
    text = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
    result = filter_sensitive_text(text, ["jwt"])
    assert "[REDACTED:jwt]" in result["clean_text"]
    assert "eyJ" not in result["clean_text"]


def test_jwt_not_triggered_by_short_strings():
    """Short 'eyJ' strings that aren't JWTs should not match."""
    text = "the eyJ fragment alone is not a JWT"
    result = filter_sensitive_text(text, ["jwt"])
    assert result["redacted_count"] == 0


def test_redact_aws_access_key():
    text = "AWS_KEY=" + "AKIA" + "IOSFODNN7EXAMPLE"
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_slack_token():
    text = "SLACK=xoxb-123456789012-abcdefghij"
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_redact_gitlab_token():
    text = "token: " + "glpat-" + "A" * 24
    result = filter_sensitive_text(text, ["api_key"])
    assert "[REDACTED:key]" in result["clean_text"]


def test_email_not_redacted_by_default():
    """Email is defined but not in the default enabled list."""
    text = "contact me at user@example.com"
    result = filter_sensitive_text(text)  # uses defaults
    assert "user@example.com" in result["clean_text"]


def test_email_redacted_when_enabled():
    text = "contact me at user@example.com"
    result = filter_sensitive_text(text, ["email"])
    assert "[REDACTED:email]" in result["clean_text"]

