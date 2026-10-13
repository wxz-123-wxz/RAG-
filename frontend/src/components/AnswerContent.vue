<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps<{ content: string; plain?: boolean; citationNumbers?: number[] }>()
const emit = defineEmits<{ citation: [number: number] }>()
const html = computed(() => {
  const template = document.createElement('template')
  template.innerHTML = DOMPurify.sanitize(marked.parse(props.content, { async: false, breaks: true, gfm: true }), {
    ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'del', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li',
      'blockquote', 'pre', 'code', 'table', 'thead', 'tbody', 'tr', 'th', 'td', 'hr', 'a'],
    ALLOWED_ATTR: ['href', 'title', 'start'],
    ALLOW_DATA_ATTR: false,
  })
  template.content.querySelectorAll('a').forEach(link => {
    // Model-authored links must not navigate to application routes or execute code.
    if (!/^https?:\/\//i.test(link.getAttribute('href') ?? '')) link.removeAttribute('href')
    else { link.target = '_blank'; link.rel = 'noopener noreferrer' }
  })
  template.content.querySelectorAll('table').forEach(table => {
    const wrapper = document.createElement('div')
    wrapper.className = 'answer-table-scroll'
    wrapper.tabIndex = 0
    wrapper.setAttribute('role', 'region')
    wrapper.setAttribute('aria-label', '回答表格，可横向滚动')
    table.replaceWith(wrapper)
    wrapper.append(table)
  })
  const numbers = new Set(props.citationNumbers ?? [])
  const walker = document.createTreeWalker(template.content, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []
  while (walker.nextNode()) {
    const node = walker.currentNode as Text
    if (!node.parentElement?.closest('pre, code, a')) nodes.push(node)
  }
  for (const node of nodes) {
    const parts = node.textContent?.split(/(\[\d+\])/g) ?? []
    if (parts.length < 2) continue
    const fragment = document.createDocumentFragment()
    for (const part of parts) {
      const number = /^\[\d+\]$/.test(part) ? Number(part.slice(1, -1)) : 0
      if (numbers.has(number)) {
        const button = document.createElement('button')
        button.type = 'button'
        button.className = 'citation-anchor'
        button.dataset.citation = String(number)
        button.setAttribute('aria-label', `查看引用 ${number}`)
        button.textContent = part
        fragment.append(button)
      } else fragment.append(document.createTextNode(part))
    }
    node.replaceWith(fragment)
  }
  return template.innerHTML
})

function selectCitation(event: MouseEvent) {
  const button = (event.target as HTMLElement).closest<HTMLButtonElement>('button[data-citation]')
  const number = Number(button?.dataset.citation)
  if (props.citationNumbers?.includes(number)) emit('citation', number)
}
</script>

<template>
  <div v-if="plain" class="answer-content plain-content">{{ content }}</div>
  <div v-else class="answer-content markdown-content" @click="selectCitation" v-html="html" />
</template>
