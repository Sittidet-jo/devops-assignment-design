// vars/argocdHealthCheck.groovy

def call(Map config, String currentImageTag) {
    def appsToCheck = []

    if (config.doDeploy == true) {
        appsToCheck << [
            name   : "${config.projectName}-app-${config.deployEnv}",
            isBatch: false
        ]
    }
    if (config.deployCronJobs == 'Yes' || config.doCronOnly == true) {
        appsToCheck << [
            name   : "${config.projectName}-cronjob-${config.deployEnv}",
            isBatch: true
        ]
    }

    if (appsToCheck.isEmpty()) {
        echo "⏭️ [HealthCheck] No apps to check for mode: ${config.pipelineMode}"
        return
    }

    def rawHost = env.ARGOCD_HOST_URL ?: 'argocd.example.com'
    def host    = rawHost.replace('https://', '').replace('http://', '')
    def credId  = config.credentials?.argocd ?: 'ARGOCD_CREDENTIALS'

    withCredentials([usernamePassword(
        credentialsId: credId,
        usernameVariable: 'AU',
        passwordVariable: 'AP'
    )]) {
        sh """
            set -e
            argocd login ${host} --username \$AU --password \$AP --insecure --grpc-web
        """

        echo "⏱️ Waiting 10 seconds for Kubernetes to register changes..."
        sleep 10

        appsToCheck.each { app ->
            def appName    = app.name
            def isBatch    = app.isBatch
            def deployStatus = 'SUCCESS'

            echo "⏳ Waiting for ArgoCD App '${appName}'${isBatch ? ' (batch — checking sync only)' : ''}..."

            try {
                if (isBatch) {
                    sh """
                        set -e
                        argocd app wait ${appName} --sync --timeout 120
                    """
                } else {
                    sh """
                        set -e
                        argocd app wait ${appName} --health --timeout 300
                    """
                }
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException abortErr) {
                deployStatus = 'ABORTED'
                echo "🛑 Pipeline was aborted by user."
            } catch (Exception waitErr) {
                deployStatus = 'FAILURE'
                echo "❌ ArgoCD Health Check Failed for '${appName}': ${waitErr.message ?: 'Unknown Error'}"
            }

            if (deployStatus == 'FAILURE') {
                error("Pipeline failed during ArgoCD Health Check for '${appName}'. Please check ArgoCD UI.")
            } else if (deployStatus == 'ABORTED') {
                error("Pipeline was aborted.")
            } else {
                echo "✅ '${appName}' is ${isBatch ? 'Synced' : 'Healthy'}!"
            }
        }
    }

    echo "✅ All ArgoCD apps are ready! [image: ${currentImageTag}]"
}