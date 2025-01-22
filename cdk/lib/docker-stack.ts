import * as cdk from "aws-cdk-lib";
import * as ecrAssets from 'aws-cdk-lib/aws-ecr-assets';
import { Construct } from 'constructs';

export class DockerStack extends cdk.Stack {

  public readonly dockerImageAsset: ecrAssets.DockerImageAsset;

  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    this.dockerImageAsset = new ecrAssets.DockerImageAsset(this, 'OrchestratorDockerImage', {
      directory: '../',
    });

  }
}