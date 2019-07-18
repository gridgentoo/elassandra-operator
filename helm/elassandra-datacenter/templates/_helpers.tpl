{{/* vim: set filetype=mustache: */}}
{{/*
Expand the name of the chart.
*/}}
{{- define "elassandra.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "elassandra.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "elassandra.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "elassandra.datacenterName" -}}
{{- (split "-" .Release.Name)._1 | required "Release name has to be clustername-dcname (in lowercase)" -}}
{{- end -}}

{{- define "elassandra.clusterName" -}}
{{- (split "-" .Release.Name)._0 -}}
{{- end -}}

{{- define "elassandra.resourceName" -}}
{{ printf "elassandra-%s-%s" (include "elassandra.clusterName" .) (include "elassandra.datacenterName" .) | trunc 63 | trimSuffix "-" }}
{{- end -}}

