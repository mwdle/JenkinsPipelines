# Security Scan Step for Jenkins

The **Security Scan** step is a Jenkins shared library function that integrates [Trivy](https://trivy.dev/) scanning into pipelines. It provides automated security checks for repository files, Infrastructure as Code (IaC) configurations, and Docker Compose container images, publishing rich reports directly to the Jenkins UI.

---

## How It Works

This step acts as a lightweight security monitor that provides insights without interrupting deployments:

1. **Filesystem & Secret Scanning:** Scans repository dependencies, Dockerfiles, and code for known vulnerabilities, misconfigurations, and leaked secrets using `trivy fs`.
2. **Compose Integration:** Optionally parses a specified Docker Compose file to scan both the IaC definition (`trivy config`) and all referenced container images (`trivy image`).
3. **Native UI Integration:** Outputs all findings in Trivy's native JSON format and uses the Jenkins Warnings Next Generation plugin to generate dedicated security dashboards, trend graphs, and line-level code highlighting.
4. **Non-Blocking Execution:** Pipeline execution will continue regardless of the number or severity of vulnerabilities found. This ensures automated deployments (especially for third-party public images in homelabs) are never blocked by upstream CVEs outside of your control.

---

## Getting Started

### Installation

Add this library to your Jenkins **Global Pipeline Libraries** configuration.

> [!NOTE]
> **Plugin Requirements:** Your Jenkins controller must have the [Warnings Next Generation Plugin](https://plugins.jenkins.io/warnings-ng/) installed to parse the Trivy JSON results and render the UI dashboards.

---

## System Requirements

This pipeline is designed for Unix-like Jenkins agents (Linux, macOS). Required tools:

- `sh` (Bourne shell)
- `trivy`
- `docker` (optional - only required if providing a Docker Compose file to scan step)

---

## Parameters Cheatsheet

| Parameter     | Type   | Description                                                               |
| ------------- | ------ | ------------------------------------------------------------------------- |
| `composeFile` | String | Path to a Docker Compose file to scan (optional, e.g., `"compose.yaml"`). |

---

## Usage in Jenkinsfiles

```groovy
@Library("JenkinsPipelines") _
// Basic usage (scans repository files only)
securityScan()
// Advanced usage (scans repository files, plus Compose IaC and referenced images)
securityScan("docker-compose.yml")
```
