# FastAPI RAG 服务

当前实现包含：

- 文档片段切分、确定性哈希向量基线；
- BM25、向量相似度和 RRF 混合检索；
- 知识库范围过滤、证据阈值和固定拒答文本；
- 可选 OpenAI 兼容 Chat Model 适配；
- JSON/内存索引存储；
- 引用编号校验和提示注入边界提示。

## 当前限制

`hashing-tfidf-baseline` 只用于零依赖开发和链路测试，不是可用于论文结论的中文语义模型。PDF/DOCX 的生产解析、真实 Embedding、Reranker 和 Chat Model 需要在用户确认许可证、模型来源、硬件和下载目录后接入。

## 本地运行

Python 依赖应安装到 D 盘项目目录的虚拟环境，禁止使用 C 盘默认缓存：

```powershell
python -m venv .runtime/python
.\.runtime\python\Scripts\Activate.ps1
python -m pip install --cache-dir .runtime/pip-cache -r rag-service/requirements.txt
uvicorn app.main:app --app-dir rag-service --host 127.0.0.1 --port 8000
```

若未设置 `RAG_SERVICE_TOKEN`，内部接口仅适合本机开发；部署时必须配置服务令牌并让 Spring Boot 使用相同的 `APP_RAG_SERVICE_TOKEN`。

## 冒烟请求

索引请求需要带知识库路径：

```json
{
  "document_version_id": "201",
  "document_name": "testing.md",
  "chunks": ["等价类划分是黑盒测试设计测试用例的方法。"]
}
```

端点：`POST /internal/v1/knowledge-bases/101/documents/index`。  
服务存活：`GET /health/live`；就绪信息：`GET /health/ready`。

