import { CircleCheck, CircleHelp, CircleX } from 'lucide-react'
import { Badge, Card, Eyebrow } from '@alinha/ui'
import { CHECK_LABEL, CHECK_WHY, detailText, OUTCOME_LABEL, STRENGTH_LABEL } from './labels'
import type { Evidence } from './types'

const ICON = {
  PASS: <CircleCheck className="size-3.5" aria-hidden />,
  FAIL: <CircleX className="size-3.5" aria-hidden />,
  UNKNOWN: <CircleHelp className="size-3.5" aria-hidden />,
} as const

const TONE = { PASS: 'signal', FAIL: 'dark', UNKNOWN: 'outline' } as const

/** The last collection, one card per repository. The badge text carries the meaning, not its colour. */
export function EvidenceList({ evidence }: { evidence: Evidence[] }) {
  const byRepository = new Map<string, Evidence[]>()
  for (const item of evidence) byRepository.set(item.repository, [...(byRepository.get(item.repository) ?? []), item])
  return (
    <div className="flex flex-col gap-4">
      {[...byRepository].map(([repository, items]) => (
        <Card key={repository} className="p-5">
          <Eyebrow as="h3">{repository}</Eyebrow>
          <ul className="mt-3 divide-y divide-line-soft">
            {items.map((item) => {
              const detail = detailText(item)
              return (
                <li key={item.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
                  <div className="min-w-0 flex-1">
                    <p className="font-semibold text-ink">{CHECK_LABEL[item.check]}</p>
                    <p className="text-[0.8125rem] text-muted">{CHECK_WHY[item.check]}</p>
                    {detail && <p className="mt-1 text-[0.8125rem] text-ink-soft">{detail}</p>}
                    {item.outcome === 'UNKNOWN' && (
                      <p className="mt-1 text-[0.8125rem] text-ink-soft">
                        {item.details.reason === 'not_available'
                          ? 'Não está disponível no plano da plataforma, ou o acesso não permite vê-la.'
                          : 'O acesso dado não tem permissão para ver esta definição.'}
                      </p>
                    )}
                    {item.outcome !== 'UNKNOWN' && STRENGTH_LABEL[item.strength] && (
                      <p className="mt-1 text-[0.75rem] text-muted">{STRENGTH_LABEL[item.strength]}</p>
                    )}
                  </div>
                  <Badge tone={TONE[item.outcome]} icon={ICON[item.outcome]}>{OUTCOME_LABEL[item.outcome]}</Badge>
                </li>
              )
            })}
          </ul>
        </Card>
      ))}
    </div>
  )
}
