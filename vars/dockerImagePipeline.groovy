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
        disableIndexTriggers:       false,
        cronSchedule:               null,
        alertEmail:                 null,
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
        try {
            imageBuildFlow()
        } catch (err) {
            if (config.alertEmail) {
                mail to: config.alertEmail,
                     subject: "🚨 Build Failure - ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                     body: "Build failed!\n\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}\n\nCheck Jenkins logs here: ${env.BUILD_URL}"
            }
            throw err
        }
    }
}

/**
 * Defines and applies all job properties, including parameters and triggers.
 */
private void setupJobProperties(Map config) {
    def jobProperties = [
        parameters([
            stringParam(name: 'IMAGE_NAME', defaultValue: config.defaultImageName, description: 'Docker image to build and push'),
            credentials(
                name: 'DOCKER_CREDENTIALS_ID',
                // `credentialType` omitted to maintain compatibility with bitwarden-credentials-provider-plugin (Credentials of all types will be shown as options for this parameter)
                defaultValue: config.defaultDockerCredentialsId,
                description: 'Docker Registry "Username with password" credential',
                required: true
            ),
            stringParam(name: 'TAG', defaultValue: config.defaultTag, description: 'Docker image tag to push'),
            stringParam(name: 'DOCKERFILE', defaultValue: config.defaultDockerfile, description: 'Dockerfile to use for building the image'),
            booleanParam(name: 'NO_CACHE', defaultValue: config.defaultNoCache, description: 'Build Docker image without cache')
        ])
    ]

    def cronSchedule = config.cronSchedule?.trim()
    def triggers = []
    if (cronSchedule) triggers.add(cron(cronSchedule))
    if (config.disableIndexTriggers)
        jobProperties.add(overrideIndexTriggers(false))
    jobProperties.add(pipelineTriggers(triggers))
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
