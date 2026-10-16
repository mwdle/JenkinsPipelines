/*
 * Run an Ansible playbook against a single SSH target.
 */
void call(Map config) {
    boolean hasPlaybookResource = config.playbookResource as boolean
    boolean hasPlaybookPath = config.playbookPath as boolean
    if (hasPlaybookResource == hasPlaybookPath) {
        error("Exactly one of 'playbookResource' or 'playbookPath' must be provided.")
    }

    String tmpDir = pwd(tmp: true)
    String extraVarsPath = "${tmpDir}/extra-vars.json"
    String playbookPath = config.playbookPath
    if (hasPlaybookResource) {
        playbookPath = "${tmpDir}/playbook.yaml"
        writeFile(file: playbookPath, text: libraryResource(config.playbookResource))
    }
    writeFile(file: extraVarsPath, text: groovy.json.JsonOutput.toJson(config.extraVars))

    List credentialBindings = [
        sshUserPrivateKey(
            credentialsId: config.sshCredential,
            keyFileVariable: 'ANSIBLE_PRIVATE_KEY_FILE',
            usernameVariable: 'ANSIBLE_REMOTE_USER'
        )
    ]
    credentialBindings.addAll(config.credentialBindings ?: [])

    withEnv([
        'ANSIBLE_HOST_KEY_CHECKING=False',
        'ANSIBLE_CALLBACKS_ENABLED=ansible.posix.profile_tasks',
        'ANSIBLE_CALLBACK_RESULT_FORMAT=yaml',
        "EXTRA_VARS_PATH=${extraVarsPath}",
        "PLAYBOOK_PATH=${playbookPath}",
        "TARGET=${config.targetHost}:${config.targetPort},"
    ]) {
        withCredentials(credentialBindings) {
            sh 'ansible-playbook "${PLAYBOOK_PATH}" -i "${TARGET}" -e "@${EXTRA_VARS_PATH}"'
        }
    }
}
