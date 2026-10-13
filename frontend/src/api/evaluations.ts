import { http } from './http'

export interface EvaluationRun {
  id: string
  name: string
  knowledgeBaseId: string
  status: string
  retrievalMethod: string
  configSnapshotJson: string
  questionCount: number
  completedCount: number
  metricsJson?: string
  startedAt?: string
  finishedAt?: string
  createdAt: string
}

export interface EvaluationResult {
  id: string
  evaluationRunId: string
  evaluationQuestionId: string
  status: string
  generatedAnswer?: string
  recallAt5?: number
  reciprocalRank?: number
  refusalCorrect?: boolean
  citationAccuracy?: number
  latencyMs?: number
  errorCode?: string
}

export async function getEvaluationRuns(knowledgeBaseId: string | number) {
  const response = await http.get<{ data: EvaluationRun[] }>(`/knowledge-bases/${knowledgeBaseId}/evaluation-runs`)
  return response.data.data
}

export async function createEvaluationRun(knowledgeBaseId: string | number, body: {
  name: string
  retrievalMethod: string
  configSnapshotJson: string
}) {
  const response = await http.post<{ data: EvaluationRun }>(`/knowledge-bases/${knowledgeBaseId}/evaluation-runs`, body)
  return response.data.data
}

export async function getEvaluationResults(knowledgeBaseId: string | number, runId: string | number) {
  const response = await http.get<{ data: EvaluationResult[] }>(`/knowledge-bases/${knowledgeBaseId}/evaluation-runs/${runId}/results`)
  return response.data.data
}
