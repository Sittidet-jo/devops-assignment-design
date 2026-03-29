package org.devops.kustomize

class Utils implements Serializable {

  def steps
  Utils(steps) { this.steps = steps }

  String ensureDotGit(String url) {
    if (!url) return url
    return url.endsWith('.git') ? url : (url + '.git')
  }

  void write(String path, String content) {
    steps.writeFile file: path, text: content
  }

  String tmpl(String pathInResources, Map vars = [:]) {
    def raw = steps.libraryResource(pathInResources)
    return substitute(raw, vars)
  }

  String substitute(String text, Map vars) {
    String out = text
    vars.each { k, v ->
      // replace {{key}} occurrences
      out = out.replaceAll("\\{\\{\\s*${java.util.regex.Pattern.quote(k)}\\s*\\}\\}", java.util.regex.Matcher.quoteReplacement("${v}"))
    }
    return out
  }

  /**
   * Safe Git Push with Retry & Rebase Logic (Local config only)
   */
  void gitCommitAndPush(branch, message) {
    steps.sh """
      set -e
      git config user.name "Jenkins CI Bot"
      git config user.email "ci-bot@example.com"
      git add .

      if ! git diff --cached --quiet; then
        git commit -m "${message}"
        
        MAX_RETRIES=10
        count=0
        success=false
        
        while [ \$count -lt \$MAX_RETRIES ]; do
          # Try to pull with rebase first to avoid conflicts
          if git ls-remote --exit-code --heads origin ${branch} > /dev/null 2>&1; then
            git pull origin ${branch} --rebase || true
          fi

          if git push origin ${branch}; then
            success=true
            break
          fi

          echo "[Git] Push failed, retrying in 3s... (\$((count+1))/\$MAX_RETRIES)"
          sleep 3
          count=\$((count + 1))
        done

        if [ "\$success" = "false" ]; then
           echo "[Git] ERROR: Failed to push to ${branch} after \$MAX_RETRIES attempts."
           exit 1
        fi
      else
        echo "[Git] No changes to commit."
      fi
    """
  }
}
