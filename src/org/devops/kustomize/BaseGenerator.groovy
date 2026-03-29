package org.devops.kustomize

class BaseGenerator implements Serializable {

  def steps
  BaseGenerator(steps=null) { this.steps = steps }

  void ensureBase(Map config, boolean rolloutEnabled) {
    def u = new Utils(this.steps)
    def stepsBlock = RolloutHelper.stepsBlock(config)

    boolean useHpa  = config.hpa?.enabled
    boolean useVpa  = config.vpa?.enabled
    boolean useKeda = config.keda?.enabled
    boolean useCron = ((config.deployCronJobs ?: config.ci?.deployCronJobs ?: 'Yes') == 'Yes') && (config.cronjobs instanceof List)
    boolean usePvc  = config.deployment?.logPvc?.enabled == true

    steps.dir('manifest-repo') {

      // ── Force Cleanup ── แยก base/app และ base/cronjob
      steps.sh 'rm -rf base/app/*.yaml base/cronjob/*.yaml || true'
      steps.sh 'mkdir -p base/app'
      if (useCron) steps.sh 'mkdir -p base/cronjob'

      // ── Prepare Data ──────────────────────────────────────────────────────
      def projectName = config.projectName.toString()
      def deployName  = config.deployName.toString()
      def namespace   = (config.namespace ?: 'default').toString()
      def replicas    = (config.replicas ?: 1).toString()
      def cPort       = (config.containerPort ?: 3000).toString()
      def tPort       = (config.targetPort ?: cPort).toString()

      def stableNodePort      = (config.nodePort && config.nodePort.toString().isInteger()) ? config.nodePort.toInteger() : 0
      def canaryNodePort      = (stableNodePort > 0) ? (stableNodePort + 100) : 0
      def stableNodePortBlock = stableNodePort > 0 ? "nodePort: ${stableNodePort}" : ''
      def canaryNodePortBlock = canaryNodePort > 0 ? "nodePort: ${canaryNodePort}" : ''

      // ── base/app — workload resources ────────────────────────────────────
      def appResourceList = []

      if (rolloutEnabled) {
          appResourceList.addAll([
            'rollout.yaml',
            'service-stable.yaml',
            'service-canary.yaml',
            'analysis-template-newman.yaml',
            'analysis-template-prometheus.yaml',
            'rbac-analysis-job.yaml'
          ])
      } else {
          appResourceList.addAll(['deployment.yaml', 'service.yaml'])
      }

      if (useHpa)  appResourceList.add('hpa.yaml')
      if (useVpa)  appResourceList.add('vpa.yaml')
      if (useKeda) appResourceList.add('scaledobject.yaml')
      if (usePvc)  appResourceList.add('pvc-logs.yaml')

      // Write base/app/kustomize-config.yaml
      u.write('base/app/kustomize-config.yaml', steps.libraryResource('org/devops/kustomize/base/kustomize-config.tmpl'))

      // Write base/app/kustomization.yaml
      StringBuilder appKb = new StringBuilder()
      appKb.append("resources:\n")
      appResourceList.each { res -> appKb.append("  - ${res}\n") }
      appKb.append("configurations:\n")
      appKb.append("  - kustomize-config.yaml\n")
      u.write('base/app/kustomization.yaml', appKb.toString())

      // ── Write workload yamls → base/app/ ─────────────────────────────────
      if (rolloutEnabled) {
        def stableSvc = "${config.serviceName}-stable"
        def canarySvc = "${config.serviceName}-canary"
        def startupTime  = (config.deployment?.startupProbe?.failureThreshold ?: 30) * (config.deployment?.startupProbe?.periodSeconds ?: 10)
        def pDeadline    = (config.deployment?.progressDeadlineSeconds ?: (startupTime + 60)).toString()

        u.write('base/app/rollout.yaml', u.tmpl('org/devops/kustomize/base/rollout.tmpl', [
          DEPLOY_NAME: deployName, NAMESPACE: namespace, REPLICAS: replicas,
          APP_NAME: projectName, IMAGE_PULL: config.imagePullSecret.toString(),
          CON_PORT: cPort, STEPS_BLOCK: stepsBlock,
          STABLE_SVC: stableSvc, CANARY_SVC: canarySvc,
          PROGRESS_DEADLINE: pDeadline
        ]))
        u.write('base/app/service-stable.yaml', u.tmpl('org/devops/kustomize/base/service-stable.tmpl', [
          SVC_NAME: stableSvc, CON_PORT: cPort, TGT_PORT: tPort,
          NODE_PORT_BLOCK: stableNodePortBlock, APP_NAME: projectName
        ]))
        u.write('base/app/service-canary.yaml', u.tmpl('org/devops/kustomize/base/service-canary.tmpl', [
          SVC_NAME: canarySvc, CON_PORT: cPort, TGT_PORT: tPort,
          NODE_PORT_BLOCK: canaryNodePortBlock, APP_NAME: projectName
        ]))
      } else {
        u.write('base/app/deployment.yaml', u.tmpl('org/devops/kustomize/base/deployment.tmpl', [
          DEPLOY_NAME: deployName, NAMESPACE: namespace, APP_NAME: projectName,
          IMAGE_PH: "${projectName}:PLACEHOLDER_TAG",
          IMAGE_PULL: config.imagePullSecret.toString(), CON_PORT: cPort
        ]))
        u.write('base/app/service.yaml', u.tmpl('org/devops/kustomize/base/service.tmpl', [
          SVC_NAME: config.serviceName.toString(), CON_PORT: cPort, TGT_PORT: tPort,
          NODE_PORT_BLOCK: stableNodePortBlock, APP_NAME: projectName
        ]))
      }

      if (useHpa) {
          def targetApi  = rolloutEnabled ? 'argoproj.io/v1alpha1' : 'apps/v1'
          def targetKind = rolloutEnabled ? 'Rollout' : 'Deployment'
          u.write('base/app/hpa.yaml', u.tmpl('org/devops/kustomize/base/hpa.tmpl', [
            NAME: "${deployName}-hpa", TARGET_NAME: deployName,
            TARGET_API: targetApi, TARGET_KIND: targetKind,
            MIN_REPLICAS: config.hpa.minReplicas.toString(),
            MAX_REPLICAS: config.hpa.maxReplicas.toString(),
            CPU_PERCENT: config.hpa.cpuPercent.toString(),
            MEM_PERCENT: config.hpa.memPercent.toString()
          ]))
      }

      if (useVpa) {
          def targetApi  = rolloutEnabled ? 'argoproj.io/v1alpha1' : 'apps/v1'
          def targetKind = rolloutEnabled ? 'Rollout' : 'Deployment'
          u.write('base/app/vpa.yaml', u.tmpl('org/devops/kustomize/base/vpa.tmpl', [
            NAME: "${deployName}-vpa", TARGET_NAME: deployName,
            TARGET_API: targetApi, TARGET_KIND: targetKind,
            UPDATE_MODE: (config.vpa.mode ?: 'Off').toString()
          ]))
      }

      if (useKeda) {
          def targetApi  = rolloutEnabled ? 'argoproj.io/v1alpha1' : 'apps/v1'
          def targetKind = rolloutEnabled ? 'Rollout' : 'Deployment'
          u.write('base/app/scaledobject.yaml', u.tmpl('org/devops/kustomize/base/scaledobject.tmpl', [
            NAME: "${deployName}-scaler", TARGET_NAME: deployName,
            TARGET_API: targetApi, TARGET_KIND: targetKind,
            MIN_REPLICAS: config.keda.minReplicas.toString(),
            MAX_REPLICAS: config.keda.maxReplicas.toString(),
            POLLING_INTERVAL: (config.keda.pollingInterval ?: 30).toString(),
            COOLDOWN_PERIOD: (config.keda.cooldownPeriod ?: 300).toString(),
            TRIGGERS_BLOCK: RolloutHelper.kedaTriggersBlock(config)
          ]))
      }

      if (usePvc) {
          def lp = config.deployment.logPvc
          u.write('base/app/pvc-logs.yaml', u.tmpl('org/devops/kustomize/base/pvc-logs.tmpl', [
              PVC_NAME: "${projectName}-logs-pvc", NAMESPACE: namespace,
              STORAGE: (lp.storage ?: '10Gi').toString(),
              STORAGE_CLASS: (lp.storageClass ?: 'longhorn').toString(),
              ACCESS_MODE: (lp.accessMode ?: 'ReadWriteMany').toString()
          ]))
      }

      // Analysis Templates & RBAC → base/app/
      u.write('base/app/analysis-template-newman.yaml',
        u.tmpl('org/devops/kustomize/base/analysis-template-newman.tmpl', [APP_NAME: projectName]))

      def promUrl = (config?.env?.PROMETHEUS_HOST_URL ?: steps?.env?.PROMETHEUS_HOST_URL ?: 'http://prometheus.default:9090').toString()
      u.write('base/app/analysis-template-prometheus.yaml',
        u.tmpl('org/devops/kustomize/base/analysis-template-prometheus.tmpl', [
          APP_NAME: projectName, NAMESPACE: namespace, PROM_URL: promUrl
        ]))

      u.write('base/app/rbac-analysis-job.yaml',
        u.tmpl('org/devops/kustomize/base/rbac-analysis-job.tmpl', [
          APP_NAME: projectName, NAMESPACE: namespace
        ]))

      // ── base/cronjob — cronjob resources เท่านั้น ─────────────────────────
      if (useCron) {
          def cronResourceList = []

          config.cronjobs.each { cronJob ->
              def cronName     = cronJob.name.toString()
              def cronBaseName = cronName.startsWith(projectName) ? cronName : "${projectName}-${cronName}"
              cronResourceList.add("cronjob-${cronBaseName}.yaml")

              u.write("base/cronjob/cronjob-${cronBaseName}.yaml",
                u.tmpl('org/devops/kustomize/base/cronjob.tmpl', [
                  CRON_NAME : cronBaseName,
                  NAMESPACE : namespace,
                  SCHEDULE  : (cronJob.schedule ?: '0 0 * * *').toString(),
                  APP_NAME  : projectName,
                  IMAGE_PULL: config.imagePullSecret.toString(),
                  COMMAND   : (cronJob.command instanceof List)
                      ? groovy.json.JsonOutput.toJson(cronJob.command)
                      : "[\"${cronJob.command}\"]"
              ]))
          }

          // Write base/cronjob/kustomization.yaml — cronjob resources เท่านั้น
          StringBuilder cronKb = new StringBuilder()
          cronKb.append("resources:\n")
          cronResourceList.each { res -> cronKb.append("  - ${res}\n") }
          u.write('base/cronjob/kustomization.yaml', cronKb.toString())
      }

      // ── Overlays skeleton ─────────────────────────────────────────────────
      ['poc', 'uat', 'prd'].each { envName ->

        // app overlay 
        steps.sh "mkdir -p overlays/${envName}/app"
        if (!steps.fileExists("overlays/${envName}/app/kustomization.yaml")) {
          u.write("overlays/${envName}/app/kustomization.yaml", """\
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../../base/app
""")
        }

        // cronjob overlay 
        if (useCron) {
          steps.sh "mkdir -p overlays/${envName}/cronjob"
          if (!steps.fileExists("overlays/${envName}/cronjob/kustomization.yaml")) {
            u.write("overlays/${envName}/cronjob/kustomization.yaml", """\
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../../base/cronjob
""")
          }
        }
      }

      // ── Push ──────────────────────────────────────────────────────────────
      u.gitCommitAndPush(
        (config.manifestBranch ?: 'main').toString(),
        "[Jenkins] Force refresh base/app + base/cronjob for ${projectName}"
      )
    }
  }

}