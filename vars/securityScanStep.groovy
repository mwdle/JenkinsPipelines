/*
 * Security Scan Step for Jenkins
 *
 * This pipeline step runs Trivy security scans.
 * Full usage instructions, configuration options, and examples are in the README.
 */
void call(String composeFile = null) {
    stage('Security Scan') {
        trivy('fs --no-progress --severity HIGH,CRITICAL --scanners vuln .', 'trivy-fs-vuln')
        trivy('fs --no-progress --scanners secret,misconfig .', 'trivy-fs-misconfig-secret')
        if (composeFile && fileExists(composeFile)) {
            trivy("config ${composeFile}", 'trivy-compose-config')
            def composeData = readYaml file: composeFile
            if (composeData?.services) {
                composeData.services.each { name, service ->
                    if (service.image) {
                        trivy("image --severity HIGH,CRITICAL --no-progress \"${service.image}\"", "trivy-image-${name}")
                    }
                }
            }
        }
        recordIssues tool: trivy(pattern: 'trivy-*.json')
    }
}

private void trivy(String command, String outputFile) {
    withEnv(['TRIVY_DISABLE_VEX_NOTICE=true']) {
        sh script: "trivy ${command} --format json --output ${outputFile}.json"
    }
}