# Podman Quadlet Pipeline for Jenkins

The **Podman Quadlet Pipeline** is a Jenkins shared library pipeline for managing rootless Podman Quadlet applications over SSH. It can provision secrets, install or update an application and its generated systemd services, or remove the application.

---

## How It Works

The pipeline provides a consistent management flow for repositories containing Quadlet files and their supporting assets:

1. **Checkout and Scan:** Checks out the repository and, for installation operations, runs the shared Trivy security scan unless disabled.
2. **Secret Provisioning:** Converts configured Jenkins credentials into Podman secrets on the target host.
3. **Repository Staging:** Synchronizes the repository to a build-specific temporary staging directory. `.git` is always excluded unconditionally regardless of any configuration. Additional patterns defined by `stagingExclusions` and `additionalStagingExclusions` are also excluded.
4. **Quadlet Installation:** Installs the staged repository as a Podman Quadlet application.
5. **Service Management:** Restarts the application's container, pod, and kube services when the installed content changes or when `FORCE_RESTART` is enabled. Unchanged services are ensured to be running.
6. **Staging Cleanup:** Removes the build-specific staging directory after the operation, including when a task fails.

The repository name becomes both the Quadlet application name and the final component of its staging path. For example, a repository named `caddy-config` is temporarily staged at `~/.staging/<BUILD_TAG>/caddy-config` by default and installed as the `caddy-config` Quadlet application.

When `QUADLET_RM` is enabled, the pipeline removes the installed Quadlet application instead of scanning, provisioning secrets, staging, and installing the repository. The Security Scan stage remains visible for consistent Jenkins stage history but reports that scanning was skipped. Of the credentials managed by `quadletPipeline`, removal binds only the target SSH credential; application credentials referenced by `podmanSecretMappings` are not bound.

> [!NOTE]
> A changed application restarts all container, pod, and kube services belonging to that application. Use systemd dependencies in the Quadlet files to define startup ordering within a multi-service application.

---

## Getting Started

### Installation

Add this library to the Jenkins **Global Pipeline Libraries** configuration.

### Repository Layout

Quadlet files and any supporting build or configuration files should live in the repository. No additional deployment subdirectory is required.

Example:

```text
my-app/
├── Containerfile
├── my-app.build
├── my-app.container
└── my-app.network
```

---

## System Requirements

The Jenkins controller requires the [Workspace Cleanup Plugin](https://plugins.jenkins.io/ws-cleanup/). It also requires the [Warnings Next Generation Plugin](https://plugins.jenkins.io/warnings-ng/) when security scanning is enabled.

The Jenkins agent requires:

- `sh`
- `git`
- `ssh`
- `rsync`
- `ansible-core`
- `ansible.posix` and `containers.podman` Ansible collections
- `trivy` when security scanning is enabled

The target Linux host requires:

- SSH access for the deployment user
- Podman 6 or later
- Rootless Podman and a running user systemd manager
- `python3`
- `rsync`

---

## Pipeline Parameters Cheatsheet

| Parameter               | Type       | Description                                                                 |
| ----------------------- | ---------- | --------------------------------------------------------------------------- |
| `TARGET_HOST`           | String     | Resolvable hostname or IP address of the deployment target.                 |
| `TARGET_PORT`           | String     | SSH port on the deployment target.                                          |
| `TARGET_SSH_CREDENTIAL` | Credential | Jenkins SSH Username with private key credential used for target access.    |
| `QUADLET_RM`            | Boolean    | Stop and remove the installed Quadlet application instead of installing it. |
| `FORCE_RESTART`         | Boolean    | Force restart Quadlet services even if no changes were detected.            |

### Config Map Parameters (Jenkinsfile)

| Parameter                     | Type      | Description                                                                                                                                                                                                                                                                                                               |
| ----------------------------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agentLabel`                  | String    | Jenkins agent label (default: `"ephemeral"`).                                                                                                                                                                                                                                                                             |
| `disableConcurrentBuilds`     | Boolean   | Prevent concurrent builds of the job (default: `true`).                                                                                                                                                                                                                                                                   |
| `disableIndexTriggers`        | Boolean   | Disable automatic branch indexing triggers (default: `false`).                                                                                                                                                                                                                                                            |
| `disableSecurityScan`         | Boolean   | Disable the shared Trivy security scan (default: `false`).                                                                                                                                                                                                                                                                |
| `cronSchedule`                | String    | Cron expression for periodic builds (default: `null`).                                                                                                                                                                                                                                                                    |
| `additionalTriggers`          | List      | Additional scripted pipeline trigger objects (default: `[]`).                                                                                                                                                                                                                                                             |
| `alertEmail`                  | String    | Email address for failure notifications (default: `ALERT_EMAIL`, or disabled when unset).                                                                                                                                                                                                                                 |
| `postCheckoutSteps`           | Closure   | Custom steps to run after checkout (default: `null`).                                                                                                                                                                                                                                                                     |
| `stagingDirectory`            | String    | Base directory under the remote SSH user's home for temporary build-specific staging (default: `.staging`).                                                                                                                                                                                                               |
| `stagingExclusions`           | List      | File patterns to exclude from staging, **replacing** the default list entirely. Use `additionalStagingExclusions` to extend the defaults instead (default: `['.gitignore', 'Jenkinsfile', 'LICENSE', 'README.md', 'renovate.json']`). `.git` is always excluded unconditionally and does not need to appear in this list. |
| `additionalStagingExclusions` | List      | Extra file patterns to exclude from staging, appended to `stagingExclusions`. Use this to extend the defaults without redefining them (default: `[]`). `.git` is always excluded unconditionally and does not need to appear in this list.                                                                                |
| `podmanSecretMappings`        | List<Map> | Jenkins credential-to-Podman-secret mappings (default: `[]`).                                                                                                                                                                                                                                                             |
| `defaultTargetHost`           | String    | Default value for `TARGET_HOST` (default: `host.containers.internal`).                                                                                                                                                                                                                                                    |
| `defaultTargetPort`           | Integer   | Default value for `TARGET_PORT` (default: `52222`).                                                                                                                                                                                                                                                                       |
| `defaultTargetSshCredential`  | String    | Default Jenkins credential ID for `TARGET_SSH_CREDENTIAL` (default: `lab-ssh`).                                                                                                                                                                                                                                           |
| `defaultForceRestart`         | Boolean   | Default value for `FORCE_RESTART` (default: `false`).                                                                                                                                                                                                                                                                     |

---

## Podman Secrets

The pipeline supports Jenkins string, username/password, and file credentials.

Podman secrets are treated as immutable. If a secret with the requested name already exists on the target, the pipeline leaves it unchanged. To rotate a secret, create it under a new Podman secret name and update the consuming Quadlet to reference that name.

String and file credentials use their Jenkins credential ID as the Podman secret name by default. Set `podmanSecretStringName` or `podmanSecretFileName` only when the Podman secret should use a different name. Username/password credentials always require explicit names because they create two separate Podman secrets.

### String Credential

```groovy
[
    type: 'string',
    credentialsId: 'caddy_cloudflare_api_token'
]
```

To use a different Podman secret name:

```groovy
[
    type: 'string',
    credentialsId: 'cloudflare-api-token',
    podmanSecretStringName: 'caddy_cloudflare_api_token'
]
```

### Username and Password Credential

A Jenkins username/password credential creates two Podman secrets:

```groovy
[
    type: 'usernamePassword',
    credentialsId: 'smtp-login',
    podmanSecretUsernameName: 'smtp_username',
    podmanSecretPasswordName: 'smtp_password'
]
```

### File Credential

```groovy
[
    type: 'file',
    credentialsId: 'application_config'
]
```

The resulting names can be referenced normally from a Quadlet:

```ini
[Container]
Secret=caddy_cloudflare_api_token,target=cloudflare_api_token
```

---

## Usage in Jenkinsfiles

```groovy
@Library('JenkinsPipelines') _

quadletPipeline(
    defaultTargetSshCredential: 'homelab-ssh'
)
```

### Full Example

```groovy
@Library('JenkinsPipelines') _

quadletPipeline(
    agentLabel: 'ephemeral',
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
        echo "Running custom post-checkout steps..."
    },
    stagingDirectory: '.staging',
    stagingExclusions: ['.gitignore', 'Jenkinsfile', 'LICENSE', 'README.md', 'renovate.json', 'docs/'],
    additionalStagingExclusions: ['images/'],
    defaultTargetHost: 'host.containers.internal',
    defaultTargetPort: 22,
    defaultTargetSshCredential: 'homelab-ssh',
    defaultForceRestart: false,
    podmanSecretMappings: [
        [
            type: 'string',
            credentialsId: 'caddy_cloudflare_api_token'
        ],
        [
            type: 'usernamePassword',
            credentialsId: 'smtp-login',
            podmanSecretUsernameName: 'smtp_username',
            podmanSecretPasswordName: 'smtp_password'
        ],
        [
            type: 'file',
            credentialsId: 'application_config'
        ]
    ]
)
```

---

## Jenkins Behavior Notes

- The first build configures parameters and branch indexing behavior, then exits with a `NOT_BUILT` result without managing the application. Run the job again to install or remove it.
- Changes to job properties and parameter defaults take effect after a build updates the job configuration.
- Concurrent builds of the same job are disabled by default. Build-specific staging paths also prevent separate jobs from overwriting one another's staged files.
- The Jenkins workspace is cleaned after every run using the Workspace Cleanup plugin.
- Failure notifications use the Jenkins `ALERT_EMAIL` environment variable by default. Set `alertEmail` in the Jenkinsfile to override it; notifications are disabled when neither provides an address.
- Host key checking is disabled because agents are ephemeral. Use this pipeline only with trusted deployment targets and networks.
