import { definePlugin } from '@alinha/plugin-sdk'
import { CodeRepositoryPage } from './pages/CodeRepositoryPage'

/**
 * Ponto de entrada do plugin no frontend. O shell lê este objeto e monta
 * as rotas e o menu — não há nada a registar à mão no shell.
 *   area 'client'     → /org/:org/<path>
 *   area 'consultant' → /carteira/<path>
 */
export default definePlugin({
  id: 'code-repository',
  name: 'Code Repository',
  routes: {
    client: [
      { path: 'code-repository', handle: { title: 'Code Repository' }, Component: CodeRepositoryPage },
    ],
  },
  nav: [
    { label: 'Code Repository', path: 'code-repository', area: 'client' },
  ],
})
