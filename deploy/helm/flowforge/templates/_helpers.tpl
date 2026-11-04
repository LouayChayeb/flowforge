{{- define "flowforge.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "flowforge.labels" -}}
app.kubernetes.io/part-of: flowforge
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Selector labels for one component: include "flowforge.selector" (list . "orchestrator") */}}
{{- define "flowforge.selector" -}}
{{- $root := index . 0 -}}
app.kubernetes.io/name: {{ index . 1 }}
app.kubernetes.io/instance: {{ $root.Release.Name }}
{{- end -}}

{{- define "flowforge.image" -}}
{{- $root := index . 0 -}}
{{- $name := index . 1 -}}
{{- if $root.Values.image.registry -}}
{{- printf "%s/%s:%s" $root.Values.image.registry $name $root.Values.image.tag -}}
{{- else -}}
{{- printf "%s:%s" $name $root.Values.image.tag -}}
{{- end -}}
{{- end -}}

{{- define "flowforge.kafkaBootstrap" -}}
{{- if .Values.kafka.enabled -}}
{{- printf "%s-kafka:9092" (include "flowforge.fullname" .) -}}
{{- else -}}
{{- required "kafka.externalBootstrap is required when kafka.enabled is false" .Values.kafka.externalBootstrap -}}
{{- end -}}
{{- end -}}

{{- define "flowforge.dbUrl" -}}
{{- if .Values.postgresql.enabled -}}
{{- printf "jdbc:postgresql://%s-postgresql:5432/%s" (include "flowforge.fullname" .) .Values.postgresql.database -}}
{{- else -}}
{{- required "postgresql.external.url is required when postgresql.enabled is false" .Values.postgresql.external.url -}}
{{- end -}}
{{- end -}}

{{- define "flowforge.dbSecret" -}}
{{- default (printf "%s-db" (include "flowforge.fullname" .)) .Values.postgresql.existingSecret -}}
{{- end -}}
