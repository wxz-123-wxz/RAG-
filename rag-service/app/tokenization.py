import re


_TOKEN_RE = re.compile(r"[\u4e00-\u9fff]|[a-zA-Z0-9_]+", re.UNICODE)


def tokenize(text: str) -> list[str]:
    tokens: list[str] = []
    for token in _TOKEN_RE.findall(text.lower()):
        if len(token) > 1 and token.isascii():
            tokens.extend(token.split("_"))
        else:
            tokens.append(token)
    return [token for token in tokens if token]

