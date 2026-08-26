/*
 * Security Scan Step for Jenkins
 *
 * This pipeline step runs Trivy security scans.
 * Full usage instructions, configuration options, and examples are in the README.
 */
void call(String composeFile = null) {
    stage('Security Scan') {
        boolean issuesFound = false
        
        echo '=== Scanning Repository Files ==='
        if (!trivy('fs --no-progress --scanners vuln,secret,misconfig .')) {
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
                        if (!trivy("image --no-progress \"${service.image}\"")) {
                            issuesFound = true
                        }
                    }
                }
            }
        }

        if (issuesFound) {
            unstable("Security scan found HIGH or CRITICAL issues. Check logs.")
        }
    }
}

/**
 * Runs a Trivy command. 
 * Returns false if issues were found, true if clean.
 */
private boolean trivy(String command) {
    return withEnv(['TRIVY_DISABLE_VEX_NOTICE=true']) {
        return ! sh(
            script: "trivy ${command} --no-progress --severity HIGH,CRITICAL --exit-code 1",
            returnStatus: true
        )
    }
}