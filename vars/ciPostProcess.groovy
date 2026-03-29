// vars/ciPostProcess.groovy

import org.devops.CredentialConstants

def call(Map args = [:]) {
    def config      = args.config
    def dockerInfo  = args.dockerInfo
    def newVersion  = args.newVersion
    def syncSkipped = args.syncSkipped ?: false

    def currentStatus = currentBuild.currentResult ?: currentBuild.result ?: 'SUCCESS'

    def ranStages = []
    if (config?.doBuild)                                        ranStages << 'Build'
    if (config?.doDeploy && config?.deployCronJobs == 'Yes')    ranStages << 'Deploy(api+cronjob)'
    else if (config?.doDeploy)                                  ranStages << 'Deploy(api)'
    else if (config?.doCronOnly)                                ranStages << 'Deploy(cronjob)'
    def stagesRan = ranStages.join('+') ?: 'None'

    echo "🏁 CI Post-Process | project=${config?.projectName ?: 'unknown'} | mode=${config?.pipelineMode ?: 'unknown'} | ran=${stagesRan} | env=${config?.deployEnv ?: 'unknown'} | status=${currentStatus}"

    if (currentStatus != 'ABORTED' && currentStatus != 'NOT_BUILT') {
        try {
            archiveReports()
        } catch (e) {
            echo "⚠️ Error during reporting stages: ${e.message}"
        }
    } else {
        echo "ℹ️ Pipeline was ${currentStatus}. Skipping report processing."
    }

    sendNotification(config, dockerInfo, newVersion, currentStatus, stagesRan, syncSkipped)

    if (dockerInfo && dockerInfo.localImage) {
        cleanupDocker(dockerInfo)
    } else {
        echo "ℹ️ No Docker image to clean up."
    }

    echo "🧹 Final workspace cleanup..."
    cleanWs()
}

private def archiveReports() {
    def globs = [
        '**/coverage/**/*.info',
        '**/coverage.json',
        'coverage.xml',
        'reports/**/*.sarif',
        'reports/yarn-audit.json',
        'reports/trivy/fs/trivy-fs-report.json',
        'reports/trivy/fs/trivy-fs-report.txt',
        'reports/trivy/fs/trivy-*.pdf',
        'reports/trivy/fs/trivy-*.docx',
        'reports/trivy/fs/trivy-*.xlsx',
        'reports/trivy/image/trivy-image-report.json',
        'reports/trivy/image/trivy-image-report.txt',
        'reports/trivy/image/trivy-*.pdf',
        'reports/trivy/image/trivy-*.docx',
        'reports/trivy/image/trivy-*.xlsx',
        'reports/zap/zap_report.html',
        'reports/zap/zap_report.xml',
        'reports/zap/zap_report.json',
        'reports/zap/zap-*.pdf',
        'reports/zap/zap-*.docx',
        'reports/zap/zap-*.xlsx',
        'reports/**/*.pdf',
        'reports/**/*.docx',
        'reports/**/*.xlsx',
        'reports/**/*.html',
        'reports/**/*.md',
        '*.log'
    ]
    echo "📦 Archiving build artifacts..."
    archiveArtifacts artifacts: globs.join(','), allowEmptyArchive: true, fingerprint: true
}



private def cleanupDocker(def dockerInfo) {
    echo '🧹 Cleaning up Docker Images...'
    myDocker.cleanupBaseImage(dockerInfo: dockerInfo)
}

private def sendNotification(Map config, def dockerInfo, def newVersion, def status, String stagesRan, boolean syncSkipped = false) {
    if (!config) return
    def imageTag = dockerInfo?.imageTag ?: (newVersion ?: '-')
    def imgUrl   = dockerInfo?.gitlabRegistryImage ?: dockerInfo?.localImage ?: '-'

    def errorLogs = ''
    if (status != 'SUCCESS') {
        try {
            def rawLogs = currentBuild.rawBuild
                ?.getLog(200)
                ?.findAll { line ->
                    (line =~ /(?i)(error|exception|failed|fatal|stderr|FAIL)/) &&
                    !(line =~ /(?i)(No such image|cleanup|Error\s*:\s*null|Specified HTML directory|No test report files were found)/)
                }
                ?.join('\n') ?: ''
            def modeCtx = "[Mode: ${config.pipelineMode ?: 'unknown'} | Ran: ${stagesRan}]"
            errorLogs = rawLogs ? "${modeCtx}\n${rawLogs}" : "${modeCtx}\nPipeline failed. Check Jenkins logs for details."
        } catch (ignore) {
            errorLogs = "Could not retrieve logs."
        }
    }

    notifier.call([
        projectZoneName: config.projectZoneName,
        projectName    : config.projectName,
        imageTag       : imageTag,
        fullImageUrl   : imgUrl,
        deployEnv      : config.deployEnv,
        pipelineMode   : config.pipelineMode ?: 'build-deploy-all',
        status         : status,
        buildUrl       : env.BUILD_URL,
        jobName        : env.JOB_NAME,
        duration       : currentBuild.durationString
    ])

    notifier.discordNotifier([
        projectZoneName    : config.projectZoneName,
        projectName        : config.projectName,
        deployEnv          : config.deployEnv,
        imageTag           : imageTag,
        pipelineMode       : config.pipelineMode ?: 'build-deploy-all',
        status             : status,
        errorLogs          : errorLogs,
        webhookCredentialId: config.credentials?.discord ?: 'DISCORD_WEBHOOK_URL_TEST',
        syncSkipped        : syncSkipped
    ])
}