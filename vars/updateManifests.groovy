// vars/updateManifests.groovy
def call(Map params) {
    def config     = params.config
    def dockerInfo = params.dockerInfo

    if (!(config.deployRegistry in ['gitlab', 'docker-registry'])) {
        error "deployRegistry is required (got: '${config.deployRegistry}')"
    }

    echo "📝 Updating Kubernetes Manifests for ${config.projectType}..."

    def imageToSet = (config.deployRegistry == 'gitlab') ? dockerInfo.gitlabRegistryImage : dockerInfo.internalRegistryImage
    def d = config.deployment ?: [:]

    def commonParams = [
        projectType   : config.projectType,
        imageToSet    : imageToSet,
        imageTag      : dockerInfo.imageTag,
        credentialsId : (config.credentials?.gitlab ?: 'GITLAB_CREDENTIALS'),
        manifestRepoUrl: "${config.gitlab.baseUrl}/${config.gitlab.manifestPath}",
        manifestBranch: "${config.projectName}-manifest",
        projectName   : config.projectName,
        deployEnv     : config.deployEnv,
        deployRegistry: config.deployRegistry,
        gitlabImagePath: config.gitlab?.sourcePath,

        // Base Deployment Config
        deployName    : d.deployName    ?: "${config.projectName}-deployment",
        serviceName   : d.serviceName   ?: "${config.projectName}-service",
        namespace     : d.namespace     ?: 'default',
        containerPort : (d.containerPort ?: 3000) as int,
        targetPort    : (d.targetPort   ?: (d.containerPort ?: 3000)) as int,
        nodePort      : d.nodePort,
        imagePullSecret: d.imagePullSecret,

        // Scaling & GitOps
        scalingStrategy: config.scalingStrategy,
        keda      : config.keda,
        ci        : config.ci,
        hpa       : config.hpa,
        vpa       : config.vpa,
        deployment: d,
        cronjobs  : config.cronjobs,

        // Pipeline mode flags — จำเป็นสำหรับ OverlayUpdater เลือก overlay path
        pipelineMode  : config.pipelineMode,
        doCronOnly    : config.doCronOnly,
        doDeploy      : config.doDeploy,
        deployCronJobs: config.deployCronJobs,
        analysis       : config.analysis,
        dastScan       : config.dastScan,
        kubeHealthCheck: config.kubeHealthCheck,
    ]

    if (config.projectType == 'backend') {
        commonParams.putAll([
            serviceType  : d.serviceType   ?: (d.nodePort ? 'NodePort' : 'ClusterIP'),
            configMapName: d.configMapName ?: 'backend-config',
            secretName   : d.secretName   ?: 'backend-secret',
            volumeMounts : d.volumeMounts  ?: [],
            extraEnvFrom : d.extraEnvFrom  ?: []
        ])
    }

    if (config.deployStrategy == 'canary') {
        commonParams.put('rolloutEnabled', true)
        commonParams.put('rollout',        config.rollout)
        commonParams.put('rolloutSpec',    config.rolloutSpec)

        if (config.projectType == 'frontend') {
            commonParams.put('replicas', (d.replicas ?: 3) as int)
            if (!d.resources) {
                commonParams.put('resources', [
                    requests: [cpu: '250m', memory: '2000m'],
                    limits  : [cpu: '512m', memory: '1Gi']
                ])
            }
            if (!d.probes) {
                commonParams.put('probes', [
                    liveness : [path: '/', port: commonParams.containerPort, initialDelaySeconds: 15, periodSeconds: 10],
                    readiness: [path: '/', port: commonParams.containerPort, initialDelaySeconds: 5,  periodSeconds: 5]
                ])
            }
        } else {
            commonParams.put('replicas', (d.replicas ?: 3) as int)
            if (!d.resources) {
                commonParams.put('resources', [
                    requests: [cpu: '100m', memory: '500m'],
                    limits  : [cpu: '256m', memory: '512Mi']
                ])
            }
            if (!d.probes) {
                commonParams.put('probes', [
                    liveness : [path: '/health', port: commonParams.containerPort, initialDelaySeconds: 15, periodSeconds: 10],
                    readiness: [path: '/ready',  port: commonParams.containerPort, initialDelaySeconds: 5,  periodSeconds: 5]
                ])
            }
        }
        kustomize.call(commonParams)

    } else {
        commonParams.put('replicas', (d.replicas ?: 1) as int)
        kustomize.call(commonParams)
    }
}