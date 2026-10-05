import * as cdk from 'aws-cdk-lib';
import * as pipelines from 'aws-cdk-lib/pipelines';
import * as codebuild from 'aws-cdk-lib/aws-codebuild';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as secrets from 'aws-cdk-lib/aws-secretsmanager';
import { Construct } from 'constructs';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';
import { CONFIGS, GITHUB_BRANCH, GITHUB_REPO, OrchestratorConfig } from './config';
import { AmiStack } from './stacks/ami-stack';
import { Ec2Stack } from './stacks/ec2-stack';
import { NetworkStack } from './stacks/network-stack';
import { StorageStack } from './stacks/storage-stack';
import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * The orchestrator version this checkout builds. The pipeline only runs on the release branch, which
 * push-release.yml points at the tagged release commit, so there it is always a released version.
 */
function releasedOrchestratorVersion(): string | undefined {
  const pom = readFileSync(join(__dirname, '..', '..', 'pom.xml'), 'utf8').replace(/<parent>[\s\S]*?<\/parent>/, '');
  const version = /<version>([^<]+)<\/version>/.exec(pom)?.[1];
  if (!version) throw new Error('No <version> in pom.xml');
  if (!version.endsWith('-SNAPSHOT')) return version;
  if (process.env.CODEBUILD_BUILD_ID) {
    throw new Error(`Refusing to publish SNAPSHOT ${version} as the latest orchestrator version`);
  }
  return undefined;
}

class AppStage extends cdk.Stage {
  constructor(scope: Construct, id: string, config: OrchestratorConfig) {
    super(scope, id, { env: config.account.environment });

    new StorageStack(this, 'OrchestratorStorageStack', {
      stage: config.account.stage,
      orchestratorVersion: releasedOrchestratorVersion(),
    });

    for (const region of config.regions) {
      const env = { account: config.account.accountId, region };
      // Construct id kept from when this stack held the ECS cluster; changing it would recreate the VPC.
      const network = new NetworkStack(this, `OrchestratorEcsStack-${region}`, { env });
      new Ec2Stack(this, `OrchestratorEc2Stack-${region}`, {
        env,
        stage: config.account.stage,
        registryApiKeyId: config.registryApiKeyId,
        securityGroup: network.securityGroup,
      });
      // Built once per account and copied to every region, rather than built in each.
      if (region === 'us-east-1') {
        new AmiStack(this, 'OrchestratorAmiStack', {
          env,
          vpc: network.vpc,
          securityGroup: network.securityGroup,
          regions: config.regions,
        });
      }
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
