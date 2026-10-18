/*
 * Podman Quadlet Jenkins Pipeline
 *
 * See README for usage instructions, configuration options, and examples
 */
void call(Map configParams = [:]) {
    // Centralized default configuration. User-provided config overrides defaults.
    Map defaults = [
        agentLabel:                  'ephemeral',
        disableConcurrentBuilds:     true,
        disableIndexTriggers:        false,
        disableSecurityScan:         false,
        cronSchedule:                null,
        additionalTriggers:          [],
        alertEmail:                  env.ALERT_EMAIL?.trim() ?: null,
        postCheckoutSteps:           null,
        stagingDirectory:            '.staging',
        stagingExclusions:           ['Jenkinsfile', 'LICENSE', 'README.md', 'renovate.json'],
        additionalStagingExclusions: [],
        podmanSecretMappings:        [],
        // Parameter defaults
        defaultTargetHost:           'host.containers.internal',
        defaultTargetPort:           52222,
        defaultTargetSshCredential:  'lab-ssh',
        defaultForceRestart:         false,
    ]
    Map config = defaults + configParams
    validateConfig(config)
    applyJobProperties(config)

    // The first build configures parameters and branch indexing behavior without deploying.
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
            if (!config.disableSecurityScan) {
                stage('Security Scan') {
                    if (params.QUADLET_RM) {
                        echo 'Skipping security scan for Quadlet removal.'
                    } else {
                        securityScanStep()
                    }
                }
            }
            stage('Manage Quadlet') {
                List credentialBindings = []
                List secretMetadata = []
                if (!params.QUADLET_RM) {
                    config.podmanSecretMappings.eachWithIndex { mapping, index ->
                        String secretVariable = "QUADLET_SECRET_${index}"
                        switch (mapping.type) {
                            case 'string':
                                credentialBindings.add(string(credentialsId: mapping.credentialsId, variable: secretVariable))
                                secretMetadata.add([name: mapping.podmanSecretStringName ?: mapping.credentialsId, source: 'env', variable: secretVariable])
                                break
                            case 'usernamePassword':
                                String usernameVariable = "${secretVariable}_USER"
                                String passwordVariable = "${secretVariable}_PASS"
                                credentialBindings.add(usernamePassword(credentialsId: mapping.credentialsId, usernameVariable: usernameVariable, passwordVariable: passwordVariable))
                                secretMetadata.add([name: mapping.podmanSecretUsernameName, source: 'env', variable: usernameVariable])
                                secretMetadata.add([name: mapping.podmanSecretPasswordName, source: 'env', variable: passwordVariable])
                                break
                            case 'file':
                                credentialBindings.add(file(credentialsId: mapping.credentialsId, variable: secretVariable))
                                secretMetadata.add([name: mapping.podmanSecretFileName ?: mapping.credentialsId, source: 'file', variable: secretVariable])
                                break
                            default:
                                error("Unsupported Podman secret mapping. ID: '${mapping.credentialsId}' type: '${mapping.type}'")
                        }
                    }
                }

                List jobNameParts = env.JOB_NAME.tokenize('/')
                String repositoryName = jobNameParts.size() > 1 ? jobNameParts[-2] : jobNameParts.first()
                if (!repositoryName?.trim()) {
                    error("Could not resolve repository name from JOB_NAME (${env.JOB_NAME})")
                }
                ansiblePlaybookStep(
                    playbookResource: 'manage-quadlet.yaml',
                    targetHost: params.TARGET_HOST,
                    targetPort: params.TARGET_PORT,
                    sshCredential: params.TARGET_SSH_CREDENTIAL,
                    credentialBindings: credentialBindings,
                    extraVars: [
                        repository_name:        repositoryName,
                        staging_directory:      "${config.stagingDirectory}/${env.BUILD_TAG}/${repositoryName}",
                        source_directory:       pwd(),
                        quadlet_rm:             params.QUADLET_RM,
                        force_restart:          params.FORCE_RESTART,
                        staging_exclusions:     (config.stagingExclusions + config.additionalStagingExclusions).findAll { it?.trim() }.collect { "--exclude=${it.trim()}" },
                        podman_secret_mappings: secretMetadata
                    ]
                )
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
            stage('Cleanup') {
                cleanWs(deleteDirs: true, disableDeferredWipeout: true)
            }
        }
    }
}

/**
 * Defines and applies job properties, including parameters and triggers
 */
private void applyJobProperties(Map config) {
    List jobProperties = [
        parameters([
            string(name: 'TARGET_HOST', defaultValue: config.defaultTargetHost.toString(), description: 'The resolvable hostname or IP of the target machine.'),
            string(name: 'TARGET_PORT', defaultValue: config.defaultTargetPort.toString(), description: 'The SSH port of the target machine.'),
            credentials(
                name: 'TARGET_SSH_CREDENTIAL',
                // `credentialType` omitted to maintain compatibility with current limitation of credentials plugin (Credentials of all types will be shown as options for this parameter)
                // See https://github.com/jenkinsci/credentials-plugin/issues/1083 for more information
                defaultValue: config.defaultTargetSshCredential.toString(),
                description: 'The Jenkins "SSH Username with private key" credential ID for SSH access to the target machine.',
                required: true
            ),
            booleanParam(name: 'QUADLET_RM', defaultValue: false, description: 'Stop and remove the installed Quadlet application instead of installing it.'),
            booleanParam(name: 'FORCE_RESTART', defaultValue: config.defaultForceRestart, description: 'Force restart Quadlet services even if no changes were detected.'),
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
    List booleanParams = ['disableConcurrentBuilds', 'disableIndexTriggers', 'disableSecurityScan']
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
    if (!(config.stagingExclusions instanceof List)) {
        error("Config Error: 'stagingExclusions' must be a List")
    }
    config.stagingExclusions.eachWithIndex { exclusion, index ->
        if (!(exclusion instanceof CharSequence) || !exclusion.trim()) {
            error("Config Error: 'stagingExclusions[${index}]' must be a non-empty String")
        }
    }
    if (!(config.additionalStagingExclusions instanceof List)) {
        error("Config Error: 'additionalStagingExclusions' must be a List")
    }
    config.additionalStagingExclusions.eachWithIndex { exclusion, index ->
        if (!(exclusion instanceof CharSequence) || !exclusion.trim()) {
            error("Config Error: 'additionalStagingExclusions[${index}]' must be a non-empty String")
        }
    }
    if (!(config.podmanSecretMappings instanceof List)) {
        error("Config Error: 'podmanSecretMappings' must be a List")
    }
    config.podmanSecretMappings.eachWithIndex { mapping, index ->
        if (!(mapping instanceof Map)) {
            error("Config Error: 'podmanSecretMappings[${index}]' must be a Map")
        }
        if (!(mapping.type in ['string', 'usernamePassword', 'file'])) {
            error("Config Error: 'podmanSecretMappings[${index}].type' must be 'string', 'usernamePassword', or 'file'")
        }
        if (!mapping.credentialsId?.toString()?.trim()) {
            error("Config Error: 'podmanSecretMappings[${index}].credentialsId' must be a non-empty String")
        }
        if (mapping.type == 'usernamePassword' &&
            (!mapping.podmanSecretUsernameName?.toString()?.trim() || !mapping.podmanSecretPasswordName?.toString()?.trim())) {
            error("Config Error: 'podmanSecretMappings[${index}]' must define podmanSecretUsernameName and podmanSecretPasswordName")
        }
    }
}

/**
 * Validates user-provided string parameters for security and correctness
 */
private void validateParameters() {
    if (!params.TARGET_HOST.matches(/^[a-zA-Z0-9.-]+$/)) {
        error('TARGET_HOST contains invalid characters.')
    }
    if (!params.TARGET_PORT.matches(/^\d+$/)) {
        error('TARGET_PORT must be an integer, for example: 22')
    }
    int targetPort = params.TARGET_PORT.toInteger()
    if (targetPort < 1 || targetPort > 65535) {
        error("TARGET_PORT must be between 1 and 65535")
    }
}
