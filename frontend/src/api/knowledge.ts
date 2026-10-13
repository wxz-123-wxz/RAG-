import { http, type ApiResponse } from './http'
export interface KnowledgeBase {
  id: string; code: string; name: string; courseName: string; description?: string
  status: string; visibility: string; documentCount: string; manager: boolean
}
export interface DocumentRow {
  id: string; title: string; filename: string; status: string; parseStatus: string; createdAt: string
}
export interface IngestionTask {
  id: string; status: string; progressPercent: number; errorCode?: string; errorMessage?: string
}

/**
 * 保留后端返回的知识库名称和数据结构，只隐藏名称前缀中的“408”。
 * 例如“408-计算机网络”在页面显示为“计算机网络”。
 */
export function getKnowledgeBaseDisplayName(id: string, bases: Array<Pick<KnowledgeBase, 'id' | 'name'>>): string {
  const base = bases.find((item) => item.id === id)
  if (!base) return '软件工程知识库'
  const displayName = base.name.replace(/^\s*408\s*[-_：:、.·•]?\s*/i, '').trim()
  return displayName || '软件工程知识库'
}

export async function getKnowledgeBases() {
  return (await http.get<ApiResponse<KnowledgeBase[]>>('/knowledge-bases')).data.data
}
export async function getDocuments(kb: string) {
  return (await http.get<ApiResponse<DocumentRow[]>>(`/knowledge-bases/${kb}/documents`)).data.data
}
