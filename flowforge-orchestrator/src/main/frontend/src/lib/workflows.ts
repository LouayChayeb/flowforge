import { ApiError, api, post } from './api'
import type { Input, RunStatus } from './types'

export interface StepDefinition {
  name: string
  type: string
  maxAttempts?: number
  compensateWith?: string
  dependsOn?: string[]
  waitFor?: string
}

export interface WorkflowDefinition {
  name: string
  steps: StepDefinition[]
}

export const WORKFLOW = 'order-processing'

/** Same as docs/examples/order-processing.json: reserve-inventory and check-fraud run in parallel. */
export const ORDER_PROCESSING: WorkflowDefinition = {
  name: WORKFLOW,
  steps: [
    { name: 'validate-order', type: 'VALIDATE_ORDER', maxAttempts: 1 },
    { name: 'reserve-inventory', type: 'RESERVE_INVENTORY', compensateWith: 'RELEASE_INVENTORY', dependsOn: ['validate-order'] },
    { name: 'check-fraud', type: 'CHECK_FRAUD', dependsOn: ['validate-order'] },
    { name: 'charge-payment', type: 'CHARGE_PAYMENT', compensateWith: 'REFUND_PAYMENT', maxAttempts: 5, dependsOn: ['reserve-inventory', 'check-fraud'] },
    { name: 'create-shipment', type: 'CREATE_SHIPMENT' },
    { name: 'send-confirmation', type: 'SEND_CONFIRMATION' },
  ],
}

/** The same order with a 20 s cooling-off period after the charge: a durable WAIT step, during which it can be cancelled. */
export const COOLING_OFF: WorkflowDefinition = {
  name: 'order-with-cooling-off',
  steps: [
    ...ORDER_PROCESSING.steps.slice(0, 4),
    { name: 'cooling-off', type: 'WAIT', waitFor: 'PT20S', dependsOn: ['charge-payment'] },
    { name: 'create-shipment', type: 'CREATE_SHIPMENT', dependsOn: ['cooling-off'] },
    { name: 'send-confirmation', type: 'SEND_CONFIRMATION' },
  ],
}

const DEFINITIONS = new Map([ORDER_PROCESSING, COOLING_OFF].map((d) => [d.name, d]))

export interface Scenario {
  id: string
  title: string
  expect: RunStatus
  text: string
  input: Input
  workflow?: string
}

export const SCENARIOS: Scenario[] = [
  { id: 'normal', title: 'Normal order', expect: 'COMPLETED',
    text: 'All six steps succeed, one attempt each; reserve-inventory and check-fraud run at the same time. The baseline.',
    input: {} },
  { id: 'fraud', title: 'Fraud detected', expect: 'COMPENSATED',
    text: 'check-fraud fails while its parallel sibling reserve-inventory succeeds: the engine waits for the sibling, then undoes it.',
    input: { fraud: true, fraudCheckDelayMs: 1500 } },
  { id: 'flaky', title: 'Flaky carrier', expect: 'COMPLETED',
    text: 'Shipping times out twice (retryable), waits 1 s then 2 s of backoff, and succeeds on attempt 3.',
    input: { shipmentFailures: 2 } },
  { id: 'down', title: 'Carrier down', expect: 'COMPENSATED',
    text: 'Shipping keeps timing out until its 3 attempts are used up. Then payment is refunded and inventory released.',
    input: { shipmentFailures: 99 } },
  { id: 'declined', title: 'Payment declined', expect: 'COMPENSATED',
    text: 'Amount above 10 000 is a permanent failure. Only the inventory reservation needs undoing.',
    input: { amount: 50000 } },
  { id: 'xx', title: 'Undeliverable address', expect: 'COMPENSATED',
    text: 'Country XX cannot be shipped to. Undo in reverse order: refund the payment, then release inventory.',
    input: { country: 'XX' } },
  { id: 'invalid', title: 'Invalid order', expect: 'FAILED',
    text: 'No customer id: the very first step fails permanently. Nothing succeeded, so nothing to undo.',
    input: { customerId: null } },
  { id: 'refund', title: 'Refund fails too', expect: 'COMPENSATION_FAILED',
    text: 'Shipping is refused and then the refund is rejected: the one case the engine cannot fix alone.',
    input: { country: 'XX', refundFails: true } },
  { id: 'cooling', title: 'Cooling-off order', expect: 'CANCELLED', workflow: COOLING_OFF.name,
    text: 'After the charge, a durable 20 s timer runs before shipping. Press Cancel run during the wait: the charge is refunded and the stock released.',
    input: {} },
  { id: 'slow', title: 'Slow shipment', expect: 'COMPLETED',
    text: 'Shipping takes 8 s. Watch the lease count down on the Engine tab — or kill the worker running it (see the Kafka tab).',
    input: { shipmentDelayMs: 8000 } },
]

export function orderInput(extra: Input): Input {
  const n = Math.floor(1000 + Math.random() * 9000)
  const input: Input = { orderId: `ORD-${n}`, customerId: `CUS-${n % 97}`, amount: 120, country: 'FR', ...extra }
  for (const key of Object.keys(input)) if (input[key] === null) delete input[key]
  return input
}

/** Which demo scenario an input (and workflow) corresponds to. */
export function scenarioOf(input: Input | null | undefined, workflow?: string): string {
  const i = input ?? {}
  const truthy = (v: unknown) => v === true || v === 'true'
  if (workflow === COOLING_OFF.name) return 'Cooling-off order'
  if (truthy(i.refundFails)) return 'Refund fails too'
  if (truthy(i.fraud)) return 'Fraud detected'
  if (Number(i.shipmentFailures) >= 3) return 'Carrier down'
  if (Number(i.shipmentFailures) > 0) return 'Flaky carrier'
  if (Number(i.shipmentDelayMs) > 0) return 'Slow shipment'
  if (i.country === 'XX') return 'Undeliverable address'
  if (Number(i.amount) > 10000) return 'Payment declined'
  if (!i.customerId || !i.orderId || !(Number(i.amount) > 0)) return 'Invalid order'
  return 'Normal order'
}

const registered = new Set<string>()

/**
 * Registers (a new version of) a workflow if it is missing or older than this page's definition.
 * Returns the new version number, or null if the stored one was already up to date.
 */
export async function ensureWorkflow(name: string): Promise<number | null> {
  const definition = DEFINITIONS.get(name)
  if (!definition || registered.has(name)) return null
  let latest: WorkflowDefinition | null = null
  try {
    latest = await api<WorkflowDefinition>(`/api/workflows/${name}`)
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 404)) throw e
  }
  const upToDate = latest && definition.steps.every((d) => latest.steps.some((s) => s.name === d.name && s.type === d.type))
  registered.add(name)
  if (upToDate) return null
  const created = await post<{ version: number }>('/api/workflows', definition)
  return created.version
}

export interface RunCreated {
  runId: string
  workflow: string
  version: number
  status: RunStatus
}

export async function startRun(input: Input, workflow = WORKFLOW) {
  const registeredVersion = await ensureWorkflow(workflow)
  const created = await post<RunCreated>(`/api/workflows/${workflow}/runs`, { input })
  return { created, registeredVersion }
}
