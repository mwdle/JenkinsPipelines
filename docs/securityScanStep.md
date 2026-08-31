# Security Scan Step for Jenkins

The **Security Scan** step is a Jenkins shared library function that integrates [Trivy](https://trivy.dev/) scanning into pipelines. It provides automated security checks for repository files, Infrastructure as Code (IaC) configurations, and container images, publishing rich reports directly to the Jenkins UI.

---

## How It Works

This step acts as a lightweight security monitor that provides insights without interrupting deployments:

1. **Filesystem & Secret Scanning:** Scans repository dependencies, Dockerfiles, and code for known vulnerabilities, misconfigurations, and leaked secrets using `trivy fs`.
2. **Compose Integration:** Optionally scans a given container image reference using `trivy image`.
3. **Native UI Integration:** Outputs all findings in Trivy's native JSON format and uses the Jenkins Warnings Next Generation plugin to generate dedicated security dashboards, trend graphs, and line-level code highlighting.
4. **Non-Blocking Warnings:** Scans run without interrupting pipeline execution. If issues are found, the build is gracefully marked as **`UNSTABLE`** in Jenkins via a quality gate.

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
- `docker` (optional - only required if providing a container image reference to scan)

---

## Parameters Cheatsheet

| Parameter        | Type   | Description                                                               |
| ---------------- | ------ | ------------------------------------------------------------------------- |
| `imageReference` | String | The container image reference to scan (optional, e.g., `"org/image:tag"`) |

---

## Usage in Jenkinsfiles

```groovy
@Library("JenkinsPipelines") _
// Basic usage (scans repository files only)
securityScanStep()
// Advanced usage (scans repository files, container images)
securityScanStep("my/customImage:latest")
```
