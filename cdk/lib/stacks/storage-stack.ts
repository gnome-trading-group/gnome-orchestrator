import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';

export const LATEST_VERSION_PARAMETER = '/gnome/orchestrator/latest-version';

interface Props extends cdk.StackProps {
  stage: Stage;
  // The orchestrator release being deployed; undefined when synthesizing a development checkout.
  orchestratorVersion?: string;
}

export class StorageStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);

    new s3.Bucket(this, 'JournalBucket', {
      bucketName: `gnome-journals-${props.stage}`,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      lifecycleRules: [
        {
          transitions: [
            {
              storageClass: s3.StorageClass.GLACIER,
              transitionAfter: cdk.Duration.days(90),
            },
          ],
        },
      ],
    });

    // What "latest" means to the strategy and collector launchers. Written by this pipeline, which only runs
    // for release tags, so prod only moves once ApproveProd lets the release through.
    if (props.orchestratorVersion) {
      new ssm.StringParameter(this, 'LatestOrchestratorVersion', {
        parameterName: LATEST_VERSION_PARAMETER,
        stringValue: props.orchestratorVersion,
        description: 'Released gnome-orchestrator version used when a launch does not specify one',
      });
    }
  }
}
