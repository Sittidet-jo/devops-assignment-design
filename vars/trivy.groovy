// vars/trivy.groovy
//
// Scans the built Docker image → reports/trivy/image/ (FINAL report)
// FS scan is done in runSecurityScans → reports/trivy/fs/ (intermediate)
//
// Change from original: uses writeReportScripts() to deploy report_utils.py
// alongside the generator so that shared imports resolve correctly.

// ── Gate Policy (single source of truth) ─────────────────────────────────────
@NonCPS
static Map trivyGatePolicy(Map config = [:]) {
    def critThreshold = (config.criticalThreshold != null) ? config.criticalThreshold as int : 0
    def highThreshold = (config.highThreshold     != null) ? config.highThreshold     as int : 5
    return [
        critical_threshold : critThreshold,
        high_threshold     : highThreshold,
        criteria: "Critical ≤ ${critThreshold} (FAIL if exceeded), High ≤ ${highThreshold} (UNSTABLE if exceeded), Medium = informational",
    ]
}

// ── Helper: write shared utils + generator to /tmp ───────────────────────────
def writeReportScripts(String generatorResourcePath) {
    def utilsScript = libraryResource('scripts/report_utils.py')
    writeFile file: '/tmp/report_utils.py', text: utilsScript

    def genScript = libraryResource(generatorResourcePath)
    def destFile   = '/tmp/' + generatorResourcePath.tokenize('/')[-1]
    writeFile file: destFile, text: genScript

    return destFile
}

def call(Map config) {
    def imageName     = config.imageName      ?: error('imageName is required')
    def severity      = config.severity       ?: 'CRITICAL,HIGH,MEDIUM'
    def failOnScan    = (config.failOnScan    != null) ? config.failOnScan    : false
    def ignoreUnfixed = (config.ignoreUnfixed != null) ? config.ignoreUnfixed : true
    def timeout       = config.timeout        ?: '10m'
    def credentialsId = config.credentialsId  ?: 'GITLAB_DEPLOY_TOKEN'

    def policy         = trivyGatePolicy(config)
    def cleanImageName = imageName.trim().replaceAll(/^"|"$/, '')
    def ignoreFlag     = ignoreUnfixed ? '--ignore-unfixed' : ''

    echo "[Trivy Image] Scanning: ${cleanImageName}"
    echo "[Trivy Image] Gate Policy: ${policy.criteria}"
    echo '[Trivy Image] NOTE: This is the FINAL security report. FS report in reports/trivy/fs/ is intermediate.'

    withCredentials([usernamePassword(credentialsId: credentialsId,
                                     usernameVariable: 'TRIVY_USERNAME',
                                     passwordVariable: 'TRIVY_PASSWORD')]) {
        sh """#!/bin/bash
            set -e
            export TRIVY_AUTH_URL=\$(echo "${cleanImageName}" | cut -d'/' -f1)
            export TRIVY_CACHE_DIR=/shared-cache/trivy
            mkdir -p reports/trivy/image

            TRIVY_USERNAME=\$TRIVY_USERNAME TRIVY_PASSWORD=\$TRIVY_PASSWORD \\
            trivy image \\
                --cache-dir /shared-cache/trivy \\
                --skip-db-update \\
                --severity ${severity} \\
                --timeout ${timeout} \\
                ${ignoreFlag} \\
                --no-progress \\
                --format json \\
                -o reports/trivy/image/trivy-image-report.json \\
                "${cleanImageName}"

            trivy convert \\
                --format table \\
                --output reports/trivy/image/trivy-image-report.txt \\
                reports/trivy/image/trivy-image-report.json
        """
                                     }

    if (!fileExists('reports/trivy/image/trivy-image-report.json')) {
        echo '[Trivy Image][WARN] Report not found, skipping gate check'
        return
    }

    def report = readJSON file: 'reports/trivy/image/trivy-image-report.json'
    int critical = 0, high = 0, medium = 0, low = 0

    (report.Results ?: []).each { result ->
        (result.Vulnerabilities ?: []).each { vuln ->
            switch ((vuln.Severity ?: '').toUpperCase()) {
                case 'CRITICAL': critical++; break
                case 'HIGH':     high++;     break
                case 'MEDIUM':   medium++;   break
                case 'LOW':      low++;      break
            }
        }
    }

    echo "[Trivy Image] Summary: CRITICAL=${critical}, HIGH=${high}, MEDIUM=${medium}, LOW=${low}"

    archiveArtifacts artifacts: [
        'reports/trivy/image/trivy-image-report.json',
        'reports/trivy/image/trivy-image-report.txt',
    ].join(', '), allowEmptyArchive: true

    // Write shared utils + generator — only change from original
    def repoPath = config.gitlab?.sourcePath   ?: 'unknown'
    def branch   = config.gitlab?.sourceBranch ?: 'unknown'
    def repoUrl  = config.gitlab?.baseUrl ? "${config.gitlab.baseUrl}/${repoPath}" : ''

    def scriptPath = writeReportScripts('scripts/generate_trivy_reports.py')

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

        # pip install reportlab python-docx openpyxl -q || true (Already in base image)

        python3 ${scriptPath} \\
            reports/trivy/image/trivy-image-report.json \\
            reports/trivy/image \\
            "[IMAGE SCAN - FINAL] ${policy.criteria}"
    """

    archiveArtifacts artifacts: [
        "reports/trivy/image/trivy-${config.projectName}-${config.deployEnv}-*.pdf",
        "reports/trivy/image/trivy-${config.projectName}-${config.deployEnv}-*.docx",
        "reports/trivy/image/trivy-${config.projectName}-${config.deployEnv}-*.xlsx",
    ].join(', '), allowEmptyArchive: true

    boolean gateFail = (critical > policy.critical_threshold) || (high > policy.high_threshold)

    if (gateFail) {
        if (failOnScan) {
            error "[Trivy] Security Gate FAILED: CRITICAL=${critical} (threshold=${policy.critical_threshold}), HIGH=${high} (threshold=${policy.high_threshold})"
        } else {
            echo "[Trivy Image] ⚠️ Security Gate FAILED: CRITICAL=${critical} (threshold≤${policy.critical_threshold}), HIGH=${high} (threshold≤${policy.high_threshold})"
            echo '[Trivy Image] Marking pipeline UNSTABLE (Image scan is FINAL — supersedes FS result)'
            currentBuild.result = 'UNSTABLE'
        }
    } else if (critical > 0 || high > 0) {
        echo '[Trivy Image] ✅ Gate PASSED (within thresholds) — some vulns exist.'
    } else {
        echo '[Trivy Image] ✅ Gate PASSED: No significant vulnerabilities.'
    }

    return gateFail ? 'FAIL' : 'PASS'
}

