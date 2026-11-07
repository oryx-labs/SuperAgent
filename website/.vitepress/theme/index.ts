import DefaultTheme from 'vitepress/theme'
import './custom.css'
import Home from './components/Home.vue'
import Shot from './components/Shot.vue'

export default {
  extends: DefaultTheme,
  enhanceApp({ app }) {
    app.component('Home', Home)
    app.component('Shot', Shot)
  },
}
