/**
 * Wraps a block of code with a temporary environment sourced from Bitwarden.
 *
 * --- SECURITY WARNING: TEMPORARILY WRITES SECRETS TO AGENT FILESYSTEM ---
 *
 * This function temporarily writes the fetched secret content to a file on the
 * Jenkins agent's filesystem (e.g., in /tmp). While this file is immediately
 * deleted in a `finally` block, a catastrophic agent failure (e.g., power loss)
 * could prevent this cleanup from running.
 *
 * For this reason, it is STRONGLY RECOMMENDED to only use this function on
 * EPHEMERAL build agents (e.g., containers, one-shot VMs) where the
 * entire filesystem is destroyed after the build.
 *
 * USE ON PERSISTENT AGENTS AT YOUR OWN RISK.
 *
 * --- Purpose ---
 *
 * This helper function is designed to securely inject secrets for a Docker Compose execution. 
 * It fetches the contents of one or more Bitwarden secure notes, writes them to temporary `.env` files,
 * and sets the `COMPOSE_ENV_FILES` environment variable accordingly.
 *
 * The provided block of code is then executed within this context. All temporary files are automatically
 * and securely cleaned up afterward, even if the nested code fails.
 *
 * --- Example usage in a Jenkinsfile ---
 *
 * @Library("JenkinsPipelines") _
 * dockerComposePipeline(defaultBitwardenEnabled: true)
 *
 * withComposeSecrets(bitwardenItems: ["my-secure-note-env-file-name"]) {
 *     sh "docker compose up -d"
 * }
 *
 * --- Prerequisite ---
 *
 * The `JenkinsBitwardenUtils` library must be configured in Jenkins as a global shared library (Manage Jenkins → Configure System).
 * See: https://github.com/mwdle/JenkinsBitwardenUtils
 *
 * @param bitwardenItems *(Optional)* List of Bitwarden note names to fetch.
 * Defaults to a list containing the repository name: `[env.JOB_NAME.split('/')[1]]`.
 *
 * @param body Closure containing the code to execute with the secrets injected.
 */
def call(Map config = [:], Closure body) {
    // Dynamically import Bitwarden helper library
    library 'JenkinsBitwardenUtils' // See https://github.com/mwdle/JenkinsBitwardenUtils

    // Default to repo name if no bitwardenItems specified
    def bitwardenItemNames = config.bitwardenItems ?: [env.JOB_NAME.split('/')[1]]
    def envFiles = []

    // Get a path to a temporary directory (outside of the current workspace) that Jenkins will clean up at the end of the build
    def tmpdir = pwd(tmp: true)

    // Fetch secrets from Bitwarden
    withBitwarden(itemNames: bitwardenItemNames) { credentialsMap ->
        credentialsMap.each { itemName, credential ->
            if (!credential.notes.trim()) {
                error "Error: The Bitwarden item '${itemName}' contains no notes. Cannot proceed."
            }
            // Write to temporary .env file
            def envFile = "${tmpdir}/${java.util.UUID.randomUUID()}"
            writeFile(file: envFile, text: credential.notes)
            envFiles.add(envFile)
        }
    }

    // Execute the closure within the secret environment
    withEnv(["COMPOSE_ENV_FILES=${envFiles.join(',')}"]) {
        body()
    }
}