import java.nio.file.Paths

/*
 * A flexible, multi-option pipeline for managing Docker Compose applications.
 *
 * --- Customization ---
 *
 * This pipeline can be customized with a post-checkout hook. By providing a
 * closure to the `postCheckoutSteps` parameter, you can perform any custom
 * preparatory steps immediately after the source code is checked out.
 *
 * --- Using Secrets with Bitwarden ---
 *
 * To use this feature correctly, it's important to understand the two ways Docker Compose uses environment files:
 *
 * 1. For Variable Substitution: Docker Compose looks for a default `.env` file in your project root.
 *    It uses these variables to substitute values inside the `docker-compose.yml` file itself (e.g., replacing `${IMAGE_TAG}`).
 *
 * 2. For Container Environments: The `env_file:` directive loads variables from a file directly into that specific container for your application to use at runtime.
 *
 * This pipeline feature uses the first method ONLY. It fetches your Bitwarden note and provides its contents to Docker Compose for variable substitution.
 * Because of this, you CANNOT use the `env_file:` directive to load secrets from Bitwarden, as the pipeline does not place a physical file in your workspace for that purpose.

 * For maximum security, it is highly recommended to run this pipeline on ephemeral Jenkins agents (e.g., containers).
 * This ensures the temporary secret file is destroyed along with the agent's filesystem after the build,
 * providing an absolute guarantee of cleanup.
 *
 * --- Security Advisory (CWE-209) ---
 *
 * This pipeline is vulnerable to an "Information Exposure Through Error Message" attack.
 * The `docker compose` commands used throughout this pipeline can leak sensitive environment variables into the build logs via their verbose error messages.
 *
 * An actor with commit access to a repository using this pipeline could deliberately craft a malformed `compose.yaml` to intentionally trigger a descriptive validation error,
 * e.g., during a `docker compose up` command, causing a secret to be printed in the log.
 *
 * The primary mitigation for this is organizational, not technical:
 * 1. Enforce code reviews on all changes to `compose.yaml`.
 * 2. Strictly limit commit and/or Jenkins Job access using the principle of least privilege.
 *
 * --- Important Jenkins Behavior ---
 *
 * Changes to job properties (like build parameter defaults or triggers configured in the Jenkinsfile) are not applied instantly. A build must run with the new code for these configuration changes to take full effect.
 *
 * For example:
 * - To DISABLE triggers: Push `disableTriggers: true`. One final auto-build will run, after which triggers will be off.
 * - To RE-ENABLE triggers: Push `disableTriggers: false`, then run one MANUAL build to reactivate automatic triggers.
 *
 * --- System Requirements ---
 *
 * This pipeline is designed for and tested on Unix-like Jenkins agents (e.g., Linux, macOS). The following tools are required on the agent:
 * - `sh` (Bourne shell)
 * - Bitwarden CLI (`bw`)
 * - `git`
 */
def call(Map config = [:]) {

    // Read the agent label from the config map, defaulting to 'docker'
    def agentLabel = config.agentLabel ?: 'docker'
    // Disables webhook and other build triggers if true
    def disableTriggers = config.disableTriggers ?: false
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
    // Configurable default for the 'USE_BITWARDEN' pipeline parameter
    def defaultBitwardenEnabled = config.defaultBitwardenEnabled ?: false

    // Define and apply job properties and parameters
    def jobProperties = [
        parameters([
            booleanParam(name: 'COMPOSE_DOWN', defaultValue: defaultComposeDown, description: 'Action: Stop and remove services and then exit the pipeline.'),
            booleanParam(name: 'COMPOSE_RESTART', defaultValue: defaultComposeRestart, description: 'Action: Restart services and then exit the pipeline.'),
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

    // Validate all user-provided string parameters
    if (!params.TARGET_SERVICES.matches(/^[a-zA-Z0-9\s._-]*$/)) {
        error("Invalid characters in TARGET_SERVICES. Halting for security reasons.")
    }
    if (!params.LOG_TAIL_COUNT.matches(/^\d+$/)) {
        error("LOG_TAIL_COUNT must be a non-negative number.")
    }

    def targetServices = params.TARGET_SERVICES
    def logTailCount = params.LOG_TAIL_COUNT.toInteger()

    // This closure defines the core teardown, build, and deploy logic, allowing it to be called
    // conditionally with or without the Bitwarden environment wrapper.
    def stages = {
        stage('Validate') {
            echo "=== Validating Docker Compose Configuration ==="
            sh "docker compose config --quiet"
        }
        if (params.COMPOSE_DOWN) {
            stage('Teardown') {
                echo "=== Tearing Down Services ==="
                sh "docker compose down ${targetServices}"
            }
            return // Exit
        }
        if (params.COMPOSE_RESTART) {
            stage('Restart') {
                echo "=== Restarting Services ==="
                sh "docker compose restart ${targetServices}"
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
            if (logTailCount > 0) {
                sleep 3 // Short sleep to give logs time to populate
                echo "--> Showing last ${logTailCount} log lines:"
                sh "docker compose logs --tail=${logTailCount} ${targetServices}"
            }
        }
    }

    node(agentLabel) {
        stage('Checkout') {
            checkout scm
        }
        // If a post-checkout closure was provided, execute it
        if (postCheckoutSteps) {
            postCheckoutSteps()
        }
        if (params.USE_BITWARDEN) {
            echo "Bitwarden integration enabled"
            _withBitwardenEnv(config) {
                stages()
            } 
        } else {
            echo "Bitwarden integration disabled"
            stages()
        }
    }
}

/**
 * Wraps a block of code with a temporary environment sourced from Bitwarden.
 *
 * This helper function is designed to securely inject secrets for a Docker Compose execution. 
 * It fetches the contents of one or more Bitwarden secure notes, writes them to temporary `.env` files, and sets the `COMPOSE_ENV_FILES` environment variable accordingly.
 *
 * The provided block of code is then executed within this context. All temporary files are automatically and securely cleaned up afterward, even if the nested code fails.
 *
 * @param config The pipeline configuration map. Can contain `bitwardenItems` (a List of note names) to override the default behavior of using the repo name.
 * @param body A closure of code to execute within the configured environment.
 */
private void _withBitwardenEnv(Map config, Closure body) {
    // The `JenkinsBitwardenUtils` library is dynamically imported so it is only loaded if needed
    library 'JenkinsBitwardenUtils' // See https://github.com/mwdle/JenkinsBitwardenUtils
    // If 'bitwardenItems' is not provided in the config, default to a list containing just the repository name.
    def bitwardenItemNames = config.bitwardenItems ?: [env.JOB_NAME.split('/')[1]]
    def envFiles = []
    try {
        withBitwarden(itemNames: bitwardenItemNames) { credentialsMap -> // See https://github.com/mwdle/JenkinsBitwardenUtils for documentation about other supported parameters for `withBitwarden`.
            credentialsMap.each { itemName, credential ->
                if (!credential.notes || credential.notes.trim().isEmpty()) {
                    error("Error: The 'notes' field in the Bitwarden item '${itemName}' is missing or empty.")
                }
                def envFile = Paths.get(System.getProperty("java.io.tmpdir"), "${java.util.UUID.randomUUID()}.env").toString()
                writeFile(file: envFile, text: credential.notes)
                envFiles.add(envFile)
            }
        }
        withEnv(["COMPOSE_ENV_FILES=${envFiles.join(',')}"]) {
            body() // Execute the closure
        }
    } finally {
        // Cleanup all the temporary files that were created
        envFiles.each { filePath ->
            sh "rm -f '${filePath}'"
        }
    }
}