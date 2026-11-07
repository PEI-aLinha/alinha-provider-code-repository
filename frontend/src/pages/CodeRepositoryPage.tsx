import { useQuery } from '@tanstack/react-query'
import { useHost } from '@alinha/plugin-sdk'
import { PageHeader } from '@alinha/ui'

export function CodeRepositoryPage() {
  const { api } = useHost()
  const ping = useQuery({
    queryKey: ['code-repository', 'ping'],
    queryFn: () => api<{ plugin: string; status: string }>('/code-repository/ping'),
  })

  return (
    <>
      <PageHeader title="Repositório de código" />
      <p>Backend: {ping.isPending ? 'a verificar…' : ping.isError ? 'indisponível' : ping.data.status}</p>
    </>
  )
}
