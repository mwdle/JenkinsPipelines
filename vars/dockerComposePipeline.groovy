/*
 * Docker Compose Pipeline for Jenkins
 *
 * This pipeline library automates Docker Compose workflows inside Jenkins.
 * Full usage instructions, configuration options, and examples are in the README.
 */
def call(Map configParams = [:]) {

    // Centralized configuration with defaults. User-provided config overrides defaults.
    def defaults = [
        agentLabel:              'docker',
        disableConcurrentBuilds: false,
        disableIndexTriggers:    false,
        cronSchedule:            null,
        additionalTriggers:      [],
        alertEmail:              null,
        postCheckoutSteps:       null,
        // Parameter defaults
        defaultComposeDown:      false,
        defaultComposeRestart:   false,
        defaultForceRecreate:    false,
        defaultComposeBuild:     false,
        defaultNoCache:          false,
        defaultPullImages:       false,
        defaultTargetServices:   '',
        defaultLogTailCount:     '0',
        defaultDetached:         true
    ]
    def config = defaults + configParams

    validateConfig(config)

    // Setup job properties, parameters, and triggers
    setupJobProperties(config)

    // First build registers parameters and exits
    if (env.BUILD_NUMBER == '1') {
        echo "Jenkins is initializing this job. Please re-run to proceed."
        currentBuild.result = 'NOT_BUILT'
        return
    }

    // Validate all user-provided string parameters before proceeding
    validateParameters()

    node(config.agentLabel) {
        try {
            if (config.persistentWorkspace) {
                def jobNameParts = env.JOB_NAME.tokenize('/')
                def repoName = jobNameParts.size() > 1 ? jobNameParts[-2] : jobNameParts[0]
                def appRoot = "${config.persistentWorkspace}/${repoName}"
                def deploymentPath = "${appRoot}/${env.BUILD_NUMBER}"
                dir(deploymentPath) {
                    deploymentFlow(config)
                }
                cleanupPersistentWorkspace(appRoot)
            } else { // Run the Docker Compose flow within the regular ephemeral agent workspace
                deploymentFlow(config)
            }
        } catch (err) {
            def isAborted = err instanceof org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
            if (config.alertEmail && !isAborted) {
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
            booleanParam(name: 'COMPOSE_DOWN', defaultValue: config.defaultComposeDown, description: 'Action: Stop and remove services and then exit the pipeline.'),
            booleanParam(name: 'COMPOSE_RESTART', defaultValue: config.defaultComposeRestart, description: 'Action: Restart services and then exit the pipeline.'),
            booleanParam(name: 'FORCE_RECREATE', defaultValue: config.defaultForceRecreate, description: 'Modifier: Run `down` before `up` to force a clean start.'),
            booleanParam(name: 'COMPOSE_BUILD', defaultValue: config.defaultComposeBuild, description: 'Modifier: Build image(s) from Dockerfile(s) before running `up`.'),
            booleanParam(name: 'NO_CACHE', defaultValue: config.defaultNoCache, description: 'Modifier: Do not use cache when building images. Requires `COMPOSE_BUILD` to be enabled.'),
            booleanParam(name: 'PULL_IMAGES', defaultValue: config.defaultPullImages, description: 'Modifier: Run `pull` before `up` to fetch latest images.'),
            booleanParam(name: 'DETACHED', defaultValue: config.defaultDetached, description: 'Modifier: Run services in detached (background) mode.'),
            stringParam(name: 'TARGET_SERVICES', defaultValue: config.defaultTargetServices, description: 'Option: Specify services to target (e.g., "nextcloud db redis").'),
            stringParam(name: 'LOG_TAIL_COUNT', defaultValue: config.defaultLogTailCount.toString(), description: 'Option: Number of log lines to show after `up` completes.')
        ])
    ]
    if (config.disableConcurrentBuilds) {
        jobProperties.add(disableConcurrentBuilds())
    }
    def cronSchedule = config.cronSchedule?.trim()
    def triggers = []
    if (cronSchedule) triggers.add(cron(cronSchedule))
    if (config.additionalTriggers) triggers.addAll(config.additionalTriggers)
    if (config.disableIndexTriggers) {
        jobProperties.add(overrideIndexTriggers(false))
    }
    jobProperties.add(pipelineTriggers(triggers))

    properties(jobProperties)
}

/**
 * Validates all config map parameters for correctness.
 */
private void validateConfig(Map config) {
    if (config.persistentWorkspace) {
        if (!(config.persistentWorkspace instanceof CharSequence)) {
            error("Config Error: 'persistentWorkspace' must be a String path.")
        }
        def forbiddenPaths = ['/', '/home']
        if (forbiddenPaths.contains(config.persistentWorkspace.trim())) {
            error("Config Error: 'persistentWorkspace' cannot be a system root path (${config.persistentWorkspace}).")
        }
    }
    if (config.alertEmail && !config.alertEmail.contains('@')) {
        error("Config Error: 'alertEmail' (${config.alertEmail}) does not look like a valid email address.")
    }
    if (config.envFileCredentialIds && !(config.envFileCredentialIds instanceof List)) {
        error("Config Error: 'envFileCredentialIds' must be a List of strings.")
    }
    if (config.postCheckoutSteps && !(config.postCheckoutSteps instanceof Closure)) {
        error("Config Error: 'postCheckoutSteps' must be a code block { ... }.")
    }
    def booleanParams = [
        'disableConcurrentBuilds',
        'disableIndexTriggers',
        'defaultComposeDown',
        'defaultComposeRestart',
        'defaultForceRecreate',
        'defaultComposeBuild',
        'defaultNoCache',
        'defaultPullImages',
        'defaultDetached'
    ]
    booleanParams.each { param ->
        if (config[param] != null && !(config[param] instanceof Boolean)) {
            error("Config Error: '${param}' must be a Boolean (true/false), not a String.")
        }
    }
}

/**
 * Validates all user-provided string parameters for security and correctness.
 */
private void validateParameters() {
    if (!params.TARGET_SERVICES.matches(/^[a-zA-Z0-9\s._-]*$/)) {
        error("Invalid characters in TARGET_SERVICES. Halting for security reasons.")
    }
    if (!params.LOG_TAIL_COUNT.matches(/^-?\d+$/)) {
        error("LOG_TAIL_COUNT must be a number.")
    }
}

/**
 * Defines the core logic, allowing it to be called conditionally with or without the persistent workspace feature.
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
 * Defines the core teardown, build, and run logic.
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
        stage('Up') {
            echo "=== Starting Services ==="
            if (params.FORCE_RECREATE) {
                echo 'Force recreate requested. Executing `docker compose down` before `up`.'
                dockerCompose("down", envFileOpts)
            }
            if (params.PULL_IMAGES) {
                echo "Pulling latest images."
                dockerCompose("pull --ignore-pull-failures", envFileOpts)
            }
            def upArgs = "up"
            if (params.DETACHED) {
                upArgs += " -d --wait"
            } else {
                upArgs += " --abort-on-container-exit"
            }
            dockerCompose(upArgs, envFileOpts)
            echo "Service status:"
            dockerCompose("ps", envFileOpts)
            if (logTailCount) {
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
    def command = "docker compose --progress=plain ${envFileOpts} ${args}"
    // The 'config' command does not accept service names, but all others do.
    if (!args.startsWith('config') && params.TARGET_SERVICES?.trim()) {
        command += " ${params.TARGET_SERVICES.trim()}"
    }
    sh(command)
}

/**
 * Cleans up old build directories in a persistent workspace.
 */
private void cleanupPersistentWorkspace(String appRoot) {
    stage('Cleanup') {
        if (params.COMPOSE_DOWN) {
            echo "Cleaning up all persistent workspace folders..."
            sh "rm -rf '${appRoot}'"
        } else {
            echo "Cleaning up old build directories..."
            dir(appRoot) {
                sh "find . -maxdepth 1 -mindepth 1 -type d ! -name '${env.BUILD_NUMBER}' -exec rm -rf {} +"
            }
        }
    }
}
