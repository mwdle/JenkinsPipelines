/*
 * A flexible pipeline for building and pushing Docker images.
 *
 * This pipeline is designed for and tested on Unix-like Jenkins agents (e.g.,
 * Linux, macOS). The following tools are required on the agent:
 * - `sh` (Bourne shell)
 * - `docker`
 * - `git`
 */
def call(Map config = [:]) {

    // Read the agent label from the config map, defaulting to 'docker'
    def agentLabel = config.agentLabel ?: 'docker'

    // Configurable default for the 'DOCKER_CREDENTIALS_ID' pipeline parameter
    def defaultDockerCredentialsId = config.defaultDockerCredentialsId ?: 'docker-hub'
    // Configurable default for the 'IMAGE_NAME' pipeline parameter
    def defaultImageName = config.defaultImageName ?: ''
    // Configurable default for the 'DOCKERFILE' pipeline parameter
    def defaultDockerfile = config.defaultDockerfile ?: 'Dockerfile'
    // Configurable default for the 'TAG' pipeline parameter
    def defaultTag = config.defaultTag ?: 'latest'
    // Configurable default for the 'NO_CACHE' pipeline parameter
    def defaultNoCache = config.defaultNoCache ?: false

    properties([
        parameters([
            stringParam(name: 'IMAGE_NAME', defaultValue: defaultImageName, description: 'Docker image to build and push'),
            stringParam(name: 'DOCKER_CREDENTIALS_ID', defaultValue: defaultDockerCreds, description: 'Jenkins credentials ID for Docker registry'),
            stringParam(name: 'TAG', defaultValue: defaultTag, description: 'Docker image tag to push'),
            stringParam(name: 'DOCKERFILE', defaultValue: defaultDockerfile, description: 'Dockerfile to use for building the image'),
            booleanParam(name: 'NO_CACHE', defaultValue: defaultNoCache, description: 'Build Docker image without cache')
        ])
    ])

    // First build registers parameters and exits
    if (env.BUILD_NUMBER == '1') {
        echo "Jenkins is initializing this job. Please re-run to start a deployment."
        currentBuild.result = 'NOT_BUILT'
        return
    }

    node(agentLabel) {
        stage('Checkout') {
            checkout scm
        }

        def gitSha = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
        def imageName = params.IMAGE_NAME
        def tag = params.TAG

        stage('Build Docker Image') {
            def dockerfile = params.DOCKERFILE
            def noCacheFlag = params.NO_CACHE ? '--no-cache' : ''

            echo "=== Building Docker Image: ${imageName}:${tag} ==="
            sh "docker build ${noCacheFlag} -f ${dockerfile} -t ${imageName}:${tag} -t ${imageName}:${gitSha} ."
        }

        stage('Push Docker Image') {
            echo "=== Logging in to Docker Registry ==="
            withCredentials([usernamePassword(credentialsId: params.DOCKER_CREDENTIALS_ID, usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
            }

            echo "=== Pushing Docker Image: ${imageName}:${tag} and ${imageName}:${gitSha} ==="
            sh "docker push ${imageName}:${tag}"
            sh "docker push ${imageName}:${gitSha}"
        }
    }
}
