import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import { Construct } from 'constructs';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';
import { grantResearchTables } from '../constructs/research-tables';

export const LAUNCH_TEMPLATE_NAME = 'gnome-orchestrator';
export const LIVE_LAUNCH_TEMPLATE_NAME = 'gnome-orchestrator-live';

interface Props extends cdk.StackProps {
  stage: Stage;
  registryApiKeyId: string;
}

/**
 * What the registry's launcher needs to start a strategy instance in this region. The AMI, instance type,
 * subnet, security group and user data are chosen per session at launch time: the launcher puts sessions in the
 * platform network (gnome-shared-infra), so the template names no network of its own.
 */
export class Ec2Stack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);

    // Paper and live sessions run under different roles: only a live session may read a venue account's
    // credentials (gnome/accounts/*), which for a Polymarket wallet include the key that can move its funds.
    this.launchTemplate(props, LAUNCH_TEMPLATE_NAME, `gnome-orchestrator-instance-${this.region}`, false);
    this.launchTemplate(props, LIVE_LAUNCH_TEMPLATE_NAME, `gnome-orchestrator-live-instance-${this.region}`, true);

    if (this.region !== 'us-east-1') {
      // The session monitor only exists in us-east-1. EventBridge cannot filter on instance tags, so every
      // instance's events are forwarded and the ones without a session are ignored there.
      const forward = new events.Rule(this, 'ForwardEc2StateEvents', {
        eventPattern: {
          source: ['aws.ec2'],
          detailType: ['EC2 Instance State-change Notification'],
          detail: { state: ['running', 'shutting-down', 'terminated'] },
        },
      });
      forward.addTarget(new targets.EventBus(events.EventBus.fromEventBusArn(
        this,
        'UsEast1DefaultBus',
        `arn:aws:events:us-east-1:${this.account}:event-bus/default`,
      )));
    }
  }

  private launchTemplate(props: Props, name: string, roleName: string, live: boolean): ec2.LaunchTemplate {
    // Named inside the launcher's gnome-orchestrator-* PassRole grant.
    const role = new iam.Role(this, live ? 'LiveInstanceRole' : 'InstanceRole', {
      roleName,
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('CloudWatchAgentServerPolicy'),
      ],
    });
    // Any region: instances fall back to us-east-1 for secrets that are not replicated to their own region. Every
    // session reads the market-data keys and the gnomepy token; only a live one reads its account's trading key.
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['secretsmanager:GetSecretValue'],
      resources: [
        `arn:aws:secretsmanager:*:${this.account}:secret:gnome/market-data/*`,
        `arn:aws:secretsmanager:*:${this.account}:secret:gnomepy/*`,
        ...(live ? [`arn:aws:secretsmanager:*:${this.account}:secret:gnome/accounts/*/trading-*`] : []),
      ],
    }));
    if (!live) {
      role.addToPolicy(new iam.PolicyStatement({
        effect: iam.Effect.DENY,
        actions: ['secretsmanager:GetSecretValue'],
        resources: [`arn:aws:secretsmanager:*:${this.account}:secret:gnome/accounts/*`],
      }));
    }
    // The journal shipper uploads large segments in parts, and abandons a failed multipart upload.
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:PutObject', 's3:AbortMultipartUpload'],
      resources: [`arn:aws:s3:::gnome-journals-${props.stage}/*`],
    }));
    // Strategies read research datasets (e.g. prematch predictions) and model artifacts through gnomepy.data.
    grantResearchTables(this, role, { write: false });
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:GetObject'],
      resources: [`arn:aws:s3:::gnome-research-${props.stage}/artifacts/*`],
    }));
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['apigateway:GET'],
      resources: [`arn:aws:apigateway:us-east-1::/apikeys/${props.registryApiKeyId}`],
    }));

    const template = new ec2.LaunchTemplate(this, live ? 'LiveLaunchTemplate' : 'LaunchTemplate', {
      launchTemplateName: name,
      role,
      requireImdsv2: true,
      detailedMonitoring: true,
      ebsOptimized: true,
      // Termination happens by shutting down from inside the instance, so it needs no EC2 permissions.
      instanceInitiatedShutdownBehavior: ec2.InstanceInitiatedShutdownBehavior.TERMINATE,
      blockDevices: [
        {
          deviceName: '/dev/sda1',
          volume: ec2.BlockDeviceVolume.ebs(30, {
            volumeType: ec2.EbsDeviceVolumeType.GP3,
            encrypted: true,
            deleteOnTermination: true,
          }),
        },
      ],
    });
    // A recovered instance reboots without re-running user data and would sit idle, billing; the boot guard
    // would shut it down anyway, so recovery only wastes a boot.
    (template.node.defaultChild as ec2.CfnLaunchTemplate).addPropertyOverride(
      'LaunchTemplateData.MaintenanceOptions.AutoRecovery',
      'disabled',
    );
    return template;
  }
}
