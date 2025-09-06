// Pipeline for building and pushing Docker images
def call(Map config = [:]) {

    // Read the agent label from the config map, defaulting to 'docker'
    def agentLabel = config.agentLabel ?: 'docker'

    // Setup pipeline parameter defaults using config map.
    def defaultDockerCreds = config.defaultDockerCredentialsId ?: 'docker-hub'
    def defaultImageName = config.defaultImageName ?: ''
    def defaultDockerfile = config.defaultDockerfile ?: 'Dockerfile'
    def defaultTag = config.defaultTag ?: 'latest'

    properties([
        parameters([
            stringParam(name: 'IMAGE_NAME', defaultValue: defaultImageName, description: 'Docker image to build and push'),
            stringParam(name: 'DOCKER_CREDENTIALS_ID', defaultValue: defaultDockerCreds, description: 'Jenkins credentials ID for Docker registry'),
            stringParam(name: 'TAG', defaultValue: defaultTag, description: 'Docker image tag to push'),
            stringParam(name: 'DOCKERFILE', defaultValue: defaultDockerfile, description: 'Dockerfile to use for building the image')
        ])
    ])

    node(agentLabel) {
        stage('Checkout') {
            checkout scm
        }

        // Short git SHA for tagging
        def gitSha = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()

        stage('Build Docker Image') {
            def imageName = params.IMAGE_NAME
            def tag = params.TAG
            def dockerfile = params.DOCKERFILE

            echo "=== Building Docker Image: ${imageName}:${tag} ==="
            def img = docker.build("${imageName}:${tag}", "-f ${dockerfile} .")
            img.tag("${gitSha}")
        }

        stage('Push Docker Image') {
            def imageName = params.IMAGE_NAME
            def tag = params.TAG

            echo "=== Pushing to Docker Registry ==="
            docker.withRegistry('', params.DOCKER_CREDENTIALS_ID) {
                def img = docker.image("${imageName}:${tag}")
                img.push()
                img.push(gitSha)
            }
        }
    }
}
