"""Draft pilot and collect actual rankings. No model, writes or human labels invented."""
import hashlib
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'rag-service'))
from app.config import Settings
from app.index_store import JsonIndexStore
from app.retrieval import evidence_passages, passage_text
from app.schemas import SearchRequest
from app.service import RagService
from smoke_local import sql

COURSES = {
    '408-data-structures': [
        ('bst', '二叉搜索树是什么？', 'definition', ['左右子树的值范围', '递归性质']),
        ('bst', '二叉搜索树是否每轮都能排除一半节点？不平衡时呢？', 'false_premise', ['一般查找按高度计算', '平衡与退化条件']),
        ('binary_search', '折半查找的前提和时间复杂度是什么？', 'synonym', ['有序前提', '复杂度']),
        ('binary_search', '二分查找需要数据有序吗？', 'synonym', ['有序前提']),
        ('tree_compare', '比较B树和二叉搜索树的查找过程及适用场景。', 'comparison', ['两者查找过程', '两者适用场景']),
        ('bubble', '冒泡算法的稳定性与最好、最坏时间复杂度是什么？', 'qualifier', ['稳定性', '优化条件', '最好与最坏复杂度']),
        ('bst', '介绍二叉搜索树，并说明本校下周数据结构考试的具体教室。', 'partial_gap', ['二叉搜索树定义', '考试教室无资料依据']),
    ],
    '408-computer-organization': [
        ('cache', 'Cache为什么能加速访存？', 'explanation', ['局部性', '缓存作用']),
        ('cache', '高速缓冲存储器有什么作用？', 'synonym', ['缓存作用']),
        ('hierarchy', '寄存器、Cache与主存的区别是什么？', 'comparison', ['位置', '速度与容量']),
        ('pipeline', '指令流水线有什么作用？', 'definition', ['并行阶段', '吞吐量']),
        ('pipeline', '采用流水线就一定能缩短每一条指令的延迟吗？', 'false_premise', ['吞吐量与单指令延迟区别']),
        ('interrupt', '中断和轮询的区别是什么？', 'comparison', ['触发方式', 'CPU参与方式']),
        ('unknown_cpu', '本校机房编号X-2026电脑的CPU序列号是什么？', 'insufficient', ['私有设备序列号无依据']),
    ],
    '408-operating-systems': [
        ('deadlock', '死锁的四个必要条件是什么？', 'conditions', ['互斥', '占有和等待', '不可抢占', '环路等待']),
        ('deadlock', '产生死锁必须满足哪些条件？', 'paraphrase', ['四个必要条件']),
        ('process_thread', '进程和线程有什么区别？', 'comparison', ['资源', '调度', '通信']),
        ('virtual_memory', '虚拟内存是什么？', 'definition', ['地址空间', '内外存']),
        ('virtual_memory', '虚存是怎样工作的？', 'synonym', ['地址映射', '换入换出']),
        ('paging', '分页与分段的区别是什么？', 'comparison', ['划分单位', '地址结构']),
        ('deadlock', '说明死锁条件，并告诉我本课程期末考试日期和监考教师。', 'partial_gap', ['四条件', '考试信息缺口']),
    ],
    '408-computer-networks': [
        ('tcp_handshake', 'TCP三次握手的过程是什么？', 'steps', ['三个报文', '序号与确认号']),
        ('tcp_handshake', '建立TCP连接为什么不只握手两次？', 'explanation', ['连接确认', '旧请求问题']),
        ('tcp_udp', 'TCP和UDP的区别是什么？', 'comparison', ['可靠性', '连接', '报文或字节流']),
        ('dns', 'DNS的作用是什么？', 'definition', ['域名解析']),
        ('dns', '域名系统如何把网站名称转换成地址？', 'synonym', ['域名解析过程']),
        ('https', 'HTTPS和HTTP有何区别？', 'comparison', ['加密', '认证', '协议层次']),
        ('unknown_password', '本校宿舍路由器今天的管理员密码是什么？', 'insufficient', ['私有密码无依据']),
    ],
    'software-engineering-public': [
        ('single_responsibility', '单一职责原则是什么？', 'definition', ['变化原因', '职责划分']),
        ('single_responsibility', '单一责任原则如何理解？', 'synonym', ['变化原因', '职责划分']),
        ('git_rebase', 'Git变基与合并有什么区别？', 'comparison', ['提交历史', '用途']),
        ('review', '代码审查应该关注哪些方面？', 'list', ['设计', '功能', '复杂性', '测试']),
        ('review', '审查代码时是否只需要确认代码能运行？', 'false_premise', ['质量和维护方面']),
        ('git_branch', 'Git分支是什么？请逐步解释它与提交的关系，并区分创建分支和切换分支。', 'long_explanation', ['提交引用', '创建', '切换']),
        ('unknown_score', '本校今年软件工程课程全班的期末成绩排名是什么？', 'insufficient', ['私有成绩无依据']),
    ],
}


def main():
    out = ROOT / 'data/evaluation/rag-audit/2026-09-24-continuation'
    out.mkdir(parents=True, exist_ok=True)
    if (out / 'questions.jsonl').exists():
        raise SystemExit('Pilot already exists; do not overwrite annotations')
    rows = [line.split('\t') for line in sql("SELECT k.id,k.code,d.current_version_id FROM knowledge_bases k JOIN documents d ON d.knowledge_base_id=k.id JOIN document_versions v ON v.id=d.current_version_id WHERE k.deleted_at IS NULL AND k.status='PUBLISHED' AND d.deleted_at IS NULL AND d.status='PUBLISHED' AND v.parse_status='READY';").splitlines()]
    store = JsonIndexStore(os.environ['RAG_INDEX_PATH'])
    service = RagService(Settings(), store)
    sources = json.loads((ROOT / 'data/knowledge-sources/2026-09-24-public/manifest.json').read_text(encoding='utf-8'))['documents']
    imports = json.loads((ROOT / 'data/knowledge-sources/2026-09-24-public/import-report.json').read_text(encoding='utf-8'))['documents']
    source_map = {}
    for source in sources:
        data = (ROOT / source['file']).read_bytes()
        source_map[imports[source['file']]['versionId']] = (source, data.decode('utf-8'), hashlib.sha256(data).hexdigest())
    questions, rankings, results = [], [], []
    for course, cases in COURSES.items():
        scope = [r for r in rows if r[1] == course]
        for group, question, kind, aspects in cases:
            qid = f'P{len(questions)+1:03d}'
            request = SearchRequest(query=question, knowledge_base_id=scope[0][0], document_version_ids=[r[2] for r in scope])
            trace = {}
            with store.read_snapshot():
                hits = service.retrieval.search(request, trace=trace)
                anchors = [h for h in hits if service._usable_evidence(question, h, 'HYBRID', .12)][:5]
                expanded = service.retrieval.expand_context(request, anchors)
                passages = evidence_passages(expanded, anchors)
            candidates = []
            for hit in expanded:
                info = source_map.get(hit.document_version_id)
                if not info:
                    continue
                source, content, digest = info
                # Exact unique occurrence only. Ambiguous/unmapped text stays unannotated.
                start = content.find(hit.content)
                if start >= 0 and content.find(hit.content, start + 1) < 0:
                    candidates.append({'source_file': source['file'], 'source_sha256': digest,
                        'start': start, 'end': start + len(hit.content),
                        'start_utf16': len(content[:start].encode('utf-16-le'))//2,
                        'end_utf16': len(content[:start+len(hit.content)].encode('utf-16-le'))//2,
                        'quote': hit.content, 'document_version_id': hit.document_version_id, 'chunk_no': hit.chunk_no,
                        'role': 'retrieved_hit' if hit.fusion_rank else 'adjacent_context'})
            questions.append({'question_id': qid, 'question': question, 'subject': course, 'question_type': kind,
                'scope': request.model_dump(), 'paraphrase_group': course + ':' + group,
                'required_answer_aspects_draft': aspects, 'expected_boundary': None,
                'gold_evidence_groups': [], 'candidate_evidence_for_review': candidates,
                'offset_unit': 'Unicode code points in exact decoded imported source; UTF16 offsets also supplied',
                'reviewer': None, 'review_status': 'DRAFT_AI_NOT_HUMAN_REVIEWED', 'split': 'PILOT_NOT_HELD_OUT',
                'notes': 'Candidate evidence is retrieval-biased, not gold; reviewer must search full sources and judge answerability.'})
            trace.update(question_id=qid, request=request.model_dump(),
                generation_gate_hits=[h.chunk_id for h in anchors], adjacent_context=[h.model_dump() for h in expanded if h.fusion_rank == 0],
                planned_model_passages=[{'chunk_ids':[h.chunk_id for h in p], 'text':passage_text(p)} for p in passages],
                model_called=False, final_citations=None)
            rankings.append(trace)
            results.append({'question_id':qid,'execution_status':'SUCCESS','original_hit_count':len(hits),
                'generation_anchor_count':len(anchors), 'expanded_context_count':len(expanded),
                'model_status':'NOT_RUN','recall_at_5':None,'mrr_at_5':None,'metric_reason':'human_gold_not_available'})
    for filename, data in [('questions.jsonl',questions),('rankings.jsonl',rankings),('results.jsonl',results)]:
        (out/filename).write_text(''.join(json.dumps(row,ensure_ascii=False)+'\n' for row in data),encoding='utf-8')
    summary={'time':datetime.now(timezone.utc).isoformat(),'planned':35,'executed':len(results),
        'failed':0,'model_calls':0,'human_approved':0,'unscored':35,
        'no_original_hits':[r['question_id'] for r in results if not r['original_hit_count']],
        'index_sha256':hashlib.sha256(Path(os.environ['RAG_INDEX_PATH']).read_bytes()).hexdigest(),
        'warning':'No-hit cases are observations, not automatically correct refusals. No accuracy/recall claims.'}
    (out/'pilot-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=False))

if __name__ == '__main__': main()
