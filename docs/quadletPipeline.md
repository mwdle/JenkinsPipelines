# Podman Quadlet Pipeline for Jenkins

The **Podman Quadlet Pipeline** is a Jenkins shared library pipeline for deploying rootless Podman Quadlet applications over SSH. It synchronizes a repository to the target host, provisions Podman secrets from Jenkins credentials, installs the repository as a Quadlet application, and manages its generated systemd services.

---

## How It Works

The pipeline provides a consistent deployment flow for repositories containing Quadlet files and their supporting assets:

1. **Checkout and Scan:** Checks out the repository and runs the shared Trivy security scan unless disabled.
2. **Secret Provisioning:** Converts configured Jenkins credentials into rootless Podman secrets on the target host.
3. **Repository Synchronization:** Synchronizes the repository to a stable deployment directory while excluding `.git` metadata.
4. **Quadlet Installation:** Installs the repository directory as a Podman Quadlet application and reloads user systemd.
5. **Service Management:** Restarts the application's container, pod, and kube services when the installed content changes. Unchanged services are simply ensured to be running and enabled.

The repository name becomes both the Quadlet application name and the final component of its deployment directory. For example, `caddy-config` is deployed to `deployments/caddy-config` under the remote SSH user's home directory by default and installed as the `caddy-config` Quadlet application.

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

The Jenkins agent requires:

- `sh`
- `git`
- `ssh`
- `rsync`
- `ansible-core`
- `ansible.posix` and `containers.podman` Ansible collections
- `trivy` when security scanning is enabled
- The target Linux host requires:

- SSH access for the deployment user
- Podman 6 or later
- Rootless Podman and a running user systemd manager
- `python3`
- `rsync`

---

## Pipeline Parameters Cheatsheet

| Parameter               | Type       | Description                                                                  |
| ----------------------- | ---------- | ---------------------------------------------------------------------------- |
| `TARGET_HOST`           | String     | Resolvable hostname or IP address of the deployment target.                  |
| `TARGET_PORT`           | String     | SSH port on the deployment target.                                           |
| `TARGET_SSH_CREDENTIAL` | Credential | Jenkins SSH Username with private key credential used for target access.     |
| `QUADLET_RM`            | Boolean    | Stop and remove the Quadlet application and deployment directory, then exit. |

### Config Map Parameters (Jenkinsfile)

| Parameter                    | Type      | Description                                                                                   |
| ---------------------------- | --------- | --------------------------------------------------------------------------------------------- |
| `agentLabel`                 | String    | Jenkins agent label (default: `"podman"`).                                                    |
| `disableConcurrentBuilds`    | Boolean   | Prevent concurrent builds of the job (default: `true`).                                       |
| `disableIndexTriggers`       | Boolean   | Disable automatic branch indexing triggers (default: `false`).                                |
| `disableSecurityScan`        | Boolean   | Disable the shared Trivy security scan (default: `false`).                                    |
| `cronSchedule`               | String    | Cron expression for periodic builds (default: `null`).                                        |
| `additionalTriggers`         | List      | Additional scripted pipeline trigger objects (default: `[]`).                                 |
| `alertEmail`                 | String    | Email address for failure notifications (default: `null`).                                    |
| `postCheckoutSteps`          | Closure   | Custom steps to run after checkout (default: `null`).                                         |
| `deploymentsDirectory`       | String    | Directory under the remote SSH user's home for staging repositories (default: `deployments`). |
| `podmanSecretMappings`       | List<Map> | Jenkins credential-to-Podman-secret mappings (default: `[]`).                                 |
| `defaultTargetHost`          | String    | Default value for `TARGET_HOST` (default: `host.containers.internal`).                        |
| `defaultTargetPort`          | Integer   | Default value for `TARGET_PORT` (default: `52222`).                                           |
| `defaultTargetSshCredential` | String    | Default Jenkins credential ID for `TARGET_SSH_CREDENTIAL` (default: `lab-ssh`).               |

---

## Podman Secrets

The pipeline supports Jenkins string, username/password, and file credentials. Jenkins exposes the selected credentials only for the deployment step, and the Ansible secret task suppresses its output.

Podman secrets are treated as immutable. If a secret with the requested name already exists on the target, the pipeline leaves it unchanged. To rotate a secret, create it under a new Podman secret name and update the consuming Quadlet to reference that name.

### String Credential

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
    credentialsId: 'application-config',
    podmanSecretFileName: 'application_config'
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
        echo "Running custom post-checkout steps..."
    },
    deploymentsDirectory: 'deployments',
    defaultTargetHost: 'host.containers.internal',
    defaultTargetPort: 22,
    defaultTargetSshCredential: 'homelab-ssh',
    podmanSecretMappings: [
        [
            type: 'string',
            credentialsId: 'cloudflare-api-token',
            podmanSecretStringName: 'caddy_cloudflare_api_token'
        ],
        [
            type: 'usernamePassword',
            credentialsId: 'smtp-login',
            podmanSecretUsernameName: 'smtp_username',
            podmanSecretPasswordName: 'smtp_password'
        ],
        [
            type: 'file',
            credentialsId: 'application-config',
            podmanSecretFileName: 'application_config'
        ]
    ]
)
```

---

## Jenkins Behavior Notes

- The first build registers the Jenkins parameters and exits with a `NOT_BUILT` result. Run the job again to deploy.
- Changes to job properties and parameter defaults take effect after a build updates the job configuration.
- Concurrent builds are disabled by default to prevent two deployments of the same application from overlapping.
- Host key checking is disabled because agents are ephemeral. Use this pipeline only with trusted deployment targets and networks.
