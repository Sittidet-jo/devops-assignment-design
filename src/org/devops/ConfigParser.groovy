// src/org/devops/ConfigParser.groovy

package org.devops

class ConfigParser implements Serializable {

    static def toSerializable(obj) {
        if (obj instanceof Map)  return obj.collectEntries { k, v -> [(k): toSerializable(v)] } as HashMap
        if (obj instanceof List) return obj.collect { toSerializable(it) } as ArrayList
        return obj
    }

    static Map resolveRuntimeConfig(Map cfg, Map params) {
        def param = { String name ->
            def v = params?.get(name)?.toString()?.trim() ?: ''
            return (v == 'Default') ? '' : v
        }

        // ── Deploy Environment ──────────────────────────────────────────────
        def paramEnv = param('deployENV')
        cfg.deployEnv = (paramEnv ?: cfg.imageEnv?.toString() ?: 'dev').toLowerCase()

        // ── Quality & Build Flags ───────────────────────────────────────────
        cfg.forceRebuild = resolveYesNo(param('forceRebuild'),     cfg, 'forceRebuild',     'No')

        // ── Registry & Strategy ─────────────────────────────────────────────
        cfg.deployRegistry = resolveString(param('deployRegistry'), cfg, 'deployRegistry', 'gitlab').toLowerCase()
        cfg.deployStrategy = resolveString(param('deployStrategy'), cfg, 'deployStrategy', 'default').toLowerCase()

        // ── Scaling strategy ────────────────────────────────────────────────
        def defaultScaling  = ['prd', 'prod'].contains(cfg.deployEnv) ? 'hpa' : 'vpa'
        cfg.scalingStrategy = resolveString(param('scalingStrategy'), cfg, 'scalingStrategy', '')
        if (!cfg.scalingStrategy) {
            cfg.scalingStrategy = resolveString(param('autoScaling'), cfg, 'autoScaling', defaultScaling)
        }
        cfg.scalingStrategy = cfg.scalingStrategy.toLowerCase()

        // ── Sync Policy (ArgoCD) ────────────────────────────────────────────
        def syncParam = param('autoSync')
        if (syncParam && syncParam != '') {
            cfg.autoSync = normalizeYesNo(syncParam)
        } else {
            def isAutoScaling = ['hpa', 'vpa', 'keda'].contains(cfg.scalingStrategy)
            cfg.autoSync = isAutoScaling ? 'Yes' : (normalizeYesNo(cfg.autoSync) ?: 'No')
        }

        // ── Additional Flags ────────────────────────────────────────────────
        cfg.kubeHealthCheck = resolveYesNo(param('kubeHealthCheck'), cfg, 'kubeHealthCheck', 'No')
        cfg.analysis        = resolveYesNo(param('analysis'),        cfg, 'analysis',        'No')
        cfg.dastScan        = resolveYesNo(param('dastScan'),        cfg, 'dastScan',        'No')
        cfg.enableLogPvc    = resolveYesNo(param('enableLogPvc'),    cfg, 'enableLogPvc',    'No')
        if (cfg.deployment?.logPvc != null) {
            cfg.deployment.logPvc.enabled = (cfg.enableLogPvc == 'Yes')
        }

        // ── Pipeline Mode ───────────────────────────────────────────────────
        def modeParam    = param('pipelineMode')
        def resolvedMode = modeParam ?: cfg?.ci?.pipelineMode?.toString() ?: 'build-deploy-all'
        cfg.pipelineMode = resolvedMode.toLowerCase()

        // ── Pipeline Flags ──────────────────────────────────────────────────
        //
        // mode                       | build | deploy api | deploy cronjob
        // ---------------------------|-------|------------|---------------
        // full                       |  ✅   |     ✅     |      ✅
        // build-only                 |  ✅   |     ❌     |      ❌
        // build-deploy-all           |  ✅   |     ✅     |      ✅
        // build-deploy-app           |  ✅   |     ✅     |      ❌
        // build-deploy-cronjob       |  ✅   |     ❌     |      ✅
        // deploy-all                 |  ❌   |     ✅     |      ✅
        // deploy-app                 |  ❌   |     ✅     |      ❌
        // deploy-cronjob             |  ❌   |     ❌     |      ✅

        def BUILD_MODES = [
            'full',
            'build-only',
            'build-deploy-all',
            'build-deploy-app',
            'build-deploy-cronjob'
        ]
        def DEPLOY_API_MODES = [
            'full',
            'build-deploy-all',
            'build-deploy-app',
            'deploy-all',
            'deploy-app'
        ]
        def DEPLOY_CRONJOB_MODES = [
            'full',
            'build-deploy-all',
            'build-deploy-cronjob',
            'deploy-all',
            'deploy-cronjob'
        ]

        cfg.doBuild    = cfg.pipelineMode in BUILD_MODES
        cfg.doDeploy   = cfg.pipelineMode in DEPLOY_API_MODES
        cfg.doCronOnly = (cfg.pipelineMode in DEPLOY_CRONJOB_MODES) && !(cfg.pipelineMode in DEPLOY_API_MODES)

        // deployCronJobs — ใช้ใน BaseGenerator / OverlayUpdater
        cfg.deployCronJobs = (cfg.pipelineMode in DEPLOY_CRONJOB_MODES) ? 'Yes' : 'No'

        // ── Mode-specific overrides ─────────────────────────────────────────
        if (!cfg.doBuild) cfg.forceRebuild = 'No'

        return cfg
    }

    static Map rolloutSpecFromConfig(Map cfg) {
        def ro = cfg?.rollout ?: [:]
        def enabled = ro.enabled in [true, 'true', 'True', 'YES', 'Yes']
        if (!enabled && cfg.deployStrategy != 'canary') return [:]

        boolean autoPromote = ro.autoPromotionEnabled in [true, 'true', 'True', 'YES', 'Yes']

        return [
            strategy: [
                canary: [
                    autoPromotionEnabled: autoPromote,
                    steps: normalizeRolloutSteps(ro.steps)
                ]
            ]
        ]
    }

    private static String resolveYesNo(String paramVal, Map cfg, String key, String defaultVal) {
        return normalizeYesNo(paramVal) \
            ?: normalizeYesNo(cfg?.ci?."${key}"?.toString()) \
            ?: normalizeYesNo(cfg?."${key}"?.toString()) \
            ?: defaultVal
    }

    private static String resolveString(String paramVal, Map cfg, String key, String defaultVal) {
        def p = (paramVal && paramVal != 'Default') ? paramVal : null
        return p ?: cfg?.ci?."${key}"?.toString() ?: cfg?."${key}"?.toString() ?: defaultVal
    }

    private static String normalizeYesNo(def val) {
        def v = val?.toString()?.trim()
        if (!v || v == 'Default') return null
        if (v.equalsIgnoreCase('yes') || v.equalsIgnoreCase('true')) return 'Yes'
        if (v.equalsIgnoreCase('no') || v.equalsIgnoreCase('false'))  return 'No'
        return null
    }

    private static final List<Map> DEFAULT_ROLLOUT_STEPS = [
        [setWeight: 20], [pause: [:]], [setWeight: 50], [pause: [duration: '10m']], [setWeight: 100]
    ]

    private static List<Map> normalizeRolloutSteps(def raw) {
        if (!(raw instanceof List)) return DEFAULT_ROLLOUT_STEPS

        List<Map> steps = []
        raw.each { s ->
            if (!(s instanceof Map)) return
            if (s.containsKey('setWeight')) {
                steps << [setWeight: s.setWeight as Integer]
            } else if (s.containsKey('pause')) {
                steps << [pause: s.pause]
            }
        }
        return steps ?: DEFAULT_ROLLOUT_STEPS
    }
}
