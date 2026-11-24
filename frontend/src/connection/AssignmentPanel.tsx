import { useState, type FormEvent } from 'react'
import { errorMessage, fieldErrors, useApiMutation } from '@alinha/plugin-sdk'
import { Button, Card, Eyebrow, Field, TextInput } from '@alinha/ui'
import { formatDate } from './labels'
import { QUERY_KEY, type AssignInput, type Assignment } from './types'

const EMAIL = /^[^@\s]{1,64}@[^@\s]+\.[^@\s]+$/

/**
 * Who was asked to connect the repository. The responsible person (`code-repository:assign`) can ask someone by
 * email, change it or withdraw it; everyone else only sees it.
 */
export function AssignmentPanel({ assignment, canAssign }: { assignment: Assignment | null | undefined; canAssign: boolean }) {
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | undefined>()
  const invalidates = [[...QUERY_KEY]]
  const assign = useApiMutation<AssignInput, Assignment>({
    method: 'PUT', path: '/code-repository/assignment', invalidates,
    onSuccess: () => setEmail(''),
    onError: (e) => setError(fieldErrors(e).email),
  })
  const withdraw = useApiMutation<void, void>({ method: 'DELETE', path: '/code-repository/assignment', invalidates })
  if (!assignment && !canAssign) return null

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const value = email.trim()
    if (!EMAIL.test(value)) {
      setError('Indique um endereço de email válido.')
      return
    }
    setError(undefined)
    assign.mutate({ email: value })
  }
  const problem = (assign.isError && !fieldErrors(assign.error).email) ? errorMessage(assign.error)
    : withdraw.isError ? errorMessage(withdraw.error) : null

  return (
    <Card className="flex max-w-[640px] flex-col gap-4 p-6">
      <Eyebrow as="h2">Pedir a outra pessoa</Eyebrow>
      {assignment ? (
        <p className="text-ink">
          Atribuído a <strong>{assignment.email}</strong> em {formatDate(assignment.assignedAt)}. Enviámos-lhe um email
          com o pedido.
        </p>
      ) : (
        <p className="text-[0.875rem] text-ink-soft">
          Se for outra pessoa a tratar do código (por exemplo, quem gere os sistemas), enviamos-lhe um email a pedir que
          ligue o repositório. Precisa de ter acesso à organização na aLinha.
        </p>
      )}
      {canAssign && (
        <form onSubmit={submit} noValidate className="flex flex-col gap-3" aria-label="Pedir a outra pessoa">
          <Field label={assignment ? 'Pedir a outra pessoa' : 'Email da pessoa'} error={error}>
            {({ id, describedBy, invalid }) => (
              <TextInput id={id} type="email" aria-describedby={describedBy} invalid={invalid} value={email}
                autoComplete="email" onChange={(e) => setEmail(e.target.value)} />
            )}
          </Field>
          {problem && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{problem}</p>}
          <div className="flex flex-wrap gap-3">
            <Button type="submit" variant="secondary" disabled={assign.isPending}>
              {assign.isPending ? 'A enviar…' : 'Enviar pedido'}
            </Button>
            {assignment && (
              <Button type="button" variant="ghost" onClick={() => withdraw.mutate()} disabled={withdraw.isPending}>
                Retirar pedido
              </Button>
            )}
          </div>
        </form>
      )}
    </Card>
  )
}
