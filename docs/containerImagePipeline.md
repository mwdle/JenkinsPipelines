# Container Image Pipeline for Jenkins

The **Container Image Pipeline** is a Jenkins shared library pipeline for building and publishing container images with rootless Podman on a remote build host. Jenkins agents stage the repository and invoke the build through SSH and Ansible, without requiring access to a container-engine socket.

---

## How It Works

The pipeline provides a consistent image build flow for repositories containing a Containerfile and its build context:

1. **Checkout:** Checks out the repository on the Jenkins agent and runs any configured post-checkout steps.
2. **Repository Staging:** Synchronizes the repository to a build-specific directory on the remote host while excluding `.git` metadata.
3. **Image Build:** Builds the configured image and tag using rootless Podman on the remote host.
4. **Registry Push:** Pushes the image using the selected Jenkins username/password credential.
5. **Staging Cleanup:** Removes the remote build context whether the build succeeds or fails.
6. **Security Scan:** Scans the repository and published image with Trivy unless security scanning is disabled.

Each build is staged below the remote user's home at `~/.staging/<BUILD_TAG>` by default. Jenkins provides a distinct `BUILD_TAG` containing the job identity and build number, keeping staging directories isolated across jobs, branches, and concurrent builds.

The registry password and SSH private key remain Jenkins credentials. They are not included in the staged build context or Ansible extra-vars file.

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

The Jenkins controller requires the [Warnings Next Generation Plugin](https://plugins.jenkins.io/warnings-ng/) when security scanning is enabled.

The Jenkins agent requires:

- `sh`
- `git`
- `ssh`
- `rsync`
- `ansible-core`
- `ansible.posix` and `containers.podman` Ansible collections
- `trivy` when security scanning is enabled

The remote Linux build host requires:

- SSH access for the build user
- Rootless Podman for that user
- `python3`
- `rsync`

---

## Pipeline Parameters Cheatsheet

| Parameter               | Type       | Description                                                                            |
| ----------------------- | ---------- | -------------------------------------------------------------------------------------- |
| `REGISTRY_HOST`         | String     | Container registry hostname, such as `git.mwdle.com` or `docker.io`.                   |
| `IMAGE_NAME`            | String     | Image repository name, such as `lab/jenkins-agent`.                                    |
| `REGISTRY_CREDENTIAL`   | Credential | Jenkins Username with password credential used to push and scan the image.             |
| `TAG`                   | String     | Image tag to build and push (default: `latest`).                                       |
| `CONTAINERFILE`         | String     | Containerfile path relative to the repository root (default: `Containerfile`).         |
| `NO_CACHE`              | Boolean    | Build without using cached layers (default: `false`).                                  |
| `TARGET_HOST`           | String     | Resolvable hostname or IP address of the remote build host.                            |
| `TARGET_PORT`           | String     | SSH port on the remote build host.                                                     |
| `TARGET_SSH_CREDENTIAL` | Credential | Jenkins SSH Username with private key credential used to access the remote build host. |

### Config Map Parameters (Jenkinsfile)

| Parameter                    | Type    | Description                                                                                                            |
| ---------------------------- | ------- | ---------------------------------------------------------------------------------------------------------------------- |
| `agentLabel`                 | String  | Jenkins agent label (default: `podman`).                                                                               |
| `disableConcurrentBuilds`    | Boolean | Prevent concurrent builds of the same job (default: `true`).                                                           |
| `disableIndexTriggers`       | Boolean | Disable automatic branch indexing triggers without removing configured cron or additional triggers (default: `false`). |
| `disableSecurityScan`        | Boolean | Disable the shared Trivy filesystem and image scans (default: `false`).                                                |
| `cronSchedule`               | String  | Cron expression for periodic builds (default: `null`).                                                                 |
| `additionalTriggers`         | List    | Additional scripted pipeline trigger objects (default: `[]`).                                                          |
| `alertEmail`                 | String  | Email address for failure notifications (default: `ALERT_EMAIL`, or disabled when unset).                              |
| `postCheckoutSteps`          | Closure | Custom steps to run after checkout (default: `null`).                                                                  |
| `stagingDirectory`           | String  | Directory under the remote SSH user's home used for temporary build contexts (default: `.staging`).                    |
| `defaultRegistryCredential`  | String  | Default Jenkins credential ID for `REGISTRY_CREDENTIAL` (default: `Forgejo PAT`).                                      |
| `defaultRegistryHost`        | String  | Default value for `REGISTRY_HOST` (default: `git.mwdle.com`).                                                          |
| `defaultImageName`           | String  | Default value for `IMAGE_NAME` (default: empty).                                                                       |
| `defaultTag`                 | String  | Default value for `TAG` (default: `latest`).                                                                           |
| `defaultContainerfile`       | String  | Default value for `CONTAINERFILE` (default: `Containerfile`).                                                          |
| `defaultNoCache`             | Boolean | Default value for `NO_CACHE` (default: `false`).                                                                       |
| `defaultTargetHost`          | String  | Default value for `TARGET_HOST` (default: `host.containers.internal`).                                                 |
| `defaultTargetPort`          | Integer | Default value for `TARGET_PORT` (default: `52222`).                                                                    |
| `defaultTargetSshCredential` | String  | Default Jenkins credential ID for `TARGET_SSH_CREDENTIAL` (default: `lab-ssh`).                                        |

---

## Security Considerations

> [!WARNING]
> The checked-out Containerfile and build context are executed by rootless Podman under the configured SSH account. Only build trusted, reviewed repositories with this pipeline.

**Mitigations:**

1. Use an unprivileged SSH account configured for rootless Podman.
2. Restrict the SSH and registry credentials to the minimum required access.
3. Review Containerfile and build-context changes before allowing builds from untrusted contributors.
4. Keep deployed images pinned by digest rather than automatically deploying the mutable published tag.

Host key checking is disabled because Jenkins agents are ephemeral. Use this pipeline only with trusted build targets and networks.

Trivy scans the image after it has been pushed because the Jenkins agent cannot access the remote host's Podman image storage. Findings are published through the Warnings Next Generation plugin and mark the build `UNSTABLE` rather than interrupting publication.

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
    stagingDirectory: '.staging',
    defaultRegistryCredential: 'container-registry-creds',
    defaultRegistryHost: 'registry.example.com',
    defaultImageName: 'my-org/my-image',
    defaultTag: 'latest',
    defaultContainerfile: 'Containerfile',
    defaultNoCache: false,
    defaultTargetHost: 'build-host.example.com',
    defaultTargetPort: 22,
    defaultTargetSshCredential: 'build-host-ssh'
)
```

---

## Jenkins Behavior Notes

- The first build configures parameters and branch indexing behavior, then exits with a `NOT_BUILT` result without building or publishing an image. Run the job again to build it.
- Changes to job properties and parameter defaults take effect after a build updates the job configuration.
- Concurrent builds of the same job are disabled by default. Independent jobs and branches remain isolated by their Jenkins `BUILD_TAG` staging directory.
- Failure notifications use the Jenkins `ALERT_EMAIL` environment variable by default. Set `alertEmail` in the Jenkinsfile to override it; notifications are disabled when neither provides an address.
- The registry hostname must be explicit. Use `docker.io` for Docker Hub.
- The remote staging directory is removed after every build, while Podman retains its local image layers for subsequent build caching.
