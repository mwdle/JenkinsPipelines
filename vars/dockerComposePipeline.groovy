/*
 * Docker Compose Pipeline for Jenkins
 *
 * This pipeline library automates Docker Compose deployments inside Jenkins.
 * Full usage instructions, configuration options, and examples are in the README.
 */
def call(Map config = [:]) {

    // Read the agent label from the config map, defaulting to 'docker'
    def agentLabel = config.agentLabel ?: 'docker'
    // Optional boolean Disables webhook and other build triggers if true
    def disableTriggers = config.disableTriggers ?: false
    // Optional cron schedule string for periodic builds. Only applied if `disableTriggers` is false
    def cronSchedule = config.cronSchedule?.trim()
    // Optional closure for custom steps to run after checkout
    def postCheckoutSteps = config.postCheckoutSteps
    
    // Configurable default for the 'COMPOSE_DOWN' pipeline parameter
    def defaultComposeDown = config.defaultComposeDown ?: false
    // Configurable default for the 'COMPOSE_RESTART' pipeline parameter
    def defaultComposeRestart = config.defaultComposeRestart ?: false
    // Configurable default for the 'FORCE_RECREATE' pipeline parameter
    def defaultForceRecreate = config.defaultForceRecreate ?: false
    // Configurable default for the 'COMPOSE_BUILD' pipeline parameter
    def defaultComposeBuild = config.defaultComposeBuild ?: false
    // Configurable default for the 'PULL_IMAGES' pipeline parameter
    def defaultPullImages = config.defaultPullImages ?: false
    // Configurable default for the 'TARGET_SERVICES' pipeline parameter
    def defaultTargetServices = config.defaultTargetServices ?: ''
    // Configurable default for the 'LOG_TAIL_COUNT' pipeline parameter
    def defaultLogTailCount = (config.defaultLogTailCount ?: '0').toString() // Handle potential null with a default before calling .toString()

    // Define and apply job properties and parameters
    def jobProperties = [
        parameters([
            booleanParam(name: 'COMPOSE_DOWN', defaultValue: defaultComposeDown, description: 'Action: Stop and remove services and then exit the pipeline.'),
            booleanParam(name: 'COMPOSE_RESTART', defaultValue: defaultComposeRestart, description: 'Action: Restart services and then exit the pipeline.'),
            booleanParam(name: 'FORCE_RECREATE', defaultValue: defaultForceRecreate, description: 'Modifier: Force a clean deployment by running `down` before `up`.'),
            booleanParam(name: 'COMPOSE_BUILD', defaultValue: defaultComposeBuild, description: 'Modifier: Build image(s) from Dockerfile(s) before deploying.'),
            booleanParam(name: 'PULL_IMAGES', defaultValue: defaultPullImages, description: 'Modifier: Pull the latest version of image(s) before deploying.'),
            stringParam(name: 'TARGET_SERVICES', defaultValue: defaultTargetServices, description: 'Option: Specify services to target (e.g., "nextcloud db redis").'),
            stringParam(name: 'LOG_TAIL_COUNT', defaultValue: defaultLogTailCount, description: 'Option: Number of log lines to show after deployment.')
        ])
    ]

    if (disableTriggers) {
        jobProperties.add(overrideIndexTriggers(false))
        jobProperties.add(pipelineTriggers([]))
    } else if (cronSchedule) {
        jobProperties.add(pipelineTriggers([cron(cronSchedule)]))
    }

    // Apply job properties and parameters
    properties(jobProperties)

    // First build registers parameters and exits
    if (env.BUILD_NUMBER == '1') {
        echo "Jenkins is initializing this job. Please re-run to start a deployment."
        currentBuild.result = 'NOT_BUILT'
        return
    }

    // Validate all user-provided string parameters
    if (!params.TARGET_SERVICES.matches(/^[a-zA-Z0-9\s._-]*$/)) {
        error("Invalid characters in TARGET_SERVICES. Halting for security reasons.")
    }
    if (!params.LOG_TAIL_COUNT.matches(/^\d+$/)) {
        error("LOG_TAIL_COUNT must be a non-negative number.")
    }

    def targetServices = params.TARGET_SERVICES
    def logTailCount = params.LOG_TAIL_COUNT.toInteger()

    /**
    * Runs a docker compose command with optional environment file overrides.
    */
    def dockerCompose = { String args, String envFileOpts = '' ->
        def commandString = "docker compose ${envFileOpts}"
        // The 'config' command does not accept service names.
        if (args.startsWith('config')) {
            commandString += " ${args}"
        } else {
            commandString += " ${args} ${targetServices}"
        }
        sh(commandString)
    }

    /**
    * Defines the core teardown, build, and deploy logic.
    */
    def composeStages = { String envFileOpts = '' ->
        stage('Validate') {
            echo "=== Validating Docker Compose Configuration ==="
            dockerCompose("config --quiet", envFileOpts)
        }
        if (params.COMPOSE_DOWN) {
            stage('Teardown') {
                echo "=== Tearing Down Services ==="
                dockerCompose("down", envFileOpts)
            }
            return // Exit
        }
        if (params.COMPOSE_RESTART) {
            stage('Restart') {
                echo "=== Restarting Services ==="
                dockerCompose("restart", envFileOpts)
            }
            return // Exit
        }
        if (params.COMPOSE_BUILD) {
            stage('Build') {
                echo "=== Building Docker Images ==="
                dockerCompose("build", envFileOpts)
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

    /**
    * Defines the core deployment logic, allowing it to be called conditionally with or without the persistent workspace feature.
    */
    def deploymentFlow = {
        stage('Checkout') {
            checkout scm
        }
        // If a post-checkout closure was provided, execute it
        if (postCheckoutSteps) {
            postCheckoutSteps()
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

    node(agentLabel) {
        if (config.persistentWorkspace) {
            def repoName = env.JOB_NAME.split('/')[1]
            def appRoot = "${config.persistentWorkspace}/${repoName}"
            def deploymentPath = "${appRoot}/${env.BUILD_NUMBER}"
            try {
                dir(deploymentPath) {
                    deploymentFlow()
                }
            } finally {
                stage('Cleanup Old Deployments') {
                    if (params.COMPOSE_DOWN) {
                        echo "Cleaning up all persistent deployment folders for ${repoName}..."
                        sh "rm -rf ${appRoot}"
                    } else if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                        echo "Build was successful. Cleaning up old deployments..."
                        dir(appRoot) {
                            sh "find . -maxdepth 1 -mindepth 1 -type d ! -name '${env.BUILD_NUMBER}' -exec rm -rf {} +"
                        }
                    } else {
                        echo "Build failed. Skipping cleanup to preserve the last known-good deployment."
                    }
                }
            }
        } else { // Run the Docker Compose flow within the regular ephemeral agent workspace
            deploymentFlow()
        }
    }
}