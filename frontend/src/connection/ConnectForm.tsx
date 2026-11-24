import { useState, type FormEvent } from 'react'
import { errorMessage, fieldErrors, useApiMutation } from '@alinha/plugin-sdk'
import { Button, Field, Note, TextArea, TextInput } from '@alinha/ui'
import { QUERY_KEY, type Connection, type ConnectInput } from './types'

const ACCOUNT = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/
const MAX_REPOSITORIES = 20

/** Client-side checks: the same rules as the backend, so most mistakes show before sending. */
export function validate(account: string, repositories: string[], token: string, tokenRequired: boolean) {
  const errors: Record<string, string> = {}
  if (!ACCOUNT.test(account)) errors.account = 'Indique o nome da organização ou do utilizador, tal como aparece no endereço.'
  const outside = repositories.some((r) => !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(r) || r.split('/')[0].toLowerCase() !== account.toLowerCase())
  if (repositories.length > MAX_REPOSITORIES || outside) {
    errors.repositories = `Indique até ${MAX_REPOSITORIES} repositórios no formato ${account || 'organização'}/repositório, um por linha.`
  }
  if (tokenRequired && !token) errors.token = 'Para ligar o repositório, cole um token de acesso.'
  return errors
}

export function ConnectForm({ current, onDone, onCancel }: { current?: Connection; onDone: () => void; onCancel?: () => void }) {
  const [account, setAccount] = useState(current?.account ?? '')
  const [repositoriesText, setRepositoriesText] = useState(current?.repositories.join('\n') ?? '')
  const [token, setToken] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const connect = useApiMutation<ConnectInput, Connection>({
    method: 'PUT',
    path: '/code-repository/connection',
    invalidates: [[...QUERY_KEY]],
    onSuccess: () => {
      setToken('')
      onDone()
    },
    onError: (error) => setErrors(fieldErrors(error)),
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const repositories = repositoriesText.split(/[\s,]+/).map((r) => r.trim()).filter(Boolean)
    const found = validate(account.trim(), repositories, token.trim(), !current)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    connect.mutate({ host: 'github', account: account.trim(), repositories, ...(token.trim() ? { token: token.trim() } : {}) })
  }

  const problem = connect.isError && Object.keys(fieldErrors(connect.error)).length === 0 ? errorMessage(connect.error) : null

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-5" aria-label="Ligar repositório de código">
      <Note>
        Só pedimos acesso de leitura. Não alteramos nada nos seus repositórios e não lemos o código: apenas verificamos
        as definições de segurança.
      </Note>

      <Field label="Plataforma" optional>
        {({ id }) => <TextInput id={id} value="GitHub" readOnly disabled />}
      </Field>

      <Field label="Organização ou utilizador no GitHub" hint="O nome que aparece no endereço, por exemplo github.com/empresa." error={errors.account}>
        {({ id, describedBy, invalid }) => (
          <TextInput id={id} aria-describedby={describedBy} invalid={invalid} value={account} autoComplete="off"
            onChange={(e) => setAccount(e.target.value)} />
        )}
      </Field>

      <Field label="Repositórios a verificar" optional error={errors.repositories}
        hint={`Um por linha, por exemplo ${account || 'empresa'}/site. Deixe em branco para verificar todos os que o token consegue ver (até ${MAX_REPOSITORIES}).`}>
        {({ id, describedBy, invalid }) => (
          <TextArea id={id} aria-describedby={describedBy} invalid={invalid} rows={3} value={repositoriesText}
            onChange={(e) => setRepositoriesText(e.target.value)} />
        )}
      </Field>

      <Field label="Token de acesso" optional={!!current} error={errors.token}
        hint={current ? 'Deixe em branco para manter o token guardado.' : 'Guardado num cofre seguro. Nunca o voltamos a mostrar.'}>
        {({ id, describedBy, invalid, required }) => (
          <TextInput id={id} type="password" aria-describedby={describedBy} invalid={invalid} required={required}
            autoComplete="off" spellCheck={false} value={token} onChange={(e) => setToken(e.target.value)} />
        )}
      </Field>

      <details className="rounded-xl border border-line-soft px-4 py-3 text-[0.875rem] text-ink-soft">
        <summary className="cursor-pointer font-semibold text-ink">Como criar um token só de leitura</summary>
        <ol className="mt-2 list-decimal space-y-1 pl-5">
          <li>No GitHub, abra Settings → Developer settings → Personal access tokens → Fine-grained tokens.</li>
          <li>Escolha a organização como dono do recurso e os repositórios a verificar.</li>
          <li>Em permissões do repositório, dê só <strong>Read-only</strong> a: Administration, Dependabot alerts,
            Secret scanning alerts e Metadata.</li>
          <li>Defina uma data de validade, gere o token e cole-o aqui.</li>
        </ol>
      </details>

      {problem && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{problem}</p>}

      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={connect.isPending}>{connect.isPending ? 'A ligar…' : current ? 'Guardar e recolher' : 'Ligar e recolher'}</Button>
        {onCancel && <Button type="button" variant="ghost" onClick={onCancel}>Cancelar</Button>}
      </div>
    </form>
  )
}
