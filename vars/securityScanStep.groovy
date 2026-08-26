/*
 * Security Scan Step for Jenkins
 *
 * This pipeline step runs Trivy security scans.
 * Full usage instructions, configuration options, and examples are in the README.
 */
void call(String composeFile = null) {
    stage('Security Scan') {
        boolean issuesFound = false
        echo '=== Scanning Repository Files for Vulnerabilities ==='
        if (!trivy('fs --no-progress --severity HIGH,CRITICAL --scanners vuln .')) {
            issuesFound = true
        }
        echo '=== Scanning Repository Files for Secrets and Misconfigurations ==='
        if (!trivy('fs --no-progress --scanners secret,misconfig .')) {
            issuesFound = true
        }
        if (composeFile && fileExists(composeFile)) {
            echo "=== Scanning Compose Config: ${composeFile} ==="
            if (!trivy("config ${composeFile}")) {
                issuesFound = true
            }

            echo '=== Scanning Compose Images ==='
            def composeData = readYaml file: composeFile
            if (composeData?.services) {
                composeData.services.each { name, service ->
                    if (service.image) {
                        echo "--> Scanning Image: ${service.image}"
                        if (!trivy("image --severity HIGH,CRITICAL --no-progress \"${service.image}\"")) {
                            issuesFound = true
                        }
                    }
                }
            }
        }
        if (issuesFound) {
            unstable("Security scan found issues warranting attention. Please check logs.")
        }
    }
}

/**
 * Runs a Trivy command.
 * Returns false if issues warranting attention were found, true otherwise.
 */
private boolean trivy(String command) {
    return withEnv(['TRIVY_DISABLE_VEX_NOTICE=true']) {
        return ! sh(
            script: "trivy ${command} --exit-code 1",
            returnStatus: true
        )
    }
}