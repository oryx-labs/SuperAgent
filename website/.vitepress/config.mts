import { defineConfig } from 'vitepress'

const base = '/SuperAgent/'
const siteUrl = 'https://oryx-labs.github.io/SuperAgent/'
const github = 'https://github.com/oryx-labs/SuperAgent'
const demo = 'http://117.72.92.117:18081/login'

export default defineConfig({
  title: 'SuperAgent',
  titleTemplate: ':title — SuperAgent',
  description:
    'AI collaborative work system for companies, not personal assistants. Everyone works with AI in one system, and the work becomes company assets.',
  base,
  cleanUrls: true,
  appearance: false, // light only

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }],
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    [
      'link',
      {
        rel: 'stylesheet',
        href: 'https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;600;700&display=swap',
      },
    ],
    ['meta', { name: 'author', content: 'SuperAgent' }],
    [
      'meta',
      {
        name: 'keywords',
        content:
          'SuperAgent, enterprise AI, self-hosted AI, AI work platform, AI governance, model routing, agents, organizational knowledge, MCP',
      },
    ],
    ['meta', { name: 'robots', content: 'index, follow' }],
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:site_name', content: 'SuperAgent' }],
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
  ],

  locales: {
    root: {
      label: 'English',
      lang: 'en-US',
      themeConfig: {
        nav: [
          { text: 'Home', link: '/' },
          { text: 'Docs', link: '/docs/what' },
          { text: 'Live demo', link: demo },
          { text: 'GitHub', link: github },
        ],
        sidebar: {
          '/docs/': [
            {
              text: 'Introduction',
              items: [
                { text: 'What is SuperAgent', link: '/docs/what' },
                { text: 'Why SuperAgent', link: '/docs/why' },
              ],
            },
            {
              text: 'Capabilities',
              items: [{ text: 'Features', link: '/docs/features' }],
            },
            {
              text: 'Getting Started',
              items: [{ text: 'Quick Start', link: '/docs/getting-started' }],
            },
          ],
        },
        editLink: {
          pattern: `${github}/edit/main/website/:path`,
          text: 'Edit this page on GitHub',
        },
        docFooter: { prev: 'Previous', next: 'Next' },
      },
    },
    zh: {
      label: '中文',
      lang: 'zh-CN',
      link: '/zh/',
      themeConfig: {
        nav: [
          { text: '首页', link: '/zh/' },
          { text: '文档', link: '/zh/docs/what' },
          { text: '在线体验', link: demo },
          { text: 'GitHub', link: github },
        ],
        sidebar: {
          '/zh/docs/': [
            {
              text: '介绍',
              items: [
                { text: 'SuperAgent 是什么', link: '/zh/docs/what' },
                { text: '为什么选 SuperAgent', link: '/zh/docs/why' },
              ],
            },
            {
              text: '核心能力',
              items: [{ text: '功能说明', link: '/zh/docs/features' }],
            },
            {
              text: '快速入门',
              items: [{ text: '快速开始', link: '/zh/docs/getting-started' }],
            },
          ],
        },
        editLink: {
          pattern: `${github}/edit/main/website/:path`,
          text: '在 GitHub 上编辑此页',
        },
        docFooter: { prev: '上一页', next: '下一页' },
        outline: { label: '本页目录' },
        lastUpdated: { text: '最后更新' },
      },
    },
  },

  themeConfig: {
    siteTitle: false, // logo image already contains the SuperAgent wordmark
    logo: '/logo.png',
    socialLinks: [{ icon: 'github', link: github }],
  },

  sitemap: { hostname: siteUrl },

  transformHead({ page, title, description }) {
    const pagePath = page.replace(/index\.md$/, '').replace(/\.md$/, '')
    const canonicalUrl = new URL(pagePath || './', siteUrl).href
    return [
      ['meta', { property: 'og:title', content: title }],
      ['meta', { property: 'og:description', content: description }],
      ['meta', { property: 'og:url', content: canonicalUrl }],
      ['meta', { name: 'twitter:title', content: title }],
      ['meta', { name: 'twitter:description', content: description }],
      ['link', { rel: 'canonical', href: canonicalUrl }],
    ]
  },
})
