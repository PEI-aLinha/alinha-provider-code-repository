import { useState, type FormEvent } from 'react'
import { errorMessage, fieldErrors, useApiMutation } from '@alinha/plugin-sdk'
import { Button, Field, Note, Segmented, TextArea, TextInput } from '@alinha/ui'
import { HOST_LABEL } from './labels'
import { QUERY_KEY, type Connection, type ConnectInput, type Host } from './types'

const MAX_REPOSITORIES = 20
const GITHUB_ACCOUNT = /^[A-Za-z0-9][A-Za-z0-9-]{0,38}$/
const GITHUB_REPOSITORY = /^[A-Za-z0-9_.-]{1,100}\/[A-Za-z0-9_.-]{1,100}$/
const GITLAB_PATH = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}(\/[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}){0,19}$/
const SERVER = /^https:\/\/([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(:\d{1,5})?\/?$/i

const isGitLabPath = (value: string) => value.length <= 255 && GITLAB_PATH.test(value) && !value.includes('..')

/** Client-side checks: the same rules as the backend, so most mistakes show before sending. */
export function validate(input: { host: Host; account: string; repositories: string[]; token: string; baseUrl: string },
  tokenRequired: boolean) {
  const { host, account, repositories, token, baseUrl } = input
  const errors: Record<string, string> = {}
  const accountOk = host === 'github' ? GITHUB_ACCOUNT.test(account) : isGitLabPath(account)
  if (!accountOk) {
    errors.account = host === 'github'
      ? 'Indique o nome da organização ou do utilizador, tal como aparece no endereço.'
      : 'Indique o caminho do grupo (por exemplo empresa ou empresa/equipa) ou o nome do utilizador.'
  }
  const inAccount = (r: string) => host === 'github'
    ? GITHUB_REPOSITORY.test(r) && r.split('/')[0].toLowerCase() === account.toLowerCase()
    : isGitLabPath(r) && r.toLowerCase().startsWith(`${account.toLowerCase()}/`)
  if (repositories.length > MAX_REPOSITORIES || !repositories.every(inAccount)) {
    errors.repositories = `Indique até ${MAX_REPOSITORIES} repositórios dentro de ${account || 'organização'}, um por linha.`
  }
  if (host === 'gitlab' && baseUrl && !SERVER.test(baseUrl)) {
    errors.baseUrl = 'Indique o endereço do servidor GitLab, por exemplo https://gitlab.empresa.pt.'
  }
  if (tokenRequired && !token) errors.token = 'Para ligar o repositório, cole um token de acesso.'
  return errors
}

/** The stored token stays valid only for the same host and server, and only if the connection used one. */
const keepsToken = (current: Connection | undefined, host: Host, baseUrl: string) =>
  !!current && current.auth === 'token' && current.host === host && (current.baseUrl ?? '') === normalise(baseUrl)

const normalise = (baseUrl: string) => {
  const value = baseUrl.trim().replace(/\/$/, '').toLowerCase().replace(/:443$/, '')
  return value === 'https://gitlab.com' ? '' : value
}

export function ConnectForm({ current, onDone, onCancel }: { current?: Connection; onDone: () => void; onCancel?: () => void }) {
  const [host, setHost] = useState<Host>(current?.host ?? 'github')
  const [baseUrl, setBaseUrl] = useState(current?.baseUrl ?? '')
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
  const tokenRequired = !keepsToken(current, host, baseUrl)

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const repositories = repositoriesText.split(/[\s,]+/).map((r) => r.trim()).filter(Boolean)
    const input = { host, account: account.trim(), repositories, token: token.trim(), baseUrl: host === 'gitlab' ? baseUrl.trim() : '' }
    const found = validate(input, tokenRequired)
    setErrors(found)
    if (Object.keys(found).length > 0) return
    connect.mutate({
      host,
      account: input.account,
      repositories,
      ...(input.baseUrl ? { baseUrl: input.baseUrl } : {}),
      ...(input.token ? { token: input.token } : {}),
    })
  }

  const problem = connect.isError && Object.keys(fieldErrors(connect.error)).length === 0 ? errorMessage(connect.error) : null
  const example = host === 'github' ? 'github.com/empresa' : 'gitlab.com/empresa/equipa'

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-5" aria-label="Ligar repositório de código">
      <Note>
        Só pedimos acesso de leitura. Não alteramos nada nos seus repositórios e não lemos o código: apenas verificamos
        as definições de segurança, agora e depois todos os dias.
      </Note>

      <Field label="Plataforma">
        {({ id }) => (
          <div id={id}>
            <Segmented<Host> ariaLabel="Plataforma" value={host} onChange={setHost}
              options={(['github', 'gitlab'] as const).map((value) => ({ value, label: HOST_LABEL[value] }))} />
          </div>
        )}
      </Field>

      {host === 'gitlab' && (
        <Field label="Servidor GitLab" optional error={errors.baseUrl}
          hint="Só se a organização tiver o seu próprio servidor, por exemplo https://gitlab.empresa.pt. Deixe em branco para gitlab.com.">
          {({ id, describedBy, invalid }) => (
            <TextInput id={id} aria-describedby={describedBy} invalid={invalid} value={baseUrl} autoComplete="off"
              inputMode="url" placeholder="https://gitlab.com" onChange={(e) => setBaseUrl(e.target.value)} />
          )}
        </Field>
      )}

      <Field label={host === 'github' ? 'Organização ou utilizador no GitHub' : 'Grupo ou utilizador no GitLab'}
        hint={`O nome que aparece no endereço, por exemplo ${example}.`} error={errors.account}>
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

      <Field label="Token de acesso" optional={!tokenRequired} error={errors.token}
        hint={tokenRequired ? 'Guardado num cofre seguro. Nunca o voltamos a mostrar.' : 'Deixe em branco para manter o token guardado.'}>
        {({ id, describedBy, invalid, required }) => (
          <TextInput id={id} type="password" aria-describedby={describedBy} invalid={invalid} required={required}
            autoComplete="off" spellCheck={false} value={token} onChange={(e) => setToken(e.target.value)} />
        )}
      </Field>

      <details className="rounded-xl border border-line-soft px-4 py-3 text-[0.875rem] text-ink-soft">
        <summary className="cursor-pointer font-semibold text-ink">Como criar um token só de leitura</summary>
        {host === 'github' ? (
          <ol className="mt-2 list-decimal space-y-1 pl-5">
            <li>No GitHub, abra Settings → Developer settings → Personal access tokens → Fine-grained tokens.</li>
            <li>Escolha a organização como dono do recurso e os repositórios a verificar.</li>
            <li>Em permissões do repositório, dê só <strong>Read-only</strong> a: Administration, Dependabot alerts,
              Secret scanning alerts e Metadata.</li>
            <li>Defina uma data de validade, gere o token e cole-o aqui.</li>
          </ol>
        ) : (
          <ol className="mt-2 list-decimal space-y-1 pl-5">
            <li>No GitLab, abra o grupo → Settings → Access tokens (ou, para um utilizador, Preferences → Access tokens).</li>
            <li>Escolha o papel <strong>Reporter</strong> (ou Maintainer, para ver a proteção dos ramos) e só o âmbito
              <strong> read_api</strong>.</li>
            <li>Defina uma data de validade, gere o token e cole-o aqui.</li>
          </ol>
        )}
      </details>

      {problem && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{problem}</p>}

      <div className="flex flex-wrap gap-3">
        <Button type="submit" disabled={connect.isPending}>{connect.isPending ? 'A ligar…' : current ? 'Guardar e recolher' : 'Ligar e recolher'}</Button>
        {onCancel && <Button type="button" variant="ghost" onClick={onCancel}>Cancelar</Button>}
      </div>
    </form>
  )
}
