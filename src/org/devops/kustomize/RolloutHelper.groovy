package org.devops.kustomize

class RolloutHelper implements Serializable {

  static String indentBlock(String s, int n) {
    def pad = ' ' * n
    return (s ?: '').split('\n').collect { it ? pad + it : pad }.join('\n')
  }

  private static List<String> renderAnalysisStep(def analysisConfig, String indentLevel, String subIndent, Map defaultArgs = [:]) {
    List<String> lines = []
    lines << "${indentLevel}- analysis:"
    lines << "${indentLevel}${subIndent}templates:"

    if (analysisConfig instanceof String || analysisConfig instanceof GString) {
        lines << "${indentLevel}${subIndent}  - templateName: ${analysisConfig}"
    }
    else if (analysisConfig instanceof List) {
        analysisConfig.each { tName ->
            if (tName instanceof String || tName instanceof GString) {
                lines << "${indentLevel}${subIndent}  - templateName: ${tName}"
            } 
            else if (tName instanceof Map) {
                lines << "${indentLevel}${subIndent}  - templateName: ${tName.templateName}"
            }
        }
    } 
    else if (analysisConfig instanceof Map) {
        def templates = analysisConfig.templates ?: []
        templates.each { t ->
            lines << "${indentLevel}${subIndent}  - templateName: ${t.templateName}"
        }
    }

    def finalArgs = [:]

    if (defaultArgs) {
        finalArgs.putAll(defaultArgs)
    }

    if (analysisConfig instanceof Map && analysisConfig.args) {
        analysisConfig.args.each { arg ->
            finalArgs[arg.name] = arg.value
        }
    }

    if (finalArgs) {
        lines << "${indentLevel}${subIndent}args:"
        finalArgs.each { name, value ->
            def valStr = value.toString()
            if (valStr.contains('\n')) {
                lines << "${indentLevel}${subIndent}  - name: ${name}"
                lines << "${indentLevel}${subIndent}    value: |"
                valStr.split(/\r?\n/).each { l -> 
                    lines << "${indentLevel}${subIndent}      ${l}"
                }
            } else {
                lines << "${indentLevel}${subIndent}  - name: ${name}"
                lines << "${indentLevel}${subIndent}    value: \"${valStr}\""
            }
        }
    }

    return lines
  }

  static String stepsBlock(Map config) {
    List<Map> steps = (config.rollout?.steps ?: [[setWeight: 50], [pause: [:]], [setWeight: 100]]) as List<Map>
    List<String> out = []

    def analysisVal  = config.analysis ?: config.ci?.analysis ?: 'No'
    boolean wantAnalysis = (analysisVal != null) && analysisVal.toString().equalsIgnoreCase('Yes')

    def smokeConfig = (config.rollout?.smoke ?: config.smoke) ?: [:]
    def autoArgs = [
        "url":   smokeConfig.baseUrl ?: "",
        "paths": smokeConfig.paths   ?: "/"
    ]

    steps.each { Map s ->
      if (s.setWeight != null) {
        out << "        - setWeight: ${s.setWeight}"
      } else if (s.pause != null) {
        def dur = s.pause.duration
        out << (dur ? "        - pause: { duration: ${dur} }" : "        - pause: {}")
      } else if (s.containsKey('analysis')) {
        if (wantAnalysis && s.analysis) {
          if (s.analysis instanceof String || s.analysis instanceof GString ||
              s.analysis instanceof List   ||
              (s.analysis instanceof Map && !s.analysis.isEmpty())) {
            out.addAll(renderAnalysisStep(s.analysis, "        ", "    ", autoArgs))
          }
        }
      }
    }
    return out.join('\n')
  }

  static String kedaTriggersBlock(Map config) {
    List<Map> triggers = config.keda?.triggers ?: []
    List<String> out = []
    triggers.each { t ->
      out << "  - type: ${t.type}"
      
      if (t.containsKey('metricType') && t.metricType) {
         out << "    metricType: ${t.metricType}"
      }

      if (t.metadata && !t.metadata.isEmpty()) {
        out << "    metadata:"
        t.metadata.each { k, v ->
          out << "      ${k}: \"${v.toString().replace('"', '\\"')}\""
        }
      }
    }
    return out.join('\n')
  }

  private static String normalizePaths(def paths) {
    List<String> lines
    if (paths instanceof List) {
      lines = paths
    } else if (paths instanceof CharSequence) {
      lines = paths.toString().split(/\r?\n/).toList()
    } else {
      lines = ['/']
    }
    
    return lines.collect { 
        it.trim().replaceAll(/^"|"$/, '').replaceAll(/^'|'$/, '') 
    }.findAll { it }.join('\n')
  }

  private static String analysisHistoryBlock(Map config) {
    def succ = config.rollout?.historyLimit?.successfulRunHistoryLimit ?: 1
    def fail = config.rollout?.historyLimit?.unsuccessfulRunHistoryLimit ?: 1
    def raw = """\
analysis:
  successfulRunHistoryLimit: ${succ}
  unsuccessfulRunHistoryLimit: ${fail}
"""
    return indentBlock(raw, 2)
  }

  static String canaryPatch(Map config) {
    def i6 = '      '
    def i8 = '        '
    List<Map> steps = (config.rollout?.steps ?: config.rolloutSpec?.strategy?.canary?.steps ?:
                      [[setWeight:50], [pause:[:]], [setWeight:100]])

    def analysisVal = config.analysis ?: config.ci?.analysis ?: 'No'
    boolean wantAnalysis = analysisVal.toString().equalsIgnoreCase('Yes')

    def hist = wantAnalysis ? analysisHistoryBlock(config) : ""
    def stableSvc = (config.rollout?.serviceRefs?.stableService ?: '').toString()
    def canarySvc = (config.rollout?.serviceRefs?.canaryService ?: '').toString()

    def svcPort = (config.targetPort ?: config.containerPort)
    def smokeConfig = (config.rollout?.smoke ?: config.smoke) ?: [:]
    
    String defaultBaseUrl = (smokeConfig.baseUrl != null) ? smokeConfig.baseUrl.toString()
      : ('http://' + canarySvc + ':' + String.valueOf(svcPort))

    String defaultPaths = normalizePaths(smokeConfig.paths)

    def autoArgs = [
        "url": defaultBaseUrl,
        "paths": defaultPaths,
        "configMapName": "${config.projectName}-smoke-test-config-${config.deployEnv}"
    ]

    if (smokeConfig.secretName) autoArgs["secretName"] = smokeConfig.secretName.toString()
    if (smokeConfig.timeout) autoArgs["timeout"] = smokeConfig.timeout.toString()
    if (smokeConfig.count)   autoArgs["count"]   = smokeConfig.count.toString()
    
    def intervalVal = smokeConfig.interval ?: smokeConfig.smoke_interval
    if (intervalVal) autoArgs["smoke_interval"] = intervalVal.toString()
    
    if (smokeConfig.failureLimit) autoArgs["failure"] = smokeConfig.failureLimit.toString()
    else if (smokeConfig.failure) autoArgs["failure"] = smokeConfig.failure.toString()

    List<String> out = []
    out << "${i6}stableService: ${stableSvc}"
    out << "${i6}canaryService: ${canarySvc}"
    out << "${i6}steps:"

    steps.each { s ->
      if (s.setWeight != null) {
        out << "${i8}- setWeight: ${s.setWeight}"

      } else if (s.pause != null) {
        def dur = s.pause.duration
        out << (dur ? "${i8}- pause: { duration: ${dur} }" : "${i8}- pause: {}")

      } else if (s.containsKey('analysis')) {
        if (wantAnalysis) {
          if (s.analysis) {
              if (s.analysis instanceof String || s.analysis instanceof GString || s.analysis instanceof List || (s.analysis instanceof Map && !s.analysis.isEmpty())) {
                  out.addAll(renderAnalysisStep(s.analysis, i8, '    ', autoArgs))
              }
          }
        }
      }
    }

    return """\
---
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata:
  name: ${config.deployName}
spec:
${hist}
  strategy:
    canary:
${out.join('\n')}
"""
  }

  static String canaryStepsOnly(Map config) {
     return canaryPatch(config)
  }
}