"""Conservative lexical evidence gate for the hashing baseline, not a semantic score."""
import math
import re
import unicodedata


_LEADING = re.compile(r"^(?:请问|请介绍一下|请介绍|请解释一下|请解释|介绍一下|解释一下|介绍|什么是|何为|那么|说明|简述|请|如何|怎样|怎么)\s*")
_TRAILING = re.compile(r"(?:是什么|是啥|有哪些|是什么原理|有什么区别|有何区别|有什么特点|有什么作用|有什么用|是什么东西|是什么概念|的定义|的概念|的含义|的作用|的原理|的必要条件|的过程|的基本步骤|的步骤|的区别|的特点|吗|呢|吧)$")
_ENGLISH_STOP = {"what", "is", "are", "a", "an", "the", "of", "in", "how", "does", "do", "explain", "please", "and", "or"}

# Query facets guide ranking; they are not additional concepts that must occur
# literally in every chunk. These lexical cues are not a semantic reranker.
_FACET_CUES = {
    "必要条件": ("必要条件",),
    "查找过程": ("查找", "搜索", "search", "find("),
    "查找步骤": ("查找", "搜索", "search", "find("),
    "查找": ("查找", "搜索", "search", "find("),
    "适用场景": ("适用", "应用", "场景", "application", "used for", "used in", "useful for", "efficient in", "suitable for"),
    "应用场景": ("适用", "应用", "场景", "application", "used for", "used in", "useful for", "efficient in", "suitable for"),
    "时间复杂度": ("时间复杂度", "time complexity", "running time"),
    "空间复杂度": ("空间复杂度", "space complexity", "space usage"),
    "特点": ("特点", "性质", "properties"),
}


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text).lower()
    text = re.sub(r"(?<=[\u4e00-\u9fff])[·•](?=[\u4e00-\u9fff])", "", text)
    # Spelling variants preserve the distinction between B trees and B+ trees.
    text = re.sub(r"(?<![a-z0-9_])b\s*\+\s*[-－]?\s*(?:trees?(?![a-z0-9_])|树)", "b+树", text)
    text = re.sub(r"(?<![a-z0-9_])b\s*[-－]?\s*(?:trees?(?![a-z0-9_])|树)", "b树", text)
    return text


def _canonical_algorithm_subject(text: str) -> str:
    # Query-only aliases: preserve source text, stored vectors, and citation offsets.
    # Do not map ambiguous phrases such as 选择算法 or 快速算法 to sorting.
    for alias, canonical in (
        ('高速缓冲存储器', 'cache'), ('域名系统', 'dns'),
        ('单一职责原则', '单一责任原则'), ('折半查找', '二分查找'),
        ('虚存', '虚拟内存'),
    ):
        text = re.sub(r'^' + alias + r'(?=$|的|是|有|如|怎|为|需|能|\s)', canonical, text)
    text = re.sub(r"^冒泡算法(?=$|的|\s)", "冒泡排序", text)
    return re.sub(r"^((?:冒泡|选择|插入|希尔|归并|快速|堆|计数|桶|基数)排序)算法(?=$|的|\s)", r"\1", text)


def explanatory_topic(query: str) -> str | None:
    """Use the leading topic of a quoted definition as a retrieval query only.

    A translated sentence may share its concept name, but no surrounding words,
    with an English source. Do not demand literal overlap for the whole claim.
    This is not a semantic rewrite: full user wording still goes to generation.
    """
    text = normalize(query).strip()
    text = re.sub(r"^(?:请)?(?:解释|说明|介绍)(?:一下)?\s*", "", text)
    text = text.lstrip("\"'“‘「『 ")
    match = re.match(r"^([a-z0-9_+#\u4e00-\u9fff]{2,24}?)\s*(?:是|指的是)(.+)", text, re.S)
    if not match:
        return None
    topic, statement = match.groups()
    # Keep interrogative facets and explicit additional tasks in the normal path.
    # Do not turn 'B树是怎么删除的' into a generic definition lookup.
    if (topic in {"这是", "这个", "那个", "这些", "那些", "它们", "什么", "问题", "答案", "原文"}
            or re.match(r"(?:否|不|怎么|怎样|如何|什么|谁|哪|几|多少)", statement)
            or re.search(r"什么|如何|怎么|怎样|是否|为什么|有哪些|多少", statement)
            or re.search(r"(?:[，,。；;\n]|并|同时|另外|还)(?:\s|也|请)*(?:说明|解释|介绍|比较|回答|计算|给出|证明)", statement)):
        return None
    return _canonical_algorithm_subject(topic)


def query_focus(query: str) -> tuple[list[str], list[str]] | None:
    """Parse bounded condition/comparison forms without discarding unknown tasks.

    Only retrieval uses this decomposition. Generation still receives the full
    question and decides whether the retrieved text supports the requested facets.
    """
    text = normalize(query).strip(" \t\r\n?？!！。.,，:：;；")
    previous = None
    while text != previous:
        previous = text
        text = _LEADING.sub("", text).strip()
    condition = re.fullmatch(
        r"(?:产生|发生)?(.{2,24}?)(?:发生|产生)?(?:的|\s+)"
        r"(?:[一二三四五六七八九十两\d]+(?:个|项|种)\s*)?必要条件"
        r"(?:是什么|有哪些|包括哪些|包括什么|包括|有哪几种)?", text)
    if condition and re.fullmatch(r"[a-z0-9_+#\u4e00-\u9fff]+", condition[1]):
        return ([_canonical_algorithm_subject(condition[1])], ["必要条件"])
    condition = re.fullmatch(r"(?:产生|发生)?([a-z0-9_+#\u4e00-\u9fff]{2,24}?)(?:必须|需要)满足哪些条件", text)
    if condition:
        return ([_canonical_algorithm_subject(condition[1])], ['必要条件'])

    # Two whitespace-separated tree names are an unambiguous concept list;
    # keep qualifiers like 'TCP 三次握手' together on the existing path.
    trees = re.fullmatch(r"([a-z0-9_+#\u4e00-\u9fff]+树)\s+([a-z0-9_+#\u4e00-\u9fff]+树)", text)
    if trees:
        return (list(dict.fromkeys(trees.groups())), [])

    explicit = bool(re.match(r"^(?:比较|对比)(?:一下)?", text)
                    or re.search(r"(?:区别|比较|对比)$", text))
    if not explicit:
        return None
    text = re.sub(r"^(?:比较|对比)(?:一下)?\s*", "", text)
    text = re.sub(r"(?:有什么区别|有何区别|的区别|区别|比较|对比)$", "", text).strip()
    concepts, separator, facet_text = text.partition("的")
    facets = [p.strip() for p in re.split(r"以及|和|与|及|、", facet_text)] if separator else []
    if any(facet not in _FACET_CUES for facet in facets):
        return None  # Unknown qualifications must not silently disappear.
    subjects = [p.strip() for p in re.split(r"以及|和|与|、|\s+(?:and|versus|vs\.?)\s+", concepts)]
    if len(subjects) < 2 or any(not re.fullmatch(r"[a-z0-9_+#\u4e00-\u9fff]{2,24}", p) for p in subjects):
        return None
    return (list(dict.fromkeys(_canonical_algorithm_subject(p) for p in subjects)), facets)


def focus_match_count(facets: list[str], content: str) -> int:
    """Count requested facet cues only after the substantive topic gate passes."""
    text = evidence_body(content, keep_headings=True)
    return sum(any(cue in text for cue in _FACET_CUES[facet]) for facet in facets)


def focus_priority(subject: str, facets: list[str], content: str, document_name: str) -> tuple[int, int, int, int]:
    """Rank already eligible chunks by topic location and requested facet cues.

    Titles can prefer a chapter about the concept over an incidental mention;
    they never admit a candidate rejected by the body gate. Procedure cues
    distinguish actual search steps from a paragraph only giving complexity.
    """
    body = evidence_body(content)
    direct = _subject_matches(subject, normalize(document_name))
    procedure = 0
    if any(facet in {"查找过程", "查找步骤"} for facet in facets):
        procedure = sum(bool(re.search(pattern, body)) for pattern in (
            r"从.{0,12}根节点|starts? at the root|from the root",
            r"左子树|右子树|子节点|children|child|subtree",
            r"比较|若 |如果|determine|checks? if|compares?",
        ))
    headings = normalize("\n".join(line for line in content.splitlines() if line.lstrip().startswith("#")))
    heading_matches = sum(any(cue in headings for cue in _FACET_CUES[facet]) for facet in facets)
    return (int(direct), focus_match_count(facets, content), procedure, heading_matches)


def query_subjects(query: str) -> list[str]:
    topic = explanatory_topic(query)
    if topic:
        return [topic]
    focus = query_focus(query)
    if focus:
        return focus[0]
    text = normalize(query).strip(" \t\r\n?？!！。.,，:：;；")
    # Explicit comparisons may draw evidence for either subject from separate chunks.
    parts = re.split(r"[，,。?？;；\n]|(?:和|与|以及|或者|相比)|\s+(?:and|versus|vs\.?|or)\s+", text)
    subjects = []
    for part in parts:
        # Separate interrogative wording from the requested topic/facet.
        part = re.sub(r"(?:应该|应当)?关注哪些方面$|由哪些(?:部件|部分|组件)组成$", "", part)
        part = re.sub(r"(?:有哪些|有哪几种)", " ", part)
        previous = None
        while part != previous:
            previous = part
            part = _LEADING.sub("", part.strip())
            part = _TRAILING.sub("", part).strip(" \t?？!！。.,，:：;；")
        # A bounded explanatory predicate is a task, not part of the concept
        # name. Keep the full original question for generation and do not strip
        # arbitrary yes/no qualifiers or unknown multi-task comparisons.
        part_focus = query_focus(part)
        if part_focus:
            subjects.extend(part_focus[0])
            continue
        explanation = re.fullmatch(r'([a-z0-9_+#\u4e00-\u9fff]{2,24}?)(?:为什么|如何).+', part)
        if explanation:
            part = explanation[1]
        part = re.sub(r'(?:是)?怎样工作的?$', '', part)
        # Short condition subquestions can coexist with an unsupported second
        # request. This does not make the full multi-task question complete.
        part = re.sub(r'(?<=锁)条件$', '', part)
        part = _canonical_algorithm_subject(part)
        if part and part not in {"它", "它们", "这个", "那个", "这种", "那种", "两者", "二者"}:
            subjects.append(part)
    return subjects


def lexical_terms(text: str) -> list[str]:
    terms = []
    for term in re.findall(r"[a-z][a-z0-9_]*(?:\+\+|\+|#)?\s*树|[a-z][a-z0-9_]*(?:\+\+|\+|#)?|[\u4e00-\u9fff]+", normalize(text)):
        if term[0].isascii():
            if term not in _ENGLISH_STOP:
                terms.append(term)
        else:
            terms.extend(term)
            terms.extend(term[i:i + 2] for i in range(len(term) - 1))
    return terms


def evidence_body(content: str, *, keep_headings: bool = False) -> str:
    lines = []
    for line in content.splitlines():
        stripped = line.strip()
        if (stripped.startswith("#") and not keep_headings) or stripped.startswith(("> 来源", "> 作者", "> 固定版本", "> 本副本", "> 格式处理", "> 这是公开", "<!--")):
            continue
        if re.fullmatch(r"[-*+]?\s*\[[^\]]+\]\(#[^)]*\)", stripped):
            continue
        lines.append(line)
    return normalize("\n".join(lines))


def _subject_matches(subject: str, body: str, headings: str = "") -> bool:
    # Keep Latin identifiers bound to their Chinese suffix: b + an unrelated 树
    # elsewhere in a chunk is not evidence for B树; c/c++/c# are distinct too.
    terms = re.findall(r"[a-z][a-z0-9_]*(?:\+\+|\+|#)?\s*树|[a-z][a-z0-9_]*(?:\+\+|\+|#)?|[\u4e00-\u9fff]+", subject)
    signals = []
    contextual_signals = []
    for term in terms:
        term = term.strip()
        if term in _ENGLISH_STOP:
            continue
        if term[0].isascii():
            pattern = r"(?<![a-z0-9_+#])" + re.escape(term).replace(r"\ ", r"\s*") + r"(?![a-z0-9_+#])"
            # Chinese text may immediately follow an ASCII identifier.
            signals.append(bool(re.search(pattern, body)))
            contextual_signals.append(bool(re.search(pattern, body + "\n" + headings)))
        elif term in {"栈", "堆", "锁", "图", "树"}:
            signals.append(term in body)
            contextual_signals.append(term in body)
        elif len(term) >= 2:
            if len(term) <= 3:
                signals.append(term in body)
            else:
                grams = {term[i:i + 2] for i in range(len(term) - 1)}
                matches = sum(gram in body for gram in grams)
                signals.append(matches >= max(2, math.ceil(len(grams) * .7)))
            contextual_signals.append(signals[-1])
    # A heading can supply an ASCII qualifier (e.g. TCP) when the substantive
    # Chinese topic (e.g. 三次握手) is present in the body. A title alone cannot pass.
    return bool(signals) and any(signals) and all(contextual_signals)


def has_relevant_evidence(query: str, content: str) -> bool:
    body = evidence_body(content)
    headings = normalize("\n".join(line.lstrip("# ") for line in content.splitlines() if line.lstrip().startswith("#")))
    return any(_subject_matches(subject, body, headings) for subject in query_subjects(query))


def is_concept_overview(query: str, content: str) -> bool:
    """Reserve evidence for a brief concept lookup; not a semantic reranker."""
    subjects = query_subjects(query)
    if len(subjects) != 1:
        return False
    topic = subjects[0]
    question = normalize(query).strip(" \t\r\n?？!！。")
    question = re.sub(r"^(?:什么是|介绍)", "", question)
    question = re.sub(r"是什么$", "", question).strip()
    if len(topic) > 24 or _canonical_algorithm_subject(question) != topic:
        return False
    # A concrete definition must start a body line. A matching title/TOC or a
    # passing mention of the subject is not sufficient to reserve the slot.
    body = evidence_body(content).replace("**", "").replace("__", "")
    pattern = r"^\s*(?:a |an )?" + re.escape(topic) + r"(?:算法)?[^\n。.!?]{0,65}(?:是一|是指|指的是|用于|定义为|\bis (?:a|an|the)\b)"
    # Some introductions explain the mechanism directly, e.g. 冒泡排序通过…实现排序.
    mechanism = (r"^\s*" + re.escape(topic) + r"(?:算法)?\s*(?:\([^\n)]{1,60}\)\s*)?"
                 r"通过[^\n。.!?]{1,65}实现")
    return bool(re.search(pattern, body, re.M) or re.search(mechanism, body, re.M))
