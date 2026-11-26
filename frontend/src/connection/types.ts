import type { components } from '@alinha/api-client'

export type Connection = components['schemas']['CodeRepositoryConnection']
export type ConnectInput = components['schemas']['CodeRepositoryConnectInput']
export type Evidence = components['schemas']['CodeRepositoryEvidence']
export type Setup = components['schemas']['CodeRepositorySetup']
export type Assignment = components['schemas']['CodeRepositoryAssignment']
export type AssignInput = components['schemas']['CodeRepositoryAssignInput']
export type AppInstallStart = components['schemas']['CodeRepositoryAppInstallStart']
export type AppInstallInput = components['schemas']['CodeRepositoryAppInstallInput']
export type Host = Connection['host']

export const QUERY_KEY = ['code-repository'] as const
export const RESOURCE = 'code-repository'
