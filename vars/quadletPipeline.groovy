/*
 * Podman Quadlet Jenkins Pipeline
 *
 * See README for usage instructions, configuration options, and examples
 */
void call(Map configParams = [:]) {
    // Centralized default configuration. User-provided config overrides defaults.
    Map defaults = [
        agentLabel:                 'podman',
        disableConcurrentBuilds:    true,
        disableIndexTriggers:       false,
        disableSecurityScan:        false,
        cronSchedule:               null,
        additionalTriggers:         [],
        alertEmail:                 null,
        postCheckoutSteps:          null,
        deploymentsDirectory:       '~/deployments',
        podmanSecretMappings:       [],
        // Parameter Defaults
        defaultTargetHost:          'lb.internal',
        defaultTargetPort:          52222,
        defaultTargetSshCredential: 'lab-ssh',
    ]
    Map config = defaults + configParams
    validateConfig(config)
    applyJobProperties(config)

    // First build registers parameters and exits
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
            // If a post-checkout closure was provided, execute it
            if (config.postCheckoutSteps) {
                config.postCheckoutSteps()
            }
            if (!config.disableSecurityScan) {
                stage('Security Scan') {
                    securityScanStep()
                }
            }
            stage('Deploy') {
                String tmpDir = pwd(tmp: true)
                String playbookPath = "${tmpDir}/deploy-quadlet.yaml"
                String playbook = libraryResource('deploy-quadlet.yaml')
                writeFile(file: playbookPath, text: playbook)

                List credentialBindings = [
                    sshUserPrivateKey(credentialsId: params.TARGET_SSH_CREDENTIAL, keyFileVariable: 'ANSIBLE_PRIVATE_KEY_FILE', usernameVariable: 'ANSIBLE_REMOTE_USER')
                ]
                List secretMetadata = []
                config.podmanSecretMappings.eachWithIndex { mapping, index ->
                    String secretVariable = "QUADLET_SECRET_${index}"
                    switch (mapping.type) {
                        case 'string':
                            credentialBindings.add(string(credentialsId: mapping.credentialsId, variable: secretVariable))
                            secretMetadata.add([name: mapping.podmanSecretStringName, source: 'env', variable: secretVariable])
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
                            secretMetadata.add([name: mapping.podmanSecretFileName, source: 'file', variable: secretVariable])
                            break
                        default:
                            error("Unsupported Podman secret mapping. ID: '${mapping.credentialsId}' type: '${mapping.type}'")
                    }
                }

                List jobNameParts = env.JOB_NAME.tokenize('/')
                String repositoryName = jobNameParts.size() > 1 ? jobNameParts[-2] : jobNameParts.first()
                if (!repositoryName?.trim()) {
                    error("Could not resolve repository name from JOB_NAME (${env.JOB_NAME})")
                }
                String deploymentDirectory = "${config.deploymentsDirectory}/${repositoryName}"

                String ansibleExtraVarsJson = groovy.json.JsonOutput.toJson([
                    repository_name:        repositoryName,
                    deployment_directory:   deploymentDirectory,
                    source_directory:       pwd(),
                    quadlet_rm:             params.QUADLET_RM,
                    podman_secret_mappings: secretMetadata
                ])
                String extraVarsPath = "${tmpDir}/extra-vars.json"
                writeFile(file: extraVarsPath, text: ansibleExtraVarsJson)

                withEnv([
                    'ANSIBLE_HOST_KEY_CHECKING=False',
                    "EXTRA_VARS_PATH=${extraVarsPath}",
                    "PLAYBOOK_PATH=${playbookPath}",
                    "TARGET=${params.TARGET_HOST}:${params.TARGET_PORT},"
                ]) {
                    withCredentials(credentialBindings) {
                        sh 'ansible-playbook "${PLAYBOOK_PATH}" -i "${TARGET}" -e "@${EXTRA_VARS_PATH}"'
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
            booleanParam(name: 'QUADLET_RM', defaultValue: false, description: 'Action: Stop and remove services and then exit the pipeline.'),
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
            error("Configuration Error: '${param}' must be a Boolean (true/false)")
        }
    }
    if (config.deploymentsDirectory && !(config.deploymentsDirectory instanceof CharSequence)) {
        error("Config Error: 'deploymentsDirectory' must be a String path")
    }
    if (config.podmanSecretMappings && !(config.podmanSecretMappings instanceof List)) {
        error("Config Error: 'podmanSecretMappings' must be a List")
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
