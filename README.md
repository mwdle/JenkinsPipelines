# JenkinsPipelines

A shared Jenkins pipeline library containing multiple reusable pipelines and functions.

## Overview

This repository contains Jenkins pipeline libraries implemented as Groovy scripts in the `vars/` directory,
with corresponding documentation stored under the `docs/` folder.

Each function can be easily integrated into your Jenkins builds via the Jenkins Shared Library mechanism.

### Included Pipelines

- [containerImagePipeline](docs/containerImagePipeline.md)
- [dockerComposePipeline](docs/dockerComposePipeline.md)
- [dockerImagePipeline](docs/dockerImagePipeline.md)
- [quadletPipeline](docs/quadletPipeline.md)
- [securityScanStep](docs/securityScanStep.md)

## Usage

To use a tool from this library, add it to your `Jenkinsfile`, for example:

```groovy
@Library("JenkinsPipelines") _
quadletPipeline([...])
```

Replace `[...]` with pipeline or step specific configuration parameters.

## Requirements

- Jenkins with Global Pipeline Libraries configured to include this repository.
- Jenkins agents with the tools required by the selected pipeline.
- Appropriate credentials configured in Jenkins for pipelines that require them.
- The [Workspace Cleanup Plugin](https://plugins.jenkins.io/ws-cleanup/) for `containerImagePipeline` and `quadletPipeline`.
- The [Warnings Plugin](https://plugins.jenkins.io/warnings-ng/) for Jenkins and `trivy` on agents when security scanning is enabled.

## Documentation

Detailed documentation for each pipeline is located in the `docs/` folder:

- [containerImagePipeline](docs/containerImagePipeline.md)
- [dockerComposePipeline](docs/dockerComposePipeline.md)
- [dockerImagePipeline](docs/dockerImagePipeline.md)
- [quadletPipeline](docs/quadletPipeline.md)
- [securityScanStep](docs/securityScanStep.md)
