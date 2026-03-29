import org.devops.ConfigParser
import org.devops.CredentialConstants
import org.devops.UiHelper

def call(Map cfg = [:]) {
    def config
    if (cfg && !cfg.isEmpty()) {
        echo 'Using inline config from Jenkinsfile'
        config = cfg
    } else {
        echo 'Loading YAML config from Shared Library'
        def jobNameParts       = env.JOB_NAME.split('%2F')
        def projectNameFromJob = jobNameParts[-1]
        def groupPath          = jobNameParts.length > 1 ? jobNameParts[0..-2].join('/') : ''
        def configFilePath     = groupPath ? "configs/${groupPath}/${projectNameFromJob}.yaml" : "configs/${projectNameFromJob}.yaml"
        config = readYaml text: libraryResource(configFilePath)
    }
    def dockerInfo  = null
    def newVersion  = null
    def syncSkipped = false
    if (config.env) {
        echo 'Applying environment overrides from Jenkinsfile config.env...'
        config.env.each { key, value ->
            env.setProperty(key, value)
            echo " - ${key} = ${value}"
        }
    }
    pipeline {
        agent any
        options {
            parallelsAlwaysFailFast()
            buildDiscarder(logRotator(numToKeepStr: '10'))
            timestamps()
        }
        triggers {
            gitlab(
                triggerOnPush: true,
                triggerOnMergeRequest: false,
                ciSkip: true,
                branchFilterType: 'NameBasedFilter',
                branchFilterName: "${config.gitlab?.sourceBranch ?: 'main'}"
            )
        }
        environment {
            PROJECT_NAME              = "${config.projectName}"
            LANGUAGE                  = "${config.language}"
            PROJECT_TYPE              = "${config.projectType}"
            GITLAB_PAT_CREDENTIALS_ID = "${config.credentials?.gitlab_text ?: CredentialConstants.GITLAB_PAT}"
            GITLAB_DEPLOY_TOKEN_ID    = "${config.credentials?.gitlab    ?: CredentialConstants.GITLAB_DEPLOY}"
        }
        parameters {
            choice(
                name: 'pipelineMode',
                choices: [
                    'Default',
                    'full',                       // build + deploy api + cronjob
                    'build-deploy-all',           // build + deploy api + cronjob
                    'build-deploy-app',           // build + deploy เฉพาะ api
                    'build-deploy-cronjob',       // build + deploy เฉพาะ cronjob
                    'build-only',                 // build อย่างเดียว
                    'deploy-all',                 // deploy api + cronjob
                    'deploy-app',                 // deploy เฉพาะ api
                    'deploy-cronjob',             // deploy เฉพาะ cronjob
                ],
                description: '''\
Pipeline execution mode — ความหมายของ suffix:
  -all     = deploy ทั้ง api และ cronjob
  -cronjob = deploy เฉพาะ cronjob ไม่แตะ api
  -app     = deploy เฉพาะ api ไม่แตะ cronjob
  (ไม่มี suffix deploy) = ขึ้นอยู่กับ group ด้านบน'''
            )
            string(
                name: 'customVersion',
                defaultValue: '',
                description: '''\
Custom version / image tag (e.g. 1.2.3)
- build modes   : กำหนด version ที่จะ build
- deploy modes  : ระบุ image tag ที่มีอยู่แล้วใน registry
- ถ้าว่าง        : auto-increment หรือใช้ latest git tag'''
            )
            choice(
                name: 'deployENV',
                choices: ['Default', 'poc', 'uat', 'prd'],
                description: UiHelper.deployEnvDesc
            )
            choice(
                name: 'forceRebuild',
                choices: ['Default', 'Yes', 'No'],
                description: UiHelper.forceRebuildDesc
            )
            choice(
                name: 'deployRegistry',
                choices: ['Default', 'gitlab', 'docker-registry'],
                description: UiHelper.deployRegistryDesc
            )
            choice(
                name: 'autoSync',
                choices: ['Default', 'Yes', 'No'],
                description: 'Yes=sync ArgoCD อัตโนมัติ | No=manual sync | Default=ใช้ค่าจาก config'
            )
            choice(
                name: 'deployStrategy',
                choices: ['Default', 'canary'],
                description: UiHelper.deployStrategyDesc
            )
            choice(
                name: 'kubeHealthCheck',
                choices: ['Default', 'Yes', 'No'],
                description: 'Yes=ตรวจ pod health หลัง deploy | Default=ใช้ค่าจาก config'
            )
            choice(
                name: 'analysis',
                choices: ['Default', 'Yes', 'No'],
                description: 'Yes=เปิด Argo Rollouts analysis | Default=ใช้ค่าจาก config'
            )
            choice(
                name: 'scalingStrategy',
                choices: ['Default', 'hpa', 'vpa', 'keda'],
                description: UiHelper.scalingStrategyDesc
            )
            choice(
                name: 'dastScan',
                choices: ['Default', 'Yes', 'No'],
                description: 'Yes=รัน OWASP ZAP DAST scan | Default=ใช้ค่าจาก config'
            )
            choice(
                name: 'enableLogPvc',
                choices: ['Default', 'Yes', 'No'],
                description: 'Yes=mount PVC สำหรับ application logs | Default=ใช้ค่าจาก config'
            )
        }
        stages {
            stage('Setup & Checkout') {
                steps {
                    script {
                        cleanWs()
                        config = ConfigParser.toSerializable(config)
                        echo "Loaded configuration for '${config.projectName}'"
                        def isManual = false
                        try {
                            isManual = (currentBuild.rawBuild?.getCause(hudson.model.Cause$UserIdCause) != null)
                        } catch (ignore) { isManual = false }
                        config.isManualRun = isManual
                        config = ConfigParser.resolveRuntimeConfig(config, params)
                        config.rolloutSpec = ConfigParser.rolloutSpecFromConfig(config)
                        def d = (config.deployment ?: [:])
                        ['cpuRequest', 'cpuLimit', 'memRequest', 'memLimit',
                         'readinessProbe', 'livenessProbe',
                         'containerPort', 'targetPort', 'nodePort', 'imagePullSecret',
                         'replicas', 'namespace', 'portName'
                        ].each { k ->
                            if (d.containsKey(k) && d[k] != null) config[k] = d[k]
                        }
                        config.rolloutEnabled = (config.rolloutSpec && !config.rolloutSpec.isEmpty())
                        echo "Run triggered by : ${config.isManualRun ? 'Manual User' : 'Git Commit'}"
                        echo "Pipeline mode    : ${config.pipelineMode}"
                        echo "Flags            : doBuild=${config.doBuild} | doDeploy=${config.doDeploy} | doCronOnly=${config.doCronOnly} | deployCronJobs=${config.deployCronJobs}"
                        echo "Env              : ${config.deployEnv} | autoSync=${config.autoSync} | forceRebuild=${config.forceRebuild}"
                        def srcUrl    = "${config.gitlab.baseUrl}/${config.gitlab.sourcePath}.git"
                        def srcBranch = config.gitlab?.sourceBranch ?: 'main'
                        def srcCred   = config.credentials?.gitlab ?: CredentialConstants.GITLAB_DEPLOY
                        utils.gitCheckout([url: srcUrl, credentialsId: srcCred, branch: srcBranch])
                        ciSetup(config)
                        def dirtyTracked = sh(
                            script: '#!/bin/bash\nset -e\ngit diff --name-only',
                            returnStdout: true
                        ).trim()
                        if (dirtyTracked) {
                            error "[CI Setup] Tracked repository files were modified by CI: ${dirtyTracked}"
                        }
                    }
                }
            }
            stage('Guard - Release-worthy Changes') {
                steps {
                    script {
                        if (config.isManualRun) { echo 'Manual run → skip guard.'; return }
                        if (!versioning.hasReleaseWorthyChanges()) {
                            currentBuild.result = 'NOT_BUILT'
                            throw new hudson.AbortException('Skip: no release-worthy changes')
                        }
                    }
                }
            }
            stage('Create Release') {
                when { expression { config.doBuild || config.doDeploy } }
                steps {
                    script {
                        newVersion = versioning.createRelease([
                            language               : env.LANGUAGE,
                            gitlabTokenCredentialId: env.GITLAB_PAT_CREDENTIALS_ID,
                            repoHttps              : "${config.gitlab.baseUrl}/${config.gitlab.sourcePath}.git",
                            branch                 : (config.gitlab?.sourceBranch ?: 'main'),
                            customVersion          : "${params.customVersion}".trim()
                        ])
                        if (!newVersion) {
                            currentBuild.result = 'NOT_BUILT'
                            throw new hudson.AbortException('Skip: no release-worthy changes')
                        }
                        echo "Release created: v${newVersion}"
                    }
                }
            }
            stage('Setup Buildx') {
                when { expression { config.doBuild } }
                steps {
                    script {
                        sh """
                            docker buildx inspect mybuilder > /dev/null 2>&1 || \
                            docker buildx create --name mybuilder --driver docker-container
                            docker buildx use mybuilder
                        """
                    }
                }
            }
            stage('Build Docker Image') {
                when { expression { config.doBuild } }
                steps {
                    script {
                        echo '🐳 Building Docker Image...'
                        def envCred = (config.projectType == 'frontend')
                            ? (config.credentials?.frontend_env ?: CredentialConstants.FRONTEND_ENV)
                            : null
                        dockerInfo = myDocker.call([
                            projectName        : config.projectName,
                            deployEnv          : config.deployEnv,
                            customVersion      : newVersion,
                            forceRebuild       : config.forceRebuild,
                            sourcesPath        : (config.build?.dockerfilePath ?: '.'),
                            nodeOptions        : config.build?.nodeOptions,
                            buildMemory        : config.build?.buildMemory,
                            nextMaxWorkers     : config.build?.nextMaxWorkers,
                            gitlabImagePath    : config.gitlab?.sourcePath,
                            gitlabRegistryHost : config.gitlab?.registryHost,
                            gitlabDeployTokenId: env.GITLAB_DEPLOY_TOKEN_ID,
                            envFileCredentialId: envCred,
                            builderName        : env.BUILDX_BUILDER,
                        ])
                    }
                }
            }
            stage('Resolve Image for Deploy') {
                when { expression { !config.doBuild && (config.doDeploy || config.doCronOnly) } }
                steps {
                    script {
                        def tag = params.customVersion?.trim()
                        if (!tag) {
                            tag = sh(
                                script: "git describe --tags --abbrev=0 2>/dev/null || echo 'latest'",
                                returnStdout: true
                            ).trim()
                            echo "No customVersion specified — resolved from git tag: ${tag}"
                        }
                        def registryImage = "${config.gitlab.registryHost}/${config.gitlab.sourcePath}/${config.deployEnv}:${tag}"
                        dockerInfo = [
                            imageTag           : tag,
                            localImage         : "${config.projectName}:${tag}",
                            gitlabRegistryImage: registryImage,
                        ]
                        newVersion = tag
                        echo "[${config.pipelineMode}] Resolved image: ${registryImage}"
                    }
                }
            }
            stage('Trivy Image Scan & SBOM') {
                when { expression { config.doBuild } }
                steps {
                    script {
                        echo '🐳 Running Trivy Image Scan (FINAL security report)...'
                        def imageGate = trivy([
                            projectName      : config.projectName,
                            deployEnv        : config.deployEnv,
                            imageName        : dockerInfo.localImage,
                            gitlab           : config.gitlab,
                            credentialsId    : env.GITLAB_DEPLOY_TOKEN_ID,
                            criticalThreshold: config.trivyCriticalThreshold,
                            highThreshold    : config.trivyHighThreshold,
                            ignoreUnfixed    : config.trivyIgnoreUnfixed != null ? config.trivyIgnoreUnfixed : true,
                        ])
                        def alreadyUnstable = (currentBuild.result == 'UNSTABLE')
                        echo '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━'
                        echo 'Trivy Image Gate (FINAL)'
                        echo "  Trivy Image : ${imageGate == 'FAIL' ? '❌ FAIL' : '✅ PASS'}"
                        echo "  Pipeline was already UNSTABLE: ${alreadyUnstable}"
                        echo '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━'
                        if (imageGate == 'FAIL') {
                            if (alreadyUnstable) {
                                echo '[Trivy Image] ⚠️ Image scan FAILED — already UNSTABLE, skipping prompt.'
                            } else {
                                def proceed = input(
                                    message: UiHelper.gateMessage(
                                        '⚠️ Trivy Image Scan FAILED (FINAL security report)!',
                                        ['Trivy Image (vulnerabilities exceed threshold)'],
                                        'นี่คือ FINAL security report ก่อน deploy'
                                    ),
                                    ok: 'Proceed (Mark Unstable)',
                                    submitterParameter: 'approver'
                                )
                                echo "⚠️ Image Gate bypassed by: ${proceed}"
                                currentBuild.result = 'UNSTABLE'
                            }
                        } else {
                            echo '[Trivy Image] ✅ Image scan passed'
                        }
                        sbom.call([
                            image     : dockerInfo.localImage,
                            spdxOutput: 'reports/sbom.spdx.json',
                            cdxOutput : 'reports/sbom.cyclonedx.json'
                        ])
                    }
                }
            }
            stage('Push Images') {
                when { expression { config.doBuild } }
                steps {
                    script {
                        myDocker.pushToGitlabRegistry([
                            internalRegistryImage: dockerInfo.internalRegistryImage,
                            gitlabRegistryImage  : dockerInfo.gitlabRegistryImage,
                            gitlabRegistryHost   : config.gitlab?.registryHost,
                            gitlabDeployTokenId  : env.GITLAB_DEPLOY_TOKEN_ID
                        ])
                    }
                }
            }
            stage('Update Manifests') {
                when { expression { config.doDeploy || config.doCronOnly } }
                steps {
                    script {
                        updateManifests(config: config, dockerInfo: dockerInfo)
                    }
                }
            }
            stage('Create ArgoCD App & Sync') {
                when { expression { config.doDeploy || config.doCronOnly } }
                steps {
                    script {
                        def argoArgs = [
                            projectName        : config.projectName,
                            deployEnv          : config.deployEnv,
                            argocdCredentialsId: CredentialConstants.ARGOCD,
                            gitlabCredentialsId: config.credentials?.gitlab ?: CredentialConstants.GITLAB_DEPLOY,
                            manifestRepoUrl    : "${config.gitlab?.baseUrl}/${config.gitlab?.sourcePath}.git",
                            manifestBranch     : "${config.projectName}-manifest",
                            namespace          : (config.deployment?.namespace ?: 'default'),
                            autoSync           : config.autoSync,
                            deployStrategy     : config.deployStrategy,
                            rollout            : config.rollout,
                            doDeploy           : config.doDeploy,
                            deployCronJobs     : config.deployCronJobs,
                            doCronOnly         : config.doCronOnly,
                        ]

                        if (config.autoSync != 'Yes' && config.doDeploy) {
                            // ── autoSync=No: register app only, then prompt ──────────────
                            argocd.call(argoArgs + [skipSync: true])

                            notifier.sendSyncPromptToDiscord([
                                jobName            : env.JOB_NAME,
                                buildNumber        : env.BUILD_NUMBER,
                                buildUrl           : env.BUILD_URL,
                                projectName        : config.projectName,
                                deployEnv          : config.deployEnv,
                                webhookCredentialId: config.credentials?.discord ?: 'DISCORD_WEBHOOK_URL_TEST'
                            ])

                            def proceed      = false
                            def timedOut     = false
                            def appName      = "${config.projectName}-app-${config.deployEnv}"
                            def argoCredId   = config.credentials?.argocd ?: CredentialConstants.ARGOCD
                            def discordCredId = config.credentials?.discord ?: 'DISCORD_WEBHOOK_URL_TEST'

                            try {
                                timeout(time: 30, unit: 'MINUTES') {
                                    input(
                                        id     : 'Sync',
                                        message: 'Auto-sync is disabled. Run ArgoCD sync now to proceed with deployment?',
                                        ok     : 'Proceed'
                                    )
                                    proceed = true
                                }
                            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                                timedOut = e.causes.any { it instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout }
                                echo timedOut
                                    ? '⏰ Sync timed out after 30 minutes — deployment and DAST will be skipped.'
                                    : '⏭️ Sync skipped by user — deployment and DAST will be skipped.'
                            }

                            def argoSyncStatus = 'N/A'
                            if (proceed) {
                                argocd.syncApp(appName: appName, credentialsId: argoCredId)
                                argoSyncStatus = argocd.getAppSyncStatus(appName: appName, credentialsId: argoCredId)
                            } else {
                                syncSkipped = true
                            }

                            notifier.sendSyncResultToDiscord([
                                webhookCredentialId: discordCredId,
                                projectName        : config.projectName,
                                deployEnv          : config.deployEnv,
                                imageTag           : newVersion,
                                result             : proceed ? 'proceed' : (timedOut ? 'timeout' : 'skip'),
                                argoSyncStatus     : argoSyncStatus
                            ])
                        } else {
                            // ── autoSync=Yes or cron-only: normal sync ───────────────────
                            argocd.call(argoArgs + [skipSync: false])
                        }
                    }
                }
            }
            stage('DAST Scan & Canary Promotion') {
                when { expression {
                    config.dastScan == 'Yes' && config.deployStrategy == 'canary' &&
                    !config.doCronOnly && config.doDeploy && !syncSkipped
                } }
                steps {
                    script {
                        def appName    = "${config.projectName}-app-${config.deployEnv}"
                        def argoCredId = config.credentials?.argocd ?: CredentialConstants.ARGOCD
                        echo '🛡️ Starting DAST Scan for Canary Deployment...'
                        try {
                            dastScan(config)
                            if (currentBuild.result != 'FAILURE') {
                                echo '✅ [Pipeline] DAST Passed. Promoting Rollout past 0%...'
                                argocd.actionRollout(appName: appName, action: 'resume', credentialsId: argoCredId)
                            }
                        } catch (Exception e) {
                            echo "❌ [Pipeline] DAST Failed: ${e.message}"
                            currentBuild.result = 'FAILURE'
                            echo '[Pipeline] Aborting Rollout due to Security Failure...'
                            argocd.actionRollout(appName: appName, action: 'abort', credentialsId: argoCredId)
                            error('DAST Scan Failed: Pipeline Aborted')
                        }
                    }
                }
            }
            stage('Health Check & Notify') {
                steps {
                    script {
                        if (syncSkipped) {
                            echo '⏭️ Sync was skipped — skipping health check.'
                            return
                        }
                        def currentImageTag = dockerInfo?.imageTag ?: newVersion ?: 'unknown'
                        argocdHealthCheck(config, currentImageTag)
                    }
                }
            }
        }
        post {
            always {
                script {
                    ciPostProcess.call(
                        config     : config,
                        dockerInfo : dockerInfo,
                        newVersion : newVersion,
                        syncSkipped: syncSkipped
                    )
                }
            }
        }
    }
}