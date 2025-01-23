import * as cdk from "aws-cdk-lib";
import * as pipelines from "aws-cdk-lib/pipelines";
import * as secretsmanager from 'aws-cdk-lib/aws-secretsmanager';
import * as config from "./config";
import { Construct } from "constructs";
import { DockerStack } from "./docker-stack";
import { CollectorStack } from "./collector/collector-stack";
import { BuildSpec } from "aws-cdk-lib/aws-codebuild";

class AppStage extends cdk.Stage {

  public readonly dockerStack: DockerStack;
  public readonly CollectorStack: CollectorStack;

  constructor(scope: Construct, id: string, props?: cdk.StageProps) {
    super(scope, id, props);
    
    const dockerStack = new DockerStack(this, "DockerStack", props);
    const collectorStack = new CollectorStack(this, "CollectorStack", {
      ...props,
      dockerImage: dockerStack.dockerImageAsset,
    });
  }
}

export class OrchestratorPipelineStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);
    
    const secret = secretsmanager.Secret.fromSecretNameV2(this, 'GithubMavenSecret', 'GITHUB_MAVEN');

    const pipeline = new pipelines.CodePipeline(this, "OrchestratorPipeline", {
      crossAccountKeys: true,
      pipelineName: "OrchestratorPipeline",
      synth: new pipelines.ShellStep("deploy", {
        input: pipelines.CodePipelineSource.gitHub(config.GITHUB_REPO, config.GITHUB_BRANCH),
        commands: [ 
          "cd ./cdk",
          "npm ci",
          "npx cdk synth"
        ],
        primaryOutputDirectory: "cdk/cdk.out",
      }),
      codeBuildDefaults: {
        partialBuildSpec: BuildSpec.fromObject({
          env: {
            variables: {
              GITHUB_ACTOR: secret.secretValueFromJson('GITHUB_ACTOR').unsafeUnwrap(),
              GITHUB_TOKEN: secret.secretValueFromJson('GITHUB_TOKEN').unsafeUnwrap(),
            }
          } 
        })
      },
    });

    const dev = new AppStage(this, "Dev", {
      env: config.ACCOUNTS.dev,
    })
    const staging = new AppStage(this, "Staging", {
      env: config.ACCOUNTS.staging,
    });
    const prod = new AppStage(this, "Prod", {
      env: config.ACCOUNTS.prod,
    });

    pipeline.addStage(dev);
    pipeline.addStage(staging);
    pipeline.addStage(prod, {
      pre: [new pipelines.ManualApprovalStep('ApproveProd')],
    });
  }
}