import { definePlugin } from '@alinha/plugin-sdk'
import { CodeRepositoryPage } from './pages/CodeRepositoryPage'

/**
 * The plugin's frontend entry point. The shell reads this object and mounts
 * the routes and the menu — nothing to register by hand in the shell.
 *   area 'client'     → /org/:org/<path>
 *   area 'consultant' → /portfolio/<path>
 * User-visible text (title, label) and paths are in Portuguese, plain language
 * (UI requirement in the technical report).
 */
export default definePlugin({
  id: 'code-repository',
  name: 'Code Repository',
  routes: {
    client: [
      { path: 'integrations/code-repository', handle: { title: 'Repositório de código' }, Component: CodeRepositoryPage },
    ],
  },
  nav: [
    { label: 'Repositório de código', path: 'integrations/code-repository', area: 'client' },
  ],
})
