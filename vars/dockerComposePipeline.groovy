// A flexible, multi-option pipeline for managing Docker Compose applications
def call(Map config = [:]) {
    // This library is only required if the USE_BITWARDEN parameter is set to true
    library 'JenkinsBitwardenUtils' // See https://github.com/mwdle/JenkinsBitwardenUtils

    // Read the agent label from the config map, defaulting to 'docker'
    def agentLabel = config.agentLabel ?: 'docker'

    // Define and apply job properties and parameters
    properties([
        parameters([
            booleanParam(name: 'COMPOSE_DOWN', defaultValue: false, description: 'Action: Stop and remove all services defined in the Compose file and then exit the pipeline.'),
            booleanParam(name: 'FORCE_RECREATE', defaultValue: false, description: 'Modifier: Force a clean deployment by running `down` before `up`.'),
            booleanParam(name: 'COMPOSE_BUILD', defaultValue: false, description: 'Modifier: Build image(s) from Dockerfile(s) before deploying.'),
            booleanParam(name: 'PULL_IMAGES', defaultValue: false, description: 'Modifier: Pull the latest version of image(s) before deploying.'),
            stringParam(name: 'TARGET_SERVICES', defaultValue: '', description: 'Option: Specify services to target (e.g., "nextcloud db redis").'),
            stringParam(name: 'LOG_TAIL_COUNT', defaultValue: '0', description: 'Option: Number of log lines to show after deployment.'),
            booleanParam(name: 'USE_BITWARDEN', defaultValue: true, description: 'Option: Fetch a Bitwarden secure note with the same name as the repository, parse it as a .env file, and apply the contents as secure environment variables for the compose commands.')
        ])
    ])

    // First build registers parameters and exits
    if (env.BUILD_NUMBER == '1') {
        echo "Jenkins is initializing this job. Please re-run to start a deployment."
        currentBuild.result = 'NOT_BUILT'
        return
    }

    def repoName = env.JOB_NAME.split('/')[1]
    def targetServices = params.TARGET_SERVICES

    // This closure defines the core build and deploy logic, allowing it to be called
    // conditionally with or without the Bitwarden environment wrapper.
    def deployAndBuildStages = {
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
        if (params.COMPOSE_DOWN) {
            stage('Teardown') {
                echo "=== Tearing Down Services ==="
                sh "docker compose down"
            }
            return // Exit
        }
        // Proceed with the standard build/deploy logic if not tearing down.
        if (params.USE_BITWARDEN) {
            // Assumes a secure note in Bitwarden with the same name as the repository.
            // The note's contents are parsed as a .env file and injected into the environment.
            echo "Bitwarden integration enabled"
            withBitwardenEnv(itemName: repoName) {
                deployAndBuildStages()
            }
        } else {
            echo "Bitwarden integration disabled"
            deployAndBuildStages()
        }
    }
}