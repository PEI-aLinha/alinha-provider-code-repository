import { useEffect, useRef, useState } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import { errorMessage, rememberReturnPath, useApiMutation } from '@alinha/plugin-sdk'
import { Button } from '@alinha/ui'
import { leaveFor } from './leave'
import { QUERY_KEY, type AppInstallInput, type AppInstallStart, type Connection } from './types'

/** Sends the person to GitHub to install the aLinha App; GitHub brings them back here through the shell's /resume. */
export function InstallAppButton() {
  const { pathname } = useLocation()
  const start = useApiMutation<void, AppInstallStart>({
    path: '/code-repository/github-app/installations',
    onSuccess: ({ installUrl }) => {
      rememberReturnPath(pathname)
      leaveFor(installUrl)
    },
  })
  return (
    <div className="flex flex-col gap-2">
      <div>
        <Button onClick={() => start.mutate()} disabled={start.isPending || start.isSuccess}>
          {start.isPending || start.isSuccess ? 'A abrir o GitHub…' : 'Instalar a aplicação do GitHub'}
        </Button>
      </div>
      {start.isError && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{errorMessage(start.error)}</p>}
    </div>
  )
}

const ID = /^[1-9][0-9]{0,19}$/

/**
 * Back from GitHub (`?installation_id=…&state=…&code=…`): finishes the connection once, then clears the address so a
 * reload does not send the used state again. `setup_action=request`: an organisation owner still has to approve it.
 */
export function useAppInstallReturn() {
  const [params, setParams] = useSearchParams()
  const sent = useRef(false)
  // Kept after the address is cleared.
  const [requested, setRequested] = useState(false)
  const complete = useApiMutation<AppInstallInput, Connection>({
    method: 'PUT',
    path: '/code-repository/github-app/installation',
    invalidates: [[...QUERY_KEY]],
  })
  const installationId = params.get('installation_id') ?? ''
  const state = params.get('state')
  const code = params.get('code')
  const approvalRequested = params.get('setup_action') === 'request'
  const returning = ID.test(installationId) && !!state && !!code

  useEffect(() => {
    if (sent.current || (!returning && !approvalRequested)) return
    sent.current = true
    if (returning) complete.mutate({ installationId: Number(installationId), state, code })
    else setRequested(true)
    setParams({}, { replace: true })
  }, [returning, approvalRequested, installationId, state, code, complete, setParams])

  return { completing: complete.isPending, error: complete.isError ? complete.error : null, requested }
}
