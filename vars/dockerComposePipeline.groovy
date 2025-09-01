def call(Map config = [:]) {
    library 'JenkinsBitwardenUtils' // See https://github.com/mwdle/JenkinsBitwardenUtils (must be added to Jenkins shared library configurations)

    // Define and apply job properties and parameters
    properties([
        parameters([
            booleanParam(name: 'COMPOSE_BUILD', defaultValue: false, description: 'Execute `docker compose build` before deploying (has no effect if there are no image(s) to build)'),
            booleanParam(name: 'COMPOSE_DOWN', defaultValue: false, description: 'Execute `docker compose down` before deploying (has no effect if there is no existing project to take down)'),
            booleanParam(name: 'PULL_IMAGES', defaultValue: false, description: 'Pull latest images(s) before deploying (has no effect if there are no image(s) to pull)')
        ])
    ])

    // First build registers parameters and exits
    if (env.BUILD_NUMBER == '1') {
        echo """
        Jenkins is initializing this job and registering parameters.
        Please re-run the job if you want to start a real deployment.
        Skipping build...
        """
        currentBuild.result = 'NOT_BUILT'
        return
    }

    // Scripted pipeline instead of declarative pipeline allows wrapping multiple stages within `withBitwardenEnv` function from shared library to avoid refetching secrets
    node('docker') { // Agent label `docker` is defined in JCasC -- see `JCasC/jenkins.yaml` in https://github.com/mwdle/JenkinsConfig
        stage('Checkout') {
            checkout scm
        }

        // Use Bitwarden provided .env variables from secure note for Docker Compose build and deploy
        // Assumes the associated .env variables are in a secure note in Bitwarden with the same name as the repository
        // Uses function from shared library defined at the top of this file
        withBitwardenEnv(itemName: env.JOB_BASE_NAME) {
            if (params.COMPOSE_BUILD) {
                stage('Build') {
                    echo "=== Building Docker Images ==="
                    sh 'docker compose build'
                }
            }

            stage('Deploy') {
                echo "=== Deploying Services ==="
                if (params.COMPOSE_DOWN) {
                    echo "Compose down requested: Executing `docker compose down` before redeploy"
                    sh 'docker compose down'
                }
                if (params.PULL_IMAGES) {
                    echo "Pulling latest images"
                    sh 'docker compose pull --ignore-pull-failures'
                }
                sh 'docker compose up -d'

                echo "Deployment status:"
                sh 'docker compose ps'
                sleep 3
                sh 'docker compose logs --tail=15'
            }
        }
    }
}