/*
 * A flexible, multi-option pipeline for managing Docker Compose applications.
 *
 * IMPORTANT: Changes to Jenkins job properties (e.g., parameters or triggers)
 * are not applied instantly, as they modify the job's underlying configuration.
 * A build must run with the new code for these changes to take full effect.
 *
 * - To DISABLE triggers: Push `disableTriggers: true`. One final auto-build will
 * run, after which triggers will be off.
 *
 * - To RE-ENABLE triggers: Push `disableTriggers: false`. You must then run one
 * MANUAL build to reactivate automatic triggers for future pushes.
 *
 * (Note: This behavior does not affect execution variables like `agentLabel`,
 * which take effect immediately on the next build.)
 */
def call(Map config = [:]) {

    // Read the agent label from the config map, defaulting to 'docker'
    def agentLabel = config.agentLabel ?: 'docker'
    // Disables webhook and other build triggers if true
    def disableTriggers = config.disableTriggers ?: false
    
    // Configurable default for the 'COMPOSE_DOWN' pipeline parameter
    def defaultComposeDown = config.defaultComposeDown ?: false
    // Configurable default for the 'FORCE_RECREATE' pipeline parameter
    def defaultForceRecreate = config.defaultForceRecreate ?: false
    // Configurable default for the 'COMPOSE_BUILD' pipeline parameter
    def defaultComposeBuild = config.defaultComposeBuild ?: false
    // Configurable default for the 'PULL_IMAGES' pipeline parameter
    def defaultPullImages = config.defaultPullImages ?: false
    // Configurable default for the 'TARGET_SERVICES' pipeline parameter
    def defaultTargetServices = config.defaultTargetServices ?: ''
    // Configurable default for the 'LOG_TAIL_COUNT' pipeline parameter
    def defaultLogTailCount = config.defaultLogTailCount ?: '0'
    // Configurable default for the 'USE_BITWARDEN' pipeline parameter
    def defaultBitwardenEnabled = config.defaultBitwardenEnabled ?: false

    // Define and apply job properties and parameters
    def jobProperties = [
        parameters([
            booleanParam(name: 'COMPOSE_DOWN', defaultValue: defaultComposeDown, description: 'Action: Stop and remove all services defined in the Compose file and then exit the pipeline.'),
            booleanParam(name: 'FORCE_RECREATE', defaultValue: defaultForceRecreate, description: 'Modifier: Force a clean deployment by running `down` before `up`.'),
            booleanParam(name: 'COMPOSE_BUILD', defaultValue: defaultComposeBuild, description: 'Modifier: Build image(s) from Dockerfile(s) before deploying.'),
            booleanParam(name: 'PULL_IMAGES', defaultValue: defaultPullImages, description: 'Modifier: Pull the latest version of image(s) before deploying.'),
            stringParam(name: 'TARGET_SERVICES', defaultValue: defaultTargetServices, description: 'Option: Specify services to target (e.g., "nextcloud db redis").'),
            stringParam(name: 'LOG_TAIL_COUNT', defaultValue: defaultLogTailCount, description: 'Option: Number of log lines to show after deployment.'),
            booleanParam(name: 'USE_BITWARDEN', defaultValue: defaultBitwardenEnabled, description: 'Option: Fetch a Bitwarden secure note with the same name as the repository, parse it as a .env file, and apply the contents as secure environment variables for the compose commands.')
        ])
    ]

    if (disableTriggers) {
        jobProperties.add(overrideIndexTriggers(false))
        jobProperties.add(pipelineTriggers([]))
    }

    // Apply job properties and parameters
    properties(jobProperties)

    // First build registers parameters and exits
    if (env.BUILD_NUMBER == '1') {
        echo "Jenkins is initializing this job. Please re-run to start a deployment."
        currentBuild.result = 'NOT_BUILT'
        return
    }

    def repoName = env.JOB_NAME.split('/')[1]
    def targetServices = params.TARGET_SERVICES

    // This closure defines the core teardown, build, and deploy logic, allowing it to be called
    // conditionally with or without the Bitwarden environment wrapper.
    def stages = {
        if (params.COMPOSE_DOWN) {
            stage('Teardown') {
                echo "=== Tearing Down Services ==="
                sh "docker compose down"
            }
            return // Exit
        }
        if (params.COMPOSE_BUILD) {
            stage('Build') {
                echo "=== Building Docker Images ==="
                sh "docker compose build ${targetServices}"
            }
        }
        stage('Deploy') {
            echo "=== Deploying Services ==="
            if (params.FORCE_RECREATE) {
                echo 'Force recreate requested. Executing `docker compose down` before redeploy.'
                sh "docker compose down ${targetServices}"
            }
            if (params.PULL_IMAGES) {
                echo "Pulling latest images."
                sh "docker compose pull --ignore-pull-failures ${targetServices}"
            }
            sh "docker compose up -d ${targetServices}"
            echo "Deployment status:"
            sh "docker compose ps ${targetServices}"
            if (params.LOG_TAIL_COUNT.toInteger() > 0) {
                sleep 3 // Short sleep to give logs time to populate
                echo "--> Showing last ${params.LOG_TAIL_COUNT} log lines:"
                sh "docker compose logs --tail=${params.LOG_TAIL_COUNT} ${targetServices}"
            }
        }
    }

    node(agentLabel) {
        stage('Checkout') {
            checkout scm
        }
        // Proceed with the standard build/deploy logic if not tearing down
        if (params.USE_BITWARDEN) {
            // This library is only imported if the USE_BITWARDEN parameter is set to true
            library 'JenkinsBitwardenUtils' // See https://github.com/mwdle/JenkinsBitwardenUtils
            // Assumes a secure note exists in Bitwarden with the same name as the repository.
            // The note's contents are written to a secure temporary file used by Docker Compose.
            echo "Bitwarden integration enabled"
            withBitwarden(itemName: repoName) { credential -> // See https://github.com/mwdle/JenkinsBitwardenUtils for documentation about other supported parameters for `withBitwarden`.
                if (!credential.notes || credential.notes.trim().isEmpty()) {
                    error("Error: The 'notes' field in the Bitwarden item '${repoName}' is missing or empty.")
                }
                def tempEnvFile = "/tmp/jenkins-env-${java.util.UUID.randomUUID()}"
                try {
                    writeFile(file: tempEnvFile, text: credential.notes)
                    withEnv(["COMPOSE_ENV_FILES=${tempEnvFile}"]) {
                        stages()
                    }
                } finally {
                    // `set +x` prevents printing the command to logs
                    // Not strictly necessary but results in less confusing logging and avoids displaying filepaths in logs.
                    sh "set +x; rm -f ${tempEnvFile}"
                }
            }
        } else {
            echo "Bitwarden integration disabled"
            stages()
        }
    }
}