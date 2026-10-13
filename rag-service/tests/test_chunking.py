import pytest

from app.chunking import split_text


def test_split_text_preserves_content_with_overlap():
    text = "第一段内容。\n" + "第二段内容。" * 80
    chunks = split_text(text, chunk_size=100, overlap=20)

    assert len(chunks) > 1
    assert all(chunks)
    assert all(len(chunk) <= 100 for chunk in chunks)
    assert "第一段内容。" in "".join(chunks)


def test_split_text_rejects_invalid_window():
    with pytest.raises(ValueError):
        split_text("text", 10, 10)


def test_split_text_returns_no_empty_chunks():
    assert split_text(" \n\t ") == []

