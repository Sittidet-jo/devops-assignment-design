// vars/dastScan.groovy

@NonCPS
static Map dastGatePolicy() {
    return [
        high_threshold:   0,
        medium_threshold: 0,
        criteria: 'HIGH = 0 (FAIL pipeline), MEDIUM = 0 (WARN/UNSTABLE), LOW = informational only',
    ]
}

@NonCPS
static String evaluateDastGate(Map counts, Map policy) {
    int high   = (counts['3'] ?: 0) as int
    int medium = (counts['2'] ?: 0) as int
    if (high   > (policy.high_threshold   as int)) return 'FAIL'
    if (medium > (policy.medium_threshold as int)) return 'WARN'
    return 'PASS'
}

def call(Map config = [:]) {
    if (!config) { echo '[DAST] No config provided, skip DAST.'; return }

    def policy    = dastGatePolicy()
    def appName   = "${config.projectName}-app-${config.deployEnv}"
    def isRollout = (config.deployStrategy == 'canary' || config.rollout?.enabled == true)

    def envName    = config.deployEnv ?: 'dev'
    def ns         = config.namespace ?: 'default'
    def svcBase    = config.serviceName ?: "${config.projectName}-service"
    def targetPort = config.targetPort ?: config.containerPort ?: 3000

    def rawArgoUrl = config.env?.ARGOCD_HOST_URL ?: env.ARGOCD_HOST_URL ?: '127.0.0.1'
    def argoIp     = rawArgoUrl.replace('https://', '').replace('http://', '').split(':')[0]
    def nodePort   = config.deployment?.nodePort ? config.deployment.nodePort.toInteger() : 0
    def targetUrl  = config.__smoke__?.base

    if (!targetUrl) {
        if (nodePort > 0) {
            targetUrl = isRollout ? "http://${argoIp}:${nodePort + 100}" : "http://${argoIp}:${nodePort}"
        } else {
            targetUrl = isRollout
                ? "http://${svcBase}-canary-${envName}.${ns}:${targetPort}"
                : "http://${svcBase}-${envName}.${ns}:${targetPort}"
        }
    }

    echo "[DAST] Target URL: ${targetUrl}"
    echo "[DAST] Gate Policy: ${policy.criteria}"

    // ── Wait for Health ──
    def argoCredId = config.credentials?.argocd ?: 'ARGOCD_CREDENTIALS'
    def argoHost   = (config.argocdHost ?: env.ARGOCD_HOST_URL ?: 'argocd.example.com')
                      .replace('https://', '').replace('http://', '')

    withCredentials([usernamePassword(credentialsId: argoCredId,
                                      usernameVariable: 'USER', passwordVariable: 'PASS')]) {
        sh "argocd login ${argoHost} --username \$USER --password \$PASS --insecure --grpc-web"
        timeout(time: 10, unit: 'MINUTES') {
            waitUntil(initialRecurrencePeriod: 30000) {
                def status = sh(script: "argocd app get ${appName} -o json | jq -r '.status.health.status'",
                                returnStdout: true).trim()
                echo "[DAST] App status: ${status}"
                return isRollout ? (status == 'Healthy' || status == 'Suspended') : (status == 'Healthy')
            }
        }
    }

    // ── ZAP Scan ──
    def zapTempDir  = "${env.WORKSPACE}/zap"
    def zapFinalDir = "${env.WORKSPACE}/reports/zap"

    sh """
        mkdir -p ${zapTempDir}
        chmod -R 777 ${zapTempDir}
    """

    def reportHtml = 'zap_report.html'
    def reportXml  = 'zap_report.xml'
    def reportJson = 'zap_report.json'

    def scanCmd
    if (config.projectType == 'backend' && config.apiSpec?.enabled) {
        def specPath = config.apiSpec.path ?: 'openapi.json'
        if (fileExists(specPath)) {
            sh "cp ${specPath} ${zapTempDir}/api-spec.json"
            scanCmd = "zap-api-scan.py -t /zap/wrk/api-spec.json -f openapi -T ${targetUrl} -r ${reportHtml} -x ${reportXml} -J ${reportJson}"
        } else {
            echo '[DAST] API Spec not found, using baseline scan'
            scanCmd = "zap-baseline.py -t ${targetUrl} -r ${reportHtml} -x ${reportXml} -J ${reportJson}"
        }
    } else {
        scanCmd = "zap-baseline.py -t ${targetUrl} -r ${reportHtml} -x ${reportXml} -J ${reportJson}"
    }

    echo "[DAST] Running: ${scanCmd}"

    def containerName = "zap-scanner-${env.BUILD_NUMBER}"

    sh """
        docker run --name ${containerName} \\
            -u root \\
            -v ${zapTempDir}:/zap/wrk \\
            -t ghcr.io/zaproxy/zaproxy:stable \\
            ${scanCmd} || echo "\$?" > ${zapTempDir}/zap_exit.txt
    """

    echo '[DAST] Extracting reports from container...'
    sh """
        docker cp ${containerName}:/zap/wrk/${reportJson} ${zapTempDir}/${reportJson} || true
        docker cp ${containerName}:/zap/wrk/${reportXml} ${zapTempDir}/${reportXml} || true
        docker cp ${containerName}:/zap/wrk/${reportHtml} ${zapTempDir}/${reportHtml} || true
    """

    sh "docker rm -f ${containerName} || true"

    echo '[DAST] Moving reports to final directory: reports/zap'
    sh """
        chmod -R 777 ${zapTempDir} || true
        mkdir -p ${env.WORKSPACE}/reports
        rm -rf ${zapFinalDir} || true
        mv ${zapTempDir} ${zapFinalDir}
    """

    def zapExitCode = 0
    if (fileExists("${zapFinalDir}/zap_exit.txt")) {
        def rawExit = readFile("${zapFinalDir}/zap_exit.txt").trim()
        zapExitCode = rawExit.isInteger() ? rawExit.toInteger() : 99
        echo "[DAST][WARN] ZAP finished with Exit Code: ${zapExitCode}"
    }

    if (zapExitCode > 2) {
        echo "[DAST][WARN] ZAP unexpected exit code: ${zapExitCode}"
        currentBuild.result = 'UNSTABLE'
    }

    // ── Evaluate Gate from JSON ──
    def gateResult = 'UNKNOWN'
    if (fileExists("${zapFinalDir}/${reportJson}")) {
        def zapReport = readJSON file: "${zapFinalDir}/${reportJson}"

        def counts = [:]
        (zapReport.site ?: []).each { site ->
            (site.alerts ?: []).each { alert ->
                def rc = alert.riskcode?.toString() ?: '0'
                counts[rc] = (counts[rc] ?: 0) + 1
            }
        }

        gateResult = evaluateDastGate(counts, policy)
        echo "[DAST] Risk counts: HIGH=${counts['3']?:0}, MEDIUM=${counts['2']?:0}, LOW=${counts['1']?:0}, INFO=${counts['0']?:0}"
        echo "[DAST] Gate: ${gateResult}"

        def pyScript = libraryResource('scripts/generate_dast_reports.py')
        writeFile file: '/tmp/generate_dast_reports.py', text: pyScript

        def repoPath = config.gitlab?.sourcePath   ?: 'unknown'
        def branch   = config.gitlab?.sourceBranch ?: 'unknown'
        def repoUrl  = config.gitlab?.baseUrl ? "${config.gitlab.baseUrl}/${repoPath}" : ''

        sh """#!/bin/bash
            export PYENV_ROOT="/opt/pyenv"
            export PATH="\$PYENV_ROOT/bin:\$PYENV_ROOT/shims:\$PATH"
            eval "\$(pyenv init -)"
            export TZ="Asia/Bangkok"

            export PROJECT_NAME="${config.projectName}"
            export DEPLOY_ENV="${config.deployEnv}"
            export REPO_PATH="${repoPath}"
            export BRANCH_NAME="${branch}"
            export REPO_URL="${repoUrl}"
            export FONT_PATH=/tmp

            pip install reportlab python-docx openpyxl -q || true
            python3 /tmp/generate_dast_reports.py \\
                ${zapFinalDir}/${reportJson} \\
                ${zapFinalDir} \\
                "${policy.criteria}"
        """
    } else {
        echo '[DAST][WARN] zap_report.json not found, skipping report generation'
    }

    if (gateResult == 'FAIL') {
        error('[DAST] Security Gate FAILED: HIGH vulnerabilities found. Aborting canary.')
    } else if (gateResult == 'WARN') {
        echo '[DAST][WARN] Security Gate WARNING: MEDIUM vulnerabilities found. Marking UNSTABLE.'
        currentBuild.result = 'UNSTABLE'
    } else {
        echo '[DAST] Security Gate PASSED.'
    }

    // ── Archive ──
    archiveArtifacts artifacts: [
        "reports/zap/${reportHtml}",
        "reports/zap/${reportXml}",
        "reports/zap/${reportJson}",
        "reports/zap/zap-${config.projectName}-${config.deployEnv}-*.pdf",
        "reports/zap/zap-${config.projectName}-${config.deployEnv}-*.docx",
        "reports/zap/zap-${config.projectName}-${config.deployEnv}-*.xlsx",
    ].join(', '), allowEmptyArchive: true

}