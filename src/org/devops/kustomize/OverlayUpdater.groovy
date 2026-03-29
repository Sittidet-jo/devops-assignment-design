package org.devops.kustomize

class OverlayUpdater implements Serializable {

  def steps
  OverlayUpdater(steps=null) { this.steps = steps }

  void updateOverlay(Map config, boolean rolloutEnabled) {
    def u = new Utils(this.steps)
    boolean enableKHC  = ((config.kubeHealthCheck ?: config.ci?.kubeHealthCheck ?: 'No') == 'Yes')
    boolean isCanary   = rolloutEnabled || ((config.ci?.deployStrategy ?: 'standard') == 'canary' || (config.rollout?.enabled ?: false))
    boolean wantAnalysis = isCanary && ((config.analysis ?: config.ci?.analysis ?: 'No').toString().equalsIgnoreCase('Yes'))
    boolean cronOnly   = config.doCronOnly == true

    boolean useHpa  = config.hpa?.enabled
    boolean useKeda = config.keda?.enabled

    def d = (config.deployment ?: [:])

    def containerPort = (d.containerPort ?: config.containerPort ?: 3000) as int
    def targetPort    = (d.targetPort    ?: config.targetPort    ?: containerPort) as int

    def projectType      = d.projectType      ?: config.projectType
    def envType          = d.envType          ?: config.envType
    def volumeSecretName = d.secretName       ?: config.volumeSecretName
    def configMapName    = d.configMapName    ?: config.configMapName

    def vm = (d.volumeMounts ?: []).find { it.name == 'config-volume' } ?: [:]
    def volumeMountPath = vm.mountPath ?: config.volumeMountPath
    def volumeSubPath   = vm.subPath   ?: config.volumeSubPath

    def cpuReq  = d.cpuRequest ?: config.cpuRequest
    def memReq  = d.memRequest ?: config.memRequest
    def cpuLim  = d.cpuLimit   ?: config.cpuLimit
    def memLim  = d.memLimit   ?: config.memLimit

    Closure fixPort = { p ->
      if (!p) return containerPort
      def s = p.toString().trim()
      return (s == '{containerPort}') ? containerPort : s
    }

    def env    = d.env ?: config.env
    def sProbe = d.startupProbe  ?: config.startupProbe
    def rProbe = d.readinessProbe ?: config.readinessProbe
    def lProbe = d.livenessProbe  ?: config.livenessProbe

    if (!config.rollout && config.rolloutSpec) {
      config.rollout = config.rolloutSpec
    }

    steps.echo "[DEBUG] wantAnalysis=${wantAnalysis}, cronOnly=${cronOnly}, hasSmoke=${config.rollout?.smoke != null}"
    steps.echo "FLAGS -> rolloutEnabled=${rolloutEnabled}, enableKHC=${enableKHC}, wantAnalysis=${wantAnalysis}"
    steps.echo "PORTS -> containerPort=${containerPort}, targetPort=${targetPort}"

    String smokeTestContent = ''
    String testFilePath = config.rollout?.smoke?.testFilePath ?: 'tests/smoke-test.json'

    if (!cronOnly) {
        if (steps.fileExists(testFilePath)) {
            smokeTestContent = steps.readFile(file: testFilePath)
            steps.echo "[INFO] Found smoke test collection at: ${testFilePath}"
        } else if (wantAnalysis) {
            steps.echo "[WARN] wantAnalysis is true but smoke test file NOT found at: ${testFilePath}"
        }
    }

    // ── เลือก overlay path ตาม mode ─────────────────────────────────────────
    // app modes   → overlays/<env>/app/   (backend และ frontend)
    // cron modes  → overlays/<env>/cronjob/
    def overlaySubDir = cronOnly ? 'cronjob' : 'app'
    def overlayDir    = "manifest-repo/overlays/${config.deployEnv}/${overlaySubDir}"

    steps.dir(overlayDir) {
      if (!steps.fileExists('kustomization.yaml')) {
        throw new RuntimeException("Kustomization file not found in overlays/${config.deployEnv}/${overlaySubDir}")
      }

      // ── Cleanup ───────────────────────────────────────────────────────────
      steps.sh "rm -f patch-*.yaml remove-replicas.yaml smoke-test-configmap.yaml smoke-test.json || true"
      steps.sh "yq eval -i 'del(.patches)' kustomization.yaml || true"
      steps.sh "yq eval -i 'del(.patchesStrategicMerge)' kustomization.yaml || true"
      steps.sh "yq eval -i 'del(.configurations)' kustomization.yaml || true"
      steps.sh "yq eval -i 'del(.resources[] | select(. == \"smoke-test-configmap.yaml\"))' kustomization.yaml || true"

      steps.timeout(time: 10, unit: 'SECONDS') {
        steps.waitUntil {
          // base/app/ สำหรับ app overlay, base/cronjob/ สำหรับ cronjob overlay
          return cronOnly
            ? steps.fileExists('../../../base/cronjob/kustomization.yaml')
            : (steps.fileExists('../../../base/app/rollout.yaml') || steps.fileExists('../../../base/app/deployment.yaml'))
        }
      }

      if (!cronOnly && smokeTestContent) {
          def cmName       = "${config.projectName}-smoke-test-config"
          def indentedJson = smokeTestContent.split('\n').collect { "    " + it }.join('\n')
          steps.writeFile file: 'smoke-test-configmap.yaml', text: """\
apiVersion: v1
kind: ConfigMap
metadata:
  name: ${cmName}
data:
  smoke-test.json: |
${indentedJson}
"""
          steps.sh "kustomize edit add resource smoke-test-configmap.yaml || true"
      }

      // ── Replicas / scaling ────────────────────────────────────────────────
      if (!cronOnly) {
          if (useHpa || useKeda) {
              steps.echo "[INFO] AutoScaling (HPA/KEDA) Enabled: Removing static replicas from overlay"
              steps.sh "yq eval -i 'del(.replicas)' kustomization.yaml || true"
              steps.writeFile file: 'remove-replicas.yaml', text: '''
- op: remove
  path: /spec/replicas
'''
              steps.sh "kustomize edit add patch --path remove-replicas.yaml --kind Rollout || true"
          } else {
              if (rolloutEnabled) {
                  steps.sh "yq eval -i 'del(.replicas)' kustomization.yaml || true"
              } else {
                  steps.echo "Updating Kustomize overlay: set replicas ${config.replicas}"
                  steps.sh "kustomize edit set replicas ${config.deployName}=${config.replicas ?: 1} || true"
              }
          }
      }

      // ── kustomize-native edits ────────────────────────────────────────────
      // set image เฉพาะ app overlay — cronjob overlay ใช้ image ใน patch-overrides.yaml โดยตรง
      if (!cronOnly) {
          steps.echo "Updating Kustomize overlay: set image ${config.projectName}=${config.imageToSet}"
          steps.sh "kustomize edit set image ${config.projectName}=${config.imageToSet}"
      }
      steps.sh "kustomize edit set namesuffix -- -${config.deployEnv}"
      def targetNs = config.namespace ?: config.deployEnv ?: 'default'
      steps.sh "kustomize edit set namespace ${targetNs}"

      def workloadKind  = rolloutEnabled ? 'Rollout' : 'Deployment'
      def stableSvcName = "${config.serviceName}-stable"
      def canarySvcName = "${config.serviceName}-canary"

      // ── Blocks สำหรับ workload patch ──────────────────────────────────────
      String containerExtras = ''
      String volumeBlock     = ''

      if (projectType == 'backend' &&
          envType == 'config.yaml' &&
          volumeSecretName && volumeMountPath && volumeSubPath) {

          containerExtras = """
          volumeMounts:
            - name: config-volume
              mountPath: ${volumeMountPath}
              subPath: ${volumeSubPath}"""

          volumeBlock = """
      volumes:
        - name: config-volume
          secret:
            secretName: ${volumeSecretName}"""

      } else if (projectType == 'backend' && (envType == '.env' || envType == 'configMap')) {
          def envSources = []
          if (configMapName) {
              envSources << (envType == 'configMap'
                  ? "\n            - configMapRef:\n                name: ${configMapName}"
                  : "\n            - secretRef:\n                name: ${configMapName}")
          }
          def extras = config.deployment?.extraEnvFrom ?: []
          extras.each { item ->
              if (item.configMapRef) envSources << "\n            - configMapRef:\n                name: ${item.configMapRef}"
              if (item.secretRef)    envSources << "\n            - secretRef:\n                name: ${item.secretRef}"
          }
          if (!envSources.isEmpty()) {
              containerExtras = "\n          envFrom:${envSources.join('')}"
          }
      }

      boolean usePvc = d.logPvc?.enabled == true
      if (usePvc) {
          def pvcName = "${config.projectName}-logs-pvc"
          def mounts  = d.logPvc?.mounts ?: [[ mountPath: (d.logPvc?.mountPath ?: '/app/logs') ]]

          String newMounts = ''
          mounts.each { m ->
              newMounts += "\n            - name: logs-storage\n              mountPath: ${m.mountPath}"
              if (m.subPath) newMounts += "\n              subPath: ${m.subPath}"
          }

          if (containerExtras.contains('volumeMounts:')) {
              containerExtras = containerExtras.stripTrailing() + newMounts
          } else {
              containerExtras += "\n                volumeMounts:${newMounts}"
          }

          String newVolume = "\n        - name: logs-storage\n          persistentVolumeClaim:\n            claimName: ${pvcName}-${config.deployEnv}"
          if (volumeBlock.contains('volumes:')) {
              volumeBlock = volumeBlock.stripTrailing() + newVolume
          } else {
              volumeBlock += "\n            volumes:${newVolume}"
          }
      }

      String envBlock = ''
      if (env && env instanceof Map && !env.isEmpty()) {
          envBlock = "          env:\n"
          env.each { k, v ->
              envBlock += "            - name: ${k}\n"
              envBlock += "              value: \"${v.toString().replace('"', '\\"')}\"\n"
          }
      }

      String resourceBlock = ''
      if (cpuReq || memReq || cpuLim || memLim) {
          resourceBlock = """
          resources:
            requests:
              ${cpuReq ? "cpu: ${cpuReq}" : 'cpu: 100m'}
              ${memReq ? "memory: ${memReq}" : 'memory: 256Mi'}
            limits:
              ${cpuLim ? "cpu: ${cpuLim}" : 'cpu: 500m'}
              ${memLim ? "memory: ${memLim}" : 'memory: 512Mi'}"""
      }

      String startupProbeBlock  = ''
      String readinessProbeBlock = ''
      String livenessProbeBlock  = ''
      if (enableKHC && sProbe) {
          startupProbeBlock = """
          startupProbe:
            httpGet:
              path: ${sProbe.path ?: '/'}
              port: ${fixPort(sProbe.port)}
            failureThreshold: ${sProbe.failureThreshold ?: 30}
            periodSeconds: ${sProbe.periodSeconds ?: 10}
            timeoutSeconds: ${sProbe.timeoutSeconds ?: 5}"""
      }
      if (enableKHC && rProbe) {
          readinessProbeBlock = """
          readinessProbe:
            httpGet:
              path: ${rProbe.path ?: '/'}
              port: ${fixPort(rProbe.port)}
            initialDelaySeconds: ${rProbe.initialDelaySeconds ?: 5}
            periodSeconds: ${rProbe.periodSeconds ?: 5}
            timeoutSeconds: ${rProbe.timeoutSeconds ?: 5}"""
      }
      if (enableKHC && lProbe) {
          livenessProbeBlock = """
          livenessProbe:
            httpGet:
              path: ${lProbe.path ?: '/'}
              port: ${fixPort(lProbe.port)}
            initialDelaySeconds: ${lProbe.initialDelaySeconds ?: 15}
            periodSeconds: ${lProbe.periodSeconds ?: 10}
            timeoutSeconds: ${lProbe.timeoutSeconds ?: 5}"""
      }

      def pNameRaw = config.portName ?: 'http'
      def pName    = (pNameRaw instanceof Map) ? 'http' : pNameRaw.toString()

      def stableNodePort      = (config.nodePort && config.nodePort.toString().isInteger()) ? config.nodePort.toInteger() : 0
      def canaryNodePort      = (stableNodePort > 0) ? (stableNodePort + 100) : 0
      def stableNodePortBlock = stableNodePort > 0 ? "nodePort: ${stableNodePort}" : ""
      def canaryNodePortBlock = canaryNodePort > 0 ? "nodePort: ${canaryNodePort}" : ""

      // ── ถ้า cronOnly — เขียนเฉพาะ cronPatch ─────────────────────────────────
      // cronPatch สร้างจาก config.cronjobs โดยตรง
      if (cronOnly) {
          boolean useCron = (config.cronjobs instanceof List) && !config.cronjobs.isEmpty()
          if (!useCron) {
              steps.echo "[WARN] cronjob-only mode but no cronjobs defined — nothing to patch"
              steps.writeFile file: 'patch-overrides.yaml', text: "# no cronjobs\n"
          } else {
              String cronContainerExtras = containerExtras ? containerExtras.replaceAll('(?m)^(?=.)', '    ') : ''
              String cronResourceBlock   = resourceBlock   ? resourceBlock.replaceAll('(?m)^(?=.)', '    ')   : ''
              String cronVolumeBlock     = volumeBlock     ? volumeBlock.replaceAll('(?m)^(?=.)', '    ')     : ''
              String cronPatch = ''

              config.cronjobs.each { cronJob ->
                  def cronName     = cronJob.name.toString()
                  def cronBaseName = cronName.startsWith(config.projectName.toString())
                      ? cronName : "${config.projectName}-${cronName}"

                  def mergedEnv = [:]
                  if (env && env instanceof Map) mergedEnv.putAll(env)
                  if (cronJob.env && cronJob.env instanceof Map) mergedEnv.putAll(cronJob.env)

                  String thisCronEnvBlock = ''
                  if (!mergedEnv.isEmpty()) {
                      thisCronEnvBlock = '          env:\n'
                      mergedEnv.each { k, v ->
                          thisCronEnvBlock += "            - name: ${k}\n"
                          thisCronEnvBlock += "              value: \"${v.toString().replace('"', '\\"')}\"\n"
                      }
                  }
                  String cronEnvBlock = thisCronEnvBlock ? thisCronEnvBlock.replaceAll('(?m)^(?=.)', '    ') : ''

                  cronPatch += """\
---
apiVersion: batch/v1
kind: CronJob
metadata:
  name: ${cronBaseName}
spec:
  jobTemplate:
    spec:
      template:
        spec:
          containers:
            - name: ${cronBaseName}
              image: ${config.imageToSet}
${cronEnvBlock}${cronContainerExtras}${cronResourceBlock}
${cronVolumeBlock}
"""
              }
              steps.writeFile file: 'patch-overrides.yaml', text: cronPatch
          }
      } else {
          // ── Normal mode — full patch ──────────────────────────────────────
          def replicaPatch = ""
          if (!useHpa && !useKeda) {
              replicaPatch = """\
apiVersion: ${rolloutEnabled ? 'argoproj.io/v1alpha1' : 'apps/v1'}
kind: ${workloadKind}
metadata:
  name: ${config.deployName}
spec:
  replicas: ${config.replicas ?: 1}
"""
          }

          String servicePatch = ""
          if (rolloutEnabled) {
              servicePatch = """\
---
apiVersion: v1
kind: Service
metadata:
  name: ${stableSvcName}
spec:
  ports:
    - name: ${pName}
      port: ${containerPort}
      targetPort: ${targetPort}
${stableNodePortBlock ? '      ' + stableNodePortBlock : ''}
---
apiVersion: v1
kind: Service
metadata:
  name: ${canarySvcName}
spec:
  ports:
    - name: ${pName}
      port: ${containerPort}
      targetPort: ${targetPort}
${canaryNodePortBlock ? '      ' + canaryNodePortBlock : ''}
"""
          } else {
              servicePatch = """\
---
apiVersion: v1
kind: Service
metadata:
  name: ${config.serviceName}
spec:
  ports:
    - name: ${pName}
      port: ${containerPort}
      targetPort: ${targetPort}
${stableNodePortBlock ? '      ' + stableNodePortBlock : ''}
"""
          }

          String canaryPatch = ''
          if (rolloutEnabled) {
              def mergedConfig = config.clone()
              mergedConfig.rollout = (mergedConfig.rollout ?: [:])
              mergedConfig.rollout.serviceRefs = [
                  stableService: stableSvcName,
                  canaryService: canarySvcName
              ]
              def finalCanarySvc = "${canarySvcName}-${config.deployEnv}"
              if (mergedConfig.rollout.smoke) {
                  String svcPortStr = String.valueOf(targetPort ?: containerPort)
                  mergedConfig.rollout.smoke.baseUrl = 'http://' + finalCanarySvc + ':' + svcPortStr
              }
              canaryPatch = RolloutHelper.canaryPatch(mergedConfig)
          }

          // ── เขียน app patch — ไม่มี cronPatch (CronJob อยู่ใน base/cronjob) ──
          steps.writeFile file: 'patch-overrides.yaml', text: """\
apiVersion: ${rolloutEnabled ? 'argoproj.io/v1alpha1' : 'apps/v1'}
kind: ${workloadKind}
metadata:
  name: ${config.deployName}
spec:
  template:
    spec:
      imagePullSecrets:
        - name: ${config.imagePullSecret ?: 'imagePullSecretName'}
      containers:
        - name: ${config.projectName}
          image: ${config.imageToSet}
${envBlock}
          ports:
            - name: ${pName}
              containerPort: ${containerPort}
${containerExtras}
${resourceBlock}
${startupProbeBlock}
${readinessProbeBlock}
${livenessProbeBlock}
${volumeBlock}
${replicaPatch ? "---\n" + replicaPatch : ""}
${servicePatch}
${canaryPatch}
"""
      }

      // Debug
      steps.sh "echo '--- DEBUG: patch-overrides.yaml content ---'; cat patch-overrides.yaml"

      // ── Include patch to kustomization ───────────────────────────────────
      steps.sh '''
        set -e
        yq eval -i '.patches = (.patches // [])' kustomization.yaml
        yq eval -i '.patches |= map(select(.path != "patch-overrides.yaml"))' kustomization.yaml
        yq eval -i '.patches += [{"path":"patch-overrides.yaml"}]' kustomization.yaml
        yq eval -i 'del(.patchesStrategicMerge)' kustomization.yaml
      '''

      if (!cronOnly && useKeda) {
          def targetNameWithSuffix = "${config.deployName}-${config.deployEnv}"
          steps.writeFile file: 'patch-scaledobject-ref.yaml', text: """
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: ${config.deployName}-scaler
spec:
  scaleTargetRef:
    apiVersion: ${rolloutEnabled ? 'argoproj.io/v1alpha1' : 'apps/v1'}
    kind: ${workloadKind}
    name: ${targetNameWithSuffix}
"""
          steps.sh "kustomize edit add patch --path patch-scaledobject-ref.yaml || true"
      }

      steps.sh "git add . || true"
      steps.sh "git status"

      u.gitCommitAndPush(
          (config.manifestBranch ?: 'main').toString(),
          "[Jenkins] Update ${config.deployEnv}/${overlaySubDir} overlay for ${config.projectName} -> ${config.imageTag}".toString()
      )

      // Sanity render
      steps.sh "kustomize build . > rendered.yaml"
    }
  }

}