import * as cdk from 'aws-cdk-lib';
import * as pipelines from 'aws-cdk-lib/pipelines';
import * as codebuild from 'aws-cdk-lib/aws-codebuild';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as secrets from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';
import { CONFIGS, GITHUB_BRANCH, GITHUB_REPO, OrchestratorConfig } from './config';
import { EcrStack } from './stacks/ecr-stack';
import { EcsStack } from './stacks/ecs-stack';
import { StorageStack } from './stacks/storage-stack';

class AppStage extends cdk.Stage {
  constructor(scope: Construct, id: string, config: OrchestratorConfig) {
    super(scope, id, { env: config.account.environment });

    const ecrStack = new EcrStack(this, 'OrchestratorEcrStack', {
      ecsRegions: config.ecsRegions,
    });

    new StorageStack(this, 'OrchestratorStorageStack');

    for (const region of config.ecsRegions) {
      new EcsStack(this, `OrchestratorEcsStack-${region}`, {
        env: { account: config.account.accountId, region },
        stage: config.account.stage,
      });
    }
  }
}

export class OrchestratorPipelineStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const npmSecret = secrets.Secret.fromSecretNameV2(this, 'NPMToken', 'npm-token');
    const githubSecret = secrets.Secret.fromSecretNameV2(this, 'GithubMaven', 'GITHUB_MAVEN');
    const dockerHubCredentials = secrets.Secret.fromSecretNameV2(this, 'DockerHub', 'docker-hub-credentials');

    const pipeline = new pipelines.CodePipeline(this, 'OrchestratorPipeline', {
      crossAccountKeys: true,
      pipelineName: 'OrchestratorPipeline',
      dockerEnabledForSynth: true,
      synth: new pipelines.ShellStep('Synth', {
        input: pipelines.CodePipelineSource.gitHub(GITHUB_REPO, GITHUB_BRANCH),
        commands: [
          'echo "//npm.pkg.github.com/:_authToken=${NPM_TOKEN}" > ~/.npmrc',
          'cd cdk/',
          'npm ci',
          'npx cdk synth',
        ],
        env: {
          NPM_TOKEN: npmSecret.secretValue.unsafeUnwrap(),
          MAVEN_CREDENTIALS: githubSecret.secretValue.unsafeUnwrap(),
        },
        primaryOutputDirectory: 'cdk/cdk.out',
      }),
      dockerCredentials: [
        pipelines.DockerCredential.dockerHub(dockerHubCredentials),
      ],
      assetPublishingCodeBuildDefaults: {
        buildEnvironment: {
          buildImage: codebuild.LinuxBuildImage.AMAZON_LINUX_2_5,
          privileged: true,
          environmentVariables: {
            MAVEN_CREDENTIALS: {
              value: githubSecret.secretValue.unsafeUnwrap(),
            },
          },
        },
        cache: codebuild.Cache.local(codebuild.LocalCacheMode.DOCKER_LAYER),
      },
      synthCodeBuildDefaults: {
        buildEnvironment: {
          buildImage: codebuild.LinuxBuildImage.AMAZON_LINUX_2_5,
          privileged: true,
          environmentVariables: {
            MAVEN_CREDENTIALS: {
              value: githubSecret.secretValue.unsafeUnwrap(),
            },
          },
        },
        rolePolicy: [
          new iam.PolicyStatement({
            actions: ['sts:AssumeRole'],
            resources: ['*'],
            conditions: {
              StringEquals: {
                'iam:ResourceTag/aws-cdk:bootstrap-role': 'lookup',
              },
            },
          }),
        ],
      },
    });

    const dev = new AppStage(this, 'Dev', CONFIGS[Stage.DEV]!);
    const prod = new AppStage(this, 'Prod', CONFIGS[Stage.PROD]!);

    pipeline.addStage(dev);
    pipeline.addStage(prod, {
      pre: [new pipelines.ManualApprovalStep('ApproveProd')],
    });

    pipeline.buildPipeline();
    npmSecret.grantRead(pipeline.synthProject.role!);
    npmSecret.grantRead(pipeline.pipeline.role);
    githubSecret.grantRead(pipeline.synthProject.role!);
    githubSecret.grantRead(pipeline.pipeline.role);
  }
}
