import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, type PluginHost } from '@alinha/plugin-sdk'
import { renderWithHost } from '../test/renderWithHost'
import type { Connection, Evidence } from '../connection/types'
import { CodeRepositoryPage, isRunning } from './CodeRepositoryPage'

const CONNECTION: Connection = {
  host: 'github', account: 'acme', repositories: [], status: 'ACTIVE', lastError: null,
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

/** A fake /api: answers by path and method. */
function apiWith(routes: Record<string, unknown>) {
  return vi.fn(async (path: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (!(key in routes)) throw new Error(`unexpected ${key}`)
    const answer = routes[key]
    if (answer instanceof Error) throw answer
    return answer
  }) as unknown as PluginHost['api']
}

const render = (api: PluginHost['api'], can: PluginHost['can'] = () => true) =>
  renderWithHost(<CodeRepositoryPage />, { host: { api, can } })

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
    expect(screen.getByText('O token não tem permissão para ver esta definição.')).toBeInTheDocument()
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
})
