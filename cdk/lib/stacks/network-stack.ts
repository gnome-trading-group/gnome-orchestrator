import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';

export const ORCHESTRATOR_TAG = 'gnome:purpose';
export const ORCHESTRATOR_TAG_VALUE = 'orchestrator-ecs';

/**
 * Network and logging shared by strategy instances. Kept under its original construct id
 * (OrchestratorEcsStack-<region>) because gnome-controller's backtest and pipeline stacks look the VPC up by
 * name, and renaming the stack would recreate it.
 */
export class NetworkStack extends cdk.Stack {
  public readonly vpc: ec2.Vpc;
  public readonly securityGroup: ec2.SecurityGroup;
  public readonly logGroup: logs.LogGroup;

  constructor(scope: Construct, id: string, props: cdk.StackProps) {
    super(scope, id, { ...props, crossRegionReferences: true });

    this.vpc = new ec2.Vpc(this, 'OrchestratorVpc', {
      vpcName: 'gnome-orchestrator-vpc',
      maxAzs: 2,
      subnetConfiguration: [
        {
          name: 'public',
          subnetType: ec2.SubnetType.PUBLIC,
          cidrMask: 24,
        },
      ],
    });

    cdk.Tags.of(this.vpc).add(ORCHESTRATOR_TAG, ORCHESTRATOR_TAG_VALUE);
    for (const subnet of this.vpc.publicSubnets) {
      cdk.Tags.of(subnet).add(ORCHESTRATOR_TAG, ORCHESTRATOR_TAG_VALUE);
    }

    // The description is out of date but changing it would replace the security group.
    this.securityGroup = new ec2.SecurityGroup(this, 'OrchestratorSg', {
      vpc: this.vpc,
      securityGroupName: 'gnome-orchestrator-sg',
      description: 'Orchestrator ECS tasks - all outbound for WebSocket/HTTPS',
      allowAllOutbound: true,
    });
    cdk.Tags.of(this.securityGroup).add(ORCHESTRATOR_TAG, ORCHESTRATOR_TAG_VALUE);

    this.logGroup = new logs.LogGroup(this, 'OrchestratorLogGroup', {
      logGroupName: `/gnome/orchestrator/${this.region}`,
      retention: logs.RetentionDays.ONE_MONTH,
    });
  }
}
