# Container Image Pipeline for Jenkins

The **Container Image Pipeline** is a Jenkins shared library pipeline for building and publishing container images through a remote rootless Podman service. A socket-enabled Jenkins agent uses the Podman remote client to upload the checked-out repository as the build context.

---

## How It Works

The pipeline provides a consistent image build flow for repositories containing a Containerfile and its build context:

1. **Checkout:** Checks out the repository on the Jenkins agent and runs any configured post-checkout steps.
2. **Image Build:** Builds the configured image through the remote Podman service.
3. **Security Scan:** Scans the repository and locally built image with Trivy, or reports that scanning was skipped when disabled.
4. **Registry Push:** Pushes the image using the selected Jenkins username/password credential.

The registry password remains a Jenkins credential and is passed to Podman through standard input only during the push stage. The pipeline logs out after the push completes.

---

## Getting Started

### Installation

Add this library to the Jenkins **Global Pipeline Libraries** configuration.

### Repository Layout

The Containerfile and any files it references must live in the repository. A custom relative Containerfile path can be selected with the `CONTAINERFILE` build parameter.

Example:

```text
my-image/
├── Containerfile
├── Jenkinsfile
├── requirements.txt
└── src/
```

Use `.containerignore` to exclude files from the Podman build context.

---

## System Requirements

The Jenkins controller requires the [Workspace Cleanup Plugin](https://plugins.jenkins.io/ws-cleanup/). It also requires the [Warnings Next Generation Plugin](https://plugins.jenkins.io/warnings-ng/) when security scanning is enabled.

The Jenkins agent requires:

- `sh`
- `git`
- `podman-remote`
- Access to the remote Podman socket through `CONTAINER_HOST` and `DOCKER_HOST`
- `trivy` when security scanning is enabled

The configured Podman service must support remote image builds and pushes.

---

## Pipeline Parameters Cheatsheet

| Parameter             | Type       | Description                                                                      |
| --------------------- | ---------- | -------------------------------------------------------------------------------- |
| `REGISTRY_HOST`       | String     | Container registry hostname, such as `git.mwdle.com` or `docker.io`.             |
| `IMAGE_NAME`          | String     | Image repository name, such as `lab/jenkins-agent`.                              |
| `REGISTRY_CREDENTIAL` | Credential | Jenkins Username with password credential used to access the container registry. |
| `TAG`                 | String     | Image tag to build and push (default: `latest`).                                 |
| `CONTAINERFILE`       | String     | Containerfile path relative to the repository root (default: `Containerfile`).   |
| `NO_CACHE`            | Boolean    | Build without using cached layers (default: `false`).                            |

### Config Map Parameters (Jenkinsfile)

| Parameter                   | Type    | Description                                                                                                            |
| --------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------- |
| `agentLabel`                | String  | Jenkins agent label (default: `podman`).                                                                               |
| `disableConcurrentBuilds`   | Boolean | Prevent concurrent builds of the same job (default: `true`).                                                           |
| `disableIndexTriggers`      | Boolean | Disable automatic branch indexing triggers without removing configured cron or additional triggers (default: `false`). |
| `disableSecurityScan`       | Boolean | Disable the shared Trivy filesystem and image scans (default: `false`).                                                |
| `cronSchedule`              | String  | Cron expression for periodic builds (default: `null`).                                                                 |
| `additionalTriggers`        | List    | Additional scripted pipeline trigger objects (default: `[]`).                                                          |
| `alertEmail`                | String  | Email address for failure notifications (default: `ALERT_EMAIL`, or disabled when unset).                              |
| `postCheckoutSteps`         | Closure | Custom steps to run after checkout (default: `null`).                                                                  |
| `defaultRegistryCredential` | String  | Default Jenkins credential ID for `REGISTRY_CREDENTIAL` (default: `Forgejo PAT`).                                      |
| `defaultRegistryHost`       | String  | Default value for `REGISTRY_HOST` (default: `git.mwdle.com`).                                                          |
| `defaultImageName`          | String  | Default value for `IMAGE_NAME` (default: empty).                                                                       |
| `defaultTag`                | String  | Default value for `TAG` (default: `latest`).                                                                           |
| `defaultContainerfile`      | String  | Default value for `CONTAINERFILE` (default: `Containerfile`).                                                          |
| `defaultNoCache`            | Boolean | Default value for `NO_CACHE` (default: `false`).                                                                       |

---

## Security Considerations

> [!WARNING]
> The `podman` agent has full control over every container managed by the configured Podman service. Only build trusted, reviewed repositories with this pipeline.

**Mitigations:**

1. Connect only to the nested rootless PinP service, never the outer host's Podman socket.
2. Reserve the `podman` agent label for trusted pipelines that require container-runtime access.
3. Restrict registry credentials to the minimum required access.
4. Review Containerfile and build-context changes before allowing builds from untrusted contributors.
5. Keep deployed images pinned by digest rather than automatically deploying the mutable published tag.

Trivy scans the locally built image through PinP's Docker-compatible API before it is pushed. Findings are published through the Warnings Next Generation plugin and mark the build `UNSTABLE` rather than preventing publication.

---

## Usage in Jenkinsfiles

```groovy
@Library('JenkinsPipelines') _

containerImagePipeline(
    defaultImageName: 'lab/jenkins-agent'
)
```

### Full Example

```groovy
@Library('JenkinsPipelines') _

containerImagePipeline(
    agentLabel: 'podman',
    disableConcurrentBuilds: true,
    disableIndexTriggers: false,
    disableSecurityScan: false,
    cronSchedule: '0 0 * * *',
    additionalTriggers: [
        // Built in triggers can be provided here.
        // The GenericTrigger in particular requires the Generic Webhook Trigger plugin.
        // Must use scripted pipeline syntax.
        [$class: 'GenericTrigger',
            genericVariables: [
                [key: 'GIT_REPO', value: '$.repository.full_name'],
                [key: 'GIT_SENDER', value: '$.sender.login']
            ],
            causeString: 'Triggered by webhook',
            token: '', tokenCredentialId: 'My Webhook Token',
        ]
    ],
    alertEmail: 'admin@example.com',
    postCheckoutSteps: {
        echo 'Running custom post-checkout steps...'
    },
    defaultRegistryCredential: 'container-registry-creds',
    defaultRegistryHost: 'registry.example.com',
    defaultImageName: 'my-org/my-image',
    defaultTag: 'latest',
    defaultContainerfile: 'Containerfile',
    defaultNoCache: false
)
```

---

## Jenkins Behavior Notes

- The first build configures parameters and branch indexing behavior, then exits with a `NOT_BUILT` result without building or publishing an image. Run the job again to build it.
- Changes to job properties and parameter defaults take effect after a build updates the job configuration.
- Concurrent builds of the same job are disabled by default.
- The Jenkins workspace is cleaned after every run using the Workspace Cleanup plugin.
- Failure notifications use the Jenkins `ALERT_EMAIL` environment variable by default. Set `alertEmail` in the Jenkinsfile to override it; notifications are disabled when neither provides an address.
- The registry hostname must be explicit. Use `docker.io` for Docker Hub.
- PinP retains image layers for subsequent build caching. The Jenkins workspace is the build context and is removed after every run.
