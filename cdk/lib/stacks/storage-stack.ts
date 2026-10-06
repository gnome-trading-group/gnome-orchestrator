import * as cdk from 'aws-cdk-lib';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';
import * as fs from 'fs';
import * as path from 'path';

export const LATEST_VERSION_PARAMETER = '/gnome/orchestrator/latest-version';
export const PROPERTIES_PARAMETER_PREFIX = '/gnome/orchestrator/properties';

// The stage's orchestrator.<stage>.properties as key -> default value. Only plain `key=value` lines are used, which
// is all these files contain.
export function readOrchestratorProperties(stage: Stage): Record<string, string> {
  const file = path.join(__dirname, '..', '..', '..', 'src', 'main', 'resources', `orchestrator.${stage}.properties`);
  const properties: Record<string, string> = {};
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || trimmed.startsWith('!')) continue;
    const eq = trimmed.indexOf('=');
    if (eq <= 0) continue;
    properties[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  return properties;
}

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

      // The controller offers these keys as per-session overrides, and the strategy launcher rejects any override
      // the session's orchestrator version doesn't list. "latest" moves with latest-version; each release also
      // keeps its own copy so sessions pinned to an older version are checked against what that version reads.
      const propertiesJson = JSON.stringify({
        version: props.orchestratorVersion,
        properties: readOrchestratorProperties(props.stage),
      });
      new ssm.StringParameter(this, 'LatestOrchestratorProperties', {
        parameterName: `${PROPERTIES_PARAMETER_PREFIX}/latest`,
        stringValue: propertiesJson,
        description: 'Property keys and default values of the latest gnome-orchestrator release',
      });
      // Retained so the next release, which drops this resource from the template, leaves it in place rather than
      // deleting it. Releases only move forward, so a version's parameter is never recreated.
      new ssm.StringParameter(this, `OrchestratorProperties-${props.orchestratorVersion}`, {
        parameterName: `${PROPERTIES_PARAMETER_PREFIX}/${props.orchestratorVersion}`,
        stringValue: propertiesJson,
        description: `Property keys and default values of gnome-orchestrator ${props.orchestratorVersion}`,
      }).applyRemovalPolicy(cdk.RemovalPolicy.RETAIN);
    }
  }
}
