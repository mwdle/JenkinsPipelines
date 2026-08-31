/*
 * Security Scan Step for Jenkins
 *
 * This pipeline step runs Trivy security scans.
 * Full usage instructions, configuration options, and examples are in the README.
 */
void call(String imageReference = null) {
    trivy('fs --scanners vuln --severity HIGH,CRITICAL .', 'fs-vuln')
    trivy('fs --scanners misconfig,secret .', 'fs-misconfig-secret')
    if (imageReference) {
        trivy("image --severity HIGH,CRITICAL '${imageReference}'", "image")
    }
    recordIssues(
        tool: trivy(pattern: 'trivy-*.json'),
        qualityGates: [[threshold: 1, type: 'TOTAL', criticality: 'UNSTABLE']]
    )
}

private void trivy(String command, String scanName) {
    withEnv(['TRIVY_DISABLE_VEX_NOTICE=true']) {
        sh script: "trivy ${command} --ignore-unfixed --disable-telemetry --no-progress --format json --output 'trivy-${scanName}.json'"
    }
}