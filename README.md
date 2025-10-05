# JenkinsPipelines

A shared Jenkins pipeline library containing multiple reusable pipeline definitions.

## Overview

This repository contains Jenkins pipeline libraries implemented as Groovy scripts in the `vars/` directory,
with corresponding documentation stored under the `docs/` folder.

Each pipeline can be easily integrated into your Jenkins projects via the Jenkins Shared Library mechanism.

### Included Pipelines

- [dockerComposePipeline](docs/dockerComposePipeline.md)
- [dockerImagePipeline](docs/dockerImagePipeline.md)

## Usage

To use a pipeline in this library, add it to your `Jenkinsfile`, for example:

```groovy
@Library("JenkinsPipelines") _
dockerComposePipeline([...])
```

Replace `[...]` with pipeline-specific configuration parameters.

## Requirements

- Jenkins with Global Pipeline Libraries configured to include this repository.
- Jenkins agents with necessary tools installed (e.g., `sh`, `git` - possibly others depending on the pipeline).
- Appropriate credentials configured in Jenkins for pipelines that require them.

## Documentation

Detailed documentation for each pipeline is located in the `docs/` folder:

- [dockerComposePipeline](docs/dockerComposePipeline.md)
- [dockerImagePipeline](docs/dockerImagePipeline.md)

## Contributing

Contributions are welcome!  
If you encounter a bug or have a feature request, please open an issue on [GitHub Issues](https://github.com/mwdle/JenkinsPipelines/issues).

To contribute code:

1. Fork the repository.
2. Implement your changes, with appropriate documentation.
3. Submit a pull request describing the changes and why they are needed.

Please ensure your contributions follow existing style conventions and include tests where applicable.

## License

MIT License — see [LICENSE](LICENSE).
