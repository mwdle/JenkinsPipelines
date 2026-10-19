# Ansible Playbook Step for Jenkins

The **Ansible Playbook Step** is a Jenkins shared library function for running an Ansible playbook against a single SSH target. It standardizes SSH credential binding, inline inventory construction, extra-variable handling, callback configuration, and command execution.

---

## How It Works

The step performs the common setup required by the Ansible-backed pipelines in this library:

1. **Playbook Resolution:** Uses either a playbook already present in the Jenkins workspace or a playbook embedded in this shared library.
2. **Extra Variables:** Serializes the supplied map to a temporary JSON file and passes it to Ansible with `-e @<file>`.
3. **SSH Authentication:** Binds a Jenkins SSH Username with private key credential to Ansible's standard environment variables.
4. **Additional Credentials:** Optionally binds other Jenkins credentials for lookup from the playbook or supporting tasks.
5. **Single-Host Inventory:** Constructs an inline inventory from the configured target host and port.
6. **Consistent Output:** Enables the `ansible.posix.profile_tasks` callback and YAML-formatted task results.

Exactly one of `playbookPath` or `playbookResource` must be provided.

---

## Getting Started

### Installation

Add this library to your Jenkins **Global Pipeline Libraries** configuration.

### System Requirements

The Jenkins controller requires the [Credentials Binding Plugin](https://plugins.jenkins.io/credentials-binding/) and [SSH Credentials Plugin](https://plugins.jenkins.io/ssh-credentials/).

The full example also uses the [Workspace Cleanup Plugin](https://plugins.jenkins.io/ws-cleanup/) to clean the Jenkins workspace and its temporary directory.

The Jenkins agent requires:

- `sh`
- `ansible-core`
- The `ansible.posix` collection
- Network access to the target SSH service

The target host requires:

- SSH access for the selected Jenkins credential
- Python suitable for the modules used by the playbook
- Any playbook-specific dependencies

---

## Parameters Cheatsheet

| Parameter            | Type           | Required | Description                                                                                                 |
| -------------------- | -------------- | -------- | ----------------------------------------------------------------------------------------------------------- |
| `playbookPath`       | String         | One of   | Path to a playbook already available in the Jenkins workspace. Mutually exclusive with `playbookResource`.  |
| `playbookResource`   | String         | One of   | Path to a playbook in this shared library's `resources/` directory. Mutually exclusive with `playbookPath`. |
| `targetHost`         | String         | Yes      | Resolvable hostname or IP address of the SSH target.                                                        |
| `targetPort`         | String/Integer | Yes      | SSH port of the target.                                                                                     |
| `sshCredential`      | String         | Yes      | Jenkins SSH Username with private key credential ID used for target authentication.                         |
| `credentialBindings` | List           | No       | Additional Jenkins credential bindings made available while Ansible runs (default: `[]`).                   |
| `extraVars`          | Map            | Yes      | JSON-serializable Ansible extra variables written to a temporary file and supplied with `-e @<file>`.       |

---

## Usage in Jenkinsfiles

### Workspace Playbook

Use `playbookPath` when the playbook is stored in the application repository:

```groovy
ansiblePlaybookStep(
    playbookPath: 'deploy.yaml',
    targetHost: params.TARGET_HOST,
    targetPort: params.TARGET_PORT,
    sshCredential: params.TARGET_SSH_CREDENTIAL,
    extraVars: [application_version: '1.2.3']
)
```

Relative paths are resolved from the current Jenkins workspace directory.

### Shared-Library Playbook

Use `playbookResource` when the playbook is stored under this library's `resources/` directory:

```groovy
ansiblePlaybookStep(
    playbookResource: 'manage-quadlet.yaml',
    targetHost: params.TARGET_HOST,
    targetPort: params.TARGET_PORT,
    sshCredential: params.TARGET_SSH_CREDENTIAL,
    extraVars: [
        repository_name: repositoryName,
        source_directory: pwd(),
        quadlet_rm: params.QUADLET_RM
    ]
)
```

### Additional Credentials

Additional credential bindings can expose secrets to the Ansible process without including secret values in `extraVars`:

```groovy
credentialBindings: [
    usernamePassword(
        credentialsId: 'sudo-password',
        usernameVariable: 'SUDO_USER',
        passwordVariable: 'SUDO_PASSWORD'
    ),
    string(credentialsId: 'application-password', variable: 'APPLICATION_PASSWORD')
]
```

Read those values from the environment in the playbook:

```yaml
vars:
  ansible_become_pass: "{{ lookup('env', 'SUDO_PASSWORD') }}"
  application_password: "{{ lookup('env', 'APPLICATION_PASSWORD') }}"
```

### Full Example

```groovy
@Library('jenkins-pipelines') _

node('ephemeral') {
    try {
        stage('Checkout') {
            checkout scm
        }

        stage('Deploy') {
            ansiblePlaybookStep(
                playbookPath: 'deploy.yaml',
                targetHost: 'app-host.example.com',
                targetPort: 22,
                sshCredential: 'app-host-ssh',
                credentialBindings: [
                    string(
                        credentialsId: 'application-token',
                        variable: 'APPLICATION_TOKEN'
                    )
                ],
                extraVars: [
                    application_version: env.BUILD_NUMBER
                ]
            )
        }
    } finally {
        cleanWs(deleteDirs: true, disableDeferredWipeout: true)
    }
}
```

In this example, `deploy.yaml` is stored in the application repository and can read the additional credential with `lookup('env', 'APPLICATION_TOKEN')`.

---

## Security and Behavior Notes

- Host-key checking is disabled. Use this step only with trusted targets and networks.
- Do not place secret values in `extraVars`; the resulting JSON file exists in the Jenkins workspace temporary directory. Bind secrets with `credentialBindings` and read them through Ansible's environment lookup instead.
- The SSH credential supplies both `ANSIBLE_PRIVATE_KEY_FILE` and `ANSIBLE_REMOTE_USER`.
- The inventory contains only `targetHost:targetPort`; this step is intentionally scoped to a single SSH target.
- The step enables `ansible.posix.profile_tasks`, so the `ansible.posix` collection must be installed on the Jenkins agent.
