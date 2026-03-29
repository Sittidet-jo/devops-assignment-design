package org.devops.kustomize

class GitOps implements Serializable {

  def steps
  GitOps(steps=null) { this.steps = steps }

  void cloneAndCheckout(Map config) {
    def utils = new Utils(this.steps)
    def manifestUrl = utils.ensureDotGit(config.manifestRepoUrl)

    steps.dir('manifest-repo') {
      steps.withCredentials([steps.usernamePassword(credentialsId: config.credentialsId, usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASSWORD')]) {
        steps.withEnv([
          "URL=${manifestUrl}",
          "BRANCH=${config.manifestBranch ?: 'main'}"
        ]) {
          steps.sh '''
            set -e
            git config --global http.sslVerify false
            git config --global user.name "Jenkins Bot"
            git config --global user.email "jenkins@example.com"

            if [ -d ".git" ]; then
              git fetch origin
            else
              AUTH_URL="$(printf '%s' "$URL" | sed "s#^https://#https://${GIT_USER}:${GIT_PASSWORD}@#")"
              git clone "$AUTH_URL" .
              git remote set-url origin "$AUTH_URL"
              git fetch origin
            fi

            if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
              git checkout -B "$BRANCH" "origin/$BRANCH"
              git pull --ff-only origin "$BRANCH" || true
            else
              git checkout --orphan "$BRANCH"
              git rm -rf . || true
            fi
          '''
        }

        if (!steps.fileExists('base')) {
          steps.sh 'mkdir -p base overlays/poc overlays/uat overlays/prd'
        }
      }
    }
  }

}
