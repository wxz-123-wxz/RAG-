import { http } from './http'

export interface CountRow {
  question: string
  count: number
  feedbackCount?: number
}

export interface DocumentCountRow {
  documentTitle: string
  count: number
}

export interface AnalyticsSnapshot {
  days: number
  answerCount: number
  refusalCount: number
  meanLatencyMs: number
  popularQuestions: CountRow[]
  refusedQuestions: CountRow[]
  citedDocuments: DocumentCountRow[]
}

export async function getAnalytics(knowledgeBaseId: string | number, days = 30) {
  const response = await http.get<{ data: AnalyticsSnapshot }>(
    `/knowledge-bases/${knowledgeBaseId}/analytics`,
    { params: { days } },
  )
  return response.data.data
}

export async function getAdminAnalytics(days = 30) {
  const response = await http.get<{ data: AnalyticsSnapshot }>('/admin/analytics', { params: { days } })
  return response.data.data
}

export async function getAnalyticsForKnowledgeBases(knowledgeBaseIds: Array<string | number>, days = 30) {
  if (!knowledgeBaseIds.length) {
    return {
      days,
      answerCount: 0,
      refusalCount: 0,
      meanLatencyMs: 0,
      popularQuestions: [],
      refusedQuestions: [],
      citedDocuments: [],
    } satisfies AnalyticsSnapshot
  }

  const snapshots = await Promise.all(knowledgeBaseIds.map((id) => getAnalytics(id, days)))
  const mergeQuestions = (key: 'popularQuestions' | 'refusedQuestions') => {
    const counts = new Map<string, { count: number; feedbackCount: number }>()
    for (const snapshot of snapshots) {
      for (const row of snapshot[key]) {
        const current = counts.get(row.question) ?? { count: 0, feedbackCount: 0 }
        current.count += row.count
        current.feedbackCount += row.feedbackCount ?? 0
        counts.set(row.question, current)
      }
    }
    return [...counts.entries()]
      .map(([question, values]) => ({ question, ...values }))
      .sort((a, b) => b.count - a.count)
  }
  const documents = new Map<string, number>()
  for (const snapshot of snapshots) {
    for (const row of snapshot.citedDocuments) {
      documents.set(row.documentTitle, (documents.get(row.documentTitle) ?? 0) + row.count)
    }
  }
  const answerCount = snapshots.reduce((sum, snapshot) => sum + snapshot.answerCount, 0)
  const meanLatencyMs = answerCount
    ? Math.round(snapshots.reduce((sum, snapshot) => sum + snapshot.meanLatencyMs * snapshot.answerCount, 0) / answerCount)
    : 0
  return {
    days,
    answerCount,
    refusalCount: snapshots.reduce((sum, snapshot) => sum + snapshot.refusalCount, 0),
    meanLatencyMs,
    popularQuestions: mergeQuestions('popularQuestions'),
    refusedQuestions: mergeQuestions('refusedQuestions'),
    citedDocuments: [...documents.entries()]
      .map(([documentTitle, count]) => ({ documentTitle, count }))
      .sort((a, b) => b.count - a.count)
  }
}
