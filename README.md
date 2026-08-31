# JenkinsPipelines

A shared Jenkins pipeline library containing multiple reusable pipelines and functions.

## Overview

This repository contains Jenkins pipeline libraries implemented as Groovy scripts in the `vars/` directory,
with corresponding documentation stored under the `docs/` folder.

Each function can be easily integrated into your Jenkins builds via the Jenkins Shared Library mechanism.

### Included Pipelines

- [dockerComposePipeline](docs/dockerComposePipeline.md)
- [dockerImagePipeline](docs/dockerImagePipeline.md)
- [securityScanStep](docs/securityScanStep.md)

## Usage

To use a tool from this library, add it to your `Jenkinsfile`, for example:

```groovy
@Library("JenkinsPipelines") _
dockerComposePipeline([...])
```

Replace `[...]` with pipeline or step specific configuration parameters.

## Requirements

- Jenkins with Global Pipeline Libraries configured to include this repository.
- Jenkins agents with necessary tools installed (e.g., `sh` and `docker` at minimum, others depending on the pipeline).
- Appropriate credentials configured in Jenkins for pipelines that require them.
- The [Warnings Plugin](https://plugins.jenkins.io/warnings-ng/) for Jenkins and `trivy` in agents (if using the security scan function which is enabled by default in both pipelines)

## Documentation

Detailed documentation for each pipeline is located in the `docs/` folder:

- [dockerComposePipeline](docs/dockerComposePipeline.md)
- [dockerImagePipeline](docs/dockerImagePipeline.md)
- [securityScanStep](docs/securityScanStep.md)

## Contributing

Contributions are welcome!  
If you encounter a bug or have a feature request, please open an issue on [GitHub Issues](https://github.com/mwdle/JenkinsPipelines/issues).

To contribute code:

1. Fork the repository.
2. Implement your changes, with appropriate documentation.
3. Submit a pull request describing the changes and why they are needed.

Please ensure your contributions follow existing style conventions and include tests where applicable.
