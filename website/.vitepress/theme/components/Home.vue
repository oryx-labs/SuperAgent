<script setup>
import { computed } from 'vue'
import { useData, withBase } from 'vitepress'

const { lang } = useData()
const isZh = computed(() => lang.value === 'zh-CN')
const t = (zh, en) => (isZh.value ? zh : en)

const github = 'https://github.com/oryx-labs/SuperAgent'
const demo = 'http://117.72.92.117:18081/login'
const docsFeatures = computed(() =>
  withBase(isZh.value ? '/zh/docs/features' : '/docs/features')
)
const docsStart = computed(() =>
  withBase(isZh.value ? '/zh/docs/getting-started' : '/docs/getting-started')
)

const check = 'M20 6L9 17l-5-5'
const cross = 'M18 6L6 18M6 6l12 12'

const pillars = computed(() => [
  t('公司可控', 'Company-controlled'),
  t('节省成本', 'Saves cost'),
  t('自动沉淀资产', 'Assets accumulate'),
  t('好用', 'Easy to use'),
])

const personal = computed(() => [
  t('只帮一个人', 'Helps one person'),
  t('人走了,工作就没了', 'Work leaves with the person'),
  t('每人自费,token 浪费', 'Everyone pays; tokens wasted'),
  t('公司看不见', 'Invisible to the company'),
])
const company = computed(() => [
  t('全公司一个系统', 'One system for the whole company'),
  t('工作沉淀为公司资产', 'Work becomes company assets'),
  t('入职即用,按量付费', 'Ready on day one, pay per use'),
  t('公司可控,可审计', 'Controlled and auditable'),
])

const soon = () => t('即将推出', 'Soon')

const features = computed(() => [
  {
    icon: 'M4 6h16M4 12h16M4 18h10',
    eyebrow: t('工作即资产', 'Work becomes assets'),
    title: t('人会变,积累不会丢', 'People change. The work stays.'),
    bullets: [
      { text: t('Agent 的产出和员工发来的资料都存进「文件」', 'Everything agents make, and everything people send them, lands in Files') },
      { text: t('每个 Agent 有长期记忆,越用越懂这份工作', 'Each agent keeps long-term memory and gets better at the job') },
      { text: t('换人时,Agent 连同记忆和文件一起留下', 'When people change, the agent, its memory, and its files stay') },
    ],
    panel: 'flow',
  },
  {
    icon: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
    eyebrow: t('协作与交接', 'Collaboration & handoff'),
    title: t('接着前人做,不用从头来', 'Pick up where others left off'),
    bullets: [
      { text: t('多人在同一个 Agent 上协作', 'Several people, one shared agent') },
      { text: t('离职后 Agent 和上下文交接', 'Agents and context handed over'), soon: true },
    ],
    shot: { src: '/shots/agents.jpg', label: 'agents', w: 1649, h: 987, alt: t('团队共享的 Agent', 'A shared team agent') },
  },
  {
    icon: 'M12 2l9 5-9 5-9-5 9-5zM3 12l9 5 9-5M3 17l9 5 9-5',
    eyebrow: t('方法复用', 'Reusable methods'),
    title: t('好的做法,变成全公司的规范', 'Good practice becomes company practice'),
    bullets: [
      { text: t('Skills 分个人和公司两级', 'Skills at personal and company levels') },
      { text: t('Agent 是团队资产:设定、记忆、文件都属于公司', 'Agents are team assets: setup, memory, and files belong to the company') },
      { text: t('新人直接接手,不用从零开始', 'New hires take over instead of starting from zero') },
    ],
    panel: 'tiers',
  },
  {
    icon: 'M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
    eyebrow: t('成本与模型', 'Cost & models'),
    title: t('入职就有 AI,只为用量付费', 'AI from day one. Pay for usage.'),
    bullets: [
      { text: t('员工不用自费订阅', 'No personal subscriptions') },
      { text: t('按任务、按模型看花费', 'Spend per task and per model') },
      { text: t('按难度自动选模型', 'Model picked by task difficulty'), soon: true },
    ],
    shot: { src: '/shots/analytics.jpg', label: 'analytics', w: 1643, h: 840, alt: t('用量与花费', 'Usage and spend') },
  },
  {
    icon: 'M12 3l8 3v5c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-3zM9 12l2 2 4-4',
    eyebrow: t('审计与安全', 'Audit & security'),
    title: t('AI 怎么用,公司看得见', 'See how AI is used'),
    bullets: [
      { text: t('关键决策等人确认', 'Key decisions wait for a person') },
      { text: t('身份、权限、运行日志', 'Identity, permissions, run logs') },
      { text: t('对接企业审批', 'Enterprise approvals'), soon: true },
    ],
    shot: { src: '/shots/todo.jpg', label: 'to-do', w: 1655, h: 751, alt: t('等待确认的决策', 'Decisions awaiting confirmation') },
  },
])

const more = computed(() => [
  { icon: 'M12 3l8 3v5c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-3z', title: t('每人一个 SuperAgent', 'A SuperAgent each'), desc: t('接活、计划、派活、回报', 'Plans, assigns, reports back') },
  { icon: 'M4 5h16v10H4zM2 19h20M9 9l2 2 4-4', title: t('编码 Agent', 'Coding agents'), desc: t('Claude Code、Codex、DeepSeek、Kimi、Qwen、GLM', 'Claude Code, Codex, DeepSeek, Kimi, Qwen, GLM') },
  { icon: 'M12 7V3m0 4a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM12 12h4', title: t('自动化', 'Automation'), desc: t('定时、一次性、API 触发', 'Scheduled, one-off, API') },
  { icon: 'M9 3v18M3 9h12M15 15h6M18 12v6', title: t('连接外部系统', 'Connectors'), desc: t('飞书、GitHub、Gitee、MCP', 'Feishu, GitHub, Gitee, MCP') },
  { icon: 'M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z', title: t('模型无关', 'Model-agnostic'), desc: t('不被厂商锁定', 'No vendor lock-in') },
  { icon: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM12 7v5l3 3', title: t('运行日志', 'Run logs'), desc: t('每一步都有记录', 'Every step recorded') },
])
</script>

<template>
  <div class="sa-home">
    <!-- Hero -->
    <section class="sa-hero">
      <div class="sa-wrap sa-hero__grid">
        <div class="sa-hero__copy">
          <span class="sa-badge"
            ><span class="dot"></span
            >{{ t('为公司而建,不是个人助手', 'Built for companies, not personal assistants') }}</span
          >
          <h1>
            {{ t('企业的', 'AI collaborative work system') }}
            <span class="accent">{{ t('AI 协作工作系统', 'for companies') }}</span>
          </h1>
          <p class="lede">
            {{
              t(
                '全员与 AI 在一个系统里协作,工作自动沉淀为公司资产,人走了也不会丢。',
                'Everyone works with AI in one system, and the work becomes company assets that stay when people leave.'
              )
            }}
          </p>
          <div class="sa-actions">
            <a class="sa-btn sa-btn--primary" :href="demo" target="_blank" rel="noreferrer">{{
              t('在线体验', 'Try the live demo')
            }}</a>
            <a class="sa-btn sa-btn--ghost" :href="github" target="_blank" rel="noreferrer">
              <svg viewBox="0 0 24 24" width="17" height="17" fill="currentColor"><path d="M12 .3a12 12 0 0 0-3.8 23.4c.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.3-1.8-1.3-1.8-1.1-.7 0-.7 0-.7 1.2 0 1.9 1.2 1.9 1.2 1 1.8 2.8 1.3 3.5 1 0-.8.4-1.3.7-1.6-2.7-.3-5.5-1.3-5.5-6 0-1.3.5-2.3 1.2-3.1-.1-.4-.5-1.6.1-3.2 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0c2.3-1.5 3.3-1.2 3.3-1.2.6 1.6.2 2.8.1 3.2.8.8 1.2 1.8 1.2 3.1 0 4.7-2.8 5.7-5.5 6 .4.4.8 1.1.8 2.2v3.3c0 .3.2.7.8.6A12 12 0 0 0 12 .3Z"/></svg>
              GitHub
            </a>
          </div>
          <div class="sa-hero__trust">
            <span class="sa-pill" v-for="p in pillars" :key="p"
              ><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg
              >{{ p }}</span
            >
          </div>
        </div>

        <div class="sa-hero__visual">
          <div class="sa-shot sa-shot--hero sa-mock--float">
            <div class="sa-shot__bar"><i></i><i></i><i></i><span>superagent · tasks</span></div>
            <img :src="withBase('/shots/tasks.jpg')" :alt="t('SuperAgent 任务看板', 'SuperAgent tasks board')" width="1652" height="893" loading="eager" />
          </div>
        </div>
      </div>
    </section>

    <!-- Not a personal assistant -->
    <section class="sa-section">
      <div class="sa-wrap">
        <div class="sa-head">
          <h2>{{ t('个人助手帮一个人,SuperAgent 帮整个公司', 'Assistants help a person. SuperAgent helps the company.') }}</h2>
        </div>
        <div class="sa-vs">
          <div class="sa-vs__card">
            <h3>{{ t('个人 AI 助手', 'Personal AI assistants') }}</h3>
            <ul>
              <li v-for="x in personal" :key="x"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="cross"/></svg>{{ x }}</li>
            </ul>
          </div>
          <div class="sa-vs__card sa-vs__card--us">
            <h3>SuperAgent</h3>
            <ul>
              <li v-for="x in company" :key="x"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ x }}</li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- Core capabilities, in priority order -->
    <section class="sa-section" style="padding-top: 24px">
      <div class="sa-wrap">
        <div class="sa-head">
          <div class="sa-eyebrow">{{ t('核心能力', 'Core capabilities') }}</div>
          <h2>{{ t('让 AI 在公司里真正用起来', 'What makes AI work for a company') }}</h2>
        </div>

        <div
          v-for="(f, i) in features"
          :key="f.eyebrow"
          class="sa-feature"
          :class="{ 'sa-feature--reverse': i % 2 === 1 }"
        >
          <div class="sa-feature__copy">
            <span class="sa-feature__eyebrow"
              ><span class="sa-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="f.icon"/></svg></span
              >{{ f.eyebrow }}</span
            >
            <h3>{{ f.title }}</h3>
            <ul>
              <li v-for="b in f.bullets" :key="b.text">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ b.text }}<span v-if="b.soon" class="sa-soon">{{ soon() }}</span>
              </li>
            </ul>
          </div>

          <div class="sa-feature__media">
            <div v-if="f.shot" class="sa-shot">
              <div class="sa-shot__bar"><i></i><i></i><i></i><span>superagent · {{ f.shot.label }}</span></div>
              <img :src="withBase(f.shot.src)" :alt="f.shot.alt" :width="f.shot.w" :height="f.shot.h" loading="lazy" />
            </div>

            <div v-else-if="f.panel === 'flow'" class="sa-panel">
              <div class="sa-flow">
                <div class="sa-frow"><span class="n">1</span>{{ t('员工在系统里工作', 'People work in the system') }}</div>
                <div class="sa-frow"><span class="n">2</span>{{ t('产出和资料存进文件', 'Output and material saved to Files') }}</div>
                <div class="sa-frow active"><span class="n">3</span>{{ t('Agent 沉淀长期记忆', 'Agents build long-term memory') }}<span class="arrow">→</span></div>
                <div class="sa-frow"><span class="n done">✓</span>{{ t('留在公司,换人不丢', 'Stays with the company') }}</div>
              </div>
            </div>

            <div v-else-if="f.panel === 'tiers'" class="sa-panel">
              <div class="sa-agents">
                <div class="sa-acard"><span class="av"></span><div class="meta"><b>{{ t('公司 Skills', 'Company skills') }}</b><i></i></div><span class="tag">{{ t('全员', 'everyone') }}</span></div>
                <div class="sa-acard"><span class="av v3"></span><div class="meta"><b>{{ t('个人 Skills', 'Personal skills') }}</b><i></i></div><span class="tag">{{ t('仅自己', 'just you') }}</span></div>
                <div class="sa-acard"><span class="av v2"></span><div class="meta"><b>{{ t('团队 Agent', 'Team agents') }}</b><i></i></div><span class="tag">{{ t('记忆 + 文件', 'memory + files') }}</span></div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Any kind of work -->
    <section class="sa-more">
      <div class="sa-wrap sa-section">
        <div class="sa-head">
          <h2>{{ t('一个入口,什么工作都能做', 'One entry point for any kind of work') }}</h2>
        </div>
        <div class="sa-grid">
          <div class="sa-item" v-for="m in more" :key="m.title">
            <span class="sa-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="m.icon"/></svg></span>
            <div>
              <h4>{{ m.title }}</h4>
              <p>{{ m.desc }}</p>
            </div>
          </div>
        </div>
        <p style="text-align: center; margin-top: 40px">
          <a class="sa-btn sa-btn--ghost" :href="docsFeatures">{{ t('查看完整功能', 'See all features') }}</a>
        </p>
      </div>
    </section>

    <!-- CTA -->
    <section class="sa-cta">
      <div class="sa-wrap">
        <div class="sa-cta__panel">
          <h2>{{ t('给公司一个真正用得起来的 AI', 'Give your company AI it will actually use') }}</h2>
          <p>{{ t('开源、自托管,部署一次,全员可用。', 'Open source and self-hosted. Deploy once for everyone.') }}</p>
          <div class="sa-actions">
            <a class="sa-btn sa-btn--white" :href="demo" target="_blank" rel="noreferrer">{{ t('在线体验', 'Try the live demo') }}</a>
            <a class="sa-btn sa-btn--clear" :href="docsStart">{{ t('部署指南', 'Deployment guide') }}</a>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>
