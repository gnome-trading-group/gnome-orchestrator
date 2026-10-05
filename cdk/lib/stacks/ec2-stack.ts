import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import { Construct } from 'constructs';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';

export const LAUNCH_TEMPLATE_NAME = 'gnome-orchestrator';

interface Props extends cdk.StackProps {
  stage: Stage;
  registryApiKeyId: string;
  securityGroup: ec2.ISecurityGroup;
}

/**
 * What the registry's launcher needs to start a strategy instance in this region. The AMI, instance type,
 * subnet and user data are chosen per session at launch time.
 */
export class Ec2Stack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);

    // Named inside the launcher's gnome-orchestrator-* PassRole grant.
    const role = new iam.Role(this, 'InstanceRole', {
      roleName: `gnome-orchestrator-instance-${this.region}`,
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('CloudWatchAgentServerPolicy'),
      ],
    });
    // Any region: instances fall back to us-east-1 for secrets that are not replicated to their own region.
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['secretsmanager:GetSecretValue'],
      resources: [
        `arn:aws:secretsmanager:*:${this.account}:secret:gnome/*`,
        `arn:aws:secretsmanager:*:${this.account}:secret:gnomepy/*`,
      ],
    }));
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:PutObject'],
      resources: [`arn:aws:s3:::gnome-journals-${props.stage}/*`],
    }));
    role.addToPolicy(new iam.PolicyStatement({
      actions: ['apigateway:GET'],
      resources: [`arn:aws:apigateway:us-east-1::/apikeys/${props.registryApiKeyId}`],
    }));

    const template = new ec2.LaunchTemplate(this, 'LaunchTemplate', {
      launchTemplateName: LAUNCH_TEMPLATE_NAME,
      role,
      securityGroup: props.securityGroup,
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
}
