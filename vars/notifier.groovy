// vars/notifier.groovy

def call(Map config) {
    def val = { k, fb = '-' ->
        def x = config[k]
        (x != null && x.toString().trim()) ? x : fb
    }

    def projectZoneName = val('projectZoneName', 'Unknown Project')
    def projectName     = val('projectName', 'Unknown Service')
    def imageTag        = val('imageTag')
    def fullImageUrl    = val('fullImageUrl')
    def deployRegistry  = val('deployRegistry')
    def deployEnv       = (config.deployEnv ?: config.imageEnv ?: '-')
    def pipelineMode    = val('pipelineMode')
    def sourceRepo      = val('sourceRepo')
    def manifestRepo    = val('manifestRepo')
    def manifestBranch  = val('manifestBranch')
    def argocdApp       = val('argocdApp')
    def autoSync        = val('autoSync')
    def forceRebuild    = val('forceRebuild')
    def status          = (config.status ?: 'UNKNOWN').toString().trim()
    def errorMessage    = val('errorMessage')
    def buildUrl        = val('buildUrl')
    def jobName         = val('jobName')
    def duration        = val('duration')

    def internalRegistry   = val('internalRegistry')
    def gitlabRegistryHost = val('gitlabRegistryHost')
    def gitlabImagePath    = val('gitlabImagePath')

    def computedImage = '-'
    if (fullImageUrl != '-') {
        computedImage = fullImageUrl
    } else if (deployRegistry == 'gitlab' && gitlabRegistryHost != '-' && gitlabImagePath != '-') {
        computedImage = "${gitlabRegistryHost}/${gitlabImagePath}/${deployEnv}:${imageTag}"
    } else if (internalRegistry != '-' && projectName != '-') {
        computedImage = "${internalRegistry}/${projectName}/${deployEnv}:${imageTag}"
    }

    // ── Deploy / Build flags ───────────────────────────────────────────────────
    def DEPLOY_MODES = [
        'full',
        'build-deploy-all',      'build-deploy-app',      'build-deploy-cronjob',
        'deploy-all',            'deploy-app',             'deploy-cronjob'
    ]
    def BUILD_MODES = [
        'full',
        'build-only', 'build-deploy-all', 'build-deploy-app', 'build-deploy-cronjob'
    ]

    def didDeploy = pipelineMode in DEPLOY_MODES
    def didBuild  = pipelineMode in BUILD_MODES

    def modeLabel = didBuild && !didDeploy ? 'BUILD' : 'DEPLOYMENT'

    def titleMap = [
        SUCCESS             : "${modeLabel} SUCCESS ✅",
        FAILURE             : "${modeLabel} FAILED ❌",
        UNSTABLE            : 'PIPELINE UNSTABLE ⚠️',
        ABORTED             : 'PIPELINE ABORTED 🛑',
        NOT_BUILT           : 'PIPELINE SKIPPED ⏭️',
        SUCCESS_ABORTED     : "${modeLabel} SUCCESS ✅ (Pipeline Aborted 🛑)",
        SUCCESS_FAILED_POST : "${modeLabel} SUCCESS ✅ (Post-deploy Failed ❌)"
    ]

    def DEPLOY_CRON_MODES = [
        'full',
        'build-deploy-all',      'build-deploy-cronjob',
        'deploy-all',            'deploy-cronjob'
    ]
    def DEPLOY_APP_MODES = [
        'full',
        'build-deploy-all',      'build-deploy-app',
        'deploy-all',            'deploy-app'
    ]
    def deployedApp  = pipelineMode in DEPLOY_APP_MODES
    def deployedCron = pipelineMode in DEPLOY_CRON_MODES

    def deployedWhat = deployedApp && deployedCron ? 'app + cronjob'
                     : deployedApp                 ? 'app'
                     : deployedCron                ? 'cronjob'
                     : ''

    def tailMap = [
        SUCCESS             : didDeploy
                                ? "Deployed ${deployedWhat} successfully!"
                                : "${pipelineMode} completed — no deployment performed.",
        FAILURE             : 'Pipeline failed — please check logs.',
        UNSTABLE            : 'Quality gate or tests are unstable.',
        ABORTED             : 'Pipeline was aborted.',
        NOT_BUILT           : 'No build was performed.',
        SUCCESS_ABORTED     : 'Pipeline aborted mid-way after successful stage.',
        SUCCESS_FAILED_POST : 'Pipeline failed in post-deployment step.'
    ]

    def title = titleMap.get(status, 'PIPELINE STATUS ℹ️')
    def tail  = tailMap.get(status, "Pipeline finished with status: ${status}")

    echo """
${title}
---------------------------------------------
Project / Zone   : ${projectZoneName}
Service Name     : ${projectName}
Pipeline Mode    : ${pipelineMode}
Environment      : ${didDeploy ? deployEnv : 'N/A (no deploy)'}
Deploy Registry  : ${didDeploy ? deployRegistry : 'N/A'}
Version          : ${didBuild ? imageTag : 'N/A'}
Image Used       : ${didBuild ? computedImage : 'N/A'}
Force Rebuild    : ${forceRebuild == 'Yes' ? 'Yes (no-cache)' : 'No (cache)'}
Source Repo      : ${sourceRepo}
Manifest Repo    : ${didDeploy ? manifestRepo : 'N/A'}
Manifest Ref     : ${didDeploy ? manifestBranch : 'N/A'}
ArgoCD App       : ${didDeploy ? argocdApp : 'N/A'}
Auto Sync        : ${didDeploy ? autoSync : 'N/A'}
---------------------------------------------
Job              : ${jobName}
Build URL        : ${buildUrl}
Duration         : ${duration}
Status           : ${status}
${errorMessage != '-' ? "Error            : ${errorMessage}\n" : ''}
${tail}
"""
}

// ── Extract error lines ที่สำคัญออกจาก raw log ──────────────────────────────
@NonCPS
static String extractKeyErrorLines(String rawLog) {
    if (!rawLog) return ''

    def lines = rawLog.split('\n').toList()

    def errorPatterns = [
        /(?i)ERROR:/,
        /(?i)FATAL/,
        /(?i)Exception:/,
        /(?i)failed to/,
        /(?i)server misbehaving/,
        /(?i)dial tcp/,
        /(?i)exit code [^0]/,
        /(?i)No such file/,
        /(?i)Permission denied/,
        /(?i)Cannot connect/,
        /(?i)refused/,
        /(?i)timed? ?out/,
        /(?i)Stage ".+" skipped due to earlier failure/,
    ]

    def errorLines = []
    lines.each { line ->
        def cleanLine = line.replaceAll(/^\[[\d\-T:\.Z]+\]\s*/, '').trim()
        if (!cleanLine) return
        if (errorLines.size() >= 8) return

        for (def pattern : errorPatterns) {
            if (cleanLine =~ pattern) {
                errorLines << cleanLine
                break
            }
        }
    }

    if (!errorLines) {
        def tail = lines.takeRight(10).collect { it.replaceAll(/^\[[\d\-T:\.Z]+\]\s*/, '').trim() }.findAll { it }
        return tail.join('\n')
    }

    def result = errorLines.join('\n')
    if (result.length() > 900) {
        result = result.substring(0, 880) + '\n...(truncated)'
    }
    return result
}

def discordNotifier(Map params) {
    def credentialId = params.webhookCredentialId ?: 'DISCORD_WEBHOOK_URL_TEST'

    try {
        withCredentials([string(credentialsId: credentialId, variable: '_DISCORD_URL_CHECK')]) {
            if (!env._DISCORD_URL_CHECK) {
                echo "[Notifier] ⚠️ Discord webhook URL is empty — skipping."
                return
            }
        }
    } catch (Exception e) {
        echo "[Notifier] ⚠️ Discord credential '${credentialId}' not found — skipping."
        return
    }

    def projectZoneName = params.projectZoneName ?: 'Unknown Project'
    def projectName     = params.projectName     ?: 'Unknown Service'
    def deployEnv       = params.deployEnv       ?: 'unknown'
    def imageTag        = params.imageTag         ?: 'latest'
    def pipelineMode    = params.pipelineMode     ?: 'build-deploy-all'
    def status          = params.status           ?: 'SUCCESS'
    def errorLogs       = params.errorLogs        ?: ''

    // ── Deploy / Build / Scan flags ──────────────────────────────────────────
    def DEPLOY_MODES = [
        'full',
        'build-deploy-all',      'build-deploy-app',      'build-deploy-cronjob',
        'deploy-all',            'deploy-app',             'deploy-cronjob'
    ]
    def BUILD_MODES = [
        'full',
        'build-only', 'build-deploy-all', 'build-deploy-app', 'build-deploy-cronjob'
    ]

    def didDeploy = pipelineMode in DEPLOY_MODES
    def didBuild  = pipelineMode in BUILD_MODES

    // ── Suppress logic ────────────────────────────────────────────────────────
    if (status == 'NOT_BUILT') {
        echo "⏭️ NOT_BUILT — suppressing Discord."
        return
    }

    // suppress SUCCESS ถ้าเป็น scan/build only (ไม่ deploy) และไม่ใช่ failure
    if (status == 'SUCCESS' && !didDeploy) {
        echo "⏭️ mode=${pipelineMode} SUCCESS (no deploy) — suppressing Discord."
        return
    }

    if (status == 'SUCCESS' && didDeploy) {
        def isManual = false
        try { isManual = currentBuild.rawBuild.getCauses().any { it.class.toString().contains('UserIdCause') } } catch (ignore) {}

        def gitMsg = '-'
        try { gitMsg = sh(script: 'git log --grep="\\[skip ci\\]" --invert-grep -1 --pretty=%B | head -n 1', returnStdout: true).trim() } catch (ignore) {}

        def isImportantCommit = gitMsg.matches("^(?i)(feat|fix|perf|release|hotfix|revert)(\\(.*\\))?:.*")
        if (!isManual && !isImportantCommit) {
            echo "⏭️ SUCCESS minor commit '${gitMsg}' non-manual — suppressing Discord."
            return
        }
    }

    // ── modeLabel ─────────────────────────────────────────────────────────────
    def modeLabel = didBuild && !didDeploy ? 'BUILD' : 'DEPLOYMENT'

    def syncSkipped = params.syncSkipped ?: false

    def colorCode = "15158332"
    def titleIcon = "🚨 ${modeLabel} FAILED / DEGRADED"
    def desc      = "Pipeline failed during ${modeLabel.toLowerCase()} — check Jenkins logs."

    def envLabel = "`${deployEnv.toUpperCase()}`"

    if (syncSkipped && status == 'SUCCESS') {
        colorCode = "9807270"
        titleIcon = "⏭️ DEPLOYMENT SKIPPED"
        desc      = "Sync was declined via Discord — deployment and DAST were skipped. Image was built and pushed but not deployed."
    } else if (status == 'SUCCESS') {
        colorCode = "3066993"
        titleIcon = "✅ ${modeLabel} SUCCESS!"

        def DCRON = ['full','build-deploy-all','build-deploy-cronjob','deploy-all','deploy-cronjob']
        def DAPP  = ['full','build-deploy-all','build-deploy-app','deploy-all','deploy-app']
        def dApp  = pipelineMode in DAPP
        def dCron = pipelineMode in DCRON
        def what  = dApp && dCron ? 'app + cronjob' : dApp ? 'app' : dCron ? 'cronjob' : ''

        if (didDeploy && didBuild) {
            desc = "Built and deployed **${what}** to ${envLabel} successfully."
        } else if (didDeploy) {
            desc = "Deployed **${what}** to ${envLabel} using image tag `${imageTag}`."
        } else if (didBuild) {
            desc = "Docker image built and pushed to registry — no deployment performed."
        }
    } else if (status == 'SUCCESS_ABORTED') {
        colorCode = "3066993"
        titleIcon = "✅ ${modeLabel} SUCCESS 🛑 (Pipeline Aborted)"
        def ranWhat = didBuild ? 'Build' : 'Stage'
        desc = "${ranWhat} completed successfully but pipeline was manually aborted before finishing."
    } else if (status == 'SUCCESS_FAILED_POST') {
        colorCode = "16766720"
        titleIcon = "✅ ${modeLabel} SUCCESS ❌ (Post-step Failed)"
        desc = didDeploy
            ? "**${deployedWhat}** deployed to ${envLabel} and healthy, but a post-deployment step failed (e.g. cleanup)."
            : "Pipeline completed but a post-processing step failed — check Jenkins logs."
    } else if (status == 'UNSTABLE') {
        colorCode = "16766720"
        titleIcon = "⚠️ PIPELINE UNSTABLE"
        if (didDeploy) {
            desc = "Pipeline unstable — deployment to ${envLabel} proceeded."
        } else {
            desc = "Pipeline unstable — check build logs."
        }
    } else if (status == 'ABORTED') {
        colorCode = "9807270"
        titleIcon = "🛑 PIPELINE ABORTED"
        if (didDeploy) {
            desc = "Pipeline aborted during or after deployment to ${envLabel}."
        } else if (didBuild) {
            desc = "Pipeline aborted during the build stage — image may not have been pushed."
        } else {
            desc = "Pipeline was manually aborted."
        }
    } else if (status == 'FAILURE') {
        if (didBuild && didDeploy) {
            desc = "Build or deployment failed — Docker push or ArgoCD sync error on ${envLabel}."
        } else if (didBuild) {
            desc = "Docker image build or push failed — check build logs for errors."
        } else if (didDeploy) {
            desc = "Deployment failed — ArgoCD sync or health check error on ${envLabel}."
        }
    }

    // ── Git info ──────────────────────────────────────────────────────────────
    def gitCommit = '-'
    def gitMsg    = '-'
    try { gitCommit = sh(script: 'git log --grep="\\[skip ci\\]" --invert-grep -1 --format="%h"', returnStdout: true).trim() } catch (ignore) {}
    try { gitMsg    = sh(script: 'git log --grep="\\[skip ci\\]" --invert-grep -1 --pretty=%B | head -n 1', returnStdout: true).trim() } catch (ignore) {}

    // ── Links ─────────────────────────────────────────────────────────────────
    def duration    = currentBuild.durationString?.replace(' and counting', '') ?: 'N/A'
    def jenkinsLink = "[Jenkins](${env.BUILD_URL})"
    def actionLinks = jenkinsLink

    if (didDeploy) {
        def argoHost = (env.ARGOCD_HOST_URL ?: 'http://argocd.example.com').trim()
        if (!argoHost.startsWith('http')) argoHost = "http://${argoHost}"

        def DEPLOY_APP_MODES = [
            'full', 'build-deploy-all', 'build-deploy-app', 'deploy-all', 'deploy-app'
        ]
        def DEPLOY_CRON_MODES = [
            'full', 'build-deploy-all', 'build-deploy-cronjob', 'deploy-all', 'deploy-cronjob'
        ]

        if (pipelineMode in DEPLOY_APP_MODES) {
            actionLinks += " | [ArgoCD App](${argoHost}/applications/${projectName}-app-${deployEnv})"
        }
        if (pipelineMode in DEPLOY_CRON_MODES) {
            actionLinks += " | [ArgoCD CronJob](${argoHost}/applications/${projectName}-cronjob-${deployEnv})"
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────
    def fields = []
    fields << [ name: "Project / Zone",  value: "`${projectZoneName}`",        inline: true  ]
    fields << [ name: "Service",         value: "`${projectName}`",             inline: true  ]
    fields << [ name: "Pipeline Mode",   value: "`${pipelineMode}`",            inline: true  ]

    if (didDeploy) {
        fields << [ name: "Environment", value: "`${deployEnv.toUpperCase()}`", inline: true  ]
    }
    if (didBuild || pipelineMode in ['deploy-all', 'deploy-app', 'deploy-cronjob']) {
        fields << [ name: "Version",     value: "`${imageTag}`",                inline: true  ]
    }

    fields << [ name: "Commit",          value: "`${gitCommit}`",               inline: true  ]
    fields << [ name: "Duration",        value: "`${duration}`",                inline: true  ]
    fields << [ name: "Message",         value: "`${gitMsg}`",                  inline: false ]
    fields << [ name: "Links",           value: actionLinks,                     inline: false ]

    if (status != 'SUCCESS' && errorLogs) {
        def cleanLogs = extractKeyErrorLines(errorLogs)
        if (cleanLogs) {
            fields << [ name: "Logs / Context", value: "```text\n${cleanLogs}\n```", inline: false ]
        }
    }

    // ── Build payload ─────────────────────────────────────────────────────────
    def payloadMap = [
        embeds: [[
            title      : titleIcon,
            description: desc,
            color      : colorCode.toInteger(),
            fields     : fields,
            footer     : [ text: "Jenkins & ArgoCD | mode: ${pipelineMode}" ],
            timestamp  : java.time.Instant.now().toString()
        ]]
    ]

    writeFile file: 'discord-payload.json', text: groovy.json.JsonOutput.toJson(payloadMap)

    withCredentials([string(credentialsId: credentialId, variable: 'SECURE_DISCORD_URL')]) {
        sh '''
            curl -s -H "Content-Type: application/json" \
                 -X POST \
                 -d @discord-payload.json \
                 "$SECURE_DISCORD_URL"
        '''
    }
}

def sendSyncPromptToDiscord(Map params) {
    def jobName     = params.jobName     ?: env.JOB_NAME     ?: 'unknown'
    def buildNumber = params.buildNumber ?: env.BUILD_NUMBER ?: '?'
    def buildUrl    = params.buildUrl    ?: env.BUILD_URL    ?: ''
    def projectName = params.projectName ?: 'unknown'
    def deployEnv   = params.deployEnv   ?: 'unknown'

    def payload = groovy.json.JsonOutput.toJson([
        job      : jobName,
        build    : buildNumber,
        service  : projectName,
        env      : deployEnv,
        build_url: buildUrl,
    ])
    def botBase = (env.JENKINS_HOST_URL ?: 'http://localhost').replaceAll(':[0-9]+$', '').replaceAll('/+$', '')
    writeFile file: 'discord-sync-prompt.json', text: payload
    sh """
        curl -s -X POST ${botBase}:5000/send-sync-prompt \
             -H "Content-Type: application/json" \
             -d @discord-sync-prompt.json
    """
    echo "[Notifier] ✅ Sync prompt sent to Discord bot at ${botBase}:5000."
}

// ══════════════════════════════════════════════════════════════════════════════
// sendSyncResultToDiscord — follow-up embed after input resolves (proceed/skip/timeout)
// ══════════════════════════════════════════════════════════════════════════════
def sendSyncResultToDiscord(Map params) {
    def credentialId   = params.webhookCredentialId ?: 'DISCORD_WEBHOOK_URL_TEST'
    def projectName    = params.projectName   ?: 'unknown'
    def deployEnv      = params.deployEnv     ?: 'unknown'
    def imageTag       = params.imageTag      ?: 'unknown'
    def result         = params.result        ?: 'skip'    // 'proceed' | 'skip' | 'timeout'
    def argoSyncStatus = params.argoSyncStatus ?: 'N/A'

    try {
        withCredentials([string(credentialsId: credentialId, variable: '_DISCORD_URL_CHECK')]) {
            if (!env._DISCORD_URL_CHECK) {
                echo '[Notifier] ⚠️ Discord webhook URL is empty — skipping sync result notification.'
                return
            }
        }
    } catch (Exception e) {
        echo "[Notifier] ⚠️ Discord credential '${credentialId}' not found — skipping sync result notification."
        return
    }

    def title, color, description
    def fields = [
        [ name: 'Service',     value: "`${projectName}`",              inline: true ],
        [ name: 'Environment', value: "`${deployEnv.toUpperCase()}`",  inline: true ],
        [ name: 'Image Tag',   value: "`${imageTag}`",                 inline: true ],
    ]

    switch (result) {
        case 'proceed':
            title       = '✅ Sync Completed'
            color       = 3066993   // green
            description = 'ArgoCD sync was approved and completed successfully.'
            fields << [ name: 'ArgoCD Sync Status', value: "`${argoSyncStatus}`", inline: true ]
            break
        case 'timeout':
            title       = '⏰ Sync Timed Out'
            color       = 16776960  // yellow
            description = 'No response after 30 minutes — deployment was skipped.'
            break
        default: // 'skip'
            title       = '⏭️ Sync Skipped'
            color       = 9807270   // gray
            description = "Manifests updated but not deployed. Image `${imageTag}` is ready in registry."
            break
    }

    def payloadMap = [
        embeds: [[
            title      : title,
            description: description,
            color      : color,
            fields     : fields,
            timestamp  : java.time.Instant.now().toString()
        ]]
    ]

    writeFile file: 'discord-sync-result.json', text: groovy.json.JsonOutput.toJson(payloadMap)
    withCredentials([string(credentialsId: credentialId, variable: 'SECURE_DISCORD_URL')]) {
        sh '''
            curl -s -H "Content-Type: application/json" \
                 -X POST \
                 -d @discord-sync-result.json \
                 "$SECURE_DISCORD_URL"
        '''
    }
    echo "[Notifier] ✅ Sync result (${result}) sent to Discord."
}

