"""Offline v2 evidence-group metrics. Never score final answer citations as ranks.

Offsets are Unicode code points in the fixed source identified by SHA-256.
Each group represents a required evidence unit; alternatives are interchangeable.
Overlapping ranked chunks can jointly cover a unit, without counting it twice.
"""
METRIC_VERSION = 'source-evidence-groups-v2'


def _covered(alternative, spans):
    start, end = alternative['start'], alternative['end']
    if start < 0 or end <= start:
        raise ValueError('Invalid evidence interval')
    cursor = start
    for span in sorted((s for s in spans if s['source_sha256'] == alternative['source_sha256']),
                       key=lambda s: (s['start'], s['end'])):
        if span['end'] <= cursor:
            continue
        if span['start'] > cursor:
            break
        cursor = max(cursor, span['end'])
        if cursor >= end:
            return True
    return False


def retrieval_metrics(ranking, groups, *, k=5, status='SUCCESS', review_status='DRAFT'):
    """MRR@k is rank where the first evidence unit becomes fully covered.

    Failed requests and unreviewed gold are unscored, never correct refusals.
    Supplementary context must not appear in this original retrieval ranking.
    """
    if k < 1:
        raise ValueError('k must be positive')
    if any(item.get('fusion_rank', 1) == 0 for item in ranking):
        raise ValueError('Supplementary context is not an original retrieval rank')
    if len({g['id'] for g in groups}) != len(groups):
        raise ValueError('Duplicate evidence groups')
    if any(not g['alternatives'] for g in groups):
        raise ValueError('Evidence group needs alternatives')
    result = {'metric_version': METRIC_VERSION, 'recall_at_k': None, 'mrr_at_k': None,
              'k': k, 'request_status': status, 'review_status': review_status}
    if status != 'SUCCESS' or review_status != 'HUMAN_APPROVED' or not groups:
        result['unscored_reason'] = 'request_failed' if status != 'SUCCESS' else 'gold_not_human_approved' if review_status != 'HUMAN_APPROVED' else 'no_positive_evidence_groups'
        return result
    first = 0
    hits = set()
    spans = []
    for rank, item in enumerate(ranking[:k], 1):
        spans.extend(item['source_spans'])
        for group in groups:
            if any(_covered(alt, spans) for alt in group['alternatives']):
                hits.add(group['id'])
        if hits and first == 0:
            first = rank
    result.update(recall_at_k=len(hits) / len(groups), mrr_at_k=1 / first if first else 0,
                  hit_evidence_groups=sorted(hits), total_evidence_groups=len(groups))
    return result
