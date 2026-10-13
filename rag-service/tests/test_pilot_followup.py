import json
import pytest
from app.config import Settings
from app.relevance import query_subjects, has_relevant_evidence
from app.retrieval import MemoryIndexStore
from app.schemas import GenerateRequest, IndexDocumentRequest
from app.service import RagService


@pytest.mark.parametrize('question,subject,source', [
    ('Cache为什么能加速访存？', 'cache', 'Cache利用程序访问的时间局部性和空间局部性加速访存。'),
    ('高速缓冲存储器有什么作用？', 'cache', 'Cache保存主存的部分内容。'),
    ('产生死锁必须满足哪些条件？', '死锁', '死锁的必要条件包括互斥、占有和等待、不可抢占、环路等待。'),
    ('虚存是怎样工作的？', '虚拟内存', '虚拟内存把地址空间中的页映射到物理内存。'),
    ('域名系统如何把网站名称转换成地址？', 'dns', 'DNS服务器在数据库中查找待解析的域名。'),
    ('单一职责原则是什么？', '单一责任原则', '单一责任原则：修改一个类的原因应该只有一个。'),
    ('单一责任原则如何理解？', '单一责任原则', '单一责任原则要求一个类只负责一件事。'),
    ('折半查找有什么作用？', '二分查找', '二分查找利用数据有序性缩小搜索范围。'),
    ('哈希表为什么可以快速查找？', '哈希表', '哈希表通过哈希函数定位存储位置。'),
])
def test_explanation_predicates_and_standard_aliases_find_body(question, subject, source):
    assert query_subjects(question) == [subject]
    assert has_relevant_evidence(question, source)
    assert not has_relevant_evidence(question, '# ' + subject + '\n本文实际讲Git版本历史。')


def test_partial_condition_question_keeps_missing_task_and_full_model_input():
    question = '说明死锁条件，并告诉我本课程期末考试日期和监考教师。'
    subjects = query_subjects(question)
    assert '死锁' in subjects
    assert any('考试日期' in subject for subject in subjects)
    service = RagService(Settings(chat_provider='disabled'), MemoryIndexStore())
    source = '死锁的四个必要条件是互斥、占有和等待、不可抢占、环路等待。'
    service.index_document('kb', IndexDocumentRequest(document_version_id='v', document_name='OS', chunks=[source]))
    class Model:
        def generate(self, actual, evidence, history):
            assert actual == question
            assert evidence[0]['content'] == source
            return json.dumps({'coverage':'partial','claims':[{'text':source,'citations':[1]}],
                               'missing_aspects':['考试日期','监考教师']})
    service.chat = Model()
    answer = service.generate(GenerateRequest(question=question,query=question,knowledge_base_id='kb',
        document_version_ids=['v'],answer_mode='generated'))
    assert answer.evidence_state == 'MODEL_REPORTED_PARTIAL'
    assert '考试日期' in answer.answer and '监考教师' in answer.answer


@pytest.mark.parametrize('question', [
    '本校机房编号X-2026电脑的CPU序列号是什么？',
    '本校宿舍路由器今天的管理员密码是什么？',
    '本校今年软件工程课程全班的期末成绩排名是什么？',
    'B树是否适用于今天的天气预测？',
])
def test_private_facts_and_unknown_qualifications_do_not_match_generic_sources(question):
    assert not has_relevant_evidence(question, 'B树是一种多路平衡搜索树。Cache保存CPU常用数据。DNS解析地址。Git用于软件工程。')
