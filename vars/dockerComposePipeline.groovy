/*
 * Docker Compose Pipeline for Jenkins
 *
 * This pipeline library automates Docker Compose deployments inside Jenkins.
 * Full usage instructions, configuration options, and examples are in the README.
 */
def call(Map parameters = [:]) {

    // Centralized configuration with defaults. User-provided config overrides defaults.
    def defaults = [
        agentLabel:          'docker',
        disableTriggers:     false,
        cronSchedule:        null,
        postCheckoutSteps:   null,
        // Parameter defaults
        defaultComposeDown:    false,
        defaultComposeRestart: false,
        defaultForceRecreate:  false,
        defaultComposeBuild:   false,
        defaultNoCache:        false,
        defaultPullImages:     false,
        defaultTargetServices: '',
        defaultLogTailCount:   '0'
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
        if (config.persistentWorkspace) {
            def repoName = env.JOB_NAME.split('/')[1]
            def appRoot = "${config.persistentWorkspace}/${repoName}"
            def deploymentPath = "${appRoot}/${env.BUILD_NUMBER}"
            try {
                dir(deploymentPath) {
                    deploymentFlow(config)
                }
            } finally {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    cleanupPersistentWorkspace(appRoot, repoName)
                } else {
                    echo "Build failed with status: ${currentBuild.result}. Skipping cleanup to preserve the last known-good deployment."
                }
            }
        } else { // Run the Docker Compose flow within the regular ephemeral agent workspace
            deploymentFlow(config)
        }
    }
}

/**
 * Defines and applies all job properties, including parameters and triggers.
 */
private void setupJobProperties(Map config) {
    def jobProperties = [
        parameters([
            booleanParam(name: 'COMPOSE_DOWN', defaultValue: config.defaultComposeDown, description: 'Action: Stop and remove services and then exit the pipeline.'),
            booleanParam(name: 'COMPOSE_RESTART', defaultValue: config.defaultComposeRestart, description: 'Action: Restart services and then exit the pipeline.'),
            booleanParam(name: 'FORCE_RECREATE', defaultValue: config.defaultForceRecreate, description: 'Modifier: Force a clean deployment by running `down` before `up`.'),
            booleanParam(name: 'COMPOSE_BUILD', defaultValue: config.defaultComposeBuild, description: 'Modifier: Build image(s) from Dockerfile(s) before deploying.'),
            booleanParam(name: 'NO_CACHE', defaultValue: config.defaultNoCache, description: 'Modifier: Do not use cache when building images. Requires `COMPOSE_BUILD` to be enabled.'),
            booleanParam(name: 'PULL_IMAGES', defaultValue: config.defaultPullImages, description: 'Modifier: Pull the latest version of image(s) before deploying.'),
            stringParam(name: 'TARGET_SERVICES', defaultValue: config.defaultTargetServices, description: 'Option: Specify services to target (e.g., "nextcloud db redis").'),
            stringParam(name: 'LOG_TAIL_COUNT', defaultValue: config.defaultLogTailCount.toString(), description: 'Option: Number of log lines to show after deployment.')
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
    if (!params.TARGET_SERVICES.matches(/^[a-zA-Z0-9\s._-]*$/)) {
        error("Invalid characters in TARGET_SERVICES. Halting for security reasons.")
    }
    if (!params.LOG_TAIL_COUNT.matches(/^\d+$/)) {
        error("LOG_TAIL_COUNT must be a non-negative number.")
    }
}

/**
 * Defines the core deployment logic, allowing it to be called conditionally with or without the persistent workspace feature.
 */
private void deploymentFlow(Map config) {
    stage('Checkout') {
        checkout scm
    }
    // If a post-checkout closure was provided, execute it
    if (config.postCheckoutSteps) {
        config.postCheckoutSteps()
    }
    if (config.envFileCredentialIds) {
        echo "Secrets integration enabled."
        def credentialBindings = []
        config.envFileCredentialIds.eachWithIndex { credId, i ->
            def variableName = "COMPOSE_ENV_${i}"
            credentialBindings.add(file(credentialsId: credId, variable: variableName))
        }
        withCredentials(credentialBindings) {
            def envFileOpts = (0..<config.envFileCredentialIds.size()).collect { i ->
                '--env-file $COMPOSE_ENV_' + i
            }.join(' ')
            composeStages(envFileOpts)
        }
    } else {
        echo "Secrets integration disabled."
        composeStages()
    }
}

/**
 * Defines the core teardown, build, and deploy logic.
 */
private void composeStages(String envFileOpts = '') {
    def logTailCount = params.LOG_TAIL_COUNT.toInteger()
    stage('Validate') {
        echo "=== Validating Docker Compose Configuration ==="
        dockerCompose("config --quiet", envFileOpts)
    }
    if (params.COMPOSE_DOWN) {
        stage('Teardown') {
            echo "=== Tearing Down Services ==="
            dockerCompose("down", envFileOpts)
        }
    } else if (params.COMPOSE_RESTART) {
        stage('Restart') {
            echo "=== Restarting Services ==="
            dockerCompose("restart", envFileOpts)
        }
    } else {
        if (params.COMPOSE_BUILD) {
            stage('Build') {
                echo "=== Building Docker Images ==="
                def buildArgs = "build"
                if (params.NO_CACHE) {
                    echo "Cache disabled for this build."
                    buildArgs += " --no-cache"
                }
                dockerCompose(buildArgs, envFileOpts)
            }
        }
        stage('Deploy') {
            echo "=== Deploying Services ==="
            if (params.FORCE_RECREATE) {
                echo 'Force recreate requested. Executing `docker compose down` before redeploy.'
                dockerCompose("down", envFileOpts)
            }
            if (params.PULL_IMAGES) {
                echo "Pulling latest images."
                dockerCompose("pull --ignore-pull-failures", envFileOpts)
            }
            dockerCompose("up -d", envFileOpts)
            echo "Deployment status:"
            dockerCompose("ps", envFileOpts)
            if (logTailCount > 0) {
                sleep 3 // Short sleep to give logs time to populate
                echo "--> Showing last ${logTailCount} log lines:"
                dockerCompose("logs --tail=${logTailCount}", envFileOpts)
            }
        }
    }
}

/**
 * Runs a docker compose command with optional environment file overrides.
 */
private void dockerCompose(String args, String envFileOpts = '') {
    def command = "docker compose ${envFileOpts} ${args}"
    // The 'config' command does not accept service names, but all others do.
    if (!args.startsWith('config') && params.TARGET_SERVICES) {
        command += " ${params.TARGET_SERVICES}"
    }
    sh(command)
}

/**
 * Cleans up old deployment directories in a persistent workspace.
 */
private void cleanupPersistentWorkspace(String appRoot, String repoName) {
    stage('Cleanup Old Deployments') {
        if (params.COMPOSE_DOWN) {
            echo "Cleaning up all persistent deployment folders for ${repoName}..."
            sh "rm -rf ${appRoot}"
        } else {
            echo "Build was successful. Cleaning up old deployments..."
            dir(appRoot) {
                sh "find . -maxdepth 1 -mindepth 1 -type d ! -name '${env.BUILD_NUMBER}' -exec rm -rf {} +"
            }
        }
    }
}
