package org.devops.kustomize

class Validation implements Serializable {

  def steps
  Validation(steps=null) { this.steps = steps }

  void applyDefaults(Map config) {
    must(config, 'manifestRepoUrl')
    must(config, 'deployEnv')
    must(config, 'manifestBranch')
    must(config, 'credentialsId')
    must(config, 'projectName')
    must(config, 'deployEnv')
    must(config, 'imageToSet')
    must(config, 'imageTag')
    must(config, 'deployRegistry')

    config.imageRegistry      = config.imageRegistry      ?: (steps?.env?.IMAGE_REGISTRY ?: 'registry.local:5000')
    config.gitlabRegistryHost = config.gitlabRegistryHost ?: (steps?.env?.GITLAB_REGISTRY_HOST ?: 'gitlab.local:5050')
    config.gitlabImagePath    = config.gitlabImagePath    ?: "${config.projectName}"
    config.deployName         = config.deployName         ?: "${config.projectName}-deployment"
    config.serviceName        = config.serviceName        ?: "${config.projectName}-service"
    config.namespace          = config.namespace          ?: 'default'
    
    def envName = config.deployEnv.toString().toLowerCase()
    boolean isPrd = (envName.contains('prd') || envName.contains('prod'))

    config.hpa  = config.hpa  ?: [:]
    config.vpa  = config.vpa  ?: [:]
    config.keda = config.keda ?: [:]

    String strategy = config.scalingStrategy ?: config.ci?.autoScaling ?: config.autoScaling
    if (!strategy) {
        strategy = isPrd ? 'hpa' : 'vpa'
    }
    strategy = strategy.toLowerCase()
    
    steps.echo "[INFO] AutoScaling Strategy: ${strategy.toUpperCase()}"

    config.keda = config.keda ?: [:]
    config.hpa  = config.hpa  ?: [:]
    config.vpa  = config.vpa  ?: [:]

    config.keda.enabled = (strategy == 'keda')
    config.hpa.enabled  = (strategy == 'hpa')
    config.vpa.enabled  = (strategy == 'vpa')

    if (config.keda.enabled) {
       config.keda.minReplicas = (config.keda.minReplicas ?: config.hpa.minReplicas ?: 2) as int
       config.keda.maxReplicas = (config.keda.maxReplicas ?: config.hpa.maxReplicas ?: 10) as int
       config.replicas = config.keda.minReplicas

       if (!config.keda.triggers || config.keda.triggers.isEmpty()) {
           config.keda.triggers = [
               [ type: 'cpu', metricType: 'Utilization', metadata: [ value: '80' ] ]
           ]
       } else {
           config.keda.triggers.each { t ->
               if (t.type == 'cpu' || t.type == 'memory') {
                   if (t.metadata == null) t.metadata = [:]
                   
                   // Move top-level metricType to metadata if exists
                   if (t.metadata.containsKey('metricType')) {
                       t['metricType'] = t.metadata.remove('metricType')
                   }

                   // Migration for KEDA 2.18+ (type -> metricType inside metadata)
                   if (t.metadata.containsKey('type')) {
                       def val = t.metadata.remove('type')
                       if (!t.containsKey('metricType')) {
                           t['metricType'] = val
                       }
                   }

                   if (!t.containsKey('metricType') || t['metricType'] == null) {
                       t['metricType'] = 'Utilization'
                   }
               }
           }
       }
    }

    if (config.hpa.enabled) {
       config.hpa.minReplicas = (config.hpa.minReplicas ?: config.minReplicas ?: 2) as int
       config.hpa.maxReplicas = (config.hpa.maxReplicas ?: config.maxReplicas ?: 5) as int
       config.hpa.cpuPercent  = (config.hpa.cpuPercent  ?: config.cpuPercent  ?: 80) as int
       config.replicas = config.hpa.minReplicas
    }

    if (config.vpa.enabled) {
        if (!config.vpa.containsKey('mode')) {
            config.vpa.mode = 'Auto'
        }
    } else {
        config.vpa.mode = 'Off'
    }

    if (!config.keda.enabled && !config.hpa.enabled) {
        config.replicas = (config.replicas ?: 3) as int
    }

    config.containerPort      = (config.containerPort ?: 3000) as int
    config.targetPort         = (config.targetPort ?: config.containerPort) as int
    config.nodePort           = (config.nodePort ?: 0) as int
    config.imagePullSecret    = config.imagePullSecret ?: 'imagePullSecretName'

    config.projectType        = config.projectType ?: 'frontend'
    config.envType            = config.envType ?: (config.projectType == 'backend' ? 'configMap' : 'none')
    config.volumeMountPath    = config.volumeMountPath ?: ''
    config.volumeSubPath      = config.volumeSubPath   ?: ''
    config.volumeSecretName   = config.volumeSecretName ?: ''
    config.configMapName      = (config.configMapName ?: '').trim()

    config.rolloutSpec        = (config.rolloutSpec ?: [:])

    def smoke = (config.rollout?.smoke ?: [:])
    config.__smoke__ = [
      base     : smoke.baseUrl ?: "http://${config.serviceName}-${config.deployEnv}.${config.namespace}.svc.cluster.local:${config.targetPort ?: config.containerPort}",
      paths    : (smoke.paths ?: ['/', '/health']),
      timeout  : (smoke.timeout ?: '10').toString(),
      count    : (smoke.count ?: 3).toString(),
      interval : (smoke.interval ?: '10s').toString(),
      failure  : (smoke.failureLimit ?: 1).toString(),
    ]
  }

  boolean isRolloutEnabled(Map config) {
    return (
      config.rolloutEnabled in [true, 'true', 'True', 'YES', 'Yes'] ||
      config.useRollout in ['Yes', 'YES', true] ||
      (config.rollout?.enabled in [true, 'true', 'True', 'YES', 'Yes'])
    )
  }

  private static void must(Map cfg, String key) {
    if (!cfg.containsKey(key) || cfg[key] == null || "${cfg[key]}".trim() == '') {
      throw new RuntimeException("${key} is required")
    }
  }
}