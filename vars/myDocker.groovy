def call(Map config) {
    echo "Received config in docker.call: ${config}"

    config.projectName = config.projectName ?: error('projectName is required for docker.groovy')
    config.deployEnv   = config.deployEnv   ?: error('deployEnv is required for docker.groovy')

    config.customVersion = config.customVersion ?: ''
    config.forceRebuild  = config.forceRebuild ?: 'No'
    def nodeOptions    = (config.nodeOptions ?: '--max-old-space-size=3072').toString()
    def nextMaxWorkers = (config.nextMaxWorkers ?: '2').toString()
    def imageTag       = config.customVersion ? config.customVersion : "${env.BUILD_NUMBER}"
    def builderName    = (config.builderName ?: 'mybuilder').toString()

    def fr = config.forceRebuild
    boolean force = false
    if (fr instanceof Boolean) {
        force = fr
    } else if (fr != null) {
        force = fr.toString().trim().equalsIgnoreCase('yes') ||
                fr.toString().trim().equalsIgnoreCase('true') ||
                fr.toString().trim() == '1' ||
                fr.toString().trim().equalsIgnoreCase('y')
    }

    def contextDir      = (config.sourcesPath ?: '.')
    def gitlabHost      = (config.gitlabRegistryHost ?: env.GITLAB_REGISTRY_HOST ?: 'skywalker.inet.co.th:5050')
    def gitlabImagePath = (env.GITLAB_IMAGE_PATH ?: config.gitlabImagePath ?: (config.gitlab?.sourcePath) ?: "${config.projectName}")
    def gitlabImage     = "${gitlabHost}/${gitlabImagePath}/${config.deployEnv}:${imageTag}"
    def cacheImage      = "${gitlabHost}/${gitlabImagePath}/${config.deployEnv}:cache"
    def localTag        = "${config.projectName}-${config.deployEnv}:${imageTag}"

    def noCacheFlg   = force ? '--no-cache' : ''
    def cacheFromFlg = force ? '' : "--cache-from=type=registry,ref=${cacheImage}"
    def cacheToFlg   = "--cache-to=type=registry,ref=${cacheImage},mode=max"

    sh 'docker version'

    if (config.envFileCredentialId) {
        echo 'Using .env.production from secret file'
        withCredentials([file(credentialsId: config.envFileCredentialId, variable: 'ENV_FILE')]) {
            sh "cp \$ENV_FILE .env.production"
        }
    }

    echo "Logging in to GitLab Registry: ${gitlabHost}"
    withCredentials([usernamePassword(credentialsId: config.gitlabDeployTokenId, usernameVariable: 'GITLAB_USER', passwordVariable: 'GITLAB_PASSWORD')]) {
        sh "echo \"\$GITLAB_PASSWORD\" | docker login ${gitlabHost} -u \"\$GITLAB_USER\" --password-stdin"
    }

    try {
        if (force) {
            echo "[docker] forceRebuild=Yes -> build from scratch, will update cache after"
        } else {
            echo "[docker] Pulling remote cache from: ${cacheImage}"
        }
        retry(3) {
            sh """#!/bin/bash
                set -e

                docker buildx build ${noCacheFlg} \\
                    --builder ${builderName} \\
                    --provenance=false \\
                    --load \\
                    ${cacheFromFlg} \\
                    ${cacheToFlg} \\
                    --build-arg NODE_OPTIONS='${nodeOptions}' \\
                    --build-arg NEXT_MAX_WORKERS='${nextMaxWorkers}' \\
                    -t ${localTag} \\
                    ${contextDir}
            """
        }
    } finally {
        sh 'rm -f .env.production || true'
    }

    echo "Build done. Local image: ${localTag}"

    return [
        imageTag              : imageTag,
        localImage            : localTag,
        internalRegistryImage : localTag,
        gitlabRegistryImage   : gitlabImage
    ]
}

def pushToGitlabRegistry(Map config) {
    config.internalRegistryImage = config.internalRegistryImage ?: error('internalRegistryImage (source image) is required for pushToGitlabRegistry')
    config.gitlabRegistryImage   = config.gitlabRegistryImage   ?: error('gitlabRegistryImage is required for pushToGitlabRegistry')
    config.gitlabRegistryHost    = (config.gitlabRegistryHost ?: env.GITLAB_REGISTRY_HOST ?: 'skywalker.inet.co.th:5050')
    config.gitlabDeployTokenId   = config.gitlabDeployTokenId   ?: error('gitlabDeployTokenId is required for GitLab Registry login')

    echo "Logging in to GitLab Registry: ${config.gitlabRegistryHost}"
    withCredentials([usernamePassword(credentialsId: config.gitlabDeployTokenId, usernameVariable: 'GITLAB_USER', passwordVariable: 'GITLAB_PASSWORD')]) {
        sh "echo \"\$GITLAB_PASSWORD\" | docker login ${config.gitlabRegistryHost} -u \"\$GITLAB_USER\" --password-stdin"
    }

    echo "Tagging and pushing Docker image to GitLab Registry: ${config.gitlabRegistryImage}"
    sh "docker tag ${config.internalRegistryImage} ${config.gitlabRegistryImage}"
    sh "docker push ${config.gitlabRegistryImage}"

    echo 'Cleaning up local images...'
    sh "docker rmi ${config.gitlabRegistryImage} || true"
    if (config.internalRegistryImage && config.internalRegistryImage != config.gitlabRegistryImage) {
        sh "docker rmi ${config.internalRegistryImage} || true"
    }
}

def cleanupBaseImage(Map args = [:]) {
    def di = args.dockerInfo ?: [
        localImage           : args.localImage,
        gitlabRegistryImage  : args.gitlabRegistryImage,
        internalRegistryImage: args.internalRegistryImage
    ]

    def images = [di.localImage, di.gitlabRegistryImage, di.internalRegistryImage]
                 .findAll { it && it.trim() }

    echo "Cleaning Docker images/tags: ${images}"

    sh """
        ${images.collect { "docker rmi -f ${it} || true" }.join('\n')}
    """
}