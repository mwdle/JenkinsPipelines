// Pipeline for building and pushing Docker images
def call(Map config = [:]) {

    // Read the agent label from the config map, defaulting to 'docker'
    def agentLabel = config.agentLabel ?: 'docker'

    // Setup pipeline parameter defaults using config map.
    def defaultDockerCreds = config.defaultDockerCredentialsId ?: 'docker-hub'
    def defaultImageName = config.defaultImageName ?: ''
    def defaultDockerfile = config.defaultDockerfile ?: 'Dockerfile'
    def defaultTag = config.defaultTag ?: 'latest'
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

    node(agentLabel) {
        stage('Checkout') {
            checkout scm
        }

        def gitSha = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()

        stage('Build Docker Image') {
            def imageName = params.IMAGE_NAME
            def tag = params.TAG
            def dockerfile = params.DOCKERFILE
            def noCacheFlag = params.NO_CACHE ? '--no-cache' : ''

            echo "=== Building Docker Image: ${imageName}:${tag} ==="
            sh "docker build ${noCacheFlag} -f ${dockerfile} -t ${imageName}:${tag} -t ${imageName}:${gitSha} ."
        }

        stage('Push Docker Image') {
            def imageName = params.IMAGE_NAME
            def tag = params.TAG

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
