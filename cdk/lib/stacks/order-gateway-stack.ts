import * as cdk from 'aws-cdk-lib';
import * as autoscaling from 'aws-cdk-lib/aws-autoscaling';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecrAssets from 'aws-cdk-lib/aws-ecr-assets';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';
import { lookupPlatformNetwork, PRIMARY_REGION, SERVICE_ENDPOINTS, Stage } from '@gnome-trading-group/gnome-shared-cdk';
import { join } from 'path';
import { ORDER_GATEWAYS } from '../config';


// Provisional until the gateways are sized under load: two cores and 4 GiB, as a gateway's ingress, egress, Aeron driver,
// and each account's lane and reader all wake every few tens of microseconds; not burstable (credits would run out in
// a burst of orders) and not Graviton (the native socket library is x86 only).
const INSTANCE_TYPE = 'c7a.large';
// More than the gateways a region will hold for a while; the start route can start no more than this many at once.
const MAX_HOSTS = 4;
// Where a gateway keeps what outlives its process: each account's ownership log and the sessions it accepted.
const STATE_DIRECTORY = '/var/lib/gnome/gateway';

interface Props extends cdk.StackProps {
  stage: Stage;
  registryApiKeyId: string;
}

/**
 * Where a region's live order gateways run, with nothing about which: as market-data shards are run by their reconciler,
 * gateways are run by the gateway reconciler (OrderGatewayReconcilerStack), from what sessions want (the registry's
 * gateway rows). It runs a gateway in its venue's region as an ECS service from the base task definition here, with its
 * key and release filled in, and the capacity provider brings up a host for it; once nobody wants it and it reports no
 * session or order, it sets the service to zero, so a region without live sessions runs nothing.
 *
 * Fixed ports keep one gateway per host. Each host is on demand in the platform network; a gateway attaches its own
 * Elastic IP, which the reconciler allocates (Polymarket US counts its limit per IP, and an allowlist would name it).
 */
export class OrderGatewayStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);
    const { vpc, securityGroup } = lookupPlatformNetwork(this);

    const cluster = new ecs.Cluster(this, 'OrderGatewayCluster', {
      clusterName: ORDER_GATEWAYS.cluster,
      vpc,
      containerInsightsV2: ecs.ContainerInsights.DISABLED,
    });
    const hostRole = new iam.Role(this, 'GatewayHostRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonEC2ContainerServiceforEC2Role'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore'),
      ],
    });
    const hosts = new autoscaling.AutoScalingGroup(this, 'GatewayHosts', {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      launchTemplate: new ec2.LaunchTemplate(this, 'GatewayHostTemplate', {
        instanceType: new ec2.InstanceType(INSTANCE_TYPE),
        machineImage: ecs.EcsOptimizedImage.amazonLinux2023(),
        role: hostRole,
        securityGroup,
        requireImdsv2: true,
        // Hop limit 2: the gateway reads the instance's metadata from its container.
        httpPutResponseHopLimit: 2,
        userData: ec2.UserData.forLinux(),
        blockDevices: [{ deviceName: '/dev/xvda', volume: ec2.BlockDeviceVolume.ebs(30, { encrypted: true }) }],
      }),
      minCapacity: 0,
      maxCapacity: MAX_HOSTS,
      newInstancesProtectedFromScaleIn: true,
    });
    cluster.addAsgCapacityProvider(new ecs.AsgCapacityProvider(this, 'GatewayHostProvider', {
      capacityProviderName: ORDER_GATEWAYS.capacityProvider,
      autoScalingGroup: hosts,
      enableManagedScaling: true,
      enableManagedTerminationProtection: true,
      targetCapacityPercent: 100,
    }));

    // Named, so the gateway reconciler may pass them to the services it creates.
    const taskRole = new iam.Role(this, 'GatewayTaskRole', {
      roleName: `gnome-order-gateway-task-${this.region}`,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    // Its accounts' trading keys, replicated to its region; and the token that downloads its release.
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['secretsmanager:GetSecretValue'],
      resources: [
        `arn:aws:secretsmanager:${this.region}:${this.account}:secret:gnome/accounts/*/trading-*`,
        `arn:aws:secretsmanager:*:${this.account}:secret:gnomepy/gh-token*`,
      ],
    }));
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['apigateway:GET'],
      resources: [`arn:aws:apigateway:${PRIMARY_REGION}::/apikeys/${props.registryApiKeyId}`],
    }));
    // Its own endpoint; the table is shared with other services, so only gateway items.
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['dynamodb:PutItem'],
      resources: [`arn:aws:dynamodb:${SERVICE_ENDPOINTS.region}:${this.account}:table/${SERVICE_ENDPOINTS.tableName}`],
      conditions: { 'ForAllValues:StringLike': { 'dynamodb:LeadingKeys': ['order-gateway#*'] } },
    }));
    // Its Elastic IP, found by its tag and attached to whichever host it runs on.
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['ec2:DescribeAddresses', 'ec2:AssociateAddress'],
      resources: ['*'],
    }));
    const executionRole = new iam.Role(this, 'GatewayExecutionRole', {
      roleName: `gnome-order-gateway-execution-${this.region}`,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonECSTaskExecutionRolePolicy'),
      ],
    });
    const logGroup = new logs.LogGroup(this, 'GatewayLogs', {
      logGroupName: `/gnome/order-gateway/${this.region}`,
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const task = new ecs.Ec2TaskDefinition(this, 'GatewayTask', {
      family: ORDER_GATEWAYS.baseFamily,
      networkMode: ecs.NetworkMode.HOST,
      taskRole,
      executionRole,
      volumes: [{ name: 'state', host: { sourcePath: STATE_DIRECTORY } }],
    });
    const container = task.addContainer('Gateway', {
      containerName: 'gateway',
      image: ecs.ContainerImage.fromDockerImageAsset(new ecrAssets.DockerImageAsset(this, 'GatewayImage', {
        directory: join(__dirname, 'gateway-docker'),
        platform: ecrAssets.Platform.LINUX_AMD64,
      })),
      memoryReservationMiB: 2 * 1024,
      // GATEWAY_KEY and ORCHESTRATOR_VERSION are each gateway's, set by the start route on its own revision.
      environment: {
        STAGE: props.stage,
        AWS_REGION: this.region,
        // Nothing ships a gateway's journal yet, so it isn't written.
        JOURNAL_ENABLED: 'false',
      },
      healthCheck: {
        command: ['CMD-SHELL', 'wget --spider --quiet http://localhost:8080/health || exit 1'],
        interval: cdk.Duration.seconds(15),
        timeout: cdk.Duration.seconds(5),
        retries: 3,
        // The jar's download, the JVM, the accounts' venue connections and their open orders' catch-up.
        startPeriod: cdk.Duration.seconds(180),
      },
      logging: ecs.LogDrivers.awsLogs({
        streamPrefix: 'gateway',
        logGroup,
        mode: ecs.AwsLogDriverMode.NON_BLOCKING,
      }),
      // Aeron's media driver keeps its term buffers in /dev/shm (Docker's default is 64 MiB).
      linuxParameters: new ecs.LinuxParameters(this, 'GatewayLinuxParameters', { sharedMemorySize: 512 }),
      // The planned stop cancels every session's orders and says goodbye before anything closes.
      stopTimeout: cdk.Duration.seconds(120),
    });
    container.addMountPoints({ sourceVolume: 'state', containerPath: STATE_DIRECTORY, readOnly: false });
  }
}
