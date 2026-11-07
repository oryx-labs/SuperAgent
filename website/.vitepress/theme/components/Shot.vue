<script setup>
// A product screenshot in the docs: public/shots/docs/<name>.png. Until that file exists the page shows a
// placeholder naming it, so screenshots can be dropped in later without touching the docs.
import { onMounted, ref } from 'vue'
import { useData, withBase } from 'vitepress'

const props = defineProps({ name: { type: String, required: true }, alt: { type: String, default: '' } })
const { lang } = useData()
const img = ref(null)
const missing = ref(false)

// A 404 can fire before hydration, when @error isn't listening yet: check the loaded state too.
onMounted(() => {
  const el = img.value
  if (el && el.complete && el.naturalWidth === 0) missing.value = true
})
</script>

<template>
  <figure class="doc-shot">
    <div v-if="missing" class="doc-shot__todo">
      <span>{{ lang === 'zh-CN' ? '截图待补' : 'Screenshot to come' }}</span>
      <code>shots/docs/{{ props.name }}.png</code>
      <small v-if="props.alt">{{ props.alt }}</small>
    </div>
    <img v-else ref="img" :src="withBase('/shots/docs/' + props.name + '.png')" :alt="props.alt" loading="lazy"
      @error="missing = true" />
    <figcaption v-if="props.alt && !missing">{{ props.alt }}</figcaption>
  </figure>
</template>
