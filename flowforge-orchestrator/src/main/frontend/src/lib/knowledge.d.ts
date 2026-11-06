// Types for knowledge.js: the dashboard's explanations, written as small HTML snippets.

export interface ComponentInfo {
  title: string
  role: string
  how: string[]
  code: [string, string][]
  failure: string
}

export interface CodeMapPackage {
  module: string
  pkg: string
  text: string
  classes: [name: string, kind: string, does: string, methods: [string, string][]][]
}

export const COMPONENTS: Record<string, ComponentInfo>
export const RUN_STATES: Record<string, string>
export const STEP_STATES: Record<string, string>
export const ATTEMPT_STATES: Record<string, string>
export const TIMELINE_WHY: Record<string, string>
export const JOURNEY: [string, string][]
export const FAILURES: [string, string, string][]
export const GUARANTEES: { yes: string[]; no: string[] }
export const GLOSSARY: [string, string][]
export const CODE_MAP: CodeMapPackage[]
