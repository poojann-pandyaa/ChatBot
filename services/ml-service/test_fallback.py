import json
import os
from app import _keyword_fallback

def test_keyword_fallback():
    cases_path = os.path.join(os.path.dirname(__file__), '../../configs/classifier_test_cases.json')
    with open(cases_path, 'r') as f:
        cases = json.load(f)

    for case in cases:
        query = case['query']
        expected = case['expected']
        result = _keyword_fallback(query) or "commonsense"
        assert result == expected, f"Failed for query '{query}': expected {expected}, got {result}"

    print("Python fallback tests passed!")

if __name__ == "__main__":
    test_keyword_fallback()
