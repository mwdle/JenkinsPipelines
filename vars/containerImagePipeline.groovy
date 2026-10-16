/*
 * Container Image Jenkins Pipeline
 *
 * Builds and pushes container images through a remote Podman service.
 * See README for usage instructions, configuration options, and examples.
 */
void call(Map configParams = [:]) {
    Map defaults = [
        agentLabel:                     'podman',
        disableConcurrentBuilds:        true,
        disableIndexTriggers:           false,
        disableSecurityScan:            false,
        cronSchedule:                   null,
        additionalTriggers:             [],
        alertEmail:                     env.ALERT_EMAIL?.trim() ?: null,
        postCheckoutSteps:              null,
        // Parameter defaults
        defaultRegistryCredential:      'Forgejo PAT',
        defaultRegistryHost:            'git.mwdle.com',
        defaultImageName:               '',
        defaultTag:                     'latest',
        defaultContainerfile:           'Containerfile',
        defaultNoCache:                 false,
    ]
    Map config = defaults + configParams
    validateConfig(config)
    applyJobProperties(config)

    // The first build configures parameters and branch indexing behavior without publishing.
    if (env.BUILD_NUMBER == '1') {
        echo 'Jenkins is initializing this job. Please rebuild with parameters to proceed.'
        currentBuild.result = 'NOT_BUILT'
        return
    }

    validateParameters()

    node(config.agentLabel) {
        try {
            stage('Checkout') {
                checkout scm
            }
            if (config.postCheckoutSteps) {
                config.postCheckoutSteps()
            }

            String fullImageName = "${params.REGISTRY_HOST.trim()}/${params.IMAGE_NAME}"
            String imageReference = "${fullImageName}:${params.TAG}"
            stage('Build Image') {
                String noCache = params.NO_CACHE ? '--no-cache ' : ''
                sh "podman-remote build ${noCache} --file '${params.CONTAINERFILE}' --tag '${imageReference}' ."
            }

            stage('Security Scan') {
                if (config.disableSecurityScan) {
                    echo 'Skipping security scan because it is disabled by config.'
                } else {
                    securityScanStep(imageReference)
                }
            }

            stage('Push Image') {
                try {
                    withCredentials([
                        usernamePassword(credentialsId: params.REGISTRY_CREDENTIAL, usernameVariable: 'REGISTRY_USERNAME', passwordVariable: 'REGISTRY_PASSWORD')
                    ]) {
                        sh 'printf "%s" "$REGISTRY_PASSWORD" | podman-remote login "$REGISTRY_HOST" --username "$REGISTRY_USERNAME" --password-stdin'
                    }
                    sh "podman-remote push '${imageReference}'"
                } finally {
                    sh script: "podman-remote logout '${params.REGISTRY_HOST.trim()}'", returnStatus: true
                }
            }
        } catch (err) {
            boolean isAborted = err instanceof org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
            if (config.alertEmail && !isAborted) {
                mail(
                    to: config.alertEmail,
                    subject: "🚨 Build Failure - ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                    body: "Build failed!\n\nJob: ${env.JOB_NAME}\nBuild: #${env.BUILD_NUMBER}\n\nCheck Jenkins logs: ${env.BUILD_URL}"
                )
            }
            throw err
        } finally {
            cleanWs(deleteDirs: true, disableDeferredWipeout: true)
        }
    }
}

/**
 * Defines and applies job properties, including parameters and triggers
 */
private void applyJobProperties(Map config) {
    List jobProperties = [
        parameters([
            string(name: 'REGISTRY_HOST', defaultValue: config.defaultRegistryHost.toString(), description: 'Container registry hostname, such as docker.io.'),
            string(name: 'IMAGE_NAME', defaultValue: config.defaultImageName.toString(), description: 'Container image name to build and push.'),
            credentials(
                name: 'REGISTRY_CREDENTIAL',
                // `credentialType` omitted to maintain compatibility with current limitation of credentials plugin (Credentials of all types will be shown as options for this parameter)
                // See https://github.com/jenkinsci/credentials-plugin/issues/1083 for more information
                defaultValue: config.defaultRegistryCredential.toString(),
                description: 'The Jenkins "Username with password" credential ID for the container registry.',
                required: true
            ),
            string(name: 'TAG', defaultValue: config.defaultTag.toString(), description: 'Container image tag to push.'),
            string(name: 'CONTAINERFILE', defaultValue: config.defaultContainerfile.toString(), description: 'Containerfile path relative to the repository root.'),
            booleanParam(name: 'NO_CACHE', defaultValue: config.defaultNoCache, description: 'Build the container image without using cached layers.')
        ])
    ]
    if (config.disableConcurrentBuilds) {
        jobProperties.add(disableConcurrentBuilds())
    }
    String cronSchedule = config.cronSchedule?.trim()
    List triggers = []
    if (cronSchedule) { triggers.add(cron(cronSchedule)) }
    if (config.additionalTriggers) { triggers.addAll(config.additionalTriggers) }
    if (config.disableIndexTriggers) {
        jobProperties.add(overrideIndexTriggers(false))
    }
    jobProperties.add(pipelineTriggers(triggers))
    properties(jobProperties)
}

/**
 * Validates config map parameters for correctness
 */
private void validateConfig(Map config) {
    if (config.alertEmail) {
        config.alertEmail = config.alertEmail.trim()
        if (!config.alertEmail.matches(/^\S+@\S+\.\S+$/)) {
            error("Config Error: 'alertEmail' (${config.alertEmail}) does not look like a valid email address.")
        }
    }
    if (config.postCheckoutSteps && !(config.postCheckoutSteps instanceof Closure)) {
        error("Config Error: 'postCheckoutSteps' must be a code block { ... }.")
    }
    List booleanParams = ['disableConcurrentBuilds', 'disableIndexTriggers', 'disableSecurityScan', 'defaultNoCache']
    booleanParams.each { param ->
        if (config[param] != null && !(config[param] instanceof Boolean)) {
            error("Config Error: '${param}' must be a Boolean (true/false)")
        }
    }
}

/**
 * Validates user-provided string parameters for security and correctness
 */
private void validateParameters() {
    if (!params.REGISTRY_HOST?.matches(/^[a-zA-Z0-9.:-]+$/)) {
        error('REGISTRY_HOST is required and must be a valid registry hostname.')
    }
    if (!params.IMAGE_NAME?.matches(/^[a-zA-Z0-9\/._-]+$/)) {
        error('IMAGE_NAME is required and contains invalid characters.')
    }
    if (!params.TAG?.matches(/^[a-zA-Z0-9._-]+$/)) {
        error('TAG is required and contains invalid characters.')
    }
    if (!params.CONTAINERFILE?.matches(/^[a-zA-Z0-9\/._-]+$/) || params.CONTAINERFILE.startsWith('/') || params.CONTAINERFILE.tokenize('/').contains('..')) {
        error('CONTAINERFILE must be a relative path without parent-directory traversal.')
    }
}
