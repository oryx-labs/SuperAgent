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

const personal = computed(() =>
  isZh.value
    ? ['帮一个人提效', '资产留在个人账号里,人走即丢', '每人自费订阅,token 浪费无人管', '公司看不见、管不住']
    : [
        'Makes one person faster',
        'Work lives in a personal account and leaves with them',
        'Everyone pays for their own subscription; no one watches the tokens',
        'The company can’t see or control it',
      ]
)
const company = computed(() =>
  isZh.value
    ? ['全公司在一个系统里完成工作', '工作自动沉淀为公司资产', '入职即用,按量付费,自动省钱', '公司可控,全程可审计']
    : [
        'The whole company works in one system',
        'Work automatically becomes company assets',
        'Ready on day one, pay per use, cost-optimized',
        'Company-controlled and fully auditable',
      ]
)

const pillars = computed(() => [
  {
    icon: 'M12 3l8 3v5c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-3z',
    title: t('公司可控', 'Company-controlled'),
    desc: t('身份、权限、模型、数据都在公司手里。', 'Identity, permissions, models, and data stay with the company.'),
  },
  {
    icon: 'M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6',
    title: t('节省成本', 'Saves cost'),
    desc: t('不自费订阅,按量付费,按难度选模型。', 'No personal subscriptions, pay per use, the right model per task.'),
  },
  {
    icon: 'M4 6h16M4 12h16M4 18h10',
    title: t('自动沉淀资产', 'Assets accumulate'),
    desc: t('工作即资产,人走了,东西还在。', 'Work becomes assets that stay when people leave.'),
  },
  {
    icon: 'M14 9V5a3 3 0 0 0-6 0v4M5 9h14l-1 12H6L5 9z',
    title: t('好用', 'Easy to use'),
    desc: t('一个入口,什么工作都能做。', 'One entry point for any kind of work.'),
  },
])

const more = computed(() => [
  {
    icon: 'M12 3l8 3v5c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-3z',
    title: t('每人一个 SuperAgent', 'A SuperAgent for everyone'),
    desc: t('收件箱接活,先计划后执行,组队派活并回报。', 'Takes work into an inbox, plans, assigns a team, reports back.'),
  },
  {
    icon: 'M4 5h16v10H4zM2 19h20M9 9l2 2 4-4',
    title: t('编码 Agent', 'Coding agents'),
    desc: t('运行 Claude Code、Codex,从需求到交付。', 'Runs Claude Code and Codex from requirement to delivery.'),
  },
  {
    icon: 'M12 7V3m0 4a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM12 12h4',
    title: t('自动化', 'Automation'),
    desc: t('定时、一次性、API 触发,重复工作自动跑。', 'Scheduled, one-off, and API-triggered runs.'),
  },
  {
    icon: 'M9 3v18M3 9h12M15 15h6M18 12v6',
    title: t('办公工具接入', 'Office tools'),
    desc: t('接入飞书等工具,工作从员工常用的地方进来。', 'Connects Feishu and more, so work arrives where people already are.'),
  },
  {
    icon: 'M3 3h7v7H3zM14 3h7v7h-7zM14 14h7v7h-7zM3 14h7v7H3z',
    title: t('模型无关', 'Model-agnostic'),
    desc: t('集中接入与切换模型供应商,不被厂商锁定。', 'Add and switch model providers centrally; no lock-in.'),
  },
  {
    icon: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM12 7v5l3 3',
    title: t('运行日志', 'Run logs'),
    desc: t('每个 Agent 的会话、每轮与行为都有记录。', 'Every agent’s sessions, turns, and actions are recorded.'),
  },
])
</script>

<template>
  <div class="sa-home">
    <!-- ░░ Hero ░░ -->
    <section class="sa-hero">
      <div class="sa-wrap sa-hero__grid">
        <div class="sa-hero__copy">
          <span class="sa-badge"
            ><span class="dot"></span
            >{{ t('为公司而建,不是个人助手', 'Built for companies, not personal assistants') }}</span
          >
          <h1>
            {{ t('公司的', 'AI work system') }}
            <span class="accent">{{ t('AI 工作系统', 'for your company') }}</span>
          </h1>
          <p class="lede">
            {{
              t(
                '全公司的人在一个系统里完成工作,工作自动沉淀为公司的资产,不会因为人员变动而丢失。员工入职即用,公司按量付费。',
                'Everyone in the company works in one system, and the work automatically becomes company assets that never leave with people. Employees get AI on day one; the company pays only for what it uses.'
              )
            }}
          </p>
          <div class="sa-actions">
            <a class="sa-btn sa-btn--primary" :href="demo" target="_blank" rel="noreferrer">{{
              t('在线体验', 'Try the live demo')
            }}</a>
            <a class="sa-btn sa-btn--ghost" :href="github" target="_blank" rel="noreferrer">
              <svg viewBox="0 0 24 24" width="17" height="17" fill="currentColor"><path d="M12 .3a12 12 0 0 0-3.8 23.4c.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.3-1.8-1.3-1.8-1.1-.7 0-.7 0-.7 1.2 0 1.9 1.2 1.9 1.2 1 1.8 2.8 1.3 3.5 1 0-.8.4-1.3.7-1.6-2.7-.3-5.5-1.3-5.5-6 0-1.3.5-2.3 1.2-3.1-.1-.4-.5-1.6.1-3.2 0 0 1-.3 3.3 1.2a11.5 11.5 0 0 1 6 0c2.3-1.5 3.3-1.2 3.3-1.2.6 1.6.2 2.8.1 3.2.8.8 1.2 1.8 1.2 3.1 0 4.7-2.8 5.7-5.5 6 .4.4.8 1.1.8 2.2v3.3c0 .3.2.7.8.6A12 12 0 0 0 12 .3Z"/></svg>
              {{ t('开源 · GitHub', 'Open source on GitHub') }}
            </a>
          </div>
          <div class="sa-hero__trust">
            <span class="sa-pill" v-for="p in pillars" :key="p.title"
              ><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg
              >{{ p.title }}</span
            >
          </div>
        </div>

        <div class="sa-hero__visual">
          <div class="sa-shot sa-shot--hero sa-mock--float">
            <div class="sa-shot__bar"><i></i><i></i><i></i><span>superagent · tasks</span></div>
            <img
              :src="withBase('/shots/tasks.jpg')"
              :alt="t('SuperAgent 任务看板', 'SuperAgent tasks board')"
              width="1652"
              height="893"
              loading="eager"
            />
          </div>
        </div>
      </div>
    </section>

    <!-- ░░ Not a personal assistant ░░ -->
    <section class="sa-section">
      <div class="sa-wrap">
        <div class="sa-head">
          <div class="sa-eyebrow">{{ t('不一样的地方', 'The difference') }}</div>
          <h2>{{ t('个人助手帮一个人,SuperAgent 帮整个公司', 'Assistants help a person. SuperAgent helps the company.') }}</h2>
          <p>
            {{
              t(
                '企业对 AI 是刚需:要能用起来、好用、省钱、什么工作都能做。这需要从公司和员工的角度来设计,而不是给每个人发一个助手。',
                'Companies need AI that actually gets used: useful, affordable, and able to do any kind of work. That takes a system designed around the company and its people, not an assistant handed to each person.'
              )
            }}
          </p>
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

    <!-- ░░ Core capabilities, in priority order ░░ -->
    <section class="sa-section" style="padding-top: 24px">
      <div class="sa-wrap">
        <div class="sa-head">
          <div class="sa-eyebrow">{{ t('核心能力', 'Core capabilities') }}</div>
          <h2>{{ t('让 AI 在公司里真正用起来', 'What makes AI work for a company') }}</h2>
        </div>

        <!-- 1. Work becomes assets -->
        <div class="sa-feature">
          <div class="sa-feature__copy">
            <span class="sa-feature__eyebrow"><span class="sa-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 6h16M4 12h16M4 18h10"/></svg></span>{{ t('工作即资产', 'Work becomes assets') }}</span>
            <h3>{{ t('人会变,公司的积累不会丢', 'People change. What the company learned stays.') }}</h3>
            <p>
              {{
                t(
                  '所有人都在同一个系统里工作,对话、任务和交付物都留在公司。系统会自动提炼、归总、汇聚这些工作,逐步形成公司的知识资产。',
                  'Everyone works in the same system, so conversations, tasks, and deliverables stay with the company. The system distills, summarizes, and consolidates that work into the company’s knowledge over time.'
                )
              }}
            </p>
            <ul>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('Agent 自动提炼记忆,下次对话直接用上', 'Agents distill memory automatically and use it next time') }}</li>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('每次任务都留下可追溯的记录和交付物', 'Every task leaves a traceable record and its deliverables') }}</li>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('公司级知识自动汇聚', 'Company-wide knowledge consolidation') }}<span class="sa-soon">{{ t('即将推出', 'Soon') }}</span></li>
            </ul>
          </div>
          <div class="sa-feature__media">
            <div class="sa-panel">
              <div class="sa-panel__title">{{ t('工作 → 资产', 'work → assets') }}</div>
              <div class="sa-flow">
                <div class="sa-frow"><span class="n">1</span>{{ t('员工在系统里对话、跑任务', 'People chat and run tasks in the system') }}</div>
                <div class="sa-frow"><span class="n">2</span>{{ t('记录、交付物自动留存', 'Records and deliverables are kept') }}</div>
                <div class="sa-frow active"><span class="n">3</span>{{ t('系统提炼、归总、汇聚', 'The system distills and consolidates') }}<span class="arrow">→</span></div>
                <div class="sa-frow"><span class="n done">✓</span>{{ t('成为公司的知识资产', 'It becomes company knowledge') }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- 2. Collaboration & handoff -->
        <div class="sa-feature sa-feature--reverse">
          <div class="sa-feature__copy">
            <span class="sa-feature__eyebrow"><span class="sa-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8"/></svg></span>{{ t('协作与交接', 'Collaboration & handoff') }}</span>
            <h3>{{ t('在前人的基础上继续,而不是从头再来', 'Pick up where the last person left off') }}</h3>
            <p>
              {{
                t(
                  '员工之间的协作在同一个 AI 系统里完成。同一件事,几个人可以在同一个 Agent 上一起推进;有人离职,Agent 和上下文可以整体交接给下一个人。',
                  'Collaboration happens inside one AI system. Several people can work with the same agent on the same job, and when someone leaves, their agents and context are handed to the next person.'
                )
              }}
            </p>
            <ul>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('多人在同一个 Agent 上协作完成任务', 'Several people collaborate on one agent') }}</li>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('离职交接:Agent 与上下文转给下一个人', 'Handoff: agents and context move to the next person') }}<span class="sa-soon">{{ t('即将推出', 'Soon') }}</span></li>
            </ul>
          </div>
          <div class="sa-feature__media">
            <div class="sa-shot">
              <div class="sa-shot__bar"><i></i><i></i><i></i><span>superagent · agents</span></div>
              <img :src="withBase('/shots/agents.jpg')" :alt="t('团队共享的 Agent 与对话', 'A team agent and its conversation')" width="1649" height="987" loading="lazy" />
            </div>
          </div>
        </div>

        <!-- 3. Personal / team / company assets -->
        <div class="sa-feature">
          <div class="sa-feature__copy">
            <span class="sa-feature__eyebrow"><span class="sa-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2l9 5-9 5-9-5 9-5zM3 12l9 5 9-5M3 17l9 5 9-5"/></svg></span>{{ t('三级资产', 'Three levels of assets') }}</span>
            <h3>{{ t('个人、团队、公司,各有各的积累', 'Assets for each person, team, and the company') }}</h3>
            <p>
              {{
                t(
                  'Skills、知识库等资产分员工、团队、公司三个维度,沉淀日常工作需要的方法和资料。新人入职,直接继承团队和公司已有的积累。',
                  'Skills and knowledge bases exist at three levels (employee, team, and company), holding the methods and material everyday work needs. A new hire starts with everything the team and company already know.'
                )
              }}
            </p>
          </div>
          <div class="sa-feature__media">
            <div class="sa-panel">
              <div class="sa-panel__title">{{ t('知识与 Skills', 'knowledge & skills') }}</div>
              <div class="sa-agents">
                <div class="sa-acard"><span class="av"></span><div class="meta"><b>{{ t('公司', 'Company') }}</b><i></i></div><span class="tag">{{ t('全员可用', 'everyone') }}</span></div>
                <div class="sa-acard"><span class="av v2"></span><div class="meta"><b>{{ t('团队', 'Team') }}</b><i></i></div><span class="tag">{{ t('团队成员', 'team members') }}</span></div>
                <div class="sa-acard"><span class="av v3"></span><div class="meta"><b>{{ t('个人', 'Personal') }}</b><i></i></div><span class="tag">{{ t('仅自己', 'just you') }}</span></div>
              </div>
            </div>
          </div>
        </div>

        <!-- 4. Cost -->
        <div class="sa-feature sa-feature--reverse">
          <div class="sa-feature__copy">
            <span class="sa-feature__eyebrow"><span class="sa-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 1v22M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg></span>{{ t('成本与模型', 'Cost & models') }}</span>
            <h3>{{ t('入职就有 AI,公司只为用到的付费', 'AI from day one. Pay only for what’s used.') }}</h3>
            <p>
              {{
                t(
                  '员工不用自费订阅,也不会各自浪费 token。系统按使用量计费、不绑定模型,并根据任务难度自动选择合适的模型,帮公司省钱。',
                  'No personal subscriptions and no wasted tokens. The system bills by usage, works with any model, and routes each task to a model that fits its difficulty, which saves the company money.'
                )
              }}
            </p>
            <ul>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('按任务、按模型的花费一目了然', 'Spend visible per task and per model') }}</li>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('模型无关,供应商集中接入和切换', 'Model-agnostic; providers managed centrally') }}</li>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('按任务难度自动路由模型', 'Automatic routing by task difficulty') }}<span class="sa-soon">{{ t('即将推出', 'Soon') }}</span></li>
            </ul>
          </div>
          <div class="sa-feature__media">
            <div class="sa-shot">
              <div class="sa-shot__bar"><i></i><i></i><i></i><span>superagent · analytics</span></div>
              <img :src="withBase('/shots/analytics.jpg')" :alt="t('用量与按模型花费分析', 'Usage and spend by model')" width="1643" height="840" loading="lazy" />
            </div>
          </div>
        </div>

        <!-- 5. Audit & security -->
        <div class="sa-feature">
          <div class="sa-feature__copy">
            <span class="sa-feature__eyebrow"><span class="sa-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l8 3v5c0 5-3.5 8-8 10-4.5-2-8-5-8-10V6l8-3zM9 12l2 2 4-4"/></svg></span>{{ t('审计与安全', 'Audit & security') }}</span>
            <h3>{{ t('AI 怎么用的,公司都看得见', 'See how AI is used, everywhere') }}</h3>
            <p>
              {{
                t(
                  '系统自带审计和安全体系,接入办公工具和审批流程。关键动作交给人确认,每一步都有记录,让 AI 在公司里用得安全、看得见。',
                  'Audit and security are built in, with office tools and approvals connected. Key actions wait for a person to confirm, and every step is recorded, so AI use is safe and visible.'
                )
              }}
            </p>
            <ul>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('关键决策等人确认后才继续', 'Key decisions wait for human confirmation') }}</li>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('身份、权限与运行日志', 'Identity, permissions, and run logs') }}</li>
              <li><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path :d="check"/></svg>{{ t('对接企业审批流程', 'Enterprise approval workflows') }}<span class="sa-soon">{{ t('即将推出', 'Soon') }}</span></li>
            </ul>
          </div>
          <div class="sa-feature__media">
            <div class="sa-shot">
              <div class="sa-shot__bar"><i></i><i></i><i></i><span>superagent · to-do</span></div>
              <img :src="withBase('/shots/todo.jpg')" :alt="t('等待你确认的决策', 'Decisions waiting for confirmation')" width="1655" height="751" loading="lazy" />
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- ░░ Can do anything ░░ -->
    <section class="sa-more">
      <div class="sa-wrap sa-section">
        <div class="sa-head">
          <div class="sa-eyebrow">{{ t('什么工作都能做', 'Any kind of work') }}</div>
          <h2>{{ t('一个入口,覆盖公司的日常工作', 'One entry point for everyday work') }}</h2>
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
          <a class="sa-btn sa-btn--ghost" :href="docsFeatures">{{ t('查看完整功能说明', 'See all features') }}</a>
        </p>
      </div>
    </section>

    <!-- ░░ Four pillars ░░ -->
    <section class="sa-section">
      <div class="sa-wrap">
        <div class="sa-head">
          <div class="sa-eyebrow">{{ t('一句话', 'In short') }}</div>
          <h2>{{ t('公司可控、节省成本、自动沉淀资产、好用', 'Controlled. Cost-efficient. Accumulating. Easy.') }}</h2>
        </div>
        <div class="sa-benefits sa-benefits--4">
          <div class="sa-benefit" v-for="p in pillars" :key="p.title">
            <span class="sa-ico"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="p.icon"/></svg></span>
            <h3>{{ p.title }}</h3>
            <p>{{ p.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- ░░ CTA ░░ -->
    <section class="sa-cta">
      <div class="sa-wrap">
        <div class="sa-cta__panel">
          <h2>{{ t('给公司一个真正用得起来的 AI', 'Give your company AI it will actually use') }}</h2>
          <p>{{ t('开源、自托管。部署一次,全公司都能用。', 'Open source and self-hosted. Deploy once, and the whole company is ready.') }}</p>
          <div class="sa-actions">
            <a class="sa-btn sa-btn--white" :href="demo" target="_blank" rel="noreferrer">{{ t('在线体验', 'Try the live demo') }}</a>
            <a class="sa-btn sa-btn--clear" :href="docsStart">{{ t('部署指南', 'Deployment guide') }}</a>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>
