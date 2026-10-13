import sys
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from evidence_metrics import retrieval_metrics


def span(start, end, source='source'):
    return {'source_sha256': source, 'start': start, 'end': end}


def hit(start, end, rank=1, source='source'):
    return {'fusion_rank': rank, 'source_spans': [span(start, end, source)]}


def test_recall_counts_required_groups_not_binary_hit():
    groups = [{'id': 'a', 'alternatives': [span(5, 15)]}, {'id': 'b', 'alternatives': [span(30, 40)]}]
    result = retrieval_metrics([hit(0, 20), hit(0, 20)], groups, review_status='HUMAN_APPROVED')
    assert result['recall_at_k'] == .5
    assert result['mrr_at_k'] == 1


def test_boundary_group_requires_joint_coverage_and_uses_completion_rank():
    groups = [{'id': 'a', 'alternatives': [span(5, 25)]}]
    result = retrieval_metrics([hit(0, 15), hit(10, 30, 2)], groups, review_status='HUMAN_APPROVED')
    assert result['recall_at_k'] == 1
    assert result['mrr_at_k'] == .5
    assert retrieval_metrics([hit(0, 15), hit(16, 30, 2)], groups, review_status='HUMAN_APPROVED')['recall_at_k'] == 0


def test_alternative_sources_are_one_group_and_hash_must_match():
    groups = [{'id': 'a', 'alternatives': [span(5, 15), span(0, 10, 'alternate')]}]
    assert retrieval_metrics([hit(0, 20, source='wrong')], groups, review_status='HUMAN_APPROVED')['recall_at_k'] == 0
    assert retrieval_metrics([hit(0, 20, source='alternate')], groups, review_status='HUMAN_APPROVED')['recall_at_k'] == 1


@pytest.mark.parametrize('status,review', [('TIMEOUT', 'HUMAN_APPROVED'), ('SUCCESS', 'DRAFT')])
def test_failures_and_draft_gold_remain_unscored(status, review):
    result = retrieval_metrics([], [{'id': 'a', 'alternatives': [span(0, 10)]}], status=status, review_status=review)
    assert result['recall_at_k'] is None and result['mrr_at_k'] is None


def test_context_cannot_masquerade_as_ranked_hit():
    with pytest.raises(ValueError, match='Supplementary'):
        retrieval_metrics([hit(0, 10, 0)], [])
