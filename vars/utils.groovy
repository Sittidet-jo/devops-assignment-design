// vars/utils.groovy
def call(Map config = [:]) {
  echo 'Running generic utility function...'
}

def gitCheckout(Map config) {
  def url   = config.url ?: error('url is required for gitCheckout')
  def cred  = (config.credentialsId ?: '')
  def branch = (config.branch ?: 'main')
  def depth = (config.depth ?: 0) as int
  def to    = (config.timeoutMinutes ?: 20) as int

  echo "Checking out source code from ${url} on branch ${branch}..."
  sh 'git config --global http.sslVerify false'

  if (depth > 0) {
    git url: url,
            branch: branch,
            credentialsId: cred,
            changelog: false,
            poll: false,
            extensions: [
                [$class: 'CloneOption', depth: depth, noTags: false, reference: '', shallow: true, timeout: to]
            ]
    } else {
    git url: url,
            branch: branch,
            credentialsId: cred,
            changelog: false,
            poll: false,
            extensions: [
                [$class: 'CloneOption', shallow: false, timeout: to]
            ]
  }
}

def cleanupBaseImage(Map args = [:]) {
  def di = args.dockerInfo ?: [
    localImage            : args.localImage,
    gitlabRegistryImage   : args.gitlabRegistryImage,
    internalRegistryImage : args.internalRegistryImage
  ]

  def pruneHours = args.pruneHours ?: 24

  def imagesToRemove = [di.localImage, di.gitlabRegistryImage, di.internalRegistryImage, di.cacheImage]
                        .findAll { it && it.trim() }
                        .unique()

  echo "🧹 [Cleanup] Removing specific images: ${imagesToRemove}"
  
  sh """
    set -e
    docker container prune -f || true
    ${imagesToRemove.collect { "docker rmi -f ${it} || true" }.join('\n')}
    docker image prune -f || true
    # docker builder prune -f --filter "until=${pruneHours}h" || true
  """
}

def cleanupWorkspace(Map config = [:]) {
  def keepCaches    = (config.keepCaches ?: []) as List
  def extraExcludes = (config.extraExcludes ?: []) as List
  def aggressive    = (config.aggressive in [true, 'true', 'True', 'YES', 'Yes'])

  if (aggressive) {
    echo '[cleanupWorkspace] aggressive=true -> cleanWs ทั้งหมด'
    cleanWs()
    return
  }

  def cachePatterns = [
    docker: ['.docker-cache/**'],
    yarn  : ['.cache/yarn/**',
             '**/.yarn/cache/**'],
    npm   : ['.npm/**',
             '.cache/npm/**'],
    pnpm  : ['.pnpm-store/**',
             'node_modules/.pnpm/**'],
    pip   : ['.cache/pip/**'],
    venv  : ['.venv-*/**', '.venv/**'],
    node_modules: ['node_modules/**']
  ]

  def excludes = []
  keepCaches.unique().each { key ->
    excludes += (cachePatterns[key] ?: [])
  }
  excludes += extraExcludes

  if (excludes.isEmpty()) {
    echo '[cleanupWorkspace] no excludes specified -> cleanWs() normally'
    cleanWs()
  } else {
    echo "[cleanupWorkspace] keepCaches=${keepCaches} extraExcludes=${extraExcludes}"
    cleanWs patterns: excludes.collect { [pattern: it, type: 'EXCLUDE'] }
  }
}

def checkTooling(Map config = [:]) {
  def strict = config.strict in [null, true, 'true', 'True']
  def checkDocker = config.checkDockerSocket in [null, true, 'true', 'True']

  def scriptContent = libraryResource 'scripts/check_tooling.sh'
  writeFile file: 'check_tooling.sh', text: scriptContent

  try {
    withEnv([
        "STRICT=${strict}",
        "CHECK_DOCKER=${checkDocker}"
    ]) {
        sh 'chmod +x check_tooling.sh && ./check_tooling.sh'
    }
    } finally {
    sh 'rm -f check_tooling.sh'
  }
}
