# Security Scan Step for Jenkins

The **Security Scan** step is a Jenkins shared library function that integrates [Trivy](https://trivy.dev/) scanning into pipelines. It provides automated security checks for repository files, Infrastructure as Code (IaC) configurations, and Docker Compose container images.

---

## How It Works

This step acts as a lightweight, non-blocking security gate:

1. **Filesystem & Secret Scanning:** Scans repository dependencies, Dockerfiles, and code for known vulnerabilities, misconfigurations, and leaked secrets using `trivy fs`.
2. **Compose Integration:** Optionally parses a specified Docker Compose file to scan both the IaC definition (`trivy config`) and all referenced container images (`trivy image`).
3. **Graceful Warning System:** If `HIGH` or `CRITICAL` issues are found, the build is marked as **`UNSTABLE`** (yellow) in Jenkins, but execution continues without halting deployment.

---

## Getting Started

### Installation

Add this library to your Jenkins **Global Pipeline Libraries** configuration

---

## System Requirements

This pipeline is designed for Unix-like Jenkins agents (Linux, macOS). Required tools:

- `sh` (Bourne shell)
- `trivy`
- `docker` (optional)

---

## Parameters Cheatsheet

| Parameter     | Type   | Description                                                               |
| ------------- | ------ | ------------------------------------------------------------------------- |
| `composeFile` | String | Path to a Docker Compose file to scan (optional, e.g., `'compose.yaml'`). |

---

## Usage in Jenkinsfiles

```groovy
@Library("JenkinsPipelines") _
securityScan("compose.yaml")
```
