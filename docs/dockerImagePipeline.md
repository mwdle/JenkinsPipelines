# Docker Image Pipeline for Jenkins

The **Docker Image Pipeline** is a flexible Jenkins pipeline library designed to streamline building, tagging, and pushing Docker images to registries. It provides robust parameterization, credential injection, and configurable build behavior to make container image workflows predictable and repeatable.

---

## How It Works

This pipeline automates Docker image builds inside Jenkins. Key features include:

1. **Dynamic Parameterization:** Configure pipeline behavior at runtime with Jenkins build parameters for image name, tag, Dockerfile location, and caching.
2. **Credential Integration:** Securely inject Docker registry credentials stored in Jenkins.
3. **Git SHA Tagging:** Automatically tags built images with the current commit SHA for traceability.
4. **Flexible Builds:** Support for no-cache builds and custom Dockerfiles.

---

## Getting Started

### Installation

Add this library to your Jenkins **Global Pipeline Libraries** configuration

---

## System Requirements

This pipeline is designed for Unix-like Jenkins agents (Linux, macOS). Required tools:

- `sh` (Bourne shell)
- `git`
- `docker`

---

## Pipeline Parameters Cheatsheet

| Parameter               | Type    | Description                                                     |
| ----------------------- | ------- | --------------------------------------------------------------- |
| `IMAGE_NAME`            | String  | Docker image name to build and push (e.g., `"my-org/my-app"`).  |
| `TAG`                   | String  | Tag to apply to the image (e.g., `"latest"`, `"v1.0.0"`).       |
| `DOCKERFILE`            | String  | Path to the Dockerfile to build.                                |
| `NO_CACHE`              | Boolean | Build Docker image without cache.                               |
| `DOCKER_CREDENTIALS_ID` | String  | Jenkins credentials ID for authenticating to Docker registries. |

### Config Map Parameters (Jenkinsfile)

| Parameter                    | Type    | Description                                           |
| ---------------------------- | ------- | ----------------------------------------------------- |
| `agentLabel`                 | String  | Jenkins agent label (default: `"docker"`).            |
| `disableTriggers`            | Boolean | Disable pipeline triggers (default: `false`).         |
| `cronSchedule`               | String  | Cron expression for periodic builds.                  |
| `defaultDockerCredentialsId` | String  | Default Jenkins credentials ID for Docker registries. |
| `defaultImageName`           | String  | Default Docker image name.                            |
| `defaultDockerfile`          | String  | Default Dockerfile path.                              |
| `defaultTag`                 | String  | Default Docker image tag.                             |
| `defaultNoCache`             | Boolean | Default for building with no cache.                   |

---

## Security Considerations

> [!WARNING]
> This pipeline involves pulling credentials for Docker registries.  
> Improper handling of these credentials can result in security risks.

**Mitigations:**

1. Use Jenkins credential management to store and restrict access to credentials.
2. Restrict who can configure or trigger this pipeline.
3. Review Dockerfiles and build contexts for vulnerabilities.

---

## Usage in Jenkinsfiles

```groovy
@Library("JenkinsPipelines") _
dockerImagePipeline(
    defaultDockerCredentialsId: 'docker-hub',
    defaultImageName: 'my-org/my-app'
)
```

---

## Jenkins Behavior Notes

Changes to job properties (like parameter defaults) require **one build run** to take effect.  
Example:

- To disable triggers: `disableTriggers: true` → run build → triggers off.
- To re-enable triggers: `disableTriggers: false` → run build manually → triggers on.

## Full Example Jenkinsfile

```groovy
@Library("JenkinsPipelines") _

dockerImagePipeline(
    agentLabel: 'docker',
    disableTriggers: false,
    cronSchedule: '0 0 * * *',
    defaultDockerCredentialsId: 'docker-hub',
    defaultImageName: 'my-org/my-app',
    defaultDockerfile: 'Dockerfile',
    defaultTag: 'latest',
    defaultNoCache: false
)
```
