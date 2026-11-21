import { GitBranch } from 'lucide-react'
import { EmptyState, PageHeader } from '@alinha/ui'

export function CodeRepositoryPage() {
  return (
    <>
      <PageHeader title="Repositório de código" subtitle="Ligação aos repositórios onde a organização guarda o seu código." />
      <EmptyState icon={<GitBranch className="size-5" aria-hidden />} title="Em construção">
        Aqui vai poder ligar os repositórios de código da sua organização, só para leitura, para recolhermos provas de boas práticas de segurança.
      </EmptyState>
    </>
  )
}
