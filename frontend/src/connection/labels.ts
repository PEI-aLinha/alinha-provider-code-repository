import type { Connection, Evidence } from './types'

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
    case 'required_reviews':
      return typeof d.requiredApprovals === 'number' && d.requiredApprovals > 0
        ? `${d.requiredApprovals} ${d.requiredApprovals === 1 ? 'aprovação exigida' : 'aprovações exigidas'}`
        : null
    case 'dependency_alerts':
      if (d.enabled === false) return 'Alertas desligados'
      return typeof d.openCriticalOrHigh === 'number' && d.openCriticalOrHigh > 0
        ? `${d.openCriticalOrHigh}${d.capped ? '+' : ''} alertas críticos ou altos por resolver`
        : null
    case 'secret_scanning':
      if (d.enabled === false) return 'Deteção desligada'
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
