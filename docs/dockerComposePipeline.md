# Docker Compose Pipeline for Jenkins

The **Docker Compose Pipeline** is a flexible Jenkins pipeline library designed to streamline deployment and management of multi-service Docker Compose applications. It provides robust parameterization, secrets injection, workspace persistence, and custom hooks to make deployments predictable and repeatable.

---

## How It Works

This pipeline library automates Docker Compose workflows inside Jenkins. Key features include:

1. **Dynamic Parameterization:** Configure pipeline behavior at runtime with Jenkins build parameters for teardown, restart, build, image pull, and service targeting.
2. **Secrets Integration:** Securely injects `.env` files stored in Jenkins file credentials into Docker Compose.
3. **Custom Hooks:** Add custom pre-deployment logic via a post-checkout hook closure.
4. **Persistent Workspaces:** Maintain consistent bind-mounted deployment folders on the host to support relative mounts and avoid re-deploying unchanged services.
5. **Self-Cleaning Deployments:** Automatically clean old deployment directories after successful runs, ensuring efficient disk usage.

---

## Compose Project Naming

Docker Compose uses a **project name** to isolate resources (containers, networks, volumes). By default, it uses the folder name, which is not guaranteed to be consistent in Jenkins.

> [!WARNING]
> Define a static project name in your `docker-compose.yml` to avoid unpredictable project naming.

Example:

```yaml
name: myProjectName
```

or in an `.env` file:

```env
COMPOSE_PROJECT_NAME=my-app
```

This ensures that the pipeline updates an existing deployment instead of creating a new parallel stack.

---

## Providing Secrets via `.env`

Docker Compose supports environment variables in two ways:

1. **Variable Substitution:** Docker Compose uses a default `.env` file in your project root to replace `${VAR}` in the compose file.
2. **Container Environments:** The `env_file:` directive loads variables directly into containers.

This pipeline uses **Variable Substitution** only. `.env` files are fetched from Jenkins File Credentials and are **not** permanently written to disk, so `env_file:` cannot be used directly.

**To inject `.env` files at runtime**, pass a list of Jenkins file credential IDs via the `envFileCredentialIds` config key.

Example:

```groovy
@Library("JenkinsPipelines") _
dockerComposePipeline(
    envFileCredentialIds: ['my-app-secrets'],
    persistentWorkspace: '/opt/jenkins/deployments'
)
```

**Requirements:**

- Each credential ID must reference a Jenkins "File" credential.
- The credential file must be in valid `.env` format.

---

## Getting Started

### Installation

Add this library to your Jenkins **Global Pipeline Libraries** configuration

### Example: Custom Post-Checkout Hook

```groovy
postCheckoutSteps: {
    sh "git submodule update --init --recursive"
}
```

---

## System Requirements

This pipeline is designed for Unix-like Jenkins agents (Linux, macOS). Required tools:

- `sh` (Bourne shell)
- `git`
- `docker`

---

## Pipeline Parameters Cheatsheet

| Parameter         | Type    | Description                                        |
| ----------------- | ------- | -------------------------------------------------- |
| `COMPOSE_DOWN`    | Boolean | Stop and remove services, then exit.               |
| `COMPOSE_RESTART` | Boolean | Restart services, then exit.                       |
| `FORCE_RECREATE`  | Boolean | Force redeployment by running `down` before `up`.  |
| `COMPOSE_BUILD`   | Boolean | Build images before deploying.                     |
| `PULL_IMAGES`     | Boolean | Pull latest images before deploying.               |
| `TARGET_SERVICES` | String  | Services to target (e.g., `"nextcloud db redis"`). |
| `LOG_TAIL_COUNT`  | String  | Number of log lines to show after deployment.      |

### Config Map Parameters (Jenkinsfile)

| Parameter               | Type         | Description                                   |
| ----------------------- | ------------ | --------------------------------------------- |
| `agentLabel`            | String       | Jenkins agent label (default: `"docker"`).    |
| `disableTriggers`       | Boolean      | Disable pipeline triggers (default: `false`). |
| `cronSchedule`          | String       | Cron expression for periodic builds.          |
| `postCheckoutSteps`     | Closure      | Hook to execute after checkout.               |
| `defaultComposeDown`    | Boolean      | Default value for `COMPOSE_DOWN`.             |
| `defaultComposeRestart` | Boolean      | Default value for `COMPOSE_RESTART`.          |
| `defaultForceRecreate`  | Boolean      | Default value for `FORCE_RECREATE`.           |
| `defaultComposeBuild`   | Boolean      | Default value for `COMPOSE_BUILD`.            |
| `defaultPullImages`     | Boolean      | Default value for `PULL_IMAGES`.              |
| `defaultTargetServices` | String       | Default value for `TARGET_SERVICES`.          |
| `defaultLogTailCount`   | String       | Default value for `LOG_TAIL_COUNT`.           |
| `envFileCredentialIds`  | List<String> | Jenkins File credential IDs for `.env` files. |
| `persistentWorkspace`   | String       | Path to bind-mounted workspace on host.       |

---

## Security Considerations

> [!WARNING]
> This pipeline is vulnerable to **Information Exposure Through Error Messages** (CWE-209).  
> Malicious `docker-compose.yml` files could expose secrets injected from `.env` files.

**Mitigations:**

1. Enforce strict code review of `docker-compose.yml` changes.
2. Limit Jenkins job access using least privilege principles.
3. Follow credentials management best practices.

---

## Usage in Jenkinsfiles

```groovy
@Library("JenkinsPipelines") _
dockerComposePipeline(
    envFileCredentialIds: ['my-app-secrets'],
    persistentWorkspace: '/opt/AppData'
)
```

---

## Persistent Workspace Setup

To support relative bind mounts (`./file`) in your `docker-compose.yml`:

1. **Configure Jenkins Agent:** Bind mount the deployment directory with identical paths.  
   Example JCasC agent template:

   ```yaml
   type=bind,source=/opt/AppData,destination=/opt/AppData
   ```

2. **Set Persistent Workspace:** In your Jenkinsfile:

   ```groovy
   persistentWorkspace: '/opt/AppData'
   ```

> [!WARNING]
> The persistent workspace path must be dedicated to deployments. The pipeline will **delete old deployment folders** in this path.

---

## Jenkins Behavior Notes

Changes to job properties (like parameter defaults) require **one build run** to take effect.  
Example:

- To disable triggers: `disableTriggers: true` → run build → triggers off.
- To re-enable triggers: `disableTriggers: false` → run build manually → triggers on.

---

## Full Example Jenkinsfile

```groovy
@Library("JenkinsPipelines") _

dockerComposePipeline(
    agentLabel: 'docker',
    disableTriggers: false,
    cronSchedule: '0 0 * * *',
    postCheckoutSteps: {
        echo "Running custom post-checkout steps..."
        sh "git submodule update --init --recursive"
    },
    defaultComposeDown: false,
    defaultComposeRestart: false,
    defaultForceRecreate: false,
    defaultComposeBuild: true,
    defaultPullImages: true,
    defaultTargetServices: "web api db",
    defaultLogTailCount: "50",
    envFileCredentialIds: [
        "my-app-secrets",
        "common-database-creds"
    ],
    persistentWorkspace: '/opt/jenkins/deployments'
)
```
