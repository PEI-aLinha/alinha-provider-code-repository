import type { Connection, Evidence, Host } from './types'

export const HOST_LABEL: Record<Host, string> = { github: 'GitHub', gitlab: 'GitLab' }

/** Where the connection points, e.g. "GitHub · acme" or "GitLab (git.empresa.pt) · empresa/equipa". */
export function connectionTitle(connection: Connection): string {
  const server = connection.baseUrl ? ` (${connection.baseUrl.replace(/^https:\/\//, '')})` : ''
  return `${HOST_LABEL[connection.host]}${server} · ${connection.account}`
}

export const STRENGTH_LABEL: Partial<Record<Evidence['strength'], string>> = {
  CONTINUOUSLY_VERIFIED: 'Verificado todos os dias',
  TECHNICALLY_VERIFIED: 'Verificado na plataforma',
}

/** Plain-language names for each check (UI text in European Portuguese, ADR 0005). */
export const CHECK_LABEL: Record<Evidence['check'], string> = {
  branch_protection: 'Ramo principal protegido',
  required_reviews: 'Revisão obrigatória das alterações',
  dependency_alerts: 'Alertas de dependências vulneráveis',
  secret_scanning: 'Deteção de segredos no código',
}

export const CHECK_WHY: Record<Evidence['check'], string> = {
  branch_protection: 'As alterações só chegam ao código principal por um caminho controlado.',
  required_reviews: 'Outra pessoa aprova cada alteração antes de ser integrada.',
  dependency_alerts: 'As bibliotecas com falhas de segurança conhecidas são assinaladas.',
  secret_scanning: 'Palavras-passe e chaves deixadas no código são detetadas.',
}

export const OUTCOME_LABEL: Record<Evidence['outcome'], string> = {
  PASS: 'Cumpre',
  FAIL: 'Não cumpre',
  UNKNOWN: 'Sem acesso',
}

export const STATUS_LABEL: Record<Connection['status'], string> = {
  PENDING: 'A recolher…',
  ACTIVE: 'Ligado',
  FAILED: 'A última recolha falhou',
}

const ERROR_TEXT: Record<string, string> = {
  invalid_credentials: 'O token foi recusado: pode ter expirado ou sido revogado. Crie um novo e altere a ligação.',
  installation_not_found: 'A aplicação do GitHub já não está instalada nesta conta. Instale-a de novo ou ligue com um token.',
  provider_not_configured: 'A ligação pela aplicação do GitHub não está disponível neste momento. Ligue com um token.',
  host_not_allowed: 'O endereço do servidor não é um endereço público da internet. Indique o endereço com que acede ao GitLab.',
  connection_not_found: 'Não encontrámos o token guardado. Altere a ligação e cole o token de novo.',
  no_repositories: 'O token não vê nenhum repositório desta organização. Confirme o nome e os repositórios a que o token dá acesso.',
  invalid_scope: 'A organização ou os repositórios indicados não são válidos. Reveja a ligação.',
  unsupported_host: 'Esta plataforma ainda não é suportada.',
  invalid_result: 'Recebemos uma resposta inesperada. Tente recolher de novo.',
  transient_error: 'A plataforma não respondeu a tempo. Tente recolher de novo dentro de alguns minutos.',
  publish_failed: 'Não foi possível iniciar a recolha. Tente de novo dentro de alguns minutos.',
}

export function errorText(code: string | null | undefined): string {
  return (code && ERROR_TEXT[code]) || 'Algo correu mal na recolha. Tente de novo; se continuar, contacte o suporte.'
}

/** Details from the worker, as short sentences. Unknown keys are ignored. */
export function detailText(evidence: Evidence): string | null {
  const d = evidence.details as Record<string, boolean | number | string | undefined>
  switch (evidence.check) {
    case 'branch_protection':
      if (d.developersCanPush === true) return 'Quem desenvolve ainda pode enviar alterações diretamente'
      if (d.forcePush === true) return 'O histórico do ramo pode ser reescrito'
      return null
    case 'required_reviews':
      return typeof d.requiredApprovals === 'number' && d.requiredApprovals > 0
        ? `${d.requiredApprovals} ${d.requiredApprovals === 1 ? 'aprovação exigida' : 'aprovações exigidas'}`
        : null
    case 'dependency_alerts':
      if (d.enabled === false) return 'Alertas desligados'
      if (typeof d.openFindings === 'number' && d.openFindings > 0) return `${d.openFindings} vulnerabilidades críticas ou altas por resolver`
      return typeof d.openCriticalOrHigh === 'number' && d.openCriticalOrHigh > 0
        ? `${d.openCriticalOrHigh}${d.capped ? '+' : ''} alertas críticos ou altos por resolver`
        : null
    case 'secret_scanning':
      if (d.enabled === false) return 'Deteção desligada'
      if (typeof d.openFindings === 'number' && d.openFindings > 0) return `${d.openFindings} segredos expostos por resolver`
      return typeof d.openAlerts === 'number' && d.openAlerts > 0
        ? `${d.openAlerts}${d.capped ? '+' : ''} segredos expostos por resolver`
        : null
    default:
      return null
  }
}

const DATE = new Intl.DateTimeFormat('pt-PT', { dateStyle: 'medium', timeStyle: 'short' })

export function formatDate(value: string | null | undefined): string {
  return value ? DATE.format(new Date(value)) : '—'
}
