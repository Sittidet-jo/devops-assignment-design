// src/org/devops/CredentialConstants.groovy

package org.devops

class CredentialConstants implements Serializable {

    // GitLab
    static final String GITLAB_PAT    = 'GITLAB_PAT_CREDENTIALS'
    static final String GITLAB_DEPLOY = 'GITLAB_CREDENTIALS'

    // Environment Secrets
    static final String FRONTEND_ENV  = 'FRONTEND_ENV'

    // ArgoCD
    static final String ARGOCD        = 'ARGOCD_CREDENTIALS'

}