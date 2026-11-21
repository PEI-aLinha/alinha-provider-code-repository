import { definePlugin } from '@alinha/plugin-sdk'
import { GitBranch } from 'lucide-react'
import { CodeRepositoryPage } from './pages/CodeRepositoryPage'

/**
 * The plugin's frontend entry point. The shell reads this object and mounts
 * the routes and the menu — nothing to register by hand in the shell.
 *   area 'client'     → /org/:org/<path>
 *   area 'consultant' → /portfolio/:org/<path>
 * User-visible text (title, label) is in European Portuguese, plain language
 * (UI requirement in the technical report). Paths are in English.
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
    { label: 'Repositório de código', path: 'integrations/code-repository', area: 'client', icon: GitBranch, order: 70 },
  ],
})
