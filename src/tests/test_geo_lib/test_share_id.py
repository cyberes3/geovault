from geo_lib.sharing.share_id import ShareId


def test_parse_accepts_uuid4():
    parsed = ShareId.parse("f8a918ab-7f53-4ef3-be11-a957c40ebd02")
    assert parsed is not None
    assert parsed.value == "f8a918ab-7f53-4ef3-be11-a957c40ebd02"


def test_parse_normalizes_case():
    parsed = ShareId.parse("F8A918AB-7F53-4EF3-BE11-A957C40EBD02")
    assert parsed is not None
    assert parsed.value == "f8a918ab-7f53-4ef3-be11-a957c40ebd02"


def test_parse_rejects_non_uuid4():
    assert ShareId.parse("not-a-uuid") is None
    assert ShareId.parse("") is None
    assert ShareId.parse(None) is None
    # UUID1-shaped value
    assert ShareId.parse("f8a918ab-7f53-1ef3-be11-a957c40ebd02") is None


def test_generate_unique_retries_until_free():
    seen = {"first"}
    calls = {"n": 0}

    def exists(token: str) -> bool:
        calls["n"] += 1
        if token in seen:
            return True
        seen.add(token)
        return False

    generated = ShareId.generate_unique(exists)
    assert generated.value not in {"first"}
    assert ShareId.parse(generated.value) is not None
    assert calls["n"] >= 1
