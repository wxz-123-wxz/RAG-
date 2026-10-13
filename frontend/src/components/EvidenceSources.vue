<script setup lang="ts">
import { FileText } from 'lucide-vue-next'

defineProps<{
  citations: Array<{ number: number; documentTitle: string; heading?: string; pageStart?: number; quote: string }>
  active: number | null
  loading?: boolean
  error?: boolean
  general?: boolean
}>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="loading" class="evidence-empty" role="status">正在加载原文…</div>
  <div v-else-if="error" class="evidence-empty" role="alert">
    <p>原文加载失败，回答已保留。</p><el-button @click="$emit('retry')">重新加载来源</el-button>
  </div>
  <div v-else-if="citations.length" class="evidence-track">
    <article v-for="citation in citations" :key="citation.number" :data-source="citation.number"
      class="evidence-item" :class="{ 'is-active': active === citation.number }" tabindex="-1">
      <span class="citation-number">{{ citation.number }}</span>
      <div>
        <h3>{{ citation.documentTitle }}</h3>
        <p class="evidence-location">{{ citation.heading || '原文片段' }}<template v-if="citation.pageStart"> · 第 {{ citation.pageStart }} 页</template></p>
        <blockquote>{{ citation.quote }}</blockquote>
      </div>
    </article>
  </div>
  <div v-else class="evidence-empty">
    <FileText :size="27" />
    <p>{{ general ? '通用回答没有知识库引用' : '暂无可展示的引用' }}</p>
    <small>{{ general ? '此回答未联网核验。' : '有依据的回答会在这里展示原文。' }}</small>
  </div>
</template>
