/*
 * Container Image Jenkins Pipeline
 *
 * Builds and pushes container images on a remote Podman host.
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
        stagingDirectory:               '.staging',
        // Parameter defaults
        defaultRegistryCredential:      'Forgejo PAT',
        defaultRegistryHost:            'git.mwdle.com',
        defaultImageName:               '',
        defaultTag:                     'latest',
        defaultContainerfile:           'Containerfile',
        defaultNoCache:                 false,
        defaultTargetHost:              'host.containers.internal',
        defaultTargetPort:              52222,
        defaultTargetSshCredential:     'lab-ssh',
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
            stage('Build and Push') {
                ansiblePlaybookStep(
                    playbookResource: 'build-container-image.yaml',
                    targetHost: params.TARGET_HOST,
                    targetPort: params.TARGET_PORT,
                    sshCredential: params.TARGET_SSH_CREDENTIAL,
                    credentialBindings: [
                        usernamePassword(credentialsId: params.REGISTRY_CREDENTIAL, usernameVariable: 'REGISTRY_USERNAME', passwordVariable: 'REGISTRY_PASSWORD')
                    ],
                    extraVars: [
                        source_directory:  pwd(),
                        staging_directory: "${config.stagingDirectory}/${env.BUILD_TAG}",
                        image_name:        fullImageName,
                        image_tag:         params.TAG,
                        containerfile:     params.CONTAINERFILE,
                        no_cache:          params.NO_CACHE
                    ]
                )
            }

            if (!config.disableSecurityScan) {
                stage('Security Scan') {
                    withCredentials([
                        usernamePassword(credentialsId: params.REGISTRY_CREDENTIAL, usernameVariable: 'TRIVY_USERNAME', passwordVariable: 'TRIVY_PASSWORD')
                    ]) {
                        securityScanStep("${fullImageName}:${params.TAG}")
                    }
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
            booleanParam(name: 'NO_CACHE', defaultValue: config.defaultNoCache, description: 'Build the container image without using cached layers.'),
            string(name: 'TARGET_HOST', defaultValue: config.defaultTargetHost.toString(), description: 'The resolvable hostname or IP of the build host.'),
            string(name: 'TARGET_PORT', defaultValue: config.defaultTargetPort.toString(), description: 'The SSH port of the build host.'),
            credentials(
                name: 'TARGET_SSH_CREDENTIAL',
                // `credentialType` omitted to maintain compatibility with current limitation of credentials plugin (Credentials of all types will be shown as options for this parameter)
                // See https://github.com/jenkinsci/credentials-plugin/issues/1083 for more information
                defaultValue: config.defaultTargetSshCredential.toString(),
                description: 'The Jenkins "SSH Username with private key" credential ID for SSH access to the build host.',
                required: true
            )
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
    if (!(config.stagingDirectory instanceof CharSequence) || !config.stagingDirectory.trim()) {
        error("Config Error: 'stagingDirectory' must be a non-empty String path")
    }
    if (config.stagingDirectory.startsWith('/') || config.stagingDirectory.tokenize('/').contains('..')) {
        error("Config Error: 'stagingDirectory' must be relative to the target user's home directory")
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
    if (!params.TARGET_HOST.matches(/^[a-zA-Z0-9.-]+$/)) {
        error('TARGET_HOST contains invalid characters.')
    }
    if (!params.TARGET_PORT.matches(/^\d+$/)) {
        error('TARGET_PORT must be an integer, for example: 22')
    }
    int targetPort = params.TARGET_PORT.toInteger()
    if (targetPort < 1 || targetPort > 65535) {
        error('TARGET_PORT must be between 1 and 65535')
    }
}
