// my-dev-ops-library/vars/versioning.groovy

def createRelease(Map config) {
  def language = (config.language ?: 'javascript').trim()
  def gitlabTokenCredentialId = config.gitlabTokenCredentialId ?: error('gitlabTokenCredentialId is required')
  def branch = (config.branch ?: config.gitlab?.sourceBranch ?: 'main').trim()
  def customVersion = (config.customVersion ?: '').trim()

  def srBranches = (config.srBranches ?: '').trim()
  def srLang = language.toLowerCase()

  echo "Running release process for language='${language}', branch='${branch}'"

  withCredentials([string(credentialsId: gitlabTokenCredentialId, variable: 'GITLAB_TOKEN')]) {
    try {
      withEnv(["GIT_BRANCH_NAME=${branch}"]) {
            sh '''
                set -ex
                REPO_URL="$(git config --get remote.origin.url)"
                BASE_HOST="$(echo "$REPO_URL" | sed -E 's#https?://##; s#/.*##')"
                git config --global url."https://oauth2:$GITLAB_TOKEN@${BASE_HOST}/".insteadOf "https://${BASE_HOST}/"

                git fetch origin "$GIT_BRANCH_NAME" --tags
                git checkout -B "$GIT_BRANCH_NAME" "origin/$GIT_BRANCH_NAME"
            '''
      }

      if (customVersion) {
          def semverPattern = /^\d+\.\d+\.\d+([a-zA-Z][a-zA-Z0-9]*|(-[a-zA-Z0-9]+(\.[a-zA-Z0-9]+)*))?$/
          if (!(customVersion ==~ semverPattern)) {
              error("Invalid version format '${customVersion}' — must be x.x.x (e.g. 1.2.3) or x.x.x-suffix (e.g. 1.2.3-beta.1)")
          }

          def tagName = "v${customVersion}"
          def latestTag = sh(script: 'git describe --tags --abbrev=0 2>/dev/null || true', returnStdout: true).trim()

          def tagExists = sh(script: "git tag -l ${tagName}", returnStdout: true).trim()
          if (tagExists) {
              echo "Tag ${tagName} already exists. Using existing tag."
              return customVersion
          }

          if (latestTag) {
              def latest = latestTag.replaceFirst(/^v/, '')

              def isOlder = sh(
                  script: "printf '%s\n%s\n' '${customVersion}' '${latest}' | sort -V | head -1 | grep -qx '${customVersion}'",
                  returnStatus: true
              ) == 0 && customVersion != latest

              if (isOlder) {
                  echo "⚠️ '${customVersion}' is older than latest '${latestTag}' — building image only, no tag will be created"
                  return customVersion
              }

              try {
                  timeout(time: 5, unit: 'MINUTES') {
                      input(
                          message: """⚠️ About to create a new Git Tag: v${customVersion}
(Current Latest Tag: ${latestTag})

WARNING: Creating this tag will cause the next Semantic Release to bump the version starting from v${customVersion}.

Are you sure you want to proceed?""".stripIndent(),
                          ok: 'Proceed (Create Tag)'
                      )
                  }
              } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                  currentBuild.result = 'ABORTED'
                  error("🛑 Pipeline aborted by user (or Timeout) — tag was not created")
              }
          }

          withEnv(["RELEASE_TAG=${tagName}"]) {
              sh '''
                  git config --global user.name "Jenkins Bot"
                  git config --global user.email "jenkins-bot@inet.co.th"
                  git tag -a "$RELEASE_TAG" -m "release: $RELEASE_TAG (manual override)"
                  git push origin "$RELEASE_TAG"
              '''
          }
          echo "Manual release created: ${tagName}"
          return customVersion
      }

      String prevTag = sh(returnStdout: true, script: 'git describe --tags --abbrev=0 2>/dev/null || true').trim()
      echo "Previous git tag: ${prevTag ?: '(none)'}"

      withEnv([
        "BRANCH_NAME=${branch}",
        "GIT_BRANCH=${branch}",
        "SR_BRANCHES=${srBranches}",
        "SR_LANG=${srLang}",
        "GIT_AUTHOR_NAME=Jenkins Bot",
        "GIT_AUTHOR_EMAIL=jenkins-bot@inet.co.th",
        "GIT_COMMITTER_NAME=Jenkins Bot",
        "GIT_COMMITTER_EMAIL=jenkins-bot@inet.co.th"
      ]) {
        sh '''
          set -ex

          export CI=true
          export GL_TOKEN=$GITLAB_TOKEN
          git config user.name "Jenkins Bot"
          git config user.email "jenkins-bot@inet.co.th"

          REPO_URL="$(git config --get remote.origin.url)"
          BASE_HOST="$(echo "$REPO_URL" | sed -E 's#https?://##; s#/.*##')"

          git config --global url."https://oauth2:$GITLAB_TOKEN@${BASE_HOST}/".insteadOf "https://${BASE_HOST}/"

          echo "Running semantic-release…"
          npx semantic-release --ci --branch "$BRANCH_NAME"
        '''
      }
      String newTag = sh(returnStdout: true, script: 'git describe --tags --abbrev=0 2>/dev/null || true').trim()

      if (!newTag) {
         echo 'No git tags found. Defaulting to 0.0.0'
         return '0.0.0'
      }

      def newVersion = newTag.replaceFirst(/^v/, '')
      return newVersion

    } catch (e) {
      echo 'Release process failed.'
      error(e.toString())
    }
  }
}

def hasReleaseWorthyChanges() {
  def lastTag = sh(script: 'git describe --tags --abbrev=0 2>/dev/null || true', returnStdout: true).trim()
  if (!lastTag) return true

  def logs = sh(script: "git log --format='%s%n%b' ${lastTag}..HEAD", returnStdout: true)
  writeFile file: 'commits_since_last_tag.txt', text: logs

  return [
    "grep -E -i -q '^(feat|fix|perf|revert)(\\([^)]+\\))?(!)?:' commits_since_last_tag.txt",
    "grep -E -i -q '^([a-z]+)(\\([^)]+\\))?!:' commits_since_last_tag.txt",
    "grep -E -i -q '^BREAKING CHANGE(S)?:' commits_since_last_tag.txt",
    "grep -E -q '(^|[[:space:]])release:[[:space:]]v?[0-9]+\\.[0-9]+\\.[0-9]+' commits_since_last_tag.txt"
  ].any { cmd -> sh(script: cmd, returnStatus: true) == 0 }
}
