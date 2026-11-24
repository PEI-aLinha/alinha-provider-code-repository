import type { components } from '@alinha/api-client'

export type Connection = components['schemas']['CodeRepositoryConnection']
export type ConnectInput = components['schemas']['CodeRepositoryConnectInput']
export type Evidence = components['schemas']['CodeRepositoryEvidence']

export const QUERY_KEY = ['code-repository'] as const
export const RESOURCE = 'code-repository'
