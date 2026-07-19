import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import { Construct } from 'constructs';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';

export const CLUSTER_NAME = 'gnome-orchestrator';
export const TASK_DEFINITION_FAMILY = 'gnome-orchestrator-trading';
export const ORCHESTRATOR_TAG = 'gnome:purpose';
export const ORCHESTRATOR_TAG_VALUE = 'orchestrator-ecs';

interface Props extends cdk.StackProps {
  stage: Stage;
}

export class EcsStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, { ...props, crossRegionReferences: true });

    const vpc = new ec2.Vpc(this, 'OrchestratorVpc', {
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

    cdk.Tags.of(vpc).add(ORCHESTRATOR_TAG, ORCHESTRATOR_TAG_VALUE);
    for (const subnet of vpc.publicSubnets) {
      cdk.Tags.of(subnet).add(ORCHESTRATOR_TAG, ORCHESTRATOR_TAG_VALUE);
    }

    const securityGroup = new ec2.SecurityGroup(this, 'OrchestratorSg', {
      vpc,
      securityGroupName: 'gnome-orchestrator-sg',
      description: 'Orchestrator ECS tasks — all outbound for WebSocket/HTTPS',
      allowAllOutbound: true,
    });
    cdk.Tags.of(securityGroup).add(ORCHESTRATOR_TAG, ORCHESTRATOR_TAG_VALUE);

    const cluster = new ecs.Cluster(this, 'OrchestratorCluster', {
      clusterName: CLUSTER_NAME,
      vpc,
    });

    const executionRole = new iam.Role(this, 'TaskExecutionRole', {
      roleName: `gnome-orchestrator-execution-${this.region}`,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });

    const taskRole = new iam.Role(this, 'TaskRole', {
      roleName: `gnome-orchestrator-task-${this.region}`,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['secretsmanager:GetSecretValue'],
      resources: [`arn:aws:secretsmanager:${this.region}:${this.account}:secret:gnome/*`],
    }));

    const logGroup = new logs.LogGroup(this, 'OrchestratorLogGroup', {
      logGroupName: `/gnome/orchestrator/${this.region}`,
      retention: logs.RetentionDays.ONE_MONTH,
    });

    const repository = ecr.Repository.fromRepositoryAttributes(this, 'OrchestratorRepo', {
      repositoryArn: `arn:aws:ecr:${this.region}:${this.account}:repository/gnome-orchestrator`,
      repositoryName: 'gnome-orchestrator',
    });

    const taskDefinition = new ecs.FargateTaskDefinition(this, 'OrchestratorTaskDef', {
      family: TASK_DEFINITION_FAMILY,
      cpu: 2048,
      memoryLimitMiB: 4096,
      executionRole,
      taskRole,
    });

    taskDefinition.addContainer('orchestrator', {
      image: ecs.ContainerImage.fromEcrRepository(repository, 'latest'),
      environment: {
        MAIN_CLASS: 'group.gnometrading.trading.TradingOrchestrator',
        STAGE: props.stage,
      },
      logging: ecs.LogDrivers.awsLogs({
        streamPrefix: 'orchestrator',
        logGroup,
      }),
    });

    if (this.region !== 'us-east-1') {
      const rule = new events.Rule(this, 'ForwardEcsTaskEvents', {
        eventPattern: {
          source: ['aws.ecs'],
          detailType: ['ECS Task State Change'],
          detail: { clusterArn: [cluster.clusterArn] },
        },
      });
      rule.addTarget(
        new targets.EventBus(
          events.EventBus.fromEventBusArn(
            this,
            'UsEast1DefaultBus',
            `arn:aws:events:us-east-1:${this.account}:event-bus/default`,
          ),
        ),
      );
    }

    new cdk.CfnOutput(this, 'ClusterArn', {
      value: cluster.clusterArn,
      exportName: `OrchestratorClusterArn-${this.region}`,
    });
    new cdk.CfnOutput(this, 'TaskDefinitionArn', {
      value: taskDefinition.taskDefinitionArn,
      exportName: `OrchestratorTaskDefArn-${this.region}`,
    });
  }
}
