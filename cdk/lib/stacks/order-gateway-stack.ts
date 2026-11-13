import * as cdk from 'aws-cdk-lib';
import * as autoscaling from 'aws-cdk-lib/aws-autoscaling';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecrAssets from 'aws-cdk-lib/aws-ecr-assets';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as ssm from 'aws-cdk-lib/aws-ssm';
import { Construct } from 'constructs';
import {
  API_KEY_SECRET_NAMES, CLOCK_STATUS_DIR, hostClockUserData, lookupPlatformNetwork, PLATFORM_SSM, PRIMARY_REGION,
  SERVICE_ENDPOINTS, Stage,
} from '@gnome-trading-group/gnome-shared-cdk';
import { join } from 'path';
import { ORDER_GATEWAYS } from '../config';


// Provisional until the gateways are sized under load: two cores and 4 GiB, as a gateway's ingress, egress, Aeron driver,
// and each account's lane and reader all wake every few tens of microseconds; not burstable (credits would run out in
// a burst of orders) and not Graviton (the native socket library is x86 only). The same size on Intel is the fallback:
// a live host launches into the precision-time placement group, which fails when the zone has none of the first type's
// hardware with the Nitro clock, and a venue's order path waits on that launch.
const INSTANCE_TYPES = ['c7a.large', 'c7i.large'];
// A paper gateway simulates its venue for paper sessions and holds nothing anyone would lose: one core and 2 GiB, not
// burstable for the same reason as live.
const PAPER_INSTANCE_TYPE = 'c7a.medium';
// More than the gateways a region will hold for a while; the start route can start no more than this many at once.
const MAX_HOSTS = 4;
// One paper gateway per venue paper-traded from the region.
const MAX_PAPER_HOSTS = 4;
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
 *
 * Paper gateways, which simulate a venue for paper sessions, run the same image on hosts and a base task definition of
 * their own: smaller hosts, and a task role that reads the market-data lease API's key rather than any venue account's
 * trading keys. They trade with no venue, so have no Elastic IP.
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
    // Live gateways time what they send and what the venue answers, which sessions compare with their own clocks; a
    // paper gateway's venue is simulated, so its clock stays on Amazon Time Sync.
    const precisionTimeGroup = ssm.StringParameter.valueForStringParameter(this, PLATFORM_SSM.precisionTimeGroup);
    this.hosts(cluster, vpc, securityGroup, hostRole, 'Gateway', INSTANCE_TYPES, MAX_HOSTS,
      ORDER_GATEWAYS.modes.live.capacityProvider, precisionTimeGroup);
    this.hosts(cluster, vpc, securityGroup, hostRole, 'GatewayPaper', [PAPER_INSTANCE_TYPE], MAX_PAPER_HOSTS,
      ORDER_GATEWAYS.modes.paper.capacityProvider);

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
    this.grantGatewayBasics(taskRole, props.registryApiKeyId);
    // Its Elastic IP, found by its tag and attached to whichever host it runs on.
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['ec2:DescribeAddresses', 'ec2:AssociateAddress'],
      resources: ['*'],
    }));
    const paperTaskRole = new iam.Role(this, 'GatewayPaperTaskRole', {
      roleName: `gnome-order-gateway-paper-task-${this.region}`,
      assumedBy: new iam.ServicePrincipal('ecs-tasks.amazonaws.com'),
    });
    // The market-data API key, to read each paper session's lease (its one copy is in us-east-1); and the token that
    // downloads its release.
    paperTaskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['secretsmanager:GetSecretValue'],
      resources: [
        `arn:aws:secretsmanager:us-east-1:${this.account}:secret:${API_KEY_SECRET_NAMES.MARKET_DATA}-*`,
        `arn:aws:secretsmanager:*:${this.account}:secret:gnomepy/gh-token*`,
      ],
    }));
    this.grantGatewayBasics(paperTaskRole, props.registryApiKeyId);
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

    const image = ecs.ContainerImage.fromDockerImageAsset(new ecrAssets.DockerImageAsset(this, 'GatewayImage', {
      directory: join(__dirname, 'gateway-docker'),
      platform: ecrAssets.Platform.LINUX_AMD64,
    }));
    const common = { stage: props.stage, image, executionRole, logGroup };
    this.task({
      ...common, id: 'Gateway', family: ORDER_GATEWAYS.modes.live.baseFamily, taskRole,
      memoryReservationMiB: 2 * 1024, sharedMemoryMiB: 512,
    });
    // Under what a 2 GiB host registers with ECS once the kernel and the agent have theirs. Its /dev/shm holds only the
    // sessions' and its streams' images, 3 x 256 KiB each.
    this.task({
      ...common, id: 'GatewayPaper', family: ORDER_GATEWAYS.modes.paper.baseFamily, taskRole: paperTaskRole,
      memoryReservationMiB: 1536, sharedMemoryMiB: 384,
    });
  }

  /**
   * A group of gateway hosts that scales from zero, and its capacity provider in the cluster. With more than one type,
   * the first is used whenever it launches and the rest only when it can't.
   */
  private hosts(
    cluster: ecs.Cluster, vpc: ec2.IVpc, securityGroup: ec2.ISecurityGroup, hostRole: iam.IRole, id: string,
    instanceTypes: string[], maxHosts: number, capacityProviderName: string, placementGroup?: string,
  ) {
    const userData = ec2.UserData.forLinux();
    userData.addCommands(...hostClockUserData());
    const launchTemplate = new ec2.LaunchTemplate(this, `${id}HostTemplate`, {
      instanceType: new ec2.InstanceType(instanceTypes[0]),
      machineImage: ecs.EcsOptimizedImage.amazonLinux2023(),
      role: hostRole,
      securityGroup,
      requireImdsv2: true,
      // Hop limit 2: the gateway reads the instance's metadata from its container.
      httpPutResponseHopLimit: 2,
      userData,
      blockDevices: [{ deviceName: '/dev/xvda', volume: ec2.BlockDeviceVolume.ebs(30, { encrypted: true }) }],
    });
    if (placementGroup) {
      (launchTemplate.node.defaultChild as ec2.CfnLaunchTemplate)
        .addPropertyOverride('LaunchTemplateData.Placement.GroupName', placementGroup);
    }
    const hosts = new autoscaling.AutoScalingGroup(this, `${id}Hosts`, {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PUBLIC },
      ...(instanceTypes.length === 1 ? { launchTemplate } : {
        mixedInstancesPolicy: {
          launchTemplate,
          launchTemplateOverrides: instanceTypes.map((type) => ({ instanceType: new ec2.InstanceType(type) })),
          instancesDistribution: {
            onDemandAllocationStrategy: autoscaling.OnDemandAllocationStrategy.PRIORITIZED,
            onDemandBaseCapacity: 0,
            onDemandPercentageAboveBaseCapacity: 100,
          },
        },
      }),
      minCapacity: 0,
      maxCapacity: maxHosts,
      newInstancesProtectedFromScaleIn: true,
    });
    cluster.addAsgCapacityProvider(new ecs.AsgCapacityProvider(this, `${id}HostProvider`, {
      capacityProviderName,
      autoScalingGroup: hosts,
      enableManagedScaling: true,
      enableManagedTerminationProtection: true,
      targetCapacityPercent: 100,
    }));
  }

  /** What every gateway's task role may do, live or paper. */
  private grantGatewayBasics(taskRole: iam.Role, registryApiKeyId: string) {
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['apigateway:GET'],
      resources: [`arn:aws:apigateway:${PRIMARY_REGION}::/apikeys/${registryApiKeyId}`],
    }));
    // Its own endpoint; the table is shared with other services, so only gateway items.
    taskRole.addToPolicy(new iam.PolicyStatement({
      actions: ['dynamodb:PutItem'],
      resources: [`arn:aws:dynamodb:${SERVICE_ENDPOINTS.region}:${this.account}:table/${SERVICE_ENDPOINTS.tableName}`],
      conditions: { 'ForAllValues:StringLike': { 'dynamodb:LeadingKeys': ['order-gateway#*'] } },
    }));
  }

  /** A base task definition, which the reconciler copies into each gateway's own revision. */
  private task(props: {
    id: string; family: string; stage: Stage; image: ecs.ContainerImage; taskRole: iam.IRole; executionRole: iam.IRole;
    logGroup: logs.ILogGroup; memoryReservationMiB: number; sharedMemoryMiB: number;
  }) {
    const task = new ecs.Ec2TaskDefinition(this, `${props.id}Task`, {
      family: props.family,
      networkMode: ecs.NetworkMode.HOST,
      taskRole: props.taskRole,
      executionRole: props.executionRole,
      volumes: [
        { name: 'state', host: { sourcePath: STATE_DIRECTORY } },
        // The host's clock state, which the gateway reports beside its minutes.
        { name: 'clock', host: { sourcePath: CLOCK_STATUS_DIR } },
      ],
    });
    const container = task.addContainer('Gateway', {
      containerName: 'gateway',
      image: props.image,
      memoryReservationMiB: props.memoryReservationMiB,
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
        logGroup: props.logGroup,
        mode: ecs.AwsLogDriverMode.NON_BLOCKING,
      }),
      // Aeron's media driver keeps its term buffers in /dev/shm (Docker's default is 64 MiB).
      linuxParameters: new ecs.LinuxParameters(this, `${props.id}LinuxParameters`, {
        sharedMemorySize: props.sharedMemoryMiB,
      }),
      // The planned stop cancels every session's orders and says goodbye before anything closes.
      stopTimeout: cdk.Duration.seconds(120),
    });
    container.addMountPoints(
      { sourceVolume: 'state', containerPath: STATE_DIRECTORY, readOnly: false },
      { sourceVolume: 'clock', containerPath: CLOCK_STATUS_DIR, readOnly: true },
    );
  }
}
