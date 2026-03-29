import org.devops.CredentialConstants

// ══════════════════════════════════════════════════════════════════════════════
// ArgoCD — Register Repo + Create/Update App + Sync
// ══════════════════════════════════════════════════════════════════════════════

def call(Map config = [:]) {
    def deployEnv = config.deployEnv?.toString()?.trim()
    if (!deployEnv || deployEnv == 'Default') {
        error("[ArgoCD] deployEnv is '${deployEnv}' — ConfigParser must resolve it before calling argocd.call()")
    }

    def argoCredId     = config.argocdCredentialsId ?: CredentialConstants.ARGOCD
    def gitlabCredId   = config.gitlabCredentialsId ?: CredentialConstants.GITLAB_DEPLOY
    def repoUrl        = config.manifestRepoUrl
    def manifestBranch = config.manifestBranch      ?: "${config.projectName}-manifest"
    def namespace      = config.namespace           ?: 'default'
    def autoSync       = config.autoSync             == 'Yes'
    def skipSync       = config.skipSync            == true
    def isCanary       = (config.deployStrategy == 'canary' || config.rollout?.enabled == true)
    def argoHost       = (env.ARGOCD_HOST_URL ?: 'argocd.example.com')
                           .replace('https://', '').replace('http://', '')

    if (!repoUrl) {
        error("[ArgoCD] manifestRepoUrl is required but was not provided.")
    }

    // ── กำหนด apps ที่ต้อง sync ตาม mode ─────────────────────────────────────
    // doDeploy=true  → sync api app
    // deployCronJobs=Yes หรือ doCronOnly=true → sync cronjob app
    boolean syncApi    = config.doDeploy == true
    boolean syncCron   = (config.deployCronJobs == 'Yes') || (config.doCronOnly == true)

    def apps = []

    if (syncApi) {
        apps << [
            name: "${config.projectName}-app-${deployEnv}",
            path: "overlays/${deployEnv}/app",
        ]
    }
    if (syncCron) {
        apps << [
            name: "${config.projectName}-cronjob-${deployEnv}",
            path: "overlays/${deployEnv}/cronjob",
        ]
    }

    if (apps.isEmpty()) {
        error("[ArgoCD] ไม่มี app ที่ต้อง sync — ตรวจสอบ doDeploy และ deployCronJobs")
    }

    withCredentials([
        usernamePassword(credentialsId: argoCredId,   usernameVariable: 'ARGO_USER',   passwordVariable: 'ARGO_PASS'),
        usernamePassword(credentialsId: gitlabCredId, usernameVariable: 'GITLAB_USER', passwordVariable: 'GITLAB_PASS')
    ]) {
        // ── 1. Login ────────────────────────────────────────────────────────
        sh """
            argocd login ${argoHost} \\
                --username \$ARGO_USER \\
                --password \$ARGO_PASS \\
                --insecure \\
                --grpc-web
        """

        // ── 2. Register Repo  ──────────────────
        echo "[ArgoCD] Registering repo: ${repoUrl}"
        sh """
            argocd repo add ${repoUrl} \\
                --username \$GITLAB_USER \\
                --password \$GITLAB_PASS \\
                --upsert \\
                --insecure-skip-server-verification
        """

        // ── 3. Create / Update / Sync  ────────────────────────────
        def syncPolicy = autoSync
            ? "--sync-policy automated --auto-prune --self-heal"
            : "--sync-policy none"

        apps.each { app ->
            echo "[ArgoCD] Creating/updating app: ${app.name} (path: ${app.path})"
            sh """
                argocd app create ${app.name} \\
                    --repo ${repoUrl} \\
                    --path ${app.path} \\
                    --dest-server https://kubernetes.default.svc \\
                    --dest-namespace ${namespace} \\
                    --revision ${manifestBranch} \\
                    --project default \\
                    ${syncPolicy} \\
                    --upsert
            """

            if (skipSync) {
                echo "[ArgoCD] ⏭️ ${app.name} — sync deferred (autoSync=false)."
            } else {
                echo "[ArgoCD] Syncing app: ${app.name}"
                sh "argocd app sync ${app.name} --timeout 120 || true"

                if (isCanary && app.name.contains('-app-')) {
                    echo "[ArgoCD] Canary — waiting for sync only (will pause at 0%)..."
                    sh """
                        argocd app wait ${app.name} \\
                            --timeout 180 \\
                            --sync
                    """
                    echo "[ArgoCD] ✅ ${app.name} synced. Canary paused at 0% — DAST will resume."
                } else {
                    sh """
                        argocd app wait ${app.name} \\
                            --timeout 180 \\
                            --health \\
                            --sync
                    """
                    echo "[ArgoCD] ✅ ${app.name} synced and healthy."
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// syncApp — on-demand sync for a single app (used by DAST stage when autoSync=false)
// ══════════════════════════════════════════════════════════════════════════════
def syncApp(Map config = [:]) {
    def appName  = config.appName  ?: error('[ArgoCD] syncApp: appName is required')
    def credId   = config.credentialsId ?: CredentialConstants.ARGOCD
    def argoHost = (env.ARGOCD_HOST_URL ?: 'argocd.example.com')
                     .replace('https://', '').replace('http://', '')

    withCredentials([usernamePassword(
        credentialsId: credId,
        usernameVariable: 'ARGO_USER',
        passwordVariable: 'ARGO_PASS'
    )]) {
        sh """
            argocd login ${argoHost} \\
                --username \$ARGO_USER \\
                --password \$ARGO_PASS \\
                --insecure \\
                --grpc-web

            argocd app sync ${appName} --timeout 120

            argocd app wait ${appName} \\
                --timeout 180 \\
                --sync
        """
    }
    echo "[ArgoCD] ✅ ${appName} synced on-demand. Canary paused at 0% — DAST will resume."
}

// ══════════════════════════════════════════════════════════════════════════════
// actionRollout — Rollouts (canary)
// ══════════════════════════════════════════════════════════════════════════════
def actionRollout(Map config = [:]) {
    def appName  = config.appName
    def action   = config.action   // "resume" | "abort" | "retry"
    def credId   = config.credentialsId ?: CredentialConstants.ARGOCD
    def argoHost = (env.ARGOCD_HOST_URL ?: 'argocd.example.com')
                     .replace('https://', '').replace('http://', '')

    withCredentials([usernamePassword(
        credentialsId: credId,
        usernameVariable: 'ARGO_USER',
        passwordVariable: 'ARGO_PASS'
    )]) {
        sh """
            argocd login ${argoHost} \\
                --username \$ARGO_USER \\
                --password \$ARGO_PASS \\
                --insecure \\
                --grpc-web

            argocd app actions run ${appName} ${action} \\
                --kind Rollout \\
                --grpc-web
        """
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// getAppSyncStatus — fetch .status.sync.status from ArgoCD for a given app
// ══════════════════════════════════════════════════════════════════════════════
def getAppSyncStatus(Map config = [:]) {
    def appName  = config.appName  ?: error('[ArgoCD] getAppSyncStatus: appName is required')
    def credId   = config.credentialsId ?: CredentialConstants.ARGOCD
    def argoHost = (env.ARGOCD_HOST_URL ?: 'argocd.example.com')
                     .replace('https://', '').replace('http://', '')

    def syncStatus = 'Unknown'
    withCredentials([usernamePassword(
        credentialsId: credId,
        usernameVariable: 'ARGO_USER',
        passwordVariable: 'ARGO_PASS'
    )]) {
        syncStatus = sh(
            script: """
                argocd login ${argoHost} \\
                    --username \$ARGO_USER \\
                    --password \$ARGO_PASS \\
                    --insecure \\
                    --grpc-web > /dev/null 2>&1

                argocd app get ${appName} --output json | \\
                    python3 -c "import sys,json; print(json.load(sys.stdin)['status']['sync']['status'])"
            """,
            returnStdout: true
        ).trim()
    }
    return syncStatus
}