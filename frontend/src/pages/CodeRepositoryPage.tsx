import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { GitBranch } from 'lucide-react'
import { errorMessage, useApiMutation, useApiQuery, useHost } from '@alinha/plugin-sdk'
import { Badge, Button, Card, EmptyState, ErrorState, Eyebrow, Modal, Note, PageHeader, SkeletonRows } from '@alinha/ui'
import { AssignmentPanel } from '../connection/AssignmentPanel'
import { ConnectForm } from '../connection/ConnectForm'
import { EvidenceList } from '../connection/EvidenceList'
import { InstallAppButton, useAppInstallReturn } from '../connection/GitHubApp'
import { connectionTitle, errorText, formatDate, STATUS_LABEL } from '../connection/labels'
import { QUERY_KEY, RESOURCE, type Connection, type Evidence, type Setup } from '../connection/types'

/** While a collection runs, ask again every few seconds. */
const POLL_MS = 4000
/** Same deadline as the backend: a collection PENDING for longer is treated as lost and may be started again. */
const STALE_MS = 15 * 60 * 1000

export function isRunning(connection: Connection, now = Date.now()): boolean {
  if (connection.status !== 'PENDING') return false
  return !connection.collectingSince || now - new Date(connection.collectingSince).getTime() < STALE_MS
}

export function CodeRepositoryPage() {
  const { api, can } = useHost()
  const canConnect = can(RESOURCE, 'connect')
  const [editing, setEditing] = useState(false)
  const appReturn = useAppInstallReturn()
  const setup = useApiQuery<Setup>([...QUERY_KEY, 'setup'], '/code-repository/setup')
  // 204 (nothing connected) resolves to undefined, which TanStack Query does not accept as data: keep it as null.
  const connection = useQuery<Connection | null>({
    queryKey: [...QUERY_KEY, 'connection'],
    queryFn: async ({ signal }) => (await api<Connection | undefined>('/code-repository/connection', { signal })) ?? null,
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? POLL_MS : false),
  })
  const status = connection.data?.status
  const evidence = useApiQuery<Evidence[]>([...QUERY_KEY, 'evidence', status ?? 'none'], '/code-repository/evidence', {
    enabled: !!connection.data,
  })

  return (
    <>
      <PageHeader title="Repositório de código"
        subtitle="Verificamos, só com acesso de leitura, as boas práticas de segurança dos repositórios onde a organização guarda o seu código." />
      {appReturn.requested && (
        <Note>O pedido de instalação foi enviado a quem gere a organização no GitHub. Quando for aprovado, volte a esta página e instale de novo.</Note>
      )}
      {appReturn.error && <p role="alert" className="mb-4 text-[0.875rem] font-semibold text-danger">{errorMessage(appReturn.error)}</p>}
      {appReturn.completing ? (
        <SkeletonRows label="A concluir a ligação ao GitHub…" />
      ) : connection.isPending ? (
        <SkeletonRows label="A carregar a ligação…" />
      ) : connection.isError ? (
        <ErrorState error={connection.error} onRetry={() => void connection.refetch()} />
      ) : !connection.data || editing ? (
        <div className="flex flex-col gap-6">
          <NotConnected current={connection.data ?? undefined} canConnect={canConnect} githubApp={setup.data?.githubApp ?? false}
            onDone={() => setEditing(false)} onCancel={connection.data ? () => setEditing(false) : undefined} />
          {!connection.data && <AssignmentPanel assignment={setup.data?.assignment} canAssign={can(RESOURCE, 'assign')} />}
        </div>
      ) : (
        <div className="flex flex-col gap-6">
          <ConnectionCard connection={connection.data} canConnect={canConnect} onEdit={() => setEditing(true)} />
          <section aria-labelledby="evidence-heading" className="flex flex-col gap-3">
            <h2 id="evidence-heading" className="text-[1.125rem] font-bold text-ink">O que encontrámos</h2>
            {evidence.isPending ? (
              <SkeletonRows label="A carregar os resultados…" />
            ) : evidence.isError ? (
              <ErrorState error={evidence.error} onRetry={() => void evidence.refetch()} />
            ) : evidence.data.length === 0 ? (
              <p className="text-ink-soft">Os resultados aparecem aqui quando a primeira recolha terminar.</p>
            ) : (
              <EvidenceList evidence={evidence.data} />
            )}
          </section>
        </div>
      )}
    </>
  )
}

function NotConnected({ current, canConnect, githubApp, onDone, onCancel }: {
  current?: Connection; canConnect: boolean; githubApp: boolean; onDone: () => void; onCancel?: () => void
}) {
  const [withToken, setWithToken] = useState(false)
  if (!canConnect) {
    return (
      <EmptyState icon={<GitBranch className="size-5" aria-hidden />} title="Ainda não está ligado">
        Peça ao responsável da organização, ou a quem ele indicar, para ligar o repositório de código.
      </EmptyState>
    )
  }
  if (githubApp && !withToken) {
    return (
      <Card className="flex max-w-[640px] flex-col gap-4 p-6">
        <Eyebrow as="h2">{current ? 'Alterar a ligação' : 'Ligar o repositório de código'}</Eyebrow>
        <div className="flex flex-col gap-2">
          <h3 className="font-bold text-ink">GitHub: instalar a aplicação da aLinha (recomendado)</h3>
          <p className="text-[0.875rem] text-ink-soft">
            Escolhe no GitHub os repositórios a verificar. A aplicação só tem acesso de leitura às definições de segurança,
            não precisa de criar nenhum token e pode removê-la a qualquer momento.
          </p>
          <InstallAppButton />
        </div>
        <div className="flex flex-wrap gap-3 border-t border-line-soft pt-4">
          <Button variant="secondary" onClick={() => setWithToken(true)}>Ligar com um token (GitHub ou GitLab)</Button>
          {onCancel && <Button variant="ghost" onClick={onCancel}>Cancelar</Button>}
        </div>
      </Card>
    )
  }
  return (
    <Card className="max-w-[640px] p-6">
      <Eyebrow as="h2">{current ? 'Alterar a ligação' : 'Ligar o repositório de código'}</Eyebrow>
      <div className="mt-4">
        <ConnectForm current={current} onDone={onDone} onCancel={githubApp ? () => setWithToken(false) : onCancel} />
      </div>
    </Card>
  )
}

function ConnectionCard({ connection, canConnect, onEdit }: { connection: Connection; canConnect: boolean; onEdit: () => void }) {
  const [confirming, setConfirming] = useState(false)
  const invalidates = [[...QUERY_KEY]]
  const collect = useApiMutation<void, Connection>({ path: '/code-repository/collections', invalidates })
  const disconnect = useApiMutation<void, void>({ method: 'DELETE', path: '/code-repository/connection', invalidates,
    onSuccess: () => setConfirming(false) })
  const pending = isRunning(connection)
  const actionError = collect.error ?? disconnect.error

  return (
    <Card className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <Eyebrow as="h2">{connectionTitle(connection)}</Eyebrow>
          <p className="mt-1 text-[0.875rem] text-ink-soft">
            {connection.repositories.length > 0
              ? connection.repositories.join(', ')
              : connection.auth === 'app'
                ? 'Os repositórios escolhidos ao instalar a aplicação do GitHub'
                : 'Todos os repositórios que o token consegue ver'}
          </p>
        </div>
        <Badge tone={connection.status === 'FAILED' ? 'dark' : connection.status === 'ACTIVE' ? 'signal' : 'neutral'}>
          {STATUS_LABEL[connection.status]}
        </Badge>
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[0.875rem]">
        <dt className="text-muted">Última recolha</dt>
        <dd className="text-ink">{formatDate(connection.lastCollectedAt)}</dd>
        <dt className="text-muted">Acesso</dt>
        <dd className="text-ink">{connection.auth === 'app' ? 'Aplicação do GitHub' : 'Token de acesso'}</dd>
        <dt className="text-muted">Recolha automática</dt>
        <dd className="text-ink">Todos os dias</dd>
      </dl>

      {connection.status === 'FAILED' && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{errorText(connection.lastError)}</p>}
      {connection.truncated && (
        <Note>A conta tem mais de 20 repositórios: verificámos só os primeiros. Indique quais quer verificar em “Alterar ligação”.</Note>
      )}
      {actionError && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{errorMessage(actionError)}</p>}

      {canConnect && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => collect.mutate()} disabled={pending || collect.isPending}>
            {pending ? 'A recolher…' : 'Recolher agora'}
          </Button>
          <Button variant="secondary" onClick={onEdit} disabled={pending}>Alterar ligação</Button>
          <Button variant="ghost" onClick={() => setConfirming(true)}>Desligar</Button>
        </div>
      )}

      <Modal open={confirming} onOpenChange={setConfirming} title="Desligar o repositório?"
        description="Apagamos o acesso guardado e deixamos de recolher, incluindo a recolha diária. Os resultados já recolhidos ficam no histórico."
        footer={(
          <>
            <Button variant="ghost" onClick={() => setConfirming(false)}>Cancelar</Button>
            <Button onClick={() => disconnect.mutate()} disabled={disconnect.isPending}>Desligar</Button>
          </>
        )} />
    </Card>
  )
}
