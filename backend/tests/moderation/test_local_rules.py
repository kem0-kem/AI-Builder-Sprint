from app.moderation.local_rules import LocalRuleEngine, normalize_text
from app.moderation.schemas import ModerationCategory, ModerationDecision


def test_normalization_is_deterministic() -> None:
    assert normalize_text("  ＨＥＬＬＯ\r\n  world  ") == "HELLO\nworld"
    assert normalize_text(normalize_text("  ＨＥＬＬＯ\r\n  world  ")) == "HELLO\nworld"


def test_exact_repeated_message_spam_is_blocked() -> None:
    result = LocalRuleEngine().inspect("buy now\nBUY   NOW\nbuy now\nbuy now")
    assert result.decision is ModerationDecision.BLOCK
    assert result.categories == {ModerationCategory.SPAM}


def test_more_than_three_urls_is_blocked() -> None:
    result = LocalRuleEngine().inspect(
        "https://a.example https://b.example https://c.example https://d.example"
    )
    assert result.decision is ModerationDecision.BLOCK


def test_adjacent_comma_and_semicolon_urls_are_counted_separately() -> None:
    result = LocalRuleEngine().inspect(
        "https://a.example,https://b.example;https://c.example,https://d.example"
    )
    assert result.decision is ModerationDecision.BLOCK


def test_personal_data_patterns_are_reviewed_not_blocked() -> None:
    engine = LocalRuleEngine()
    for text in (
        "mail me at person@example.com",
        "call 010-1234-5678",
        "resident 900101-1234567",
    ):
        result = engine.inspect(text)
        assert result.decision is ModerationDecision.REVIEW
        assert result.categories == {ModerationCategory.PERSONAL_DATA}


def test_repeated_character_run_is_reviewed() -> None:
    result = LocalRuleEngine().inspect("ㅋㅋㅋㅋㅋㅋㅋㅋㅋㅋ")
    assert result.decision is ModerationDecision.REVIEW
    assert result.categories == {ModerationCategory.SPAM}


def test_normal_text_is_allowed() -> None:
    result = LocalRuleEngine().inspect("안녕하세요. 오늘 날씨가 좋네요.")
    assert result.decision is ModerationDecision.ALLOW
    assert result.categories == set()
