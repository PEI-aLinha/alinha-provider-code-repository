import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, type PluginHost } from '@alinha/plugin-sdk'
import { renderWithHost } from '../test/renderWithHost'
import type { Connection, Evidence, Setup } from '../connection/types'
import { CodeRepositoryPage, isRunning } from './CodeRepositoryPage'
import { leaveFor } from '../connection/leave'

vi.mock('../connection/leave', () => ({ leaveFor: vi.fn() }))

const CONNECTION: Connection = {
  host: 'github', auth: 'token', baseUrl: null, account: 'acme', repositories: [], status: 'ACTIVE', lastError: null,
  lastCollectedAt: '2026-11-24T10:00:00Z', truncated: false, connectedAt: '2026-11-24T09:00:00Z',
}

const item = (check: Evidence['check'], outcome: Evidence['outcome'], details: Evidence['details'] = {}): Evidence => ({
  id: `${check}-id`, repository: 'acme/api', check, outcome, details, controlIds: ['PR.SP-4-S'],
  strength: 'TECHNICALLY_VERIFIED', collectedAt: '2026-11-24T10:00:00Z',
})

const EVIDENCE: Evidence[] = [
  item('branch_protection', 'PASS'),
  item('required_reviews', 'PASS', { requiredApprovals: 2 }),
  item('dependency_alerts', 'FAIL', { enabled: true, openCriticalOrHigh: 3 }),
  item('secret_scanning', 'UNKNOWN', { reason: 'no_permission' }),
]

const SETUP: Setup = { githubApp: false, assignment: null }

/** A fake /api: answers by path and method. `/setup` answers SETUP unless the test gives another. */
function apiWith(given: Record<string, unknown>) {
  const routes: Record<string, unknown> = { 'GET /code-repository/setup': SETUP, ...given }
  return vi.fn(async (path: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (!(key in routes)) throw new Error(`unexpected ${key}`)
    const answer = routes[key]
    if (answer instanceof Error) throw answer
    return answer
  }) as unknown as PluginHost['api']
}

const render = (api: PluginHost['api'], can: PluginHost['can'] = () => true, url = '/org/acme/integrations/code-repository') =>
  renderWithHost(<CodeRepositoryPage />, { host: { api, can }, url })

describe('CodeRepositoryPage', () => {
  it('offers the connect form when nothing is connected', async () => {
    render(apiWith({ 'GET /code-repository/connection': undefined }))

    expect(await screen.findByRole('form', { name: 'Ligar repositório de código' })).toBeInTheDocument()
    expect(screen.getByText(/Só pedimos acesso de leitura/)).toBeInTheDocument()
  })

  it('tells people without permission whom to ask', async () => {
    render(apiWith({ 'GET /code-repository/connection': undefined }), () => false)

    expect(await screen.findByText('Ainda não está ligado')).toBeInTheDocument()
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
  })

  it('shows the connection and what each check found, in plain words', async () => {
    render(apiWith({ 'GET /code-repository/connection': CONNECTION, 'GET /code-repository/evidence': EVIDENCE }))

    expect(await screen.findByText('Ramo principal protegido')).toBeInTheDocument()
    expect(screen.getByText('GitHub · acme')).toBeInTheDocument()
    expect(screen.getByText('Todos os repositórios que o token consegue ver')).toBeInTheDocument()
    expect(screen.getByText('2 aprovações exigidas')).toBeInTheDocument()
    expect(screen.getByText('3 alertas críticos ou altos por resolver')).toBeInTheDocument()
    expect(screen.getByText('O acesso dado não tem permissão para ver esta definição.')).toBeInTheDocument()
    expect(screen.getAllByText('Verificado na plataforma')).toHaveLength(3)
    expect(screen.getByText('Todos os dias')).toBeInTheDocument()
    expect(screen.getAllByText('Cumpre')).toHaveLength(2)
    expect(screen.getByText('Não cumpre')).toBeInTheDocument()
  })

  it('explains a failed collection', async () => {
    render(apiWith({
      'GET /code-repository/connection': { ...CONNECTION, status: 'FAILED', lastError: 'invalid_credentials' },
      'GET /code-repository/evidence': [],
    }))

    expect(await screen.findByText(/O token foi recusado/)).toBeInTheDocument()
    expect(await screen.findByText('Os resultados aparecem aqui quando a primeira recolha terminar.')).toBeInTheDocument()
  })

  it('warns when only part of the account was checked', async () => {
    render(apiWith({ 'GET /code-repository/connection': { ...CONNECTION, truncated: true }, 'GET /code-repository/evidence': [] }))

    expect(await screen.findByText(/verificámos só os primeiros/)).toBeInTheDocument()
  })

  it('starts a new collection', async () => {
    const api = apiWith({
      'GET /code-repository/connection': CONNECTION,
      'GET /code-repository/evidence': EVIDENCE,
      'POST /code-repository/collections': { ...CONNECTION, status: 'PENDING' },
    })
    render(api)

    fireEvent.click(await screen.findByRole('button', { name: 'Recolher agora' }))

    await waitFor(() => expect(api).toHaveBeenCalledWith('/code-repository/collections', expect.objectContaining({ method: 'POST' })))
  })

  it('disables the actions while a collection runs', async () => {
    render(apiWith({ 'GET /code-repository/connection': { ...CONNECTION, status: 'PENDING' }, 'GET /code-repository/evidence': [] }))

    expect(await screen.findByRole('button', { name: 'A recolher…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Alterar ligação' })).toBeDisabled()
  })

  it('lets a collection stuck for more than 15 minutes be started again', async () => {
    const since = new Date(Date.now() - 16 * 60 * 1000).toISOString()
    render(apiWith({
      'GET /code-repository/connection': { ...CONNECTION, status: 'PENDING', collectingSince: since },
      'GET /code-repository/evidence': [],
    }))

    expect(await screen.findByRole('button', { name: 'Recolher agora' })).toBeEnabled()
  })

  it('counts only a recent PENDING as running', () => {
    const now = Date.parse('2026-11-24T10:20:00Z')
    expect(isRunning({ ...CONNECTION, status: 'PENDING', collectingSince: '2026-11-24T10:10:00Z' }, now)).toBe(true)
    expect(isRunning({ ...CONNECTION, status: 'PENDING', collectingSince: '2026-11-24T10:00:00Z' }, now)).toBe(false)
    expect(isRunning(CONNECTION, now)).toBe(false)
  })

  it('hides the actions from people who can only read', async () => {
    render(apiWith({ 'GET /code-repository/connection': CONNECTION, 'GET /code-repository/evidence': EVIDENCE }),
      (_resource, action) => action !== 'connect')

    expect(await screen.findByText('Ramo principal protegido')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Recolher agora' })).not.toBeInTheDocument()
  })

  it('opens the form to change the connection and goes back on cancel', async () => {
    render(apiWith({ 'GET /code-repository/connection': CONNECTION, 'GET /code-repository/evidence': EVIDENCE }))

    fireEvent.click(await screen.findByRole('button', { name: 'Alterar ligação' }))
    expect(screen.getByText('Alterar a ligação')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
    expect(await screen.findByRole('button', { name: 'Recolher agora' })).toBeInTheDocument()
  })

  it('asks before disconnecting', async () => {
    const api = apiWith({
      'GET /code-repository/connection': CONNECTION,
      'GET /code-repository/evidence': EVIDENCE,
      'DELETE /code-repository/connection': undefined,
    })
    render(api)

    fireEvent.click(await screen.findByRole('button', { name: 'Desligar' }))
    const dialog = await screen.findByRole('dialog', { name: 'Desligar o repositório?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Desligar' }))

    await waitFor(() => expect(api).toHaveBeenCalledWith('/code-repository/connection', expect.objectContaining({ method: 'DELETE' })))
  })

  it('shows an error with a retry when the connection cannot be read', async () => {
    render(apiWith({ 'GET /code-repository/connection': new ApiError(500, '/code-repository/connection') }))

    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })

  it('shows why an action failed', async () => {
    render(apiWith({
      'GET /code-repository/connection': CONNECTION,
      'GET /code-repository/evidence': EVIDENCE,
      'POST /code-repository/collections': new ApiError(409, '/code-repository/collections', undefined, {
        code: 'COLLECTION_RUNNING', detail: 'Já está a decorrer uma recolha. Aguarde que termine.',
      }),
    }))

    fireEvent.click(await screen.findByRole('button', { name: 'Recolher agora' }))

    expect(await screen.findByText('Já está a decorrer uma recolha. Aguarde que termine.')).toBeInTheDocument()
  })

  it('offers the GitHub App first when it is configured, and a token as the alternative', async () => {
    const api = apiWith({
      'GET /code-repository/setup': { githubApp: true, assignment: null },
      'GET /code-repository/connection': undefined,
      'POST /code-repository/github-app/installations': { installUrl: 'https://github.com/apps/alinha/installations/new?state=s' },
    })
    render(api)

    fireEvent.click(await screen.findByRole('button', { name: 'Instalar a aplicação do GitHub' }))
    await waitFor(() => expect(leaveFor).toHaveBeenCalledWith('https://github.com/apps/alinha/installations/new?state=s'))
    expect(sessionStorage.getItem('alinha:resume')).toContain('/org/acme/integrations/code-repository')

    fireEvent.click(screen.getByRole('button', { name: 'Ligar com um token (GitHub ou GitLab)' }))
    expect(screen.getByRole('form', { name: 'Ligar repositório de código' })).toBeInTheDocument()
  })

  it('goes to GitHub from an address with a trailing slash', async () => {
    render(apiWith({
      'GET /code-repository/setup': { githubApp: true, assignment: null },
      'GET /code-repository/connection': undefined,
      'POST /code-repository/github-app/installations': { installUrl: 'https://github.com/apps/alinha/installations/new?state=t' },
    }), () => true, '/org/acme/integrations/code-repository/')

    fireEvent.click(await screen.findByRole('button', { name: 'Instalar a aplicação do GitHub' }))

    await waitFor(() => expect(leaveFor).toHaveBeenCalledWith('https://github.com/apps/alinha/installations/new?state=t'))
  })

  it('finishes the GitHub App connection when GitHub sends the person back', async () => {
    const api = apiWith({
      'GET /code-repository/connection': { ...CONNECTION, auth: 'app', status: 'PENDING' },
      'GET /code-repository/evidence': [],
      'PUT /code-repository/github-app/installation': { ...CONNECTION, auth: 'app', status: 'PENDING' },
    })
    const { router } = render(api, () => true, '/org/acme/integrations/code-repository?installation_id=42&state=st&code=c0de')

    await waitFor(() => expect(api).toHaveBeenCalledWith('/code-repository/github-app/installation', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ installationId: 42, state: 'st', code: 'c0de' }),
    })))
    expect(router.state.location.search).toBe('')
    expect(await screen.findByText('Os repositórios escolhidos ao instalar a aplicação do GitHub')).toBeInTheDocument()
    expect(screen.getByText('Aplicação do GitHub')).toBeInTheDocument()
  })

  it('says why the GitHub App could not be connected', async () => {
    render(apiWith({
      'GET /code-repository/connection': undefined,
      'PUT /code-repository/github-app/installation': new ApiError(422, '/code-repository/github-app/installation', undefined, {
        code: 'APP_INSTALL_EXPIRED', detail: 'A instalação da aplicação expirou ou não foi iniciada nesta página. Comece de novo.',
      }),
    }), () => true, '/org/acme/integrations/code-repository?installation_id=42&state=st&code=c')

    expect(await screen.findByText(/A instalação da aplicação expirou/)).toBeInTheDocument()
  })

  it('tells that an organisation owner must approve the installation', async () => {
    render(apiWith({ 'GET /code-repository/connection': undefined }), () => true,
      '/org/acme/integrations/code-repository?setup_action=request')

    expect(await screen.findByText(/O pedido de instalação foi enviado/)).toBeInTheDocument()
  })

  it('shows a GitLab self-managed connection with its server', async () => {
    render(apiWith({
      'GET /code-repository/connection': { ...CONNECTION, host: 'gitlab', baseUrl: 'https://git.acme.pt', account: 'acme/team' },
      'GET /code-repository/evidence': [item('secret_scanning', 'UNKNOWN', { reason: 'not_available' }),
        { ...item('dependency_alerts', 'FAIL', { enabled: true, openFindings: 4 }), strength: 'CONTINUOUSLY_VERIFIED' }],
    }))

    expect(await screen.findByText('GitLab (git.acme.pt) · acme/team')).toBeInTheDocument()
    expect(await screen.findByText(/Não está disponível no plano/)).toBeInTheDocument()
    expect(screen.getByText('4 vulnerabilidades críticas ou altas por resolver')).toBeInTheDocument()
    expect(screen.getByText('Verificado todos os dias')).toBeInTheDocument()
  })

  it('lets the responsible person ask someone by email, and shows who was asked', async () => {
    const api = apiWith({
      'GET /code-repository/connection': undefined,
      'PUT /code-repository/assignment': { email: 'ana@acme.pt', assignedAt: '2026-11-24T10:00:00Z' },
    })
    render(api)

    fireEvent.change(await screen.findByLabelText('Email da pessoa'), { target: { value: 'nope' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enviar pedido' }))
    expect(screen.getByText('Indique um endereço de email válido.')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Email da pessoa'), { target: { value: ' ana@acme.pt ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Enviar pedido' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/code-repository/assignment', expect.objectContaining({
      method: 'PUT', body: JSON.stringify({ email: 'ana@acme.pt' }),
    })))
  })

  it('shows the assignment to someone who cannot change it, and lets the responsible withdraw it', async () => {
    const setup = { githubApp: false, assignment: { email: 'ana@acme.pt', assignedAt: '2026-11-24T10:00:00Z' } }
    const { unmount } = render(apiWith({ 'GET /code-repository/setup': setup, 'GET /code-repository/connection': undefined }),
      (_r, action) => action !== 'assign')
    expect(await screen.findByText('ana@acme.pt')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retirar pedido' })).not.toBeInTheDocument()
    unmount()

    const api = apiWith({ 'GET /code-repository/setup': setup, 'GET /code-repository/connection': undefined,
      'DELETE /code-repository/assignment': undefined })
    render(api)
    fireEvent.click(await screen.findByRole('button', { name: 'Retirar pedido' }))
    await waitFor(() => expect(api).toHaveBeenCalledWith('/code-repository/assignment', expect.objectContaining({ method: 'DELETE' })))
  })
})
