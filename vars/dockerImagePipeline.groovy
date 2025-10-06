/*
 * Docker Image Pipeline for Jenkins
 *
 * This pipeline library automates building and pushing Docker images inside Jenkins.
 * Full usage instructions, configuration options, and examples are in the README.
 */
def call(Map parameters = [:]) {

    // Centralized configuration with defaults. User-provided config overrides defaults.
    def defaults = [
        agentLabel:                 'docker',
        disableTriggers:            false,
        cronSchedule:               null,
        // Parameter defaults
        defaultDockerCredentialsId: 'docker-hub',
        defaultImageName:           '',
        defaultDockerfile:          'Dockerfile',
        defaultTag:                 'latest',
        defaultNoCache:             false
    ]
    def config = defaults + parameters

    // Setup job properties, parameters, and triggers
    setupJobProperties(config)

    // First build registers parameters and exits
    if (env.BUILD_NUMBER == '1') {
        echo "Jenkins is initializing this job. Please re-run to start a deployment."
        currentBuild.result = 'NOT_BUILT'
        return
    }

    // Validate all user-provided string parameters before proceeding
    validateParameters()

    node(config.agentLabel) {
        imageBuildFlow()
    }
}

/**
 * Defines and applies all job properties, including parameters and triggers.
 */
private void setupJobProperties(Map config) {
    def jobProperties = [
        parameters([
            stringParam(name: 'IMAGE_NAME', defaultValue: config.defaultImageName, description: 'Docker image to build and push'),
            stringParam(name: 'DOCKER_CREDENTIALS_ID', defaultValue: config.defaultDockerCredentialsId, description: 'Jenkins credentials ID for Docker registry'),
            stringParam(name: 'TAG', defaultValue: config.defaultTag, description: 'Docker image tag to push'),
            stringParam(name: 'DOCKERFILE', defaultValue: config.defaultDockerfile, description: 'Dockerfile to use for building the image'),
            booleanParam(name: 'NO_CACHE', defaultValue: config.defaultNoCache, description: 'Build Docker image without cache')
        ])
    ]

    def cronSchedule = config.cronSchedule?.trim()
    if (config.disableTriggers) {
        jobProperties.add(overrideIndexTriggers(false))
        jobProperties.add(pipelineTriggers([]))
    } else if (cronSchedule) {
        jobProperties.add(pipelineTriggers([cron(cronSchedule)]))
    }

    properties(jobProperties)
}

/**
 * Validates all user-provided string parameters for security and correctness.
 */
private void validateParameters() {
    if (!params.IMAGE_NAME.matches(/^[a-zA-Z0-9\/._-]+$/)) {
        error("Invalid characters in IMAGE_NAME. Halting for security reasons.")
    }
    if (!params.TAG.matches(/^[a-zA-Z0-9._-]+$/)) {
        error("Invalid characters in TAG. Halting for security reasons.")
    }
    if (!params.DOCKERFILE.matches(/^[a-zA-Z0-9\/._-]+$/)) {
        error("Invalid characters in DOCKERFILE. Halting for security reasons.")
    }
}

/**
 * Defines the core logic for checking out, building, and pushing the Docker image.
 */
private void imageBuildFlow() {
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
