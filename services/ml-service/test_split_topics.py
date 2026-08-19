"""
Tests for _split_topics() and TOPIC_OVERLOAD_THRESHOLD from app.py.

Follows the same convention as test_fallback.py:
  - flat pytest-style, plain `from app import` import
  - no test-framework scaffolding beyond bare `assert`
  - `test_...` function names
  - `if __name__ == "__main__":` runner block

Run with:
    python3 -m pytest services/ml-service/test_split_topics.py -v
or from inside services/ml-service/:
    ../.venv/bin/pytest test_split_topics.py -v
"""

import sys
import os
import pytest

# Make sure the ml-service directory is on sys.path when run from repo root
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from app import _split_topics, TOPIC_OVERLOAD_THRESHOLD  # noqa: E402  (after sys.path fix)


# ─── Case a ──────────────────────────────────────────────────────────────────

def test_single_question():
    """a. Single question with one '?' → exactly 1 topic."""
    topics = _split_topics("What is the capital of France?")
    assert len(topics) == 1
    assert topics[0] == "What is the capital of France"


# ─── Case b ──────────────────────────────────────────────────────────────────

def test_five_unrelated_questions():
    """b. Five unrelated '?'-delimited questions → exactly 5 topics."""
    query = (
        "What is the capital of France? "
        "How do I fix a NullPointerException? "
        "What is quantum entanglement? "
        "What is a banana bread recipe? "
        "How does mortgage refinance work?"
    )
    topics = _split_topics(query)
    assert len(topics) == 5


# ─── Case c ──────────────────────────────────────────────────────────────────

def test_boundary_exactly_three_not_overload():
    """c. Exactly 3 '?'-delimited topics → NOT flagged as overload."""
    query = "One question here? Two questions there? Three questions wow?"
    topics = _split_topics(query)
    assert len(topics) == 3
    assert not (len(topics) > TOPIC_OVERLOAD_THRESHOLD)


def test_boundary_exactly_four_is_overload():
    """c. Exactly 4 '?'-delimited topics → IS flagged as overload."""
    query = "One question here? Two questions there? Three questions wow? Four questions now?"
    topics = _split_topics(query)
    assert len(topics) == 4
    assert len(topics) > TOPIC_OVERLOAD_THRESHOLD


# ─── Case d ──────────────────────────────────────────────────────────────────

def test_semicolon_separated_no_question_mark():
    """d. Four semicolon-separated clauses with no '?' → semicolon branch fires."""
    query = "first clause here; second clause there; third clause wow; fourth clause finally"
    topics = _split_topics(query)
    assert len(topics) == 4


# ─── Case e ──────────────────────────────────────────────────────────────────

def test_numbered_list_dot_style():
    """e. Numbered list using '1.' style markers → all three topics split correctly."""
    query = "1. First topic here 2. Second topic there 3. Third topic wow"
    topics = _split_topics(query)
    assert len(topics) == 3


def test_numbered_list_paren_style():
    """e. Numbered list using '1)' style markers → all three topics split correctly."""
    query = "1) First topic here 2) Second topic there 3) Third topic wow"
    topics = _split_topics(query)
    assert len(topics) == 3


def test_numbered_list_mixed_styles():
    """e. Mixed '1.' and '1)' markers in the same query → all three split correctly."""
    query = "1. First topic here 2) Second topic there 3. Third topic wow"
    topics = _split_topics(query)
    assert len(topics) == 3


# ─── Case f ──────────────────────────────────────────────────────────────────

def test_short_numbered_stems_survive_word_filter():
    """f. Numbered stems that are exactly 2 words survive the >= 2-word filter."""
    query = "1. Explain gravity 2. Explain inflation 3. Explain recursion"
    topics = _split_topics(query)
    # Each stem is 2 words; all three should survive
    assert len(topics) == 3
    # Spot-check text
    assert "Explain gravity" in topics[0]
    assert "Explain inflation" in topics[1]


# ─── Case g ──────────────────────────────────────────────────────────────────

def test_empty_string():
    """g. Empty string → []."""
    assert _split_topics("") == []


def test_whitespace_only():
    """g. Whitespace-only string → []."""
    assert _split_topics("   \t  \n  ") == []


# ─── Case h ──────────────────────────────────────────────────────────────────

def test_single_vague_word_no_question_mark():
    """h. Single word below the word-count filter → [] (scope stays untouched)."""
    result = _split_topics("it")
    assert result == []
    # Confirm this cannot accidentally be mistaken for overload
    assert not (len(result) > TOPIC_OVERLOAD_THRESHOLD)


# ─── Case i ──────────────────────────────────────────────────────────────────

def test_decimal_numbers_do_not_split():
    """
    i. Declarative query containing decimal numbers should not be split
       incorrectly by the list-marker regex.
    """
    query = (
        "GDP grew 3.2 percent, inflation hit 2.1 percent, "
        "unemployment fell to 4.5 percent, and consumer confidence rose 1.8 points"
    )
    topics = _split_topics(query)
    assert len(topics) == 1
    assert not (len(topics) > TOPIC_OVERLOAD_THRESHOLD)


# ─── Case j ──────────────────────────────────────────────────────────────────

def test_rhetorical_question_in_quotes():
    """
    j. A sentence with a rhetorical '?' inside quoted speech should resolve
       to 1 real topic.
    """
    query = 'He said "why?" and walked out.'
    topics = _split_topics(query)
    assert len(topics) == 1
    assert not (len(topics) > TOPIC_OVERLOAD_THRESHOLD)


# ─── Case k ──────────────────────────────────────────────────────────────────

def test_terse_one_word_topics():
    """
    k. One-word '?'-delimited topics should not be silently dropped.
       Five real distinct questions should produce 5 topics.
    """
    query = "Cats? Dogs? Birds? Fish? Reptiles?"
    topics = _split_topics(query)
    assert len(topics) == 5
    assert len(topics) > TOPIC_OVERLOAD_THRESHOLD


# ─── Runner ───────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    pytest.main(["-v", __file__])
