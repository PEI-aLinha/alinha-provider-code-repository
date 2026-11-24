import { fireEvent, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, type PluginHost } from '@alinha/plugin-sdk'
import { renderWithHost } from '../test/renderWithHost'
import { ConnectForm, validate } from './ConnectForm'
import type { Connection } from './types'

const CONNECTION: Connection = {
  host: 'github', account: 'acme', repositories: ['acme/api'], status: 'ACTIVE', lastError: null,
  lastCollectedAt: '2026-11-24T10:00:00Z', truncated: false, connectedAt: '2026-11-24T09:00:00Z',
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })

describe('validate', () => {
  it('accepts an account, its repositories and a token', () => {
    expect(validate('acme', ['acme/api', 'ACME/web'], 'ghp', true)).toEqual({})
  })

  it('names every invalid field', () => {
    expect(Object.keys(validate('acme/x', ['other/api'], '', true))).toEqual(['account', 'repositories', 'token'])
  })

  it('allows no token when one is already stored, and at most 20 repositories', () => {
    expect(validate('acme', [], '', false)).toEqual({})
    expect(validate('acme', Array.from({ length: 21 }, (_, i) => `acme/r${i}`), 't', false)).toHaveProperty('repositories')
  })
})

describe('ConnectForm', () => {
  it('sends the connection without spaces and clears the token on success', async () => {
    const onDone = vi.fn()
    const api = vi.fn(async () => CONNECTION) as PluginHost['api']
    renderWithHost(<ConnectForm onDone={onDone} />, { host: { api } })

    type(/Organização ou utilizador/, ' acme ')
    type(/Repositórios a verificar/, 'acme/api\nacme/web')
    type(/Token de acesso/, ' ghp_x ')
    fireEvent.click(screen.getByRole('button', { name: 'Ligar e recolher' }))

    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(api).toHaveBeenCalledWith('/code-repository/connection', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ host: 'github', account: 'acme', repositories: ['acme/api', 'acme/web'], token: 'ghp_x' }),
    }))
    expect(screen.getByLabelText(/Token de acesso/)).toHaveValue('')
  })

  it('does not call the API when a field is invalid', () => {
    const api = vi.fn() as PluginHost['api']
    renderWithHost(<ConnectForm onDone={vi.fn()} />, { host: { api } })

    fireEvent.click(screen.getByRole('button', { name: 'Ligar e recolher' }))

    expect(screen.getByText('Para ligar o repositório, cole um token de acesso.')).toBeInTheDocument()
    expect(api).not.toHaveBeenCalled()
  })

  it('leaves the token out when changing a connection without a new one', async () => {
    const api = vi.fn(async () => CONNECTION) as PluginHost['api']
    renderWithHost(<ConnectForm current={CONNECTION} onDone={vi.fn()} onCancel={vi.fn()} />, { host: { api } })

    expect(screen.getByLabelText(/Organização ou utilizador/)).toHaveValue('acme')
    fireEvent.click(screen.getByRole('button', { name: 'Guardar e recolher' }))

    await waitFor(() => expect(api).toHaveBeenCalled())
    expect(JSON.parse((vi.mocked(api).mock.calls[0][1] as RequestInit).body as string)).not.toHaveProperty('token')
  })

  it('shows the server field errors and other errors', async () => {
    const api = vi.fn()
      .mockRejectedValueOnce(new ApiError(422, '/code-repository/connection', undefined, {
        code: 'VALIDATION_FAILED', detail: 'Há dados por corrigir.',
        errors: [{ field: 'token', message: 'O token não é válido. Copie-o de novo, sem espaços.' }],
      }))
      .mockRejectedValueOnce(new ApiError(503, '/code-repository/connection', undefined, {
        code: 'SECRETS_UNAVAILABLE', detail: 'Não é possível guardar o token neste momento.',
      })) as PluginHost['api']
    renderWithHost(<ConnectForm onDone={vi.fn()} />, { host: { api } })
    type(/Organização ou utilizador/, 'acme')
    type(/Token de acesso/, 'x')

    fireEvent.click(screen.getByRole('button', { name: 'Ligar e recolher' }))
    expect(await screen.findByText('O token não é válido. Copie-o de novo, sem espaços.')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Ligar e recolher' }))
    expect(await screen.findByText('Não é possível guardar o token neste momento.')).toBeInTheDocument()
  })
})
